package com.dsh.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * In-shell browser window: a full-screen WebView without the sidebar panel's
 * iframe limitations. The dsh browser panel stays as-is for frame-friendly
 * sites; its "open in system browser" button (a window.open) and any
 * target=_blank link are routed here by MainActivity's onCreateWindow —
 * anti-framing office apps (kdocs / WPS / Tencent Docs) render and stay
 * logged in properly in this window, and Back returns to the dsh UI.
 *
 * Same egress as the shell: the app-wide ProxyController split policy
 * (loopback direct, everything else via the host clash tunnel) applies to
 * every WebView in the process.
 */
class DshBrowserActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var urlBox: EditText

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dp = resources.displayMetrics.density
        fun dp(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0E14"))
        }

        // ---- chrome bar: back / forward / refresh | address box | close
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        fun navButton(label: String, onClick: (TextView) -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(Color.parseColor("#C8D0E0"))
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setOnClickListener { onClick(this) }
            }
        val backBtn = navButton("←") { if (webView.canGoBack()) webView.goBack() }
        val fwdBtn = navButton("→") { if (webView.canGoForward()) webView.goForward() }
        val reloadBtn = navButton("⟳") { webView.reload() }
        urlBox = EditText(this).apply {
            maxLines = 1
            isSingleLine = true
            textSize = 13f
            setTextColor(Color.parseColor("#E6EAF2"))
            setHintTextColor(Color.parseColor("#5A6478"))
            hint = "输入地址"
            background = null
            setPadding(dp(10), dp(8), dp(10), dp(8))
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, actionId, event ->
                val go = actionId == EditorInfo.IME_ACTION_GO ||
                    (event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER)
                if (go) {
                    navigate(urlBox.text.toString())
                    true
                } else false
            }
        }
        val closeBtn = navButton("✕") { finish() }
        bar.addView(backBtn)
        bar.addView(fwdBtn)
        bar.addView(reloadBtn)
        bar.addView(urlBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(closeBtn)
        root.addView(bar)
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#1C2333"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        }
        root.addView(divider)

        // ---- page
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                textZoom = 100
                allowFileAccess = false
                allowContentAccess = false
                // Present as a real Chrome, not a "; wv" embedder — several
                // CN office portals serve degraded payloads to WebView UAs.
                userAgentString = userAgentString.replace("; wv", "")
                setSupportMultipleWindows(true)
            }
            // Office logins need their auth cookies across site boundaries.
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?, request: WebResourceRequest?
                ): Boolean {
                    val u = request?.url ?: return false
                    val scheme = u.scheme?.lowercase() ?: return false
                    if (scheme == "http" || scheme == "https") return false
                    // Custom schemes (baiduboxapp:// etc. app jumps): pull the
                    // inner http(s) url when present, otherwise swallow —
                    // letting them through lands on ERR_UNKNOWN_URL_SCHEME.
                    val inner = runCatching { u.getQueryParameter("url") }.getOrNull()
                    if (inner != null && inner.startsWith("http")) {
                        view?.loadUrl(inner)
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    urlBox.setText(url ?: "")
                    urlBox.setSelection(urlBox.text.length)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                    resultMsg: android.os.Message?
                ): Boolean {
                    // target=_blank / window.open inside the page: adopt into
                    // this same window via a throwaway carrier (kylin-agent
                    // AgentWeb pattern, device-proven).
                    val tmp = WebView(view!!.context)
                    tmp.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            v: WebView?, request: WebResourceRequest?
                        ): Boolean {
                            request?.url?.let { view.loadUrl(it.toString()) }
                            tmp.destroy()
                            return true
                        }
                    }
                    (resultMsg?.obj as? WebView.WebViewTransport)?.setWebView(tmp)
                    resultMsg?.sendToTarget()
                    return true
                }
            }
        }
        root.addView(webView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 21) {
            window.statusBarColor = Color.parseColor("#0B0E14")
            window.navigationBarColor = Color.parseColor("#0B0E14")
        }

        navigate(intent.dataString ?: "")
    }

    /** Load a user-typed address, completing bare hosts like kdocs.cn. */
    private fun navigate(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        val url = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains("://") -> t // custom scheme: let the client policy handle it
            else -> "https://$t"
        }
        webView.loadUrl(url)
        urlBox.setText(url)
        urlBox.setSelection(urlBox.text.length)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context, url: String) {
            ctx.startActivity(
                Intent(ctx, DshBrowserActivity::class.java).setData(Uri.parse(url))
            )
        }
    }
}
