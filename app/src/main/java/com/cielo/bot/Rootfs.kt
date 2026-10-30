package com.cielo.bot

import android.content.Context
import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/**
 * rootfs 与 proot 工具链的管理。
 *
 * 关键：Android 10+ 禁止从应用私有目录 exec（W^X）。
 * proot / loader / talloc 必须先由系统从 jniLibs 解到 nativeLibraryDir，
 * 再「软链」到 files/bin —— 不能复制，复制出来的副本执行时会 ENOENT。
 */
object Rootfs {
    fun root(ctx: Context) = File(ctx.filesDir, "rootfs")
    fun bin(ctx: Context) = File(ctx.filesDir, "bin")
    fun tmp(ctx: Context) = File(ctx.filesDir, "tmp").apply { mkdirs() }
    fun ready(ctx: Context) = File(root(ctx), "app/entrypoint.sh").exists()

    private const val NL = "\n"

    /**
     * 幂等补丁：把 bridge.js 放进容器、改 entrypoint 启动它、同步配置。
     * 解压后调用一次，之后每次启动前也调用 —— 老用户不需要重新解压 900MB。
     */
    fun applyPatches(ctx: Context) {
        val root = root(ctx)
        if (!File(root, "app").exists()) return

        // 1) bridge.js
        runCatching {
            ctx.assets.open("bridge.js").use { input ->
                File(root, "app/bridge.js").outputStream().use { input.copyTo(it) }
            }
        }

        // 1.2) OneBot 配置补丁脚本
        runCatching {
            ctx.assets.open("setup-onebot.js").use { input ->
                File(root, "app/setup-onebot.js").outputStream().use { input.copyTo(it) }
            }
        }

        // 1.5) 真 Node 运行时：不借 QQ 的 Electron（它不认 ELECTRON_RUN_AS_NODE）
        runCatching {
            val nodeBin = File(root, "usr/local/bin/node")
            val needCopy = !nodeBin.exists() || nodeBin.length() < 50L * 1024 * 1024
            if (needCopy) {
                nodeBin.parentFile?.mkdirs()
                ctx.assets.open("node-arm64").use { input ->
                    nodeBin.outputStream().use { input.copyTo(it) }
                }
                runCatching { Os.chmod(nodeBin.absolutePath, 0b111101101) } // 0755
            }
        }

        // 2) 容器里的桥配置（旧配置里不支持视觉的模型就地升级）
        runCatching {
            val cfg = File(root, "app/bridge-config.json")
            if (!cfg.exists()) {
                cfg.writeText(ConfigStore.toBridgeJson(ctx))
            } else {
                val c = ConfigStore.load(ctx)
                val fresh = ConfigStore.toBridgeJson(ctx)
                // model 变了就整份重写，其余情况保留用户在 App 里的最新设置
                if (c.model == "deepseek-flash" && !cfg.readText().contains("deepseek-flash")) {
                    cfg.writeText(fresh)
                }
            }
        }

        // 3) entrypoint：先起桥，再起 QQ
        runCatching {
            val ep = File(root, "app/entrypoint.sh")
            if (!ep.exists()) return@runCatching
            var txt = ep.readText()

            // 启动前清掉上次残留的 QQ / NapCat 进程。
            // 系统强杀 proot 时 guest 会变孤儿，继续占着 QQ 登录态，
            // 新实例一启动就报「当前账号已登录，无法重复登录」→ 表现为被踢下线要重扫码。
            if (!txt.contains("# cielo-cleanup")) {
                val D = "${'$'}"
                val cleanup = """
# cielo-cleanup: 清掉上一个实例遗留的 QQ/NapCat
for _p in /proc/[0-9]*; do
  _pid=${D}{_p#/proc/}
  [ "${D}_pid" = "${D}${D}" ] && continue
  _cmd=${D}(tr '\0' ' ' < "${D}_p/cmdline" 2>/dev/null)
  case "${D}_cmd" in
    *"/opt/QQ/"*|*"/app/napcat/"*|*"/app/bridge.js"*)
      kill -9 "${D}_pid" 2>/dev/null
      ;;
  esac
done
sleep 1
""".trimIndent()
                val lines = txt.split(NL).toMutableList()
                val insertAt = if (lines.isNotEmpty() && lines[0].startsWith("#!")) 1 else 0
                lines.add(insertAt, cleanup)
                txt = lines.joinToString(NL)
            }

            // DNS：proot 容器没有自己的 resolv.conf，或者内容指向宿主机的无效地址，
            // 会导致 getaddrinfo EAI_AGAIN（连 api.deepseek.com 直接失败）。
            if (!txt.contains("# cielo-dns")) {
                val dns = buildString {
                    append("# cielo-dns: 容器内 DNS")
                    append(NL)
                    append("mkdir -p /etc")
                    append(NL)
                    append("if [ ! -s /etc/resolv.conf ] || ! grep -q 'nameserver' /etc/resolv.conf 2>/dev/null; then")
                    append(NL)
                    append("  echo 'nameserver 223.5.5.5' > /etc/resolv.conf")
                    append(NL)
                    append("  echo 'nameserver 119.29.29.29' >> /etc/resolv.conf")
                    append(NL)
                    append("  echo 'nameserver 8.8.8.8' >> /etc/resolv.conf")
                    append(NL)
                    append("fi")
                    append(NL)
                }
                val lines = txt.split(NL).toMutableList()
                val at = if (lines.isNotEmpty() && lines[0].startsWith("#!")) 1 else 0
                lines.add(at, dns)
                txt = lines.joinToString(NL)
            }

            // 快速登录：从已登录的 onebot11_<uin>.json 里取出 QQ 号。
            // 没这一步，容器每次被系统重启都会退回扫码登录。
            if (!txt.contains("# cielo-quicklogin")) {
                val D = "${'$'}"
                val ql = buildString {
                    append("# cielo-quicklogin: 已登录过就用 -q 快速登录")
                    append(NL)
                    append("CIELO_UIN=${D}(ls /app/napcat/config/onebot11_*.json 2>/dev/null | head -1 | sed -e 's|.*onebot11_||' -e 's|[.]json||')")
                    append(NL)
                    append("if [ -n \"${D}CIELO_UIN\" ]; then")
                    append(NL)
                    append("  export ACCOUNT=\"${D}CIELO_UIN\"")
                    append(NL)
                    append("  echo \"[entrypoint] quick login as ${D}CIELO_UIN\"")
                    append(NL)
                    append("fi")
                    append(NL)
                }
                val lines = txt.split(NL).toMutableList()
                val at = if (lines.isNotEmpty() && lines[0].startsWith("#!")) 1 else 0
                lines.add(at, ql)
                txt = lines.joinToString(NL)
            }

            if (!txt.contains("/app/setup-onebot.js")) {
                val bridgeCmd = buildString {
                    // 先写 OneBot 反向连接配置 —— 必须在 QQ 启动前
                    append("gosu napcat /usr/local/bin/node /app/setup-onebot.js > /app/bridge.out 2>&1")
                    append(NL)
                    // 再起桥（后台），然后才是 QQ
                    append("gosu napcat /usr/local/bin/node /app/bridge.js >> /app/bridge.out 2>&1 &")
                    append(NL)
                    append("sleep 1")
                    append(NL)
                }

                // 先把旧版留下的单行 bridge 启动命令删掉，避免重复起
                txt = txt.replace(
                    Regex("""gosu napcat (env ELECTRON_RUN_AS_NODE=1 )?/opt/QQ/qq --no-sandbox /app/bridge\.js[^\n]*\n"""),
                    ""
                )
                txt = txt.replace(
                    Regex("""gosu napcat /usr/local/bin/node /app/bridge\.js[^\n]*\n"""),
                    ""
                )

                val plain = "gosu napcat /opt/QQ/qq --no-sandbox"
                val patched = "gosu napcat /opt/QQ/qq --no-sandbox --disable-setuid-sandbox"

                txt = when {
                    txt.contains(patched) -> txt.replaceFirst(patched, bridgeCmd + patched)
                    txt.contains(plain) -> txt.replace(
                        plain,
                        bridgeCmd + patched +
                            " --disable-gpu-sandbox --disable-gpu --disable-dev-shm-usage"
                    )
                    else -> txt
                }
            }
            ep.writeText(txt)
        }
    }

