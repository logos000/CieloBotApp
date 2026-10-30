package com.cielo.bot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 开机 / 升级后自动拉起 QQ 运行环境与对话桥。
 *
 * 注意：部分 ROM 会在“自启动管理”里默认禁掉这个，用户需要在系统设置里放行。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // rootfs 没装就没必要起
        if (!Rootfs.ready(context)) return

        runCatching {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(Intent(context, BotService::class.java))
            } else {
                context.startService(Intent(context, BotService::class.java))
            }
        }
    }
}
