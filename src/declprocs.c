/*
 * declprocs.c - 解析 APK 的 AndroidManifest.xml，取出声明过的进程名
 *
 * 三步：
 *   1. pm path <pkg> 拿到 base.apk 路径
 *   2. 在 ZIP 里找 AndroidManifest.xml：读 EOCD → 中央目录 → 本地头 → 解压
 *   3. 按 AXML chunk 走一遍，收集所有 android:process 属性
 *
 * 第 3 步是重点：AXML 的属性结构体是 20 字节（ns, name, rawValue, typedValue{size,
 * res0, dataType, data}），少读那个 u16 size 会让 dataType/data 整体错位 ——
 * 表现为「属性名读得对，值全是 0」，我第一次就是这么踩的。
 */
#include "declprocs.h"

#include <ctype.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

#include "appkillerd.h"

/* ---- 小端读取（APK 全是小端） ---- */
static unsigned int rd16(const unsigned char *p) { return (unsigned int)p[0] | ((unsigned int)p[1] << 8); }
static unsigned int rd32(const unsigned char *p) {
    return (unsigned int)p[0] | ((unsigned int)p[1] << 8) | ((unsigned int)p[2] << 16) | ((unsigned int)p[3] << 24);
}

/* ---- 只读需要的区域：微信的 base.apk 有 219 MB，整份读进内存既慢又可能被 OOM 杀掉。
 *      做法是 pread 出 EOCD（末尾最多 64KB 注释）、中央目录、以及目标条目的本地头 +
 *      压缩数据，三次小读取即可。 ---- */
static int pread_full(int fd, void *buf, size_t len, long off) {
    size_t got = 0;
    while (got < len) {
        ssize_t n = pread(fd, (char *)buf + got, len - got, (off_t)(off + (long)got));
        if (n <= 0) return -1;
        got += (size_t)n;
    }
    return 0;
}

/* 在 ZIP 里找指定条目并解压。成功返回 malloc 的缓冲与长度。 */
static unsigned char *zip_extract(int fd, long zlen, const char *want, long *out_len) {
    if (zlen < 22) return NULL;

    /* EOCD 在末尾，注释最长 65535 */
    long tail = zlen < (22 + 65535) ? zlen : (22 + 65535);
    long tail_off = zlen - tail;
    unsigned char *tb = (unsigned char *)malloc((size_t)tail);
    if (!tb) return NULL;
    if (pread_full(fd, tb, (size_t)tail, tail_off) != 0) { free(tb); return NULL; }

    long eocd = -1;
    for (long i = tail - 22; i >= 0; i--) {
        if (rd32(tb + i) == 0x06054b50u) { eocd = i; break; }
    }
    if (eocd < 0) { free(tb); return NULL; }

    unsigned int count = rd16(tb + eocd + 10);
    unsigned int cd_off = rd32(tb + eocd + 16);
    unsigned int cd_size = rd32(tb + eocd + 12);
    free(tb);
    if (cd_off >= (unsigned int)zlen || cd_size == 0 || cd_size > (16u << 20)) return NULL;

    unsigned char *cd = (unsigned char *)malloc(cd_size);
    if (!cd) return NULL;
    if (pread_full(fd, cd, cd_size, (long)cd_off) != 0) { free(cd); return NULL; }

    size_t want_len = strlen(want);
    long p = 0;
    unsigned char *result = NULL;
    for (unsigned int i = 0; i < count; i++) {
        if (p + 46 > (long)cd_size || rd32(cd + p) != 0x02014b50u) break;
        unsigned int method = rd16(cd + p + 10);
        unsigned int csize = rd32(cd + p + 20);
        unsigned int usize = rd32(cd + p + 24);
        unsigned int nlen = rd16(cd + p + 28);
        unsigned int elen = rd16(cd + p + 30);
        unsigned int clen = rd16(cd + p + 32);
        unsigned int lho = rd32(cd + p + 42);
        const char *nm = (const char *)(cd + p + 46);
        if (p + 46 + (long)nlen > (long)cd_size) break;
        if (nlen == want_len && memcmp(nm, want, want_len) == 0) {
            if (csize == 0 || csize > (64u << 20) || usize == 0 || usize > (64u << 20)) break;
            unsigned char *lh = (unsigned char *)malloc(30);
            if (!lh) break;
            if (pread_full(fd, lh, 30, (long)lho) != 0 || rd32(lh) != 0x04034b50u) { free(lh); break; }
            unsigned int lnlen = rd16(lh + 26);
            unsigned int lelen = rd16(lh + 28);
            free(lh);
            long data = (long)lho + 30 + (long)lnlen + (long)lelen;
            if (data + (long)csize > zlen) break;

            unsigned char *raw = (unsigned char *)malloc(csize);
            if (!raw) break;
            if (pread_full(fd, raw, csize, data) != 0) { free(raw); break; }

            if (method == 0) {
                *out_len = (long)csize;
                result = raw;
            } else if (method == 8) {
                unsigned char *o = (unsigned char *)malloc(usize);
                if (o) {
                    z_stream zs;
                    memset(&zs, 0, sizeof(zs));
                    /* 负数窗口位 = raw deflate（ZIP 的流不带 zlib 头） */
                    if (inflateInit2(&zs, -MAX_WBITS) == Z_OK) {
                        zs.next_in = raw;
                        zs.avail_in = csize;
                        zs.next_out = o;
                        zs.avail_out = usize;
                        int rc = inflate(&zs, Z_FINISH);
                        long produced = (long)zs.total_out;
                        inflateEnd(&zs);
                        if (rc == Z_STREAM_END) { *out_len = produced; result = o; }
                        else free(o);
                    } else free(o);
                }
                free(raw);
            } else {
                free(raw);
            }
            break;
        }
        p += 46 + (long)nlen + (long)elen + (long)clen;
    }
    free(cd);
    return result;
}

