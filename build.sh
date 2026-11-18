#!/usr/bin/env bash
# 编译 appkillerd 并打包成可直接安装的 KernelSU/Magisk 模块 zip
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${DSH_TOOLS:-E:/Documents/deepseek-harness/tools}"
NDK="${APPKILLER_NDK:-$TOOLS/ndk-r23c}"
API="${APPKILLER_API:-26}"
VERSION="$(sed -n 's/^version=//p' "$ROOT/module/module.prop" | tr -d '\r')"
VERSION="${VERSION:-v1.0}"

TOOLCHAIN="$(ls -d "$NDK"/toolchains/llvm/prebuilt/* 2>/dev/null | head -1)"
[ -n "$TOOLCHAIN" ] || { echo "找不到 NDK toolchain: $NDK"; exit 1; }
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
STRIP="$TOOLCHAIN/bin/llvm-strip"
[ -x "$CC" ] || { echo "找不到编译器: $CC"; exit 1; }

mkdir -p "$ROOT/out" "$ROOT/module/bin" "$ROOT/dist"

echo "==> 编译 (arm64-v8a, api $API)"
# clang 是原生 Windows 程序，不认 MSYS 的 /e/... 路径；先 cd 进工程再用相对路径
cd "$ROOT"
"$CC" -O2 -Wall -Wextra -Wno-unused-parameter \
      -Wl,--gc-sections --target=aarch64-linux-android${API} \
      -o module/bin/appkillerd src/appkillerd.c src/declprocs.c -lz
[ -x "$STRIP" ] && "$STRIP" --strip-all "$ROOT/module/bin/appkillerd" || true
cp -f "$ROOT/module/bin/appkillerd" "$ROOT/out/appkillerd"
echo "    产物 $ROOT/out/appkillerd ($(wc -c < "$ROOT/out/appkillerd") 字节)"

# 若已构建管理器 APK，一并打进模块
# 优先用 tools/sign_apk.sh 重签过的包：AGP 托管签名在 minSdk 33 下只写 v3 块，
# 重签过的才同时带 v2 + v3（规范要求）。没有就退回 AGP 产物。
APK_SRC="$ROOT/out/sign/appkiller.apk"
[ -f "$APK_SRC" ] || APK_SRC="$ROOT/app/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK_SRC" ] || APK_SRC="$ROOT/app/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_SRC" ]; then
    cp -f "$APK_SRC" "$ROOT/module/bin/appkiller.apk"
    echo "    打入管理器 APK ($(wc -c < "$ROOT/module/bin/appkiller.apk") 字节)"
else
    echo "    未找到 APK（$APK_SRC），模块将不含管理器界面"
fi

chmod 0755 "$ROOT/module/bin/appkillerd" \
           "$ROOT/module/service.sh" \
           "$ROOT/module/uninstall.sh" \
           "$ROOT/module/customize.sh" 2>/dev/null || true

echo "==> 打包"
python - "$ROOT" "$VERSION" <<'PY'
import os, sys, zipfile, time
root, ver = sys.argv[1], sys.argv[2]
mod = os.path.join(root, "module")
out = os.path.join(root, "dist", "appkiller-%s.zip" % ver)
if os.path.exists(out):
    os.remove(out)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for base, dirs, names in os.walk(mod):
        dirs[:] = [d for d in dirs if d not in ("logs", "run", "__pycache__")]
        for n in sorted(names):
            p = os.path.join(base, n)
            rel = os.path.relpath(p, mod).replace(os.sep, "/")
            if rel.startswith("logs/") or rel.startswith("run/"):
                continue
            zi = zipfile.ZipInfo(rel, time.localtime(os.path.getmtime(p))[:6])
            zi.external_attr = (0o755 if os.access(p, os.X_OK) else 0o644) << 16
            zi.compress_type = zipfile.ZIP_DEFLATED
            with open(p, "rb") as f:
                z.writestr(zi, f.read())
print("    %s (%d 字节)" % (out, os.path.getsize(out)))
PY
echo "==> 完成: $ROOT/dist/appkiller-$VERSION.zip"
