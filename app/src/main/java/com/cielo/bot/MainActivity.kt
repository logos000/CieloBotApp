package com.cielo.bot

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var stRootfs: TextView
    private lateinit var stProto: TextView
    private lateinit var stAuth: TextView
    private lateinit var stConsole: TextView
    private lateinit var qrBox: View
    private lateinit var qr: ImageView
    private lateinit var copyLog: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newSingleThreadExecutor()
    private val stamp = SimpleDateFormat("yyyy.MM.dd · HH:mm:ss", Locale.US)

    private var lastLogLen = -1L
    private var lastQrMtime = 0L
    private var refreshingQr = false
    private var autoOpened = false
    private lateinit var sdfTime: SimpleDateFormat

    private val tick = object : Runnable {
        override fun run() {
            clock.text = stamp.format(Date())
            ui.postDelayed(this, 1000)
        }
    }

    /** 每 1.5 秒刷新终端与端口 */
    private val tail = object : Runnable {
        override fun run() {
            pumpLog()
            pumpQr()
            pool.execute { refreshPort() }
            checkLogin()
            ui.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        clock = findViewById(R.id.clock)
        logScroll = findViewById(R.id.logScroll)
        stRootfs = findViewById(R.id.stRootfs)
        stProto = findViewById(R.id.stProto)
        stAuth = findViewById(R.id.stAuth)
        stConsole = findViewById(R.id.stConsole)
        qrBox = findViewById(R.id.qrBox)
        qr = findViewById(R.id.qr)
        sdfTime = SimpleDateFormat("HH:mm:ss", Locale.US)
        copyLog = findViewById(R.id.copyLog)

        findViewById<Button>(R.id.start).setOnClickListener { start() }

        copyLog.setOnClickListener { copyAll() }
        copyLog.setOnLongClickListener { exportLogs(); true }
        findViewById<TextView>(R.id.exportLog).setOnClickListener { exportLogs() }
        qrBox.setOnClickListener { forceReloadQr() }
        findViewById<Button>(R.id.open).apply {
            setOnClickListener { startActivity(Intent(this@MainActivity, ConfigActivity::class.java)) }
            setOnLongClickListener { openConsole(); true }
        }

        ui.post(tick)
        ui.post(tail)
        refreshRootfs()
        pool.execute { ui.post { refreshPort() } }


        // 后台掉线的主因是系统省电策略，首启就把三件事一次说清
        val prefs = getSharedPreferences("keepalive", MODE_PRIVATE)
        if (!prefs.getBoolean("asked", false) && !KeepAlive.isIgnoringBatteryOptimizations(this)) {
            prefs.edit().putBoolean("asked", true).apply()
            ui.postDelayed({ showKeepAliveGuide() }, 1200)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshRootfs()
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        ui.removeCallbacks(tail)
        super.onDestroy()
    }

    private fun refreshRootfs() {
        val installed = Rootfs.ready(this)
        stRootfs.text = if (installed) "OK" else "ABSENT"
        stRootfs.setTextColor(getColor(if (installed) R.color.yn_accent else R.color.yn_ink_muted))
    }

    private fun refreshPort() {
        val open = probe(6099) || probe(6100)
        ui.post {
        stConsole.text = if (open) "OPEN" else "CLOSED"
        stConsole.setTextColor(getColor(if (open) R.color.yn_accent else R.color.yn_ink_muted))
        stProto.text = if (open) "LINKED" else "IDLE"
        stProto.setTextColor(getColor(if (open) R.color.yn_accent else R.color.yn_ink_muted))
        stAuth.text = if (open) "NEGOTIATING" else "NO SESSION"
        stAuth.setTextColor(getColor(if (open) R.color.yn_accent_warm else R.color.yn_ink_muted))
        }
    }

    /** 只推增量：日志变长才重绘 */
    private fun pumpLog() {
        val root = Rootfs.root(this)
        val svc = File(root, "service.log")
        val llm = File(filesDir, "llm.log")
        val len = (if (svc.exists()) svc.length() else 0L) + (if (llm.exists()) llm.length() else 0L)
        if (len == lastLogLen) return
        lastLogLen = len

        // 每个文件只取尾部 24KB，避免日志涨大后每次全量读
        fun tailOf(f: File): String {
            if (!f.exists() || f.length() == 0L) return ""
            return runCatching {
                val total = f.length()
                val size = minOf(total, 24L * 1024).toInt()
                val buf = ByteArray(size)
                java.io.RandomAccessFile(f, "r").use { raf ->
                    raf.seek(total - size); raf.readFully(buf)
                }
                String(buf, Charsets.UTF_8)
            }.getOrDefault("")
        }

        val text = buildString {
            append(tailOf(svc))
            val l = tailOf(llm)
            if (l.isNotBlank()) {
                append("\n--- bridge ---\n")
                append(l)
            }
        }
        status.text = if (text.isBlank()) "> awaiting initialization" else text
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 二维码会过期：NapCat 重新落盘时就重载（按 mtime 判断） */
    private fun pumpQr() {
        // 已登录就不再刷新二维码 —— 登录后 NapCat 不再更新 png，
        // 继续按 mtime 判断会误认为"过期"并把 QQ 环境重启掉（会导致被踢下线）。
        if (autoOpened) return
        val f = File(Rootfs.root(this), "app/napcat/cache/qrcode.png")
        if (!f.exists() || f.length() < 200) return
        val mtime = f.lastModified()

        // 二维码约 2 分钟过期；超 100 秒没更新就重启 QQ 环境换一张
        val age = System.currentTimeMillis() - mtime
        if (age > 100_000L && qrBox.visibility == View.VISIBLE && !refreshingQr) {
            refreshingQr = true
            status.text = (status.text.toString() + "\n> qr expired, refreshing…").trim()
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            pool.execute {
                runCatching { stopService(Intent(this, BotService::class.java)) }
                Thread.sleep(1500)
                runCatching { startForegroundService(Intent(this, BotService::class.java)) }
                Thread.sleep(3000)
                lastQrMtime = 0L
                refreshingQr = false
            }
            return
        }

        if (mtime == lastQrMtime) return
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return
        lastQrMtime = mtime
        qr.setImageBitmap(bmp)
        qrBox.visibility = View.VISIBLE
        status.text = (status.text.toString() + "\n> qr refreshed " +
            sdfTime.format(Date(mtime))).trim()
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 点二维码手动重读（应对缓存或文件系统延迟） */
    private fun forceReloadQr() {
        lastQrMtime = 0L
        pumpQr()
        toast("已重新读取二维码")
    }

    /** 登录成功判据：NapCat 写出 onebot11_<uin>.json，或日志里出现登录成功 */
    /**
     * 用户按 Home 键时缩成画中画。
     *
     * 为什么：进程优先级里，可见 Activity 比前台服务高一档。
     * 只靠 Service，切后台就被厂商 ROM 回收；有个可见小窗，系统基本不动。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            runCatching {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().build()
                )
            }
        }
    }

    /** 后台保活引导：系统那层 + 厂商那层，一次说清 */
    private fun showKeepAliveGuide() {
        android.app.AlertDialog.Builder(this)
            .setTitle("让机器人在后台活着")
            .setMessage(
                "不设置的话，切到后台 1 分钟内 QQ 就会掉线。\n\n" +
                "① 点「电池优化」→ 选允许，跳过系统省电限制\n\n" +
                "② 点「自启动设置」→ 把 CieloBot 的自启动、关联启动、后台活动全部打开\n\n" +
                "③ 荣耀/华为还需要：设置 → 电池 → 更多电池设置 → 打开「休眠时始终保持网络连接」"
            )
            .setPositiveButton("电池优化") { _, _ -> runCatching { KeepAlive.requestIgnoreBattery(this) } }
            .setNeutralButton("自启动设置") { _, _ -> runCatching { KeepAlive.openAutoStartSettings(this) } }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun checkLogin() {
        if (autoOpened) return
        val root = Rootfs.root(this)
        val cfgDir = File(root, "app/napcat/config")
        val files = cfgDir.listFiles() ?: emptyArray()
        // 判据一：NapCat 写出 onebot11_<uin>.json
        val hasUin = files.any { it.name.startsWith("onebot11_") && it.name.endsWith(".json") }

        // 判据二（兜底）：二维码超过 90 秒不再更新且端口已开 —— 说明已经登录，不再需要扫码
        val qr = File(root, "app/napcat/cache/qrcode.png")
        val qrStale = qr.exists() &&
            System.currentTimeMillis() - qr.lastModified() > 90_000L &&
            (probe(6099) || probe(6100) || probe(6101))

        if (!hasUin && !qrStale) return
        autoOpened = true
        ui.post {
            status.text = (status.text.toString() + "\n> login detected, bridge ready").trim()
            stAuth.text = "ONLINE"
            stAuth.setTextColor(getColor(R.color.yn_accent))
            stProto.text = "READY"
            stProto.setTextColor(getColor(R.color.yn_accent))
            qrBox.visibility = View.GONE
            lastQrMtime = 0L
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

            // 登录后自动注入 OneBot ws 配置并拉起对话桥（已配置过才起）
            pool.execute {
                val touched = NapCatConfig.ensureWs(this)
                if (touched != null && ConfigStore.isConfigured(this)) {
                    ui.post {
                        status.text = (status.text.toString() + "\n> bridge started").trim()
                        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
                    }
                }
            }
        }
    }

    /** 扫 NapCat 日志目录尾部，找登录成功标记 */
    private fun tailContains(dir: File, keys: List<String>): Boolean {
        val logs = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return false
        val newest = logs.maxByOrNull { it.lastModified() } ?: return false
        if (System.currentTimeMillis() - newest.lastModified() > 10 * 60_000L) return false
        return runCatching {
            val len = newest.length()
            val size = minOf(len, 32L * 1024).toInt()
            val buf = ByteArray(size)
            java.io.RandomAccessFile(newest, "r").use { raf ->
                raf.seek(len - size); raf.readFully(buf)
            }
            val text = String(buf, Charsets.UTF_8)
            keys.any { text.contains(it) }
        }.getOrDefault(false)
    }

    /**
     * 把所有日志打包成一个 txt，用系统分享发出去。
     * 长按 COPY 触发。
     */
    private fun exportLogs() {
        toast("正在收集日志…")
        pool.execute {
            val root = Rootfs.root(this)
            val sb = StringBuilder()
            sb.append("=== CieloBot 诊断日志 ===\n")
            sb.append("时间: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("\n")
            sb.append("设备: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
              .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
              .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
            sb.append("rootfs ready: ").append(Rootfs.ready(this)).append("\n")
            sb.append("node exists: ").append(File(root, "usr/local/bin/node").exists()).append("\n")
            sb.append("bridge.js exists: ").append(File(root, "app/bridge.js").exists()).append("\n")
            sb.append("bridge-config exists: ").append(File(root, "app/bridge-config.json").exists()).append("\n")
            sb.append("onebot11 files: ")
                .append(File(root, "app/napcat/config").listFiles()
                    ?.filter { it.name.startsWith("onebot11") }
                    ?.joinToString { it.name } ?: "(none)")
                .append("\n\n")

            fun dump(title: String, f: File, limit: Int = 200_000) {
                sb.append("\n========== ").append(title).append(" ==========\n")
                if (!f.exists()) { sb.append("(不存在)\n"); return }
                sb.append("路径: ").append(f.absolutePath).append("\n")
                sb.append("大小: ").append(f.length()).append(" bytes\n")
                sb.append("----\n")
                runCatching {
                    val total = f.length()
                    val size = minOf(total, limit.toLong()).toInt()
                    val buf = ByteArray(size)
                    java.io.RandomAccessFile(f, "r").use { raf ->
                        if (total > size) raf.seek(total - size)
                        raf.readFully(buf)
                    }
                    sb.append(String(buf, Charsets.UTF_8))
                }.onFailure { sb.append("(读取失败: ").append(it.message).append(")\n") }
                sb.append("\n")
            }

            dump("service.log (proot 输出)", File(root, "service.log"))
            dump("bridge.log (桥日志)", File(root, "app/bridge.log"))
            dump("bridge.out (桥 stdout/stderr)", File(root, "app/bridge.out"))
            dump("llm.log (旧 Kotlin 桥)", File(filesDir, "llm.log"))
            dump("entrypoint.sh", File(root, "app/entrypoint.sh"), 20_000)
            dump("bridge-config.json", File(root, "app/bridge-config.json"), 5_000)

            val out = File(cacheDir, "cielo-log.txt")
            out.writeText(sb.toString())
            ui.post { shareFile(out) }
        }
    }

    private fun shareFile(f: File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "com.cielo.bot.files", f)
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "CieloBot 诊断日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "发送日志"))
        }.onFailure { toast("导出失败: ${it.message}") }
    }

    /** 统一入口：带 token 打开内置 WebView */
    private fun openConsole() {
        val token = runCatching {
            val f = File(Rootfs.root(this), "app/napcat/config/webui.json")
            if (!f.exists()) null
            else org.json.JSONObject(f.readText()).optString("token").takeIf { it.isNotBlank() }
        }.getOrNull()
        val i = Intent(this, WebActivity::class.java)
        if (token != null) i.putExtra("token", token)
        startActivity(i)
    }

    private fun copyAll() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = runCatching {
            File(Rootfs.root(this), "service.log").readText()
        }.getOrNull().orEmpty()
        if (text.isBlank()) {
            toast("日志为空")
            return
        }
        cm.setPrimaryClip(ClipData.newPlainText("cielo-log", text))
        toast("已复制 ${text.length} 字符")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun probe(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 400); true }
    } catch (e: Exception) {
        false
    }

    private fun start() {
        status.text = "> installing rootfs…"
        pool.execute {
            if (!Rootfs.ready(this)) {
                Rootfs.install(this) { msg -> ui.post { status.text = "> " + msg } }
            }
            ui.post {
                status.text = "> preparing onebot bridge config…"
                // 必须在 QQ 启动前写好：NapCat 启动时才读 onebot11*.json
                runCatching { NapCatConfig.ensureWs(this) }
                status.text = "> spawning napcat runtime…"
                startForegroundService(Intent(this, BotService::class.java))
                runCatching { startForegroundService(Intent(this, WatchdogService::class.java)) }
                refreshRootfs()
            }
            // 前台服务已记录所有输出到 service.log，这里只管轮询显示
        }
    }
}