/* ---- AXML 字符串池 ---- */
typedef struct {
    char  **items;
    int     count;
} strpool_t;

static void pool_free(strpool_t *sp) {
    if (!sp->items) return;
    for (int i = 0; i < sp->count; i++) free(sp->items[i]);
    free(sp->items);
    sp->items = NULL;
    sp->count = 0;
}

/* 把 AXML 的 string pool chunk 解出来。返回 0 成功。 */
static int pool_load(const unsigned char *ax, long alen, long chunk, strpool_t *sp) {
    if (chunk + 8 > alen) return -1;
    if (rd16(ax + chunk) != 0x0001) return -1;
    unsigned int hsize = rd16(ax + chunk + 2);
    unsigned int csize = rd32(ax + chunk + 4);
    if (chunk + (long)csize > alen) return -1;
    unsigned int strcount = rd32(ax + chunk + 8);
    unsigned int flags = rd32(ax + chunk + 16);
    unsigned int strstart = rd32(ax + chunk + 20);
    if (strcount > 200000) return -1;
    int utf8 = (flags & 0x100) != 0;
    sp->items = (char **)calloc(strcount ? strcount : 1, sizeof(char *));
    if (!sp->items) return -1;
    sp->count = (int)strcount;
    for (unsigned int i = 0; i < strcount; i++) {
        long off_pos = chunk + (long)hsize + 4L * (long)i;
        if (off_pos + 4 > chunk + (long)csize) break;
        unsigned int o = rd32(ax + off_pos);
        long sp0 = chunk + (long)strstart + (long)o;
        if (sp0 >= alen) continue;
        if (utf8) {
            long q = sp0;
            /* 一字节：UTF-16 长度；两字节：UTF-8 字节数 */
            q += 1;
            unsigned int blen = ax[q];
            if (blen & 0x80) { blen = ((blen & 0x7fu) << 8) | ax[q + 1]; q += 1; }
            q += 1;
            if (q + (long)blen > alen) continue;
            char *s = (char *)malloc(blen + 1);
            if (!s) continue;
            memcpy(s, ax + q, blen);
            s[blen] = '\0';
            sp->items[i] = s;
        } else {
            long q = sp0;
            unsigned int clen = rd16(ax + q);
            q += 2;
            if (clen & 0x8000) { clen = ((clen & 0x7fffu) << 16) | rd16(ax + q); q += 2; }
            if (q + 2L * (long)clen > alen) continue;
            char *s = (char *)malloc((size_t)clen * 3 + 1);
            if (!s) continue;
            long w = 0;
            for (unsigned int k = 0; k < clen; k++) {
                unsigned int u = rd16(ax + q + 2L * (long)k);
                if (u < 0x80) s[w++] = (char)u;
                else if (u < 0x800) { s[w++] = (char)(0xC0 | (u >> 6)); s[w++] = (char)(0x80 | (u & 0x3F)); }
                else { s[w++] = (char)(0xE0 | (u >> 12)); s[w++] = (char)(0x80 | ((u >> 6) & 0x3F)); s[w++] = (char)(0x80 | (u & 0x3F)); }
            }
            s[w] = '\0';
            sp->items[i] = s;
        }
    }
    return 0;
}

