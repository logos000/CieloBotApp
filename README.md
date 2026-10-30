# CieloBot

Android 上跑一个 QQ 机器人：**proot 容器 + NapCat + 自带 Node 桥**，全部落在手机本地，不依赖服务器。

## 为什么是这套架构

Android 10+ 禁止从应用私有目录执行二进制（W^X），而 QQ 是 Linux 程序，不能直接跑。所以：

```
BotService（Android 前台服务，只负责 proot 生命周期）
  └─ proot 容器（Debian bookworm-slim，974 MB rootfs）
      ├─ /usr/local/bin/node /app/bridge.js   ← LLM 桥（后台）
      └─ /opt/QQ/qq                            ← NapCat（前台）
```

**桥放在容器里，不放 Android Service**——这是 AstrMobile 的做法，好处是桥和 QQ 同生共死，不额外受 Android 后台服务限制。

## 数据流

```
QQ 消息
  → NapCat（websocketClients 反向连接）
    → bridge.js:3001
      → OpenAI 兼容接口
        → 回复走 send_msg
```

**用 `websocketClients` 而不是 `websocketServers`**：前者 NapCat 会轮询配置自动重连，改端口不用重启 QQ；后者只在启动时读一次。

## 目录

```
app/src/main/java/com/cielo/bot/
  BotService.kt        proot 生命周期 + WakeLock
  Rootfs.kt            rootfs 解压、工具链软链、entrypoint 补丁
  WatchdogService.kt   端口探活 + 断线重启（带 3 分钟启动宽限期）
  BootReceiver.kt      开机 / 升级后自启
  KeepAlive.kt         电池优化白名单、厂商自启动入口
  MainActivity.kt      yinian 风格主界面 + 终端日志 + 画中画
  ConfigActivity.kt    LLM 配置（endpoint / key / model / persona）
  LlmConfig.kt         配置读写（key 走 BuildConfig）
  NapCatConfig.kt      App 侧的 OneBot 配置兜底（容器内以 setup-onebot.js 为主）
  WebActivity.kt       NapCat WebUI 内置浏览器

app/src/main/assets/
  bridge.js            ★ 桥本体（Node，跑在容器里）
  setup-onebot.js      ★ 启动前写 websocketClients 配置
  rootfs.bin           ⚠ 不入库，见下
  node-arm64           ⚠ 不入库，见下
```

## 构建

### 1. 准备不入库的大文件

```bash
# Node 运行时（ARM64）
curl -L -o /tmp/node.tar.xz \
  https://mirrors.tuna.tsinghua.edu.cn/nodejs-release/v22.11.0/node-v22.11.0-linux-arm64.tar.xz
tar xf /tmp/node.tar.xz
cp node-v22.11.0-linux-arm64/bin/node app/src/main/assets/node-arm64
chmod +x app/src/main/assets/node-arm64

# rootfs（974 MB 解压后，gzip 后约 428 MB）
# 构建方式见 napcat-slim 项目：Debian bookworm-slim + NapCat + QQ
docker save ... | docker export ... | gzip -1 > app/src/main/assets/rootfs.bin
```

### 2. 密钥

```bash
# local.properties（不进 git）
echo "DEEPSEEK_API_KEY=sk-你的key" >> local.properties
echo "sdk.dir=$HOME/Library/Android/sdk" >> local.properties
```

`build.gradle.kts` 会把它注入成 `BuildConfig.DEEPSEEK_API_KEY`，App 里仍可手动改。

### 3. 编译

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug     # 产物约 475 MB
```

## 首次使用

1. 装 APK → 打开 → 按提示做保活设置（**电池优化白名单 + 厂商自启动**，不做切后台必掉线）
2. 点 `INITIALIZE` → 解压 rootfs（约 2 分钟）
3. 扫码登录 QQ
4. `CONFIG` 里填 API key 和模型 → `SAVE CONFIG`
5. QQ 里发私聊

按 Home 键会缩成画中画小窗，进程优先级提高一档，不容易被系统回收。

## 已知限制

- **厂商 ROM 的后台管理绕不过**：小米/华为/荣耀/OPPO 都要手动在系统设置里放行
- **rootfs 475 MB 的 APK**：rootfs 和 Node 都在包里
- **单账号**：一个容器一个 QQ
- **上下文在进程内存里**：容器重启后对话历史清空

## 排错

主界面终端标题栏：

- **`COPY`** 复制当前日志
- **`LOG`** 打包完整诊断日志（分享出来发人）

日志里重点看：

```
[setup] patched onebot11.json        ← 配置写入成功
listening on 127.0.0.1:3001          ← 桥启动
napcat connected from 127.0.0.1      ← QQ 连上桥
recv [p123456] 你好                   ← 收到消息
llm request / llm response           ← 模型调用
replied: xxx                          ← 回复发出
```

缺哪一段就说明卡在哪一步。

常见错误：

| 现象 | 原因 |
|---|---|
| `getaddrinfo EAI_AGAIN` | 容器 DNS 不通，entrypoint 会写 resolv.conf |
| `proot error: execve(...): ENOENT` | proot/loader 不在 nativeLibraryDir 或没设 PROOT_LOADER |
| `当前账号已登录,无法重复登录` | 上次的 QQ 成了孤儿进程，entrypoint 的 cleanup 会清 |
| `Failed to find configured root` | FileProvider 的 file_paths.xml 缺 cache-path |
