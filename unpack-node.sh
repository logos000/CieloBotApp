#!/bin/bash
# 把 nodejs + libnode108 解包进 rootfs，并重新打包
set -u
cd /mnt/e/AAProgramming/AI_workplace/cielo/CieloBotApp || exit 1

R=build-tmp/rootfs
B=build-tmp

echo "=== 解包 ==="
for d in "$B/nodejs.deb" "$B/libnode108.deb"; do
  [ -f "$d" ] || { echo "缺少 $d"; exit 1; }
  echo "  $(basename "$d")"
  dpkg-deb -x "$d" "$R"
done

echo "=== 校验 ==="
ls -lh "$R/usr/bin/node" 2>/dev/null || echo "!! /usr/bin/node 缺失"
ls -lh "$R"/usr/lib/aarch64-linux-gnu/libnode.so.* 2>/dev/null | head -2
echo "文件类型: $(file -b "$R/usr/bin/node" 2>/dev/null | cut -c1-70)"
echo "rootfs 大小: $(du -sh "$R" | cut -f1)  文件数: $(find "$R" -type f | wc -l)"

echo "=== 重新打包 ==="
rm -f app/src/main/assets/rootfs.bin
tar -C "$R" -cf - . | gzip -1 > app/src/main/assets/rootfs.bin
ls -lh app/src/main/assets/rootfs.bin
md5sum app/src/main/assets/rootfs.bin
