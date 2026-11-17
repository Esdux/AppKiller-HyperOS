#include "../src/declprocs.c"
#include <stdio.h>
int main(int argc, char **argv) {
    if (argc < 2) { printf("usage: dtest PKG\n"); return 1; }
    const char *pkg = argv[1];
    char apk[PATH_LEN * 2];
    int r = find_apk(pkg, apk, sizeof(apk));
    printf("find_apk=%d path=%s\n", r, r == 0 ? apk : "-");
    if (r != 0) return 1;
    int fd = open(apk, O_RDONLY | O_CLOEXEC);
    struct stat stt;
    fstat(fd, &stt);
    printf("fd=%d size=%ld\n", fd, (long)stt.st_size);
    long mlen = 0;
    unsigned char *m = zip_extract(fd, (long)stt.st_size, "AndroidManifest.xml", &mlen);
    printf("zip_extract=%p len=%ld first=0x%04x\n", (void *)m, mlen, m ? rd16(m) : 0);
    if (!m) return 1;
    char names[64][PKG_LEN];
    int n = axml_collect(m, mlen, pkg, &names[0][0], PKG_LEN, 64);
    printf("axml_collect=%d\n", n);
    for (int i = 0; i < n && i < 10; i++) printf("  %s\n", names[i]);
    return 0;
}