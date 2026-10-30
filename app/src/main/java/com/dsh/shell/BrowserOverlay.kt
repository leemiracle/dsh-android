package com.dsh.shell

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * Native rendering engine behind the dsh sidebar browser panel — SINGLE
 * instance by design (2026-10-30 user call: multi-engine key routing kept
 * fighting split-pane tab ids; a single engine trades tab isolation for
 * robustness).
 *
 * The panel's chrome (tab strip, address bar, toolbar) stays 100% dsh HTML;
 * the panel's iframe is blanked and the URL rendered by this native engine
 * placed exactly over the panel content rect. Tab switches re-navigate the
 * engine to that tab's remembered URL (the injected script keeps a per-tab
 * map and replays it on strip clicks), cookies/storage are shared app-wide
 * so logins survive everywhere. No anti-framing limits, persistent state.
 *
 * Bridge protocol (specs are "anything|payload" — keys are advisory only):
 * onNavigate(url) / onRect(rect) / onBack / onFwd / onReload.
 */
object BrowserOverlay {

    private val main = Handler(Looper.getMainLooper())

    private var web: WebView? = null
    private var hostRoot: FrameLayout? = null

    /** Create the engine and stack it (hidden) over the shell's root view. */
    fun attach(act: Activity, root: FrameLayout) {
        if (web != null) { hostRoot = root; return }
        hostRoot = root
        web = buildEngine(root).also { root.addView(it, FrameLayout.LayoutParams(0, 0)) }
    }

    // ---------------------------------------------------------- bridge API

    @android.webkit.JavascriptInterface
    fun onNavigate(spec: String) = main.post {
        android.util.Log.d("KylinOv", "onNavigate: $spec")
        navigate(spec.substringAfter('|', spec).trim())
    }

    @android.webkit.JavascriptInterface
    fun onRect(spec: String) = main.post { move(spec.substringAfter('|', spec)) }

    @android.webkit.JavascriptInterface
    fun onBack(@Suppress("UNUSED_PARAMETER") x: String) = main.post {
        web?.takeIf { it.canGoBack() }?.goBack()
    }

    @android.webkit.JavascriptInterface
    fun onFwd(@Suppress("UNUSED_PARAMETER") x: String) = main.post {
        web?.takeIf { it.canGoForward() }?.goForward()
    }

    @android.webkit.JavascriptInterface
    fun onReload(@Suppress("UNUSED_PARAMETER") x: String) = main.post { web?.reload() }

    /** True when the engine can go back (shell Back wiring). */
    fun activeCanGoBack(): Boolean = web?.canGoBack() == true

    fun activeGoBack() = main.post { web?.takeIf { it.canGoBack() }?.goBack() }

    // ---------------------------------------------------------- engine ops

    private fun navigate(raw: String) {
        if (raw.isEmpty()) return
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
        else if (raw.contains("://")) return
        else "https://$raw"
        val w = web ?: return
        w.visibility = View.VISIBLE
        w.bringToFront()
        if (w.url != url) w.loadUrl(url)
    }

