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

    /** Last applied engine rect (view px) — pages lay out by the size they
     *  FIRST see, so navigation must wait for a real panel rect. */
    @Volatile private var lastRectW = 0
    @Volatile private var lastRectH = 0

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

    // ------------------------------------------- agent driving surface
    // Synchronous JS evaluation for the web_* tools (loopback bridge). Same
    // thread model as kylin-agent's AgentWeb: caller thread -> post to main
    // -> evaluateJavascript -> latch (busy pages just time out, never hang).

    private fun evalJs(code: String, timeoutS: Long = 12): String? {
        val w = web ?: return null
        val box = arrayOfNulls<String>(1)
        val latch = java.util.concurrent.CountDownLatch(1)
        main.post {
            w.evaluateJavascript(code) { r -> box[0] = r; latch.countDown() }
        }
        latch.await(timeoutS, java.util.concurrent.TimeUnit.SECONDS)
        return box[0]
    }

    /** JSON-decode evaluateJavascript's double-encoded string results. */
    private fun evalJsText(code: String, timeoutS: Long = 12): String? {
        val r = evalJs(code, timeoutS) ?: return null
        if (r == "null" || r == "undefined") return null
        if (r.startsWith("\"")) {
            return runCatching {
                org.json.JSONObject("{\"v\":$r}").optString("v")
            }.getOrDefault(r)
        }
        return r
    }

    /** Open a URL and poll until the ENGINE is actually on that host (the
     *  previous page's readyState is also "complete" — waiting on
     *  readyState alone returns about:blank instantly) and loaded. */
    fun open(url: String): String {
        val w = web ?: return "(engine not ready)"
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "仅支持 http/https URL"
        val full = if (url.startsWith("http")) url else "https://$url"
        // Make the panel visible (open a browser tab if none) and route the
        // navigation through the panel's own machinery so tab title/address
        // bar stay in sync.
        HostShell.evaluate("window.__kylinOpenInPanel && window.__kylinOpenInPanel(${org.json.JSONObject.quote(full)})")
        main.post { w.bringToFront() }
        ensurePanelRect()
        // Bounded wait: complete is ideal, but busy portals sit at
        // "interactive" forever — on-target + interactive after a grace
        // period counts as loaded (baidu did exactly this).
        val start = System.currentTimeMillis()
        val deadline = start + 25_000
        val host = java.net.URI(full).host ?: ""
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(400)
            val now = evalJsText("location.href") ?: continue
            val onTarget = host.isNotEmpty() && now.contains(host)
            if (!onTarget) continue
            val ready = evalJsText("document.readyState") ?: ""
            if (ready == "complete") {
                Thread.sleep(400) // render settling
                return state()
            }
            if (System.currentTimeMillis() - start > 12_000 && ready != "") {
                Thread.sleep(600)
                return state()
            }
        }
        return "加载未完成（25s）——当前：${state()}"
    }

    fun state(): String {
        val r = evalJsText(
            "JSON.stringify({url:location.href, title:document.title, ready:document.readyState," +
                " scrollY:Math.round(scrollY), w:innerWidth, h:innerHeight})"
        ) ?: return "(JS 超时)"
        val o = runCatching { org.json.JSONObject(r) }.getOrNull() ?: return "解析失败"
        return "url=${o.optString("url")} 标题=${o.optString("title")} 状态=${o.optString("ready")} " +
            "视口=${o.optInt("w")}x${o.optInt("h")}"
    }

    fun dom(selector: String): String {
        if (selector.isBlank()) return "选择器为空"
        val sel = org.json.JSONObject.quote(selector)
        val r = evalJsText(
            "JSON.stringify([...document.querySelectorAll($sel)].slice(0,20).map(e=>" +
                "({tag:e.tagName,text:(e.innerText||'').replace(/\\s+/g,' ').slice(0,100),id:e.id||''," +
                "value:(e.value||'').slice(0,40)})))"
        ) ?: return "(JS 超时)"
        val a = runCatching { org.json.JSONArray(r) }.getOrNull() ?: return "无命中或解析失败（选择器: $selector）"
        if (a.length() == 0) return "选择器「$selector」无命中（页面可能未加载完或选择器错——可 web_state 核对 url）"
        val sb = StringBuilder("命中 ${a.length()} 个（选择器 $selector）：\n")
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            sb.append("· <").append(o.optString("tag").lowercase()).append('>')
                .append(if (o.optString("id").isNotBlank()) "#${o.optString("id")}" else "")
                .append(' ').append(o.optString("text").take(80))
                .append(if (o.optString("value").isNotBlank()) " [值:${o.optString("value")}]" else "")
                .appendLine()
        }
        return sb.toString().take(2800)
    }

    fun click(selector: String): String {
        if (selector.isBlank()) return "选择器为空"
        val sel = org.json.JSONObject.quote(selector)
        val r = evalJs(
            "(e=>{const t=document.querySelector($sel); if(!t) return 'MISS';" +
                "t.scrollIntoView({block:'center'}); t.click(); return 'OK'})()"
        ) ?: return "(JS 超时)"
        return when (r) {
            "\"OK\"" -> "已点击「$selector」（可 web_state/web_dom 核实效果）"
            "\"MISS\"" -> "未命中「$selector」（先 web_dom 确认选择器）"
            else -> "点击回执异常: ${r?.take(40)}"
        }
    }

    fun type(selector: String, text: String): String {
        if (selector.isBlank()) return "选择器为空"
        val sel = org.json.JSONObject.quote(selector)
        val txt = org.json.JSONObject.quote(text)
        val js = "(()=>{const e=document.querySelector($sel); if(!e) return 'MISS';" +
            "const set=Object.getOwnPropertyDescriptor(e.__proto__,'value')?.set; " +
            "if(set) set.call(e,$txt); else e.value=$txt;" +
            "e.dispatchEvent(new Event('input',{bubbles:true})); " +
            "e.dispatchEvent(new Event('change',{bubbles:true})); return 'OK'})()"
        return when (evalJs(js) ?: return "(JS 超时)") {
            "\"OK\"" -> "已输入「$selector」（${text.length} 字）"
            "\"MISS\"" -> "未命中「$selector」"
            else -> "输入回执异常"
        }
    }

    /**
     * Human-in-the-loop gate (browser-use semantics): wait until the page
     * changes away from the given state — typically the user finishing a
     * manual login in the visible panel. Polls engine-side for up to
     * [timeoutS] so the agent spends ONE tool call, not a polling loop.
     */
    fun waitManual(reason: String, timeoutS: Long): String {
        val startUrl = evalJsText("location.href") ?: return "(JS 超时)"
        val deadline = System.currentTimeMillis() + timeoutS * 1000
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1500)
            val now = evalJsText("location.href") ?: continue
            if (now != startUrl) {
                return "人工操作完成（页面已从 $startUrl 前进到 $now）——当前：${state()}"
            }
        }
        return "等待超时（${timeoutS}s 内页面未变化）——当前：${state()}。如仍需人工操作请提示用户。"
    }

    // ---------------------------------------------------------- engine ops

    /** Screenshot the engine's current view (PNG base64) — the model's eyes
     *  for browser-use-style visual grounding. PixelCopy needs the view
     *  attached & visible; falls back to a software draw when unavailable. */
    fun screenshotB64(): String {
        val w = web ?: return "(engine not ready)"
        if (w.visibility != View.VISIBLE || w.width <= 0) return "(engine not visible — web_open a page first)"
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(w.width, w.height, android.graphics.Bitmap.Config.ARGB_8888)
            val latch = java.util.concurrent.CountDownLatch(1)
            var ok = false
            // Both PixelCopy and draw() run on the main thread for the view.
            main.post {
                try {
                    val canvas = android.graphics.Canvas(bmp)
                    w.draw(canvas)
                    ok = true
                } catch (_: Throwable) {
                }
                latch.countDown()
            }
            latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!ok) return "(screenshot failed)"
            val baos = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, baos)
            android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (t: Throwable) {
            "(screenshot error: ${t.message?.take(60)})"
        }
    }

    /**
     * Pages size their layout to the viewport they FIRST see: navigating
     * before the panel rect arrives loads under a stale rect (zhihu rendered
     * full-app-wide until dragged). Wait for a real panel rect, bounded — if
     * none comes (no panel open), proceed anyway.
     */
    private fun ensurePanelRect(timeoutMs: Long = 3000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && (lastRectW <= 0 || lastRectH <= 0)) {
            Thread.sleep(150)
        }
    }

    private fun navigate(raw: String) {
        if (raw.isEmpty()) return
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw
        else if (raw.contains("://")) return
        else "https://$raw"
        val w = web ?: return
        ensurePanelRect()
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
        // Clamp to the physical screen: fullscreen-panel layouts can report
        // content heights far beyond the viewport (1603px vs an 864px
        // screen), leaving the engine's lower half off-screen — "page shows
        // incomplete content".
        val dm = w.resources.displayMetrics
        val maxW = dm.widthPixels
        val maxH = dm.heightPixels
        val x = (p[0] * d).toInt().coerceIn(0, maxW - 1)
        val y = (p[1] * d).toInt().coerceIn(0, maxH - 1)
        val width = (p[2] * d).toInt().coerceAtMost(maxW - x)
        val height = (p[3] * d).toInt().coerceAtMost(maxH - y)
        if (width <= 0 || height <= 0) { w.visibility = View.GONE; return }
        lastRectW = width
        lastRectH = height
        val lp = w.layoutParams as FrameLayout.LayoutParams
        lp.leftMargin = x
        lp.topMargin = y
        lp.width = width
        lp.height = height
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

  // -- agent entry: make sure a browser tab exists, then navigate it via the
  //    panel's own address bar (keeps tab title/history in sync).
  window.__kylinOpenInPanel = function (url) {
    var go = function () {
      var i = document.querySelector('input[class*="address"]');
      if (!i) return 'no-address';
      i.focus();
      var set = Object.getOwnPropertyDescriptor(i.__proto__, 'value')?.set;
      if (set) set.call(i, url); else i.value = url;
      i.dispatchEvent(new Event('input', { bubbles: true }));
      var g = document.querySelector('[class*="addressGo"]');
      if (g) { g.click(); return 'ok'; }
      i.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
      return 'ok-no-go-btn';
    };
    if (document.querySelector('input[class*="address"]')) return go();
    // The right sidebar may be COLLAPSED (rightbarCol width 0 — then there
    // is no addTab button at all). Expand it first, retried in the poll.
    var expandBar = function () {
      var ex = [...document.querySelectorAll('[aria-label]')].find(function (e) {
        return (e.getAttribute('aria-label') || '').indexOf('打开右侧边栏') !== -1;
      });
      if (ex) ex.click();
    };
    expandBar();
    // no browser tab yet: fire BOTH paths — the add-tab picker click and the
    // browser.new shortcut (Ctrl+T) — then poll up to 8s for the address
    // bar (menus animate; one path always lands) and navigate.
    var add = document.querySelector('button[class*="addTab"]');
    if (add) add.click();
    var kd = function (down) {
      document.dispatchEvent(new KeyboardEvent(down ? 'keydown' : 'keyup', {
        key: 't', code: 'KeyT', ctrlKey: true, bubbles: true, cancelable: true
      }));
    };
    kd(true); kd(false);
    var tries = 0;
    var addClicked = false;
    var iv = setInterval(function () {
      tries += 1;
      // Collapsed bar -> expand (ONLY when fully collapsed; never touch the
      // layout otherwise — dsh remembers the bar width per session, and
      // nudging it re-opens wide instead of the user's narrow sidebar).
      var bar = document.querySelector('[class*="rightbarCol"]');
      if ((!bar || bar.getBoundingClientRect().width < 50)) expandBar();
      // open the tab exactly once, as soon as the strip is reachable
      if (!addClicked) {
        var a2 = document.querySelector('button[class*="addTab"]');
        if (a2) { a2.click(); addClicked = true; }
      }
      // picker path: click the 浏览器 row whenever it appears (menu or start page)
      var leaf = [...document.querySelectorAll('body *')].find(function (e) {
        return e.children.length === 0 && (e.textContent || '').trim() === '浏览器';
      });
      if (leaf && !document.querySelector('input[class*="address"]')) {
        var row = leaf;
        for (var i = 0; i < 6 && row.parentElement; i++) {
          row = row.parentElement;
          var r = row.getBoundingClientRect();
          if (r.height > 30 && r.height < 140 && r.width > 100) break;
        }
        row.click();
      }
      if (document.querySelector('input[class*="address"]')) {
        clearInterval(iv);
        go();
        send('onNavigate', (activeTabKey() || 'kp0') + '|' + url);
      } else if (tries > 26) {
        clearInterval(iv);
        // last resort: drive the engine directly even without a panel tab
        send('onNavigate', (activeTabKey() || 'kp0') + '|' + url);
      }
    }, 300);
    return 'opening-tab';
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
