#!/bin/bash
# 给 Windows 上的 rootfs 补 Node 运行时（arm64）
# 用 apt 只下载 deb，再解包到 rootfs，不执行任何脚本
set -u
cd /mnt/e/AAProgramming/AI_workplace/cielo/CieloBotApp || exit 1

R=build-tmp/rootfs
D=build-tmp/nodejs-debs

rm -rf "$D"
mkdir -p "$D"

echo "=== 下载 arm64 nodejs deb ==="
docker run --rm --platform linux/arm64 \
  -v "$(pwd)/$D:/out" \
  arm64v8/debian:bookworm-slim \
  bash -c "apt-get update -qq && apt-get install -y -d --no-install-recommends nodejs && cp /var/cache/apt/archives/*.deb /out/ && ls -lh /out"

echo "=== 解包到 rootfs ==="
for d in "$D"/*.deb; do
  echo "  $(basename "$d")"
  dpkg-deb -x "$d" "$R"
done

echo "=== 校验 ==="
ls -lh "$R/usr/bin/node" 2>/dev/null || echo "!! /usr/bin/node 不存在"
find "$R" -maxdepth 3 -name 'node' -type f 2>/dev/null | head -5
echo "node 文件数: $(find "$R" -path '*node*' -type f 2>/dev/null | wc -l)"
echo "libnode: $(find "$R" -name 'libnode*' 2>/dev/null | head -3)"

echo "=== 重新打包 rootfs.bin ==="
rm -f app/src/main/assets/rootfs.bin
tar -C "$R" -cf - . | gzip -1 > app/src/main/assets/rootfs.bin
ls -lh app/src/main/assets/rootfs.bin
md5sum app/src/main/assets/rootfs.bin
