package com.cielo.bot

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 用户可配置项。全部落在 files/llm-config.json，导出/备份都方便。
 */
data class LlmConfig(
    val endpoint: String = "https://api.deepseek.com/v1",
    val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    val model: String = "deepseek-flash",
    val persona: String = "你是昔珞，一个说话自然、有情绪的女孩子。回复要短，像真人聊天，不要客套。",
    val replyGroupMentionOnly: Boolean = true,
    val replyPrivate: Boolean = true,
    val maxHistory: Int = 8,
    val temperature: Double = 0.8,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("endpoint", endpoint)
        put("apiKey", apiKey)
        put("model", model)
        put("persona", persona)
        put("replyGroupMentionOnly", replyGroupMentionOnly)
        put("replyPrivate", replyPrivate)
        put("maxHistory", maxHistory)
        put("temperature", temperature)
    }

    companion object {
        fun fromJson(o: JSONObject) = LlmConfig(
            endpoint = o.optString("endpoint", "https://api.deepseek.com/v1"),
            apiKey = o.optString("apiKey", BuildConfig.DEEPSEEK_API_KEY),
            model = o.optString("model", "deepseek-flash"),
            persona = o.optString("persona", LlmConfig().persona),
            replyGroupMentionOnly = o.optBoolean("replyGroupMentionOnly", true),
            replyPrivate = o.optBoolean("replyPrivate", true),
            maxHistory = o.optInt("maxHistory", 8),
            temperature = o.optDouble("temperature", 0.8),
        )
    }
}

object ConfigStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "llm-config.json")

    fun load(ctx: Context): LlmConfig = runCatching {
        val f = file(ctx)
        if (!f.exists()) return LlmConfig()
        val c = LlmConfig.fromJson(JSONObject(f.readText()))
        // 旧配置里的 deepseek-chat 不支持视觉，静默升级到多模态的 flash
        if (c.model == "deepseek-chat") c.copy(model = "deepseek-flash") else c
    }.getOrDefault(LlmConfig())

    fun save(ctx: Context, cfg: LlmConfig) {
        runCatching { file(ctx).writeText(cfg.toJson().toString(2)) }
        syncBridge(ctx)   // 立刻同步给容器里的 bridge.js
    }

    /** 给容器内 bridge.js 的配置（字段名与 bridge.js 对齐） */
    fun toBridgeJson(ctx: Context): String {
        val c = load(ctx)
        return JSONObject().apply {
            put("endpoint", c.endpoint)
            put("apiKey", c.apiKey)
            put("model", c.model)
            put("persona", c.persona)
            put("groupMentionOnly", c.replyGroupMentionOnly)
            put("replyPrivate", c.replyPrivate)
            put("maxHistory", c.maxHistory)
            put("temperature", c.temperature)
        }.toString(2)
    }

    /** 保存后同步写入容器里的 bridge 配置 */
    fun syncBridge(ctx: Context) {
        runCatching {
            val f = File(Rootfs.root(ctx), "app/bridge-config.json")
            if (f.parentFile?.exists() == true) f.writeText(toBridgeJson(ctx))
        }
    }

    fun isConfigured(ctx: Context): Boolean {
        val c = load(ctx)
        return c.apiKey.isNotBlank() && c.endpoint.isNotBlank()
    }
}
