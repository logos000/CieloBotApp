package com.cielo.bot

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import java.io.File
import org.json.JSONObject

/**
 * NapCat WebUI 容器。
 *
 * 几个必须做对的点：
 * 1. token 是动态的，每次启动 NapCat 会改写 config/webui.json
 * 2. 所有导航都必须留在 WebView 内，不能外跳浏览器
 * 3. 只允许 127.0.0.1 / localhost，其余一律拦掉
 */
class WebActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        val w = WebView(this)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        root.addView(w, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, FrameLayout.LayoutParams(-1, 4))
        setContentView(root)

        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            mediaPlaybackRequiresUserGesture = false
        }
        w.setBackgroundColor(Color.parseColor("#EAE5E1"))

        // 任何跳转都留在 WebView 里；非本地地址直接拒绝
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                val host = req.url.host ?: return true
                return !(host == "127.0.0.1" || host == "localhost")
            }

            override fun onPageFinished(view: WebView, url: String?) {
                bar.visibility = View.GONE
            }
        }
        w.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) {
                bar.progress = p
                bar.visibility = if (p >= 100) View.GONE else View.VISIBLE
            }
        }

        val token = intent.getStringExtra("token")?.takeIf { it.isNotBlank() } ?: readToken()
        val url = if (token.isNullOrBlank()) {
            "http://127.0.0.1:6099/webui"
        } else {
            "http://127.0.0.1:6099/webui?token=$token"
        }
        w.loadUrl(url)
    }

    /** token 每次启动都可能变，直接从 NapCat 的配置里读 */
    private fun readToken(): String? = runCatching {
        val f = File(Rootfs.root(this), "app/napcat/config/webui.json")
        if (!f.exists()) return null
        JSONObject(f.readText()).optString("token").takeIf { it.isNotBlank() }
    }.getOrNull()

    override fun onBackPressed() {
        // 返回键优先回 WebView 上一页
        val w = (findViewById<FrameLayout>(android.R.id.content)).getChildAt(0) as? WebView
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }
}
