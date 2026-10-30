package com.dsh.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Thin shell UI: a status screen while the runtime installs and the server
 * boots, then a fullscreen WebView pointed at the local tokenized URL.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var subStatus: TextView
    private lateinit var webView: WebView
    private var loaded = false
    /**
     * Last server URL, so back can recover the shell UI after a browser
     * panel page frame-busts its way to the top frame (top-level navigation
     * replaces the whole history, leaving nothing to go back to).
     */
    private var serverUrl: String? = null

    companion object {
        /**
         * Set when the user taps stop. System-initiated service stops (seen
         * once right after a package replace) must not resurrect the server
         * against the user's will, while a dead-but-not-stopped server should
         * come back the next time the activity is visible — onCreate alone
         * never runs again in that session.
         */
        @Volatile var userStopped = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getStringExtra(DshService.EXTRA_STATE) ?: return
            val detail = intent.getStringExtra(DshService.EXTRA_DETAIL) ?: ""
            val url = intent.getStringExtra(DshService.EXTRA_URL)
            subStatus.text = detail
            when (state) {
                DshService.STATE_RUNNING, DshService.STATE_STARTING -> {
                    status.text = if (state == DshService.STATE_RUNNING) "DeepSeek Harness" else "启动中…"
                    if (state == DshService.STATE_RUNNING && url != null && !loaded) {
                        loadUi(url)
                    }
                }
                DshService.STATE_ERROR -> status.text = "出错了"
                DshService.STATE_STOPPED -> {
                    status.text = "已停止"
                    loaded = false
                    webView.visibility = View.GONE
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0B0E14"))

        // ---- status screen
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER

        status = TextView(this).apply {
            text = "DeepSeek Harness"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        subStatus = TextView(this).apply {
            text = "初始化…"
            textSize = 14f
            setTextColor(Color.parseColor("#8B94A7"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        val stopBtn = Button(this).apply {
            text = "停止服务器"
            setOnClickListener {
                userStopped = true
                startService(Intent(this@MainActivity, DshService::class.java)
                    .setAction(DshService.ACTION_STOP))
            }
        }
        box.addView(status, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(subStatus, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        box.addView(stopBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(24)
        })
        root.addView(box, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // ---- web ui
        webView = WebView(this).apply {
            visibility = View.GONE
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                textZoom = 100
                allowFileAccess = false
                allowContentAccess = false
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    // Keep everything inside the local origin; external links
                    // would have no browser to fall back to in kiosk use.
                    val host = request?.url?.host
                    return host != null && host != "127.0.0.1" && host != "localhost"
                }
            }
        }
        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        // Split proxy policy: loopback (the local dsh UI) must stay DIRECT or
        // a system-wide proxy wedges the whole shell; everything else — the
        // browser panel's external pages — goes through the host clash tunnel
        // (adb reverse tcp:7890), the device's only TLS-trustworthy egress.
        if (androidx.webkit.WebViewFeature.isFeatureSupported(
                androidx.webkit.WebViewFeature.PROXY_OVERRIDE)) {
            runCatching {
                androidx.webkit.ProxyController.getInstance().setProxyOverride(
                    androidx.webkit.ProxyConfig.Builder()
                        .addProxyRule("http://127.0.0.1:7890")
                        .addBypassRule("127.0.0.1")
                        .addBypassRule("localhost")
                        .build(),
                    java.util.concurrent.Executors.newSingleThreadExecutor()
                ) { /* applied */ }
            }
        }

        setContentView(root)

        registerReceiver(receiver, IntentFilter(DshService.BROADCAST_STATUS),
            RECEIVER_NOT_EXPORTED)
        startService(Intent(this, DshService::class.java).setAction(DshService.ACTION_START))
    }

    override fun onStart() {
        super.onStart()
        if (!userStopped) {
            startService(Intent(this, DshService::class.java).setAction(DshService.ACTION_START))
        }
    }

    private fun loadUi(url: String) {
        loaded = true
        serverUrl = url
        runOnUiThread {
            webView.visibility = View.VISIBLE
            webView.loadUrl(url)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        webView.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) {
                webView.goBack()
                return
            }
            // A frame-busted external page sits at the top with an empty
            // history; reload the shell UI instead of leaving the app.
            val offShell = webView.url?.let { u ->
                !u.startsWith("http://127.0.0.1") && !u.startsWith("http://localhost")
            } == true
            if (offShell) {
                serverUrl?.let { webView.loadUrl(it); return }
            }
        }
        // Keep the server alive; just leave the foreground.
        moveTaskToBack(true)
    }
}
