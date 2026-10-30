package com.cielo.bot

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File

class BotService : Service() {
    private var proc: Process? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel("bot", "CieloBot", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
        startForeground(1, Notification.Builder(this, "bot")
            .setContentTitle("CieloBot 运行中").setContentText("QQ 机器人在后台")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true).setOnlyAlertOnce(true).build())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cielo:bot").apply { setReferenceCounted(false); acquire() }
        launch()
        return START_STICKY
    }

    private fun launch() {
        if (proc != null) return
        Rootfs.installToolchain(this)

        val root = Rootfs.root(this)
        val bin = Rootfs.bin(this)
        val tmp = Rootfs.tmp(this)
        val nativeDir = File(applicationInfo.nativeLibraryDir)

        // 统一入口：写 bridge.js、同步配置、改 entrypoint（幂等，老 rootfs 也能直接升级）
        Rootfs.applyPatches(this)
        // 清掉残留 X socket，否则 Xvfb 会因 "server already running" 启动失败
        runCatching {
            File(root, "tmp/.X11-unix").deleteRecursively()
            File(root, "tmp").listFiles()?.filter { it.name.startsWith(".X") && it.name.endsWith("-lock") }
                ?.forEach { it.delete() }
        }



        // 这些参数缺一不可：-0 伪装 root、--link2symlink 处理 dpkg 解包、
        // -b /system /apex 复用宿主内核接口、/dev/shm 指向可写 tmp（Electron 必需）
        val cmd = listOf(
            File(bin, "proot").absolutePath,
            "-0",
            "-r", root.absolutePath,
            "--link2symlink",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/system",
            "-b", "/apex",
            "-b", "/dev/pts",
            "-b", "${tmp.absolutePath}:${tmp.absolutePath}",
            "-b", "${tmp.absolutePath}:/dev/shm",
            "-w", "/app",
            "/usr/bin/env", "-i",
            "HOME=/root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM=xterm-256color",
            "LANG=C.UTF-8",
            "/bin/bash", "/app/entrypoint.sh"
        )

        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().apply {
            // PROOT_LOADER 是关键：proot 靠它伪造 guest ELF 的 interpreter。
            // 缺这个变量，execve 一律返回 ENOENT。
            put("PROOT_LOADER", File(bin, "loader").absolutePath)
            put("PROOT_TMP_DIR", tmp.absolutePath)
            put("LD_LIBRARY_PATH", listOf(bin.absolutePath, nativeDir.absolutePath).joinToString(":"))
            put("HOME", tmp.absolutePath)
            remove("PROOT_NO_SECCOMP")
        }

        val logFile = File(root, "service.log")
        runCatching { logFile.writeText("") }
        logFile.appendText("[svc] ld_library_path=" + pb.environment()["LD_LIBRARY_PATH"] + "\n")
        logFile.appendText("[svc] proot_loader=" + pb.environment()["PROOT_LOADER"] + "\n")
        logFile.appendText("[svc] exec: " + cmd.joinToString(" ") + "\n")

        proc = pb.start()
        Thread {
            runCatching {
                proc?.inputStream?.bufferedReader()?.forEachLine { line ->
                    logFile.appendText(line + "\n")
                }
            }
            val code = runCatching { proc?.waitFor() }.getOrNull()
            logFile.appendText("[svc] proot exited: " + code + "\n")
        }.start()
    }

    /** 用户从最近任务里划掉 App 时，不要跟着停 —— 不然 QQ 会被 --kill-on-exit 一起带走 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // 什么都不做，靠 START_STICKY 继续存活
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // 先让 proot 自己收尾（它会带走 guest 进程），再兜底强杀
        runCatching { proc?.destroy() }
        proc = null
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
