package com.cielo.bot

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 看门狗：NapCat 掉线（6099 不通）就重启 BotService。
 *
 * 两层：
 * - 服务存活时每 30s 探一次端口
 * - 服务被杀后用 AlarmManager 每 15 分钟兜底唤起自己
 */
class WatchdogService : Service() {

    private val ui = Handler(Looper.getMainLooper())
    private var fails = 0
    private var wake: PowerManager.WakeLock? = null

    private val beat = object : Runnable {
        override fun run() {
            // 宽限期：容器刚启动的 3 分钟内不判定失败。
            // QQ 从 proot 起来到监听 6099 要 30~60 秒，这段时间探测必然失败，
            // 之前没有宽限期，看门狗每次启动都会把自己刚拉起的容器杀掉。
            val svcLog = File(Rootfs.root(this@WatchdogService), "service.log")
            val sinceBoot = if (svcLog.exists()) {
                System.currentTimeMillis() - svcLog.lastModified()
            } else Long.MAX_VALUE

            val grace = sinceBoot < 180_000L

            if (!grace && !probe(6099) && !probe(6100) && !probe(6101)) {
                fails++
                log("probe failed ($fails)")
                // 连续 6 次（约 90 秒）不通才重启
                if (fails >= 6) {
                    fails = 0
                    log("restarting BotService")
                    runCatching {
                        stopService(Intent(this@WatchdogService, BotService::class.java))
                        if (Build.VERSION.SDK_INT >= 26) {
                            startForegroundService(Intent(this@WatchdogService, BotService::class.java))
                        } else {
                            startService(Intent(this@WatchdogService, BotService::class.java))
                        }
                    }
                }
            } else {
                fails = 0
            }
            renewWakeLock()
            ensureServices()
            scheduleAlarm()
            ui.postDelayed(this, 15_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("wd", "Cielo Watchdog", NotificationManager.IMPORTANCE_MIN)
            )
        }
        startForeground(3, Notification.Builder(this, "wd")
            .setContentTitle("Cielo 守护中")
            .setContentText("NapCat 掉线会自动重启")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true).setOnlyAlertOnce(true).build())

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cielo:wd").apply { setReferenceCounted(false); acquire() }

        ui.removeCallbacks(beat)
        ui.postDelayed(beat, 15_000)
        return START_STICKY
    }

    private fun scheduleAlarm() {
        runCatching {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val i = Intent(this, WatchdogService::class.java)
            val pi = PendingIntent.getService(
                this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val at = System.currentTimeMillis() + 15 * 60_000
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }

    /** WakeLock 设了 30 分钟超时，这里在失效前续上，避免泄漏又不断供 */
    private fun renewWakeLock() {
        runCatching {
            val w = wake ?: return
            if (!w.isHeld) w.acquire(30 * 60 * 1000L)
        }
    }

    /** 确保 BotService 还在；不在就重新拉起（桥在容器里，跟着 QQ 一起活） */
    private fun ensureServices() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        fun running(cls: String): Boolean =
            am.getRunningServices(200).any { it.service.className.endsWith(cls) }

        if (!running("BotService")) {
            log("BotService missing, restarting")
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, BotService::class.java))
                else startService(Intent(this, BotService::class.java))
            }
        }
    }

    private fun probe(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500); true }
    } catch (e: Exception) {
        false
    }

    private fun log(msg: String) {
        runCatching {
            File(Rootfs.root(this), "watchdog.log").appendText(
                java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + " " + msg + "\n"
            )
        }
    }

    override fun onDestroy() {
        ui.removeCallbacks(beat)
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
