package com.cielo.bot

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class ConfigActivity : Activity() {
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_config)

        val endpoint = findViewById<EditText>(R.id.endpoint)
        val apiKey = findViewById<EditText>(R.id.apiKey)
        val model = findViewById<EditText>(R.id.model)
        val persona = findViewById<EditText>(R.id.persona)
        val groupMentionOnly = findViewById<CheckBox>(R.id.groupMentionOnly)
        val replyPrivate = findViewById<CheckBox>(R.id.replyPrivate)
        val maxHistory = findViewById<EditText>(R.id.maxHistory)
        val hint = findViewById<TextView>(R.id.hint)

        val cfg = ConfigStore.load(this)
        endpoint.setText(cfg.endpoint)
        apiKey.setText(cfg.apiKey)
        model.setText(cfg.model)
        persona.setText(cfg.persona)
        groupMentionOnly.isChecked = cfg.replyGroupMentionOnly
        replyPrivate.isChecked = cfg.replyPrivate
        maxHistory.setText(cfg.maxHistory.toString())

        findViewById<TextView>(R.id.close).setOnClickListener { finish() }

        val keepHint = findViewById<TextView>(R.id.keepHint)
        keepHint.text = KeepAlive.statusText(this)
        findViewById<Button>(R.id.battery).setOnClickListener {
            KeepAlive.requestIgnoreBattery(this)
            ui.postDelayed({ keepHint.text = KeepAlive.statusText(this) }, 800)
        }
        findViewById<Button>(R.id.autostart).setOnClickListener {
            KeepAlive.openAutoStartSettings(this)
        }

        findViewById<Button>(R.id.save).setOnClickListener {
            val newCfg = LlmConfig(
                endpoint = endpoint.text.toString().trim(),
                apiKey = apiKey.text.toString().trim(),
                model = model.text.toString().trim(),
                persona = persona.text.toString().trim().ifBlank { LlmConfig().persona },
                replyGroupMentionOnly = groupMentionOnly.isChecked,
                replyPrivate = replyPrivate.isChecked,
                maxHistory = maxHistory.text.toString().toIntOrNull()?.coerceIn(2, 40) ?: 8,
            )
            if (newCfg.endpoint.isBlank()) {
                toast("endpoint 不能为空"); return@setOnClickListener
            }
            if (newCfg.apiKey.isBlank()) {
                toast("api key 不能为空"); return@setOnClickListener
            }
            ConfigStore.save(this, newCfg)

            // 桥是服务端，每条消息都重新读配置 —— 保存即生效，什么都不用重启。
            // 只更新 websocketClients，NapCat 轮询到变化会自己重连。
            val touched = NapCatConfig.ensureWs(this)

            // 桥没跑就拉起来；在跑就保持不动，别打断现有连接
            // 桥已经搬进容器（bridge.js），这里只负责把配置同步过去
            val bootErr: String? = null

            hint.text = when {
                bootErr != null -> "配置同步失败：$bootErr"
                touched != null -> "已保存 · 配置已同步到容器"
                else -> "已保存 · 等 QQ 登录后自动生效"
            }
            toast(if (touched != null) "已保存，立即生效" else "已保存（QQ 登录后生效）")
        }
    }


    /** 读容器里 bridge.js 的日志，判断桥是否连上 NapCat */
    private fun bridgeStatus(): String {
        val f = java.io.File(Rootfs.root(this), "app/bridge.log")
        if (!f.exists()) return "已保存 · 桥日志还没生成（等 QQ 启动）"
        val text = runCatching {
            val total = f.length()
            val size = minOf(total, 8L * 1024).toInt()
            val buf = ByteArray(size)
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(total - size); raf.readFully(buf)
            }
            String(buf, Charsets.UTF_8)
        }.getOrDefault("")
        return when {
            text.contains("napcat connected") -> "已保存 · NapCat 已连上桥"
            text.contains("error") -> "已保存 · 桥报错：" + (text.lines().lastOrNull { it.isNotBlank() } ?: "")
            else -> "已保存 · 桥已启动，等 NapCat 连接"
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