/* ---- 遍历 AXML，收集所有 android:process 的值 ---- */

/* 往集合里加一个名字，去重；把 ":xxx" 补成 "pkg:xxx"。 */
static void add_proc(char *out, int name_len, int max, int *n, const char *pkg, const char *raw) {
    if (!raw || !*raw) return;
    char full[PKG_LEN];
    if (raw[0] == ':') snprintf(full, sizeof(full), "%s%s", pkg, raw);
    else snprintf(full, sizeof(full), "%s", raw);
    for (int i = 0; i < *n; i++) {
        if (!strcmp(out + (size_t)i * (size_t)name_len, full)) return;
    }
    if (*n >= max) return;
    snprintf(out + (size_t)(*n) * (size_t)name_len, (size_t)name_len, "%s", full);
    (*n)++;
}

static int axml_collect(const unsigned char *ax, long alen, const char *pkg,
                        char *out, int name_len, int max) {
    if (alen < 8 || rd16(ax) != 0x0003) return -1;

    strpool_t pool = { NULL, 0 };
    int found_pkg = 0;
    int n = 0;

    /* 先单独扫一遍 chunk，找到 string pool（它总在第一个） */
    long p = 8;
    while (p + 8 <= alen) {
        unsigned int ctype = rd16(ax + p);
        unsigned int csize = rd32(ax + p + 4);
        if (csize < 8 || p + (long)csize > alen) break;
        if (ctype == 0x0001) { pool_load(ax, alen, p, &pool); break; }
        p += (long)csize;
    }
    if (!pool.items) return -1;

    /* 再走一遍，处理 START_ELEMENT */
    p = 8;
    while (p + 8 <= alen) {
        unsigned int ctype = rd16(ax + p);
        unsigned int hsize = rd16(ax + p + 2);
        unsigned int csize = rd32(ax + p + 4);
        if (csize < 8 || p + (long)csize > alen) break;

        if (ctype == 0x0102) {   /* START_ELEMENT */
            long body = p + hsize;
            if (body + 8 <= alen) {
                unsigned int name_idx = rd32(ax + body + 4);
                unsigned int attr_start = rd16(ax + body + 8);
                unsigned int attr_size = rd16(ax + body + 10);
                unsigned int attr_count = rd16(ax + body + 12);
                const char *tag = (name_idx < (unsigned int)pool.count) ? pool.items[name_idx] : "";

                /* attr_size 通常是 20；用 0 当默认值兜底成 20，别硬编码 */
                if (attr_size == 0) attr_size = 20;
                for (unsigned int i = 0; i < attr_count; i++) {
                    long ap = body + (long)attr_start + (long)attr_size * (long)i;
                    if (ap + 20 > alen) break;
                    unsigned int a_name = rd32(ax + ap + 4);
                    unsigned int a_raw = rd32(ax + ap + 8);
                    unsigned int a_type = ax[ap + 15];
                    unsigned int a_data = rd32(ax + ap + 16);
                    const char *aname = (a_name < (unsigned int)pool.count) ? pool.items[a_name] : "";

                    /* package 属性只在 manifest 标签上，用来确认这份清单确实属于该包 */
                    /* 取值：优先 rawValue，没有就看 typedValue 的字符串引用。
                     * 压缩清单里两种都可能出现，只认一种就会漏（我第一次只认 rawValue，
                     * 结果 manifest 的 package 读成空，整份清单被判成"不属于该包"）。 */
                    const char *aval = NULL;
                    if (a_raw != 0xFFFFFFFFu && a_raw < (unsigned int)pool.count) aval = pool.items[a_raw];
                    else if (a_type == 0x03 && a_data < (unsigned int)pool.count) aval = pool.items[a_data];

                    if (!strcmp(tag, "manifest") && !strcmp(aname, "package")) {
                        if (aval && !strcmp(aval, pkg)) found_pkg = 1;
                        continue;
                    }
                    if (strcmp(aname, "process") != 0) continue;

                    if (aval) add_proc(out, name_len, max, &n, pkg, aval);
                }
            }
        }
        p += (long)csize;
    }

    pool_free(&pool);
    /* 清单 package 与请求的包不一致说明读错了文件，按失败处理 */
    if (!found_pkg) return -1;
    return n;
}

