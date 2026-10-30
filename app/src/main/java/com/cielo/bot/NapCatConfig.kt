package com.cielo.bot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 让 NapCat 主动连到我们自己的 WebSocket 服务端（反向连接）。
 *
 * 为什么用 clients 而不是 servers：
 * - websocketServers（NapCat 监听）只在启动时读一次，改配置必须重启 QQ
 * - websocketClients（NapCat 往外连）内部有 NetWorkReload 轮询，改配置会自动重连
 *
 * 配置文件带 QQ 号（onebot11_<uin>.json），登录后才生成；
 * 没登录时退回写 onebot11.json，NapCat 登录后会以它为模板。
 */
object NapCatConfig {

    const val WS_PORT = 3001

    private fun configDir(ctx: android.content.Context) =
        File(Rootfs.root(ctx), "app/napcat/config")

    /** 返回被修改的配置文件路径，没有可写的则 null */
    fun ensureWs(ctx: android.content.Context): String? {
        val dir = configDir(ctx)
        if (!dir.isDirectory) return null

        // 只改 NapCat 自己生成的配置。不预置 onebot11.json ——
        // 预置会让 NapCat 跳过「按账号拆分 onebot11_<uin>.json」的流程，
        // 导致登录成功后我们检测不到。
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("onebot11_") && f.name.endsWith(".json")
        }?.toList() ?: emptyList()
        if (files.isEmpty()) return null

        var touched: String? = null
        files.forEach { f ->
            runCatching {
                val root = if (f.exists() && f.length() > 2) JSONObject(f.readText()) else JSONObject()
                val network = root.optJSONObject("network") ?: JSONObject().also { root.put("network", it) }
                // 旧版用的是 websocketServers（NapCat 监听 3001），会和我们的服务端抢端口，必须清掉
                network.optJSONArray("websocketServers")?.let { servers ->
                    val kept = JSONArray()
                    for (i in 0 until servers.length()) {
                        val s = servers.optJSONObject(i) ?: continue
                        if (s.optString("name") != "cielo-ws") kept.put(s)
                    }
                    network.put("websocketServers", kept)
                }

                val clients = network.optJSONArray("websocketClients") ?: JSONArray().also {
                    network.put("websocketClients", it)
                }

                var found = false
                for (i in 0 until clients.length()) {
                    val c = clients.optJSONObject(i) ?: continue
                    if (c.optString("name") == "cielo-bridge") {
                        found = true
                        c.put("enable", true)
                        c.put("url", "ws://127.0.0.1:$WS_PORT")
                        c.put("token", "")
                        c.put("messagePostFormat", "array")
                        c.put("reportSelfMessage", false)
                        c.put("debug", false)
                        c.put("heartInterval", 30000)
                        c.put("reconnectInterval", 3000)
                    }
                }
                if (!found) {
                    clients.put(JSONObject().apply {
                        put("name", "cielo-bridge")
                        put("enable", true)
                        put("url", "ws://127.0.0.1:$WS_PORT")
                        put("token", "")
                        put("messagePostFormat", "array")
                        put("reportSelfMessage", false)
                        put("debug", false)
                        put("heartInterval", 30000)
                        put("reconnectInterval", 3000)
                    })
                }
                f.writeText(root.toString(2))
                touched = f.absolutePath
            }
        }
        return touched
    }

    /** 登录态判断：存在 onebot11_<uin>.json 即已登录过 */
    fun loggedIn(ctx: android.content.Context): Boolean {
        val dir = configDir(ctx)
        if (!dir.isDirectory) return false
        return dir.listFiles { f ->
            f.isFile && f.name.startsWith("onebot11_") && f.name.endsWith(".json")
        }?.isNotEmpty() == true
    }
}
