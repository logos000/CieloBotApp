#!/bin/bash
# Windows/WSL 上对已解包的 rootfs 做瘦身 + 重打包
set -u
cd /mnt/e/AAProgramming/AI_workplace/cielo/CieloBotApp || exit 1

R=build-tmp/rootfs
N="$R/app/napcat/native"

echo "=== 瘦身前 ==="
du -sh "$R"
find "$R" -type f | wc -l

# 系统
rm -rf "$R/usr/share/doc" "$R/usr/share/man" "$R/usr/share/icons"
find "$R/usr/share/locale" -mindepth 1 -maxdepth 1 \
     ! -name 'C' ! -name 'C.UTF-8' ! -name 'POSIX' -exec rm -rf {} + 2>/dev/null
rm -f "$R"/usr/lib/aarch64-linux-gnu/libLLVM-15.so.1
rm -rf "$R/usr/lib/aarch64-linux-gnu/dri"

# NapCat 多平台二进制
rm -f "$N"/napi2native/napi2native.linux.x64.node \
      "$N"/napi2native/napi2native.darwin.arm64.node \
      "$N"/napi2native/napi2native.darwin.x64.node \
      "$N"/napi2native/napi2native.win32.x64.node
rm -f "$N"/ffmpeg/ffmpegAddon.darwin.x64.node \
      "$N"/ffmpeg/ffmpegAddon.win32.x64.node \
      "$N"/ffmpeg/ffmpegAddon.linux.x64.node \
      "$N"/ffmpeg/ffmpegAddon.darwin.arm64.node
rm -f "$N"/packet/MoeHoo.darwin.arm64.node \
      "$N"/packet/MoeHoo.win32.x64.node \
      "$N"/packet/MoeHoo.darwin.x64.node \
      "$N"/packet/MoeHoo.linux.x64.node
rm -rf "$N"/pty/win32.x64 "$N"/pty/linux.x64 "$N"/dpapi

echo "=== 瘦身后 ==="
du -sh "$R"
find "$R" -type f | wc -l

# 校验关键文件
echo "--- 校验 ---"
echo "libbugly: $(find "$R" -name 'libbugly.so' | wc -l) 个"
echo "libcrbase: $(find "$R" -name 'libcrbase.so' | wc -l) 个"
echo "napcat .node: $(find "$N" -name '*.node' | wc -l) 个"
ls -1 "$N"/napi2native "$N"/ffmpeg "$N"/packet "$N"/pty/* 2>/dev/null

# 重新打包
echo "=== 重打包 ==="
rm -f app/src/main/assets/rootfs.bin
tar -C "$R" -cf - . | gzip -1 > app/src/main/assets/rootfs.bin
ls -lh app/src/main/assets/rootfs.bin
md5sum app/src/main/assets/rootfs.bin
