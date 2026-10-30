# CieloBot rootfs 构建
#
# 产出 app/src/main/assets/rootfs.bin（gzip 压缩的 rootfs tar），
# 供 App 首次启动时解压到 files/rootfs。
#
# 用法（在 Windows 的 Git Bash / WSL 里）：
#   bash build-rootfs.sh
#
# 依赖：Docker（支持 linux/arm64 模拟）

set -euo pipefail

QQ_DEB_URL="https://gh-proxy.com/https://github.com/Rodert/qq-versions/releases/download/qq-packages-20260813-1d08f1d4/QQ_3.2.32_260812_arm64_01.deb"
NAPCAT_URL="https://gh-proxy.com/https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.41/NapCat.Shell.zip"

WORK="build-tmp"
OUT="app/src/main/assets/rootfs.bin"

mkdir -p "$WORK"

# 1) 下载依赖
need_download() {
  local f="$1" min="$2"
  [ ! -f "$f" ] && return 0
  local sz
  sz=$(stat -c%s "$f" 2>/dev/null || echo 0)
  [ "$sz" -lt "$min" ] && return 0
  return 1
}

if need_download "$WORK/QQ.deb" 100000000; then
  echo "[1/4] 下载 QQ arm64 deb (196 MB)…"
  rm -f "$WORK/QQ.deb"
  curl -L --fail --progress-bar -o "$WORK/QQ.deb" "$QQ_DEB_URL"
fi

if need_download "$WORK/NapCat.Shell.zip" 20000000; then
  echo "[2/4] 下载 NapCat.Shell.zip (34 MB)…"
  rm -f "$WORK/NapCat.Shell.zip"
  curl -L --fail --progress-bar -o "$WORK/NapCat.Shell.zip" "$NAPCAT_URL"
fi

# 1.5) entrypoint 必须在 build context 里（Dockerfile 从这个目录 COPY）
cp -f entrypoint.sh "$WORK/entrypoint.sh"

# 2) 构建镜像
echo "[3/4] 构建 ARM64 镜像…"
docker buildx build \
  --platform linux/arm64 \
  -f Dockerfile.rootfs \
  -t cielobot-rootfs:latest \
  --load \
  "$WORK"

# 3) 导出成 rootfs.bin
echo "[4/4] 导出 rootfs…"
CID=$(docker create --platform linux/arm64 cielobot-rootfs:latest /bin/true)
docker export "$CID" | gzip -1 > "$OUT"
docker rm "$CID" >/dev/null

ls -lh "$OUT"
echo "完成：$OUT"
echo
echo "把 $OUT 放到 app/src/main/assets/ 即可（脚本已经放在这里）。"
echo "还需要 node-arm64："
echo "  curl -L -o /tmp/node.tar.xz https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/v22.11.0/node-v22.11.0-linux-arm64.tar.xz"
echo "  tar xf /tmp/node.tar.xz"
echo "  cp node-v22.11.0-linux-arm64/bin/node app/src/main/assets/node-arm64"