    /** jniLibs 文件名 → bin 目录里的可执行名 */
    private val toolchain = listOf(
        "libproot.so" to "proot",
        "libloader.so" to "loader",
        "liblibtalloc.so.2.so" to "libtalloc.so.2",
    )

    /** 在 files/bin 里建立指向 nativeLibraryDir 的软链 */
    fun installToolchain(ctx: Context) {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val binDir = bin(ctx).apply { mkdirs() }
        toolchain.forEach { (srcName, dstName) ->
            val src = File(nativeDir, srcName)
            if (!src.exists()) return@forEach
            val dst = File(binDir, dstName)
            val isLink = runCatching { Files.isSymbolicLink(dst.toPath()) }.getOrDefault(false)
            val current = runCatching { Files.readSymbolicLink(dst.toPath()).toString() }
                .getOrDefault("")
            if (!isLink || current != src.absolutePath) {
                runCatching { dst.delete() }
                val linked = runCatching { Os.symlink(src.absolutePath, dst.absolutePath) }.isSuccess
                if (!linked) {
                    // 少数 ROM 限制 symlink，退化为复制
                    runCatching { src.copyTo(dst, overwrite = true) }
                    runCatching { Os.chmod(dst.absolutePath, 0x1ED) } // 0755
                }
            }
            runCatching { Os.chmod(src.absolutePath, 0x1ED) }
        }
    }

    fun install(ctx: Context, onProgress: (String) -> Unit) {
        val root = root(ctx); root.mkdirs()
        installToolchain(ctx)
        onProgress("解压系统文件…")

        ctx.assets.open("rootfs.bin").use { raw ->
            GzipCompressorInputStream(raw.buffered()).use { gz ->
                TarArchiveInputStream(gz).use { tar ->
                    var n = 0
                    while (true) {
                        val e = tar.nextEntry ?: break
                        val out = File(root, e.name)
                        if (!out.canonicalPath.startsWith(root.canonicalPath)) continue
                        when {
                            e.isDirectory -> out.mkdirs()
                            e.isSymbolicLink -> runCatching {
                                out.parentFile?.mkdirs()
                                runCatching { out.delete() }
                                Os.symlink(e.linkName, out.absolutePath)
                            }
                            e.isLink -> runCatching {
                                val t = File(root, e.linkName)
                                out.parentFile?.mkdirs(); t.copyTo(out, overwrite = true)
                            }
                            else -> {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { o -> tar.copyTo(o) }
                                if (e.mode and 0b001001001 != 0) out.setExecutable(true, false)
                            }
                        }
                        if (++n % 2000 == 0) onProgress("解压中… $n 个文件")
                    }
                }
            }
        }

        applyPatches(ctx)
        onProgress("安装完成")
    }
}