/* ---- pm path <pkg> → base.apk 路径 ---- */
static int find_apk(const char *pkg, char *out, size_t out_len) {
    char cmd[256];
    snprintf(cmd, sizeof(cmd), "pm path %s 2>/dev/null", pkg);
    FILE *fp = popen(cmd, "r");
    if (!fp) return -1;
    char line[PATH_LEN * 2];
    int ok = -1;
    while (fgets(line, sizeof(line), fp)) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = '\0';
        if (strncmp(line, "package:", 8) != 0) continue;
        const char *path = line + 8;
        /* 只认 base.apk：split APK 里没有清单 */
        size_t plen = strlen(path);
        if (plen < 8 || strcmp(path + plen - 8, "base.apk") != 0) continue;
        snprintf(out, out_len, "%s", path);
        ok = 0;
        break;
    }
    pclose(fp);
    return ok;
}

int decl_procs_for_pkg(const char *pkg, char *out, int name_len, int max) {
    if (!pkg || !*pkg || !out || name_len < (int)sizeof(char) * 8 || max < 1) return -1;

    char apk[PATH_LEN * 2];
    if (find_apk(pkg, apk, sizeof(apk)) != 0) return -1;

    int fd = open(apk, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    struct stat stt;
    if (fstat(fd, &stt) != 0 || stt.st_size < 22) { close(fd); return -1; }

    long mlen = 0;
    unsigned char *manifest = zip_extract(fd, (long)stt.st_size, "AndroidManifest.xml", &mlen);
    close(fd);
    if (!manifest) return -1;

    int n = axml_collect(manifest, mlen, pkg, out, name_len, max);
    free(manifest);
    if (n < 0) return -1;

    /* 主进程（= 包名）永远排第一：清单里可能一个 process 都不写，
     * 那时应用照样有主进程在跑，界面不该显示空表。 */
    int has_main = 0;
    for (int i = 0; i < n; i++) {
        if (!strcmp(out + (size_t)i * (size_t)name_len, pkg)) { has_main = 1; break; }
    }
    if (!has_main) {
        if (n < max) {
            memmove(out + (size_t)name_len, out, (size_t)n * (size_t)name_len);
            snprintf(out, (size_t)name_len, "%s", pkg);
            n++;
        }
    } else {
        /* 把主进程换到第 0 位 */
        char tmp[PKG_LEN];
        for (int i = 0; i < n; i++) {
            if (strcmp(out + (size_t)i * (size_t)name_len, pkg) == 0) {
                if (i != 0) {
                    snprintf(tmp, sizeof(tmp), "%s", out);
                    memcpy(out, out + (size_t)i * (size_t)name_len, (size_t)name_len);
                    memcpy(out + (size_t)i * (size_t)name_len, tmp, (size_t)name_len);
                }
                break;
            }
        }
    }
    return n;
}