    /** rect = "x,y,w,h" CSS px; blank hides the engine. */
    private fun move(rect: String) {
        val w = web ?: return
        if (rect.isBlank()) { w.visibility = View.GONE; return }
        val p = rect.split(",").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p[2] <= 0 || p[3] <= 0) { w.visibility = View.GONE; return }
        val d = w.resources.displayMetrics.density
        val lp = w.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = (p[0] * d).toInt()
        lp.topMargin = (p[1] * d).toInt()
        lp.width = (p[2] * d).toInt()
        lp.height = (p[3] * d).toInt()
        w.layoutParams = lp
        // Visibility keyed purely on having a rect: a freshly-navigated
        // engine still has url == null until the first response, and a GONE
        // WebView reports a 0x0 viewport.
        w.visibility = View.VISIBLE
        w.bringToFront()
    }

    /** Mirror engine state into the panel's address bar / tab title. */
    private fun pushState(view: WebView?, url: String?) {
        val w = view ?: return
        val u = url ?: return
        val title = (w.title ?: "").replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
        val safeUrl = u.replace("\\", "\\\\").replace("'", "\\'")
        val js = "window.__kylinOnState && window.__kylinOnState(" +
            "{url:'$safeUrl', title:'$title'})"
        HostShell.evaluate(js)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildEngine(root: FrameLayout): WebView = WebView(root.context).apply {
        visibility = View.GONE
        setBackgroundColor(Color.parseColor("#0B0E14"))
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            textZoom = 100
            allowFileAccess = false
            allowContentAccess = false
            userAgentString = userAgentString.replace("; wv", "")
            setSupportMultipleWindows(true)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?, request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                val scheme = u.scheme?.lowercase() ?: return false
                if (scheme == "http" || scheme == "https") return false
                // Custom app-jump schemes: extract the inner http(s) url
                // when present, otherwise swallow the navigation.
                val inner = runCatching { u.getQueryParameter("url") }.getOrNull()
                if (inner != null && inner.startsWith("http")) view?.loadUrl(inner)
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) = pushState(view, url)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?, isDialog: Boolean, isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                // target=_blank inside a panel page: adopt into the same
                // engine (onPageStarted capture — shouldOverrideUrlLoading
                // never fires for the carrier).
                val tmp = WebView(view!!.context)
                var delivered = false
                tmp.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        v: WebView?, url: String?, favicon: android.graphics.Bitmap?
                    ) {
                        if (!delivered && url != null) {
                            delivered = true
                            view.loadUrl(url)
                            v?.stopLoading()
                            tmp.post { runCatching { tmp.destroy() } }
                        }
                    }
                }
                (resultMsg?.obj as? WebView.WebViewTransport)?.setWebView(tmp)
                resultMsg?.sendToTarget()
                return true
            }
        }
    }

    // ---------------------------------------------------------- bridge JS

    /**
     * Injected into the dsh UI page. Single-engine protocol: the panel's
     * iframe creation/src changes become native navigations (the iframe is
     * blanked), the content rect is streamed for overlay placement, toolbar
     * buttons are rerouted to the engine, and __kylinOnState writes engine
     * state back into the panel DOM. A per-tab URL memory replays a tab's
     * page when its strip button is clicked (single engine = no isolation).
     */
    val BRIDGE_JS = """
(function () {
  if (window.__kylinBridgeInstalled) return 'already';
  window.__kylinBridgeInstalled = true;
  var seq = 0;
  function send(k, v) {
    try { window.kylinBridge && window.kylinBridge[k] && window.kylinBridge[k](v); } catch (e) {}
  }
  var tabUrls = {};     // tabKey -> last URL (single-engine tab memory)
  var activeKey = null; // tab hosting the browser panel right now

  function paneOf(scope) {
    return scope && (scope.closest('[data-dockkit-pane], section[class*="pane"], [class*="tabHost"]'));
  }
  function activeTabKey() {
    // Scope to the pane hosting the browser panel: split panes each have a
    // strip, and a global tabActive lookup grabs the wrong pane's tab.
    var scope = document.querySelector('[class*="SB_kFW_root"]')
             || document.querySelector('[class*="tabHostBody"]');
    var act = null;
    var pane = paneOf(scope);
    if (pane) act = pane.querySelector('[class*="tabActive"][data-dockkit-tab], [class*="tabActive"][data-tab-id]');
    if (!act) act = document.querySelector('[class*="stripTabs"] [class*="tabActive"][data-dockkit-tab], [class*="stripTabs"] [class*="tabActive"][data-tab-id]');
    var id = act && (act.getAttribute('data-dockkit-tab') || act.getAttribute('data-tab-id'));
    if (id) return 'dt' + id;
    var p = pane || (scope && scope.closest('[data-dockkit-pane], section[class*="pane"]'));
    if (p) { if (!p.dataset.kylinPane) p.dataset.kylinPane = 'kp' + (++seq); return p.dataset.kylinPane; }
    return 'kp0';
  }

  // -- navigation source of truth: the panel creating/re-pointing its iframe
  var mountedFrame = null; // identity: remounts must re-signal (dedupe guard)
  var obs = new MutationObserver(function () {
    var f = document.querySelector('iframe[data-sidebar-browser-frame]');
    if (f) {
      activeKey = activeTabKey();
      var u = f.getAttribute('src') || '';
      if (u && u.indexOf('about:blank') !== 0) {
        if (f !== mountedFrame || tabUrls[activeKey] !== u) {
          mountedFrame = f;
          tabUrls[activeKey] = u;
          send('onNavigate', activeKey + '|' + u);
        }
      }
      // Blank the iframe ONLY when it actually differs — this observer
      // watches src/style, so unconditional writes self-trigger an infinite
      // microtask loop that starves the page (found the hard way).
      if ((f.getAttribute('src') || '').indexOf('about:blank') !== 0) {
        f.setAttribute('src', 'about:blank');
      }
      if (f.style.display !== 'none') f.style.display = 'none';
    }
    scheduleRect();
  });
  obs.observe(document.documentElement, {
    childList: true, subtree: true, attributes: true,
    attributeFilter: ['src', 'style', 'class', 'data-dockkit-tab']
  });

  // -- address bar submits reroute to the engine
  document.addEventListener('submit', function (e) {
    var form = e.target;
    if (!form || !form.querySelector) return;
    var input = form.querySelector('input[class*="address"]');
    if (input) {
      e.preventDefault(); e.stopPropagation();
      var v = (input.value || '').trim();
      activeKey = activeTabKey();
      if (v) { tabUrls[activeKey] = v; send('onNavigate', activeKey + '|' + v); }
    }
  }, true);

  document.addEventListener('click', function (e) {
    if (!e.target || !e.target.closest) return;
    // Strip tab click FIRST: tab headers are divs, not buttons. Replay that
    // tab's remembered URL (single engine).
    var stripBtn = e.target.closest('[data-dockkit-tab]');
    if (stripBtn) {
      var tid = stripBtn.getAttribute('data-dockkit-tab');
      var k = tid ? 'dt' + tid : null;
      if (k && tabUrls[k] && k !== activeKey) {
        activeKey = k;
        send('onNavigate', k + '|' + tabUrls[k]);
      }
      return; // dsh still handles the actual tab switch
    }
    var b = e.target.closest('button');
    if (!b) return;
    var scope = b.closest('[class*="SB_kFW_root"]') || b.closest('[class*="tabHostBody"]');
    if (!scope) return;
    var l = b.getAttribute('aria-label') || '';
    if (l === '后退' || l === 'Back') { e.preventDefault(); e.stopPropagation(); send('onBack', ''); }
    else if (l === '前进' || l === 'Forward') { e.preventDefault(); e.stopPropagation(); send('onFwd', ''); }
    else if (l === '刷新' || l === 'Refresh' || l === 'Reload') { e.preventDefault(); e.stopPropagation(); send('onReload', ''); }
  }, true);

  // -- content rect streaming while a browser tab is active
  var lastRect = '';
  function rectSpec() {
    // Fullscreen dsh dialogs must not be covered by the engine.
    var dlg = document.querySelector('[role="dialog"]');
    if (dlg) {
      var dr = dlg.getBoundingClientRect();
      if (dr.width > 300 && dr.height > 200) return '';
    }
    var host = document.querySelector('[class*="SB_kFW_content"], [class*="SB_kFW_viewport"]');
    if (!host) {
      var addr = document.querySelector('input[class*="address"]');
      if (!addr) return '';
      host = addr.closest('[class*="SB_kFW_root"]');
      if (!host) return '';
    }
    var r = host.getBoundingClientRect();
    if (r.width < 10 || r.height < 10) return '';
    return [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)].join(',');
  }
  var pending = false;
  function scheduleRect() {
    // setTimeout, NOT rAF: WebView suspends rAF when the page is visually
    // idle, stalling the pending flag and killing the rect stream.
    if (pending) return; pending = true;
    setTimeout(function () {
      pending = false;
      var s = rectSpec();
      if (s !== lastRect) { lastRect = s; send('onRect', (activeKey||'') + '|' + s); }
    }, 120);
  }
  setInterval(scheduleRect, 300);
  window.addEventListener('resize', scheduleRect);

  // -- engine -> panel state writeback (address bar + active tab title +
  //    per-tab URL memory so switching back restores the right page)
  window.__kylinOnState = function (s) {
    var k = activeKey || activeTabKey();
    if (s.url && tabUrls[k] !== s.url) tabUrls[k] = s.url;
    var input = document.querySelector('input[class*="address"]');
    if (input && s.url && document.activeElement !== input) input.value = s.url;
    if (s.title) {
      var act = document.querySelector('[class*="stripTabs"] [class*="tabActive"] [class*="tabTitle"]');
      if (act) act.textContent = s.title;
    }
  };
  return 'installed';
})();
    """.trimIndent()
}

/** Small holder so the overlay can talk back into the shell WebView. */
object HostShell {
    @Volatile var evaluate: (String) -> Unit = {}
    fun bind(fn: (String) -> Unit) { evaluate = fn }
}
