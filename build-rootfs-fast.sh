# CieloBot rootfs 构建 —— 快版
#
# 核心思路：QEMU 慢在「执行」不在「下载」。
#   apt install      → 下载 + 跑 100 多个 postinst 脚本（ldconfig/update-alternatives/字体缓存）→ 15~25 分钟
#   apt install -d   → 只下载 deb 到 /var/cache/apt/archives                        → 1~2 分钟
#   dpkg-deb -x      → 纯解包，宿主机 x86 原生执行                                   → 几秒
#
# 所以：arm64 容器里只负责「解析依赖 + 下载」，解包全部交给宿主机。
#
# 用法（Windows 的 WSL 里）：bash build-rootfs-fast.sh

set -euo pipefail

QQ_DEB_URL="https://gh-proxy.com/https://github.com/Rodert/qq-versions/releases/download/qq-packages-20260813-1d08f1d4/QQ_3.2.32_260812_arm64_01.deb"
NAPCAT_URL="https://gh-proxy.com/https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.41/NapCat.Shell.zip"
BASE_IMAGE="arm64v8/debian:bookworm-slim"

WORK="build-tmp"
OUT="app/src/main/assets/rootfs.bin"
ROOTFS="$WORK/rootfs"

mkdir -p "$WORK"

# 需要装的包（和 Dockerfile.rootfs 一致，去掉无头用不上的 fonts-noto-cjk）
PKGS="ca-certificates curl unzip jq procps gosu xvfb dbus-x11 \
libgtk-3-0 libgtk2.0-0 libnotify4 libnss3 libxss1 libxtst6 \
libatspi2.0-0 libsecret-1-0 libgbm1 libasound2 \
libxcb1 libxkbcommon0 libpango-1.0-0 libcairo2"

# ---------- 1. 下载素材 ----------
need_download() {
  local f="$1" min="$2" sz
  [ ! -f "$f" ] && return 0
  sz=$(stat -c%s "$f" 2>/dev/null || echo 0)
  [ "$sz" -lt "$min" ] && return 0
  return 1
}

if need_download "$WORK/QQ.deb" 100000000; then
  echo "[1/5] 下载 QQ arm64 deb…"
  rm -f "$WORK/QQ.deb"
  curl -L --fail --progress-bar -o "$WORK/QQ.deb" "$QQ_DEB_URL"
fi

if need_download "$WORK/NapCat.Shell.zip" 20000000; then
  echo "[2/5] 下载 NapCat…"
  rm -f "$WORK/NapCat.Shell.zip"
  curl -L --fail --progress-bar -o "$WORK/NapCat.Shell.zip" "$NAPCAT_URL"
fi

# ---------- 2. 只在 arm64 里下载依赖（不执行脚本） ----------
echo "[3/5] 解析并下载 arm64 依赖包（只下载，不安装）…"
rm -rf "$WORK/debs"
mkdir -p "$WORK/debs"

docker run --rm --platform linux/arm64 \
  -v "$(pwd)/$WORK/debs:/out" \
  "$BASE_IMAGE" bash -c "
    set -e
    apt-get update -qq
    apt-get install -y -d --no-install-recommends $PKGS
    cp /var/cache/apt/archives/*.deb /out/
    echo \"downloaded: \$(ls /out/*.deb | wc -l) debs\"
  "

# ---------- 3. 底座 ----------
echo "[4/5] 铺底座 + 解包…"
rm -rf "$ROOTFS"
mkdir -p "$ROOTFS"

CID=$(docker create --platform linux/arm64 "$BASE_IMAGE" /bin/true)
docker export "$CID" | tar -x -C "$ROOTFS"
docker rm "$CID" >/dev/null

# 解包依赖（纯文件操作，不执行任何 postinst）
for d in "$WORK"/debs/*.deb; do
  dpkg-deb -x "$d" "$ROOTFS" 2>/dev/null || true
done
echo "  解包 $(ls "$WORK"/debs/*.deb | wc -l) 个依赖包"

# QQ
dpkg-deb -x "$WORK/QQ.deb" "$ROOTFS"
echo "  QQ → /opt/QQ"

# NapCat
mkdir -p "$ROOTFS/app/napcat/config"
unzip -q "$WORK/NapCat.Shell.zip" -d "$WORK/napcat-unzip"
cp -rf "$WORK/napcat-unzip"/* "$ROOTFS/app/napcat/"
rm -rf "$WORK/napcat-unzip"
echo "  NapCat → /app/napcat"

# entrypoint
cp -f entrypoint.sh "$ROOTFS/app/entrypoint.sh"
chmod +x "$ROOTFS/app/entrypoint.sh"

# napcat 用户（用固定 uid/gid，避免宿主机 useradd 差异）
mkdir -p "$ROOTFS/home/napcat"
grep -q '^napcat:' "$ROOTFS/etc/passwd" 2>/dev/null || \
  echo "napcat:x:1000:1000::/home/napcat:/bin/bash" >> "$ROOTFS/etc/passwd"
grep -q '^napcat:' "$ROOTFS/etc/group" 2>/dev/null || \
  echo "napcat:x:1000:" >> "$ROOTFS/etc/group"
chown -R 1000:1000 "$ROOTFS/app" "$ROOTFS/home/napcat" 2>/dev/null || true

# ---------- 4. 打包 ----------
echo "[5/5] 打包 rootfs.bin…"
tar -C "$ROOTFS" -cf - . | gzip -1 > "$OUT"

ls -lh "$OUT"
echo
echo "完成。rootfs 内容：$(du -sh "$ROOTFS" | cut -f1)，$(find "$ROOTFS" -type f | wc -l) 个文件"
