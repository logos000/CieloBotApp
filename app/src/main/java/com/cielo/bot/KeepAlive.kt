package com.cielo.bot

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 保活相关的系统设置入口。
 *
 * Android 上后台被杀主要是三件事：
 * 1. 电池优化（Doze）—— 加白名单可以显著降低被杀概率
 * 2. 厂商自启动管理 —— 必须用户手动放行，代码无法绕过
 * 3. 内存压力 —— 前台服务 + WakeLock 已经是能做的极限
 */
object KeepAlive {

    fun isIgnoringBatteryOptimizations(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 弹系统对话框请求加入电池优化白名单 */
    fun requestIgnoreBattery(ctx: Context) {
        if (Build.VERSION.SDK_INT < 23) return
        if (isIgnoringBatteryOptimizations(ctx)) return
        runCatching {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:${ctx.packageName}")
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }.onFailure {
            // 部分 ROM 屏蔽了这个 action，退回到应用详情页
            runCatching {
                val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                i.data = Uri.parse("package:${ctx.packageName}")
                if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            }
        }
    }

    /** 打开系统里本应用的自启动 / 后台管理页（各家 ROM 路径不同，尽量猜） */
    fun openAutoStartSettings(ctx: Context) {
        val candidates = listOf(
            // 小米
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // OPPO
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            // vivo
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            // 三星
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        candidates.forEach { (pkg, cls) ->
            runCatching {
                val i = Intent().setClassName(pkg, cls)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            }
        }
        // 都打不开就进应用详情
        runCatching {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:${ctx.packageName}")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        }
    }

    fun statusText(ctx: Context): String {
        val battery = if (isIgnoringBatteryOptimizations(ctx)) "已豁免" else "未豁免"
        return "电池优化: $battery"
    }
}
