#!/bin/bash
# CieloBot 容器入口。
#
# App 启动时会用 Rootfs.applyPatches() 覆写这个文件，补上：
#   - DNS 配置
#   - 残留进程清理
#   - 快速登录（-q <uin>）
#   - OneBot 配置注入 + 桥启动
# 所以这里只保留最基础的结构，关键是那行 gosu napcat 启动命令。

trap "" SIGPIPE

cd /app || exit 1

# Xvfb：QQ 是 Chromium 应用，没有 X 会直接退出
if [ ! -S /tmp/.X11-unix/X1 ]; then
    rm -f /tmp/.X1-lock
    gosu napcat Xvfb :1 -screen 0 1080x760x16 > /tmp/xvfb.log 2>&1 &
    for i in $(seq 1 30); do
        [ -S /tmp/.X11-unix/X1 ] && break
        sleep 0.5
    done
fi

export DISPLAY=:1

# 权限：App 侧 chown 过 /app，这里再兜一次
: "${NAPCAT_UID:=0}"
: "${NAPCAT_GID:=0}"
usermod -o -u "${NAPCAT_UID}" napcat 2>/dev/null || true
groupmod -o -g "${NAPCAT_GID}" napcat 2>/dev/null || true
chown -R "${NAPCAT_UID}:${NAPCAT_GID}" /app 2>/dev/null || true

cd /app/napcat || exit 1

# 启动 QQ（NapCat 通过 loadNapCat.js 注入）
gosu napcat /opt/QQ/qq --no-sandbox --disable-setuid-sandbox \
    --disable-gpu-sandbox --disable-gpu --disable-dev-shm-usage
