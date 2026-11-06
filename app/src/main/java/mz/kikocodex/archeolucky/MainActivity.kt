package mz.kikocodex.archeolucky

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Archeo Lucky Browser  ·  v1.1
 * =============================
 *  1. Abre o Google num WebView;
 *  2. Detecta o jogo ARCHÉO e mostra o toast "jogo Lindo com sucesso ✅✅";
 *  3. HUD com o SALDO do jogo em tempo real (canto superior);
 *  4. Botão flutuante "G" abre uma JANELA FLUTUANTE de configuração com:
 *       - Stake (aposta) ajustável;
 *       - Quantos cliques por sessão;
 *       - Operação AUTOMÁTICA (escolhe os blocos aleatoriamente e faz cashout),
 *         a mesma lógica de automação usada no laboratório (Playwright).
 *
 * Comunicação app <-> iframe cross-origin do jogo:
 *   - entrada  (app -> jogo): window.postMessage com relay entre frames,
 *     porque evaluateJavascript só alcança o frame principal;
 *   - saída    (jogo -> app): ArcheoBridge.postMessage + WebMessageListener.
 */
class MainActivity : android.app.Activity() {

    // ---------------------------------------------------------------- views
    private lateinit var root: View
    private lateinit var webView: WebView
    private lateinit var saldoValue: TextView
    private lateinit var liveStatus: TextView
    private lateinit var liveDot: View
    private lateinit var statusLine: TextView

    private lateinit var panel: LinearLayout
    private lateinit var panelBody: LinearLayout
    private lateinit var panelHeader: View
    private lateinit var panelSummary: TextView
    private lateinit var stakeValue: TextView
    private lateinit var clicksValue: TextView
    private lateinit var autoStatus: TextView
    private lateinit var btnAuto: TextView

    // ---------------------------------------------------------------- estado
    private val ui = Handler(Looper.getMainLooper())
    private var gameToastAlreadyShown = false
    private var gameDetected = false
    private var lastBalance: String? = null
    private var mainFrameFallbackRunning = false

    private var stake = 1.0
    private var clicksPerSession = 3
    private var autoRunning = false
    private var panelMinimized = false

    private val startUrl = "https://www.google.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        root = findViewById(android.R.id.content)
        webView = findViewById(R.id.webview)
        saldoValue = findViewById(R.id.saldoValue)
        liveStatus = findViewById(R.id.liveStatus)
        liveDot = findViewById(R.id.liveDot)
        statusLine = findViewById(R.id.statusLine)

        panel = findViewById(R.id.panel)
        panelBody = findViewById(R.id.panelBody)
        panelHeader = findViewById(R.id.panelHeader)
        panelSummary = findViewById(R.id.panelSummary)
        stakeValue = findViewById(R.id.stakeValue)
        clicksValue = findViewById(R.id.clicksValue)
        autoStatus = findViewById(R.id.autoStatus)
        btnAuto = findViewById(R.id.btnAuto)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupWebView()
        installRealtimeBridge()
        setupFloatingPanel()

        refreshStakeLabel()
        refreshClicksLabel()

        val deepLink = intent?.dataString
        webView.loadUrl(if (!deepLink.isNullOrBlank()) deepLink else startUrl)
    }

    // ================================================================ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setSupportMultipleWindows(false)
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        s.userAgentString?.let { ua ->
            if (ua.contains("; wv")) s.userAgentString = ua.replace("; wv", "")
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                request?.url?.toString()?.let { inspectUrl(it) }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (isArcheo(url)) {
                    notifyGameOpened()
                } else {
                    gameToastAlreadyShown = false
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (isArcheo(url)) notifyGameOpened()
                view?.evaluateJavascript(WATCHER_JS, null)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    startMainFrameFallback()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
        }
    }

    private fun inspectUrl(url: String) {
        if (isArcheo(url)) notifyGameOpened()
    }

    private fun isArcheo(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase(Locale.ROOT)
        if (!u.contains("archeo")) return false
        return u.contains("pridebet") ||
            u.contains("splitthepot") ||
            u.contains("playdemo") ||
            u.contains("/games/archeo")
    }

    private fun notifyGameOpened() {
        if (!gameDetected) {
            gameDetected = true
            autoStatus.text = getString(R.string.auto_idle)
        }
        if (gameToastAlreadyShown) return
        gameToastAlreadyShown = true
        Toast.makeText(this, R.string.game_toast, Toast.LENGTH_LONG).show()
        liveStatus.text = getString(R.string.status_live)
        liveStatus.setTextColor(getColor(R.color.green))
        statusLine.text = "Archéo detectado · lendo saldo…"
    }

    // ============================================== Ponte do saldo (real-time)

    private fun installRealtimeBridge() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val listener = object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy
                ) {
                    val raw = message.data ?: return
                    ui.post { handleBridgeMessage(raw) }
                }
            }
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, setOf("*"), listener)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, WATCHER_JS, setOf("*"))
        }
    }

    private fun handleBridgeMessage(raw: String) {
        try {
            val json = JSONObject(raw)
            when (json.optString("type")) {
                "hello" -> {
                    if (isArcheo(json.optString("url"))) notifyGameOpened()
                }

                "saldo" -> {
                    val value = json.optString("value")
                    if (value.isNotBlank()) {
                        notifyGameOpened()
                        updateBalance(value)
                    }
                }

                "auto" -> handleAutoReport(json)
            }
        } catch (_: Exception) {
            // não é JSON: ignora
        }
    }

    private fun handleAutoReport(json: JSONObject) {
        gameDetected = true
        autoRunning = json.optBoolean("running", autoRunning)
        val sessions = json.optInt("sessions", 0)
        val wins = json.optInt("wins", 0)
        val losses = json.optInt("losses", 0)
        val st = json.optDouble("stake", stake)
        val ck = json.optInt("clicks", clicksPerSession)

        panelSummary.text = "W$wins · L$losses"
        autoStatus.text = buildString {
            append(if (autoRunning) "● rodando" else "○ parado")
            append("  ·  sessões: ").append(sessions)
            append("\nW: ").append(wins).append("   L: ").append(losses)
            append("\nstake ").append(money(st)).append(" FUN · ").append(ck).append(" cliques")
        }
        applyAutoUi()
    }

    // Fallback para WebView antigo: lê o saldo só no frame principal.
    private fun startMainFrameFallback() {
        if (mainFrameFallbackRunning) return
        mainFrameFallbackRunning = true
        val tick = object : Runnable {
            override fun run() {
                webView.evaluateJavascript(
                    "(function(){var m=(document.body&&document.body.innerText||'').match(/Saldo\\s*([0-9.]+,\\d{2})\\s*FUN/i);" +
                        "return m?m[1]:'';})()"
                ) { result ->
                    val v = result?.replace("\"", "")?.trim()
                    if (!v.isNullOrBlank() && v != "null") {
                        notifyGameOpened()
                        updateBalance(v)
                    }
                }
                ui.postDelayed(this, 1000)
            }
        }
        ui.postDelayed(tick, 1000)
    }

    private fun updateBalance(value: String) {
        if (value == lastBalance) return
        lastBalance = value
        saldoValue.text = "$value FUN"
        liveStatus.text = getString(R.string.status_live)
        liveStatus.setTextColor(getColor(R.color.green))
        val now = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        statusLine.text = "atualizado às $now"
        pulseDot()
    }

    private fun pulseDot() {
        liveDot.animate().cancel()
        liveDot.alpha = 1f
        liveDot.animate().alpha(0.2f).setDuration(260).withEndAction {
            liveDot.animate().alpha(1f).setDuration(260).start()
        }.start()
    }

    private fun resetHud() {
        lastBalance = null
        gameToastAlreadyShown = false
        gameDetected = false
        saldoValue.text = getString(R.string.saldo_empty)
        liveStatus.text = getString(R.string.status_offline)
        liveStatus.setTextColor(getColor(R.color.muted))
        statusLine.text = getString(R.string.app_name)
    }

    // ===================================================== Janela flutuante

    private fun setupFloatingPanel() {
        findViewById<TextView>(R.id.btnGoogle).setOnClickListener {
            if (panel.visibility == View.VISIBLE) {
                panel.visibility = View.GONE
            } else {
                panel.visibility = View.VISIBLE
                panel.bringToFront()
            }
        }

        findViewById<TextView>(R.id.btnClose).setOnClickListener {
            panel.visibility = View.GONE
        }

        findViewById<TextView>(R.id.btnMinimize).setOnClickListener { toggleMinimize() }
        panelHeader.setOnClickListener { toggleMinimize() }
        panelHeader.setOnTouchListener(DragListener())

        findViewById<TextView>(R.id.btnStakeMinus).setOnClickListener {
            stake = (stake - 0.5).coerceAtLeast(0.5)
            stake = (stake * 100).roundToInt() / 100.0
            refreshStakeLabel()
            pushSettings()
        }
        findViewById<TextView>(R.id.btnStakePlus).setOnClickListener {
            stake = (stake + 0.5).coerceAtMost(100.0)
            stake = (stake * 100).roundToInt() / 100.0
            refreshStakeLabel()
            pushSettings()
        }
        findViewById<TextView>(R.id.btnClicksMinus).setOnClickListener {
            clicksPerSession = (clicksPerSession - 1).coerceAtLeast(1)
            refreshClicksLabel()
            pushSettings()
        }
        findViewById<TextView>(R.id.btnClicksPlus).setOnClickListener {
            clicksPerSession = (clicksPerSession + 1).coerceAtMost(10)
            refreshClicksLabel()
            pushSettings()
        }

        btnAuto.setOnClickListener { toggleAuto() }

        findViewById<TextView>(R.id.btnGoGoogle).setOnClickListener {
            panel.visibility = View.GONE
            resetHud()
            webView.loadUrl(startUrl)
        }
    }

    private fun toggleMinimize() {
        panelMinimized = !panelMinimized
        panelBody.visibility = if (panelMinimized) View.GONE else View.VISIBLE
        if (panelMinimized && panelSummary.text.isBlank()) panelSummary.text = "minimizado"
    }

    private fun toggleAuto() {
        if (!autoRunning) {
            if (!gameDetected) {
                Toast.makeText(this, R.string.auto_no_game, Toast.LENGTH_SHORT).show()
                return
            }
            autoRunning = true
            pushSettings()
            sendToGame("start")
            Toast.makeText(this, "Operação automática iniciada ▶", Toast.LENGTH_SHORT).show()
        } else {
            autoRunning = false
            sendToGame("stop")
            Toast.makeText(this, "Operação automática parada ■", Toast.LENGTH_SHORT).show()
        }
        applyAutoUi()
    }

    private fun applyAutoUi() {
        if (autoRunning) {
            btnAuto.text = getString(R.string.auto_stop)
            btnAuto.setBackgroundResource(R.drawable.btn_auto_stop)
            btnAuto.setTextColor(getColor(android.R.color.white))
        } else {
            btnAuto.text = getString(R.string.auto_start)
            btnAuto.setBackgroundResource(R.drawable.btn_auto)
            btnAuto.setTextColor(getColor(R.color.bg))
        }
    }

    private fun pushSettings() {
        sendToGame("setStake", JSONObject().put("value", stake))
        sendToGame("setClicks", JSONObject().put("value", clicksPerSession))
    }

    private fun refreshStakeLabel() {
        stakeValue.text = "${money(stake)} FUN"
    }

    private fun refreshClicksLabel() {
        clicksValue.text = if (clicksPerSession == 1) "1 clique" else "$clicksPerSession cliques"
    }

    private fun money(v: Double): String =
        String.format(Locale.US, "%.2f", v).replace(".", ",")

    /**
     * Envia um comando para o frame do jogo.
     * postMessage é a única via permitida entre origens diferentes — e o script
     * injetado em cada frame faz o relay até o iframe do provedor.
     */
    private fun sendToGame(cmd: String, extra: JSONObject? = null) {
        val payload = JSONObject()
            .put("__archeoLuckyCmd", true)
            .put("cmd", cmd)
            .put("id", System.currentTimeMillis().toString() + (0..9999).random())
        extra?.keys()?.forEach { k -> payload.put(k, extra.get(k)) }

        val js = """
        (function(){
          var msg = $payload;
          function push(w){ try { w.postMessage(msg, '*'); } catch(e){} }
          push(window);
          var fs = document.querySelectorAll('iframe');
          for (var i = 0; i < fs.length; i++) { try { push(fs[i].contentWindow); } catch(e){} }
        })();
        """.trimIndent()

        webView.post { webView.evaluateJavascript(js, null) }
    }

    /** Arrasta a janela flutuante pela barra de título (toque curto = minimizar). */
    private inner class DragListener : View.OnTouchListener {
        private var dx = 0f
        private var dy = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    dx = panel.x - e.rawX
                    dy = panel.y - e.rawY
                    moved = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(e.rawX - downX) > 8f || kotlin.math.abs(e.rawY - downY) > 8f) moved = true
                    val maxX = (root.width - panel.width).coerceAtLeast(0).toFloat()
                    val maxY = (root.height - panel.height).coerceAtLeast(0).toFloat()
                    panel.x = (e.rawX + dx).coerceIn(0f, maxX)
                    panel.y = (e.rawY + dy).coerceIn(0f, maxY)
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMinimize()
                    return true
                }
            }
            return false
        }
    }

    // ============================================================ ciclo de vida

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            panel.visibility == View.VISIBLE -> panel.visibility = View.GONE
            webView.canGoBack() -> webView.goBack()
            else -> {
                resetHud()
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val BRIDGE_NAME = "ArcheoBridge"

        /**
         * Script injetado em TODOS os frames (inclusive o iframe cross-origin do jogo).
         * Responsabilidades:
         *   1. VIGIA do saldo (poll a cada 500ms no texto do frame);
         *   2. MOTOR da operação automática (clica COMEÇAR, escolhe casas aleatórias,
         *      faz CASHOUT) + relay de comandos vindos do app.
         */
        private val WATCHER_JS = """
        (function () {
          try {
            if (window.__archeoLuckyWatcher) { return; }
            window.__archeoLuckyWatcher = true;

            var BRIDGE = 'ArcheoBridge';
            var AUTO = { running: false, stake: 1, clicks: 3, sessions: 0, wins: 0, losses: 0, busy: false };
            var seen = {};

            function post(obj) {
              try { var b = window[BRIDGE]; if (b && b.postMessage) { b.postMessage(JSON.stringify(obj)); } } catch (e) {}
            }
            function hasBoard() { return !!document.querySelector('div.level'); }
            function all(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }
            function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }

            function activeInfo() {
              var ls = all('div.level');
              for (var i = 0; i < ls.length; i++) {
                var a = ls[i].querySelector('.level-active');
                if (!a) { continue; }
                var bs = a.querySelectorAll('button[tile]');
                var vis = Array.prototype.slice.call(bs).filter(function (b) { return b.getBoundingClientRect().width > 0; });
                if (vis.length >= 4) { return { idx: i, tiles: vis }; }
              }
              return null;
            }

            function openedCount() {
              return all('stp-gold-digger-level-opened').filter(function (e) {
                return getComputedStyle(e).display !== 'none';
              }).length;
            }

            function cashValue() {
              var bs = all('button');
              for (var i = 0; i < bs.length; i++) {
                var t = (bs[i].innerText || '').trim();
                if (/CASHOUT/i.test(t)) {
                  var m = t.match(/([0-9\.]+,[0-9]{2})/);
                  return m ? parseFloat(m[1].replace(/\./g, '').replace(',', '.')) : 0;
                }
              }
              return 0;
            }

            function findBtn(re) {
              var bs = all('button');
              for (var i = 0; i < bs.length; i++) { if (re.test((bs[i].innerText || '').trim())) { return bs[i]; } }
              return null;
            }

            function simClick(el) {
              if (!el) { return false; }
              try {
                var r = el.getBoundingClientRect();
                var o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
                try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch (e) {}
                try { el.dispatchEvent(new MouseEvent('mousedown', o)); } catch (e) {}
                try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch (e) {}
                try { el.dispatchEvent(new MouseEvent('mouseup', o)); } catch (e) {}
                el.click();
                return true;
              } catch (e) { return false; }
            }

            function waitFor(fn, timeout, step) {
              step = step || 150;
              return new Promise(function (resolve) {
                var t0 = Date.now();
                (function loop() {
                  var v = null;
                  try { v = fn(); } catch (e) {}
                  if (v) { return resolve(v); }
                  if (Date.now() - t0 > timeout) { return resolve(null); }
                  setTimeout(loop, step);
                })();
              });
            }

            function setStake(v) {
              try {
                var inp = document.querySelector('input.stake-input') || document.querySelector('input[type=number]');
                if (!inp) { return false; }
                var val = Number(v).toFixed(2);
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                setter.call(inp, val);
                inp.dispatchEvent(new Event('input', { bubbles: true }));
                inp.dispatchEvent(new Event('change', { bubbles: true }));
                inp.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                inp.dispatchEvent(new Event('blur', { bubbles: true }));
                return true;
              } catch (e) { return false; }
            }

            function report() {
              post({ type: 'auto', running: AUTO.running, sessions: AUTO.sessions,
                     wins: AUTO.wins, losses: AUTO.losses, stake: AUTO.stake, clicks: AUTO.clicks });
            }

            async function waitPick(prevIdx, timeout) {
              var t0 = Date.now();
              while (Date.now() - t0 < timeout) {
                var a = activeInfo();
                if (a && a.idx > prevIdx) { return { ok: true, idx: a.idx }; }
                if (!a && openedCount() > 0) { return { ok: false, dead: true }; }
                if (!a && cashValue() > 0) { return { ok: true, idx: prevIdx }; }
                await sleep(150);
              }
              return { ok: false, timeout: true };
            }

            function dismissOverlays() {
              try {
                if (all('.cdk-overlay-container .cdk-overlay-backdrop').length === 0) { return; }
                var cands = all('button, [role=button]').filter(function (b) {
                  var r = b.getBoundingClientRect();
                  if (r.width <= 0 || r.height <= 0) { return false; }
                  return /^(fechar|continuar|ok|proximo|próximo|entendi|aceitar|aceito)$/i.test((b.innerText || '').trim());
                });
                for (var i = 0; i < cands.length; i++) { simClick(cands[i]); }
              } catch (e) {}
            }

            async function runSession() {
              dismissOverlays();
              // fecha rodada pendente de antes
              if (activeInfo() && cashValue() > 0) {
                simClick(findBtn(/CASHOUT/i));
                await waitFor(function () { return !activeInfo(); }, 5000);
              }
              if (!AUTO.running) { return; }

              setStake(AUTO.stake);
              await sleep(200);

              var startBtn = findBtn(/COME[CÇ]AR/i);
              if (!startBtn) { return; }
              simClick(startBtn);

              var s = await waitFor(activeInfo, 7000);
              if (!s) { return; }

              var prev = s.idx;
              var alive = true;

              for (var i = 0; i < AUTO.clicks; i++) {
                if (!AUTO.running) { break; }
                var a = activeInfo();
                if (!a) { alive = false; break; }
                var tile = a.tiles[Math.floor(Math.random() * a.tiles.length)];
                simClick(tile);
                var res = await waitPick(prev, 7000);
                if (res && res.ok) { prev = res.idx; } else { alive = false; break; }
              }

              if (alive && cashValue() > 0 && activeInfo()) {
                simClick(findBtn(/CASHOUT/i));
                AUTO.wins++;
                await waitFor(function () { return !activeInfo(); }, 6000);
              } else if (alive) {
                AUTO.wins++;
              } else {
                AUTO.losses++;
                await waitFor(function () { return !activeInfo(); }, 4000);
              }

              AUTO.sessions++;
              report();
            }

            async function loop() {
              if (AUTO.busy) { return; }
              AUTO.busy = true;
              while (AUTO.running) {
                try { await runSession(); } catch (e) {}
                await sleep(350);
              }
              AUTO.busy = false;
              report();
            }

            function handleCmd(d) {
              if (!hasBoard()) { return false; }
              switch (d.cmd) {
                case 'setStake':
                  AUTO.stake = Number(d.value) || 1;
                  setStake(AUTO.stake);
                  report();
                  return true;
                case 'setClicks':
                  AUTO.clicks = Math.min(10, Math.max(1, Number(d.value) || 3));
                  report();
                  return true;
                case 'start':
                  if (!AUTO.running) { AUTO.running = true; report(); loop(); }
                  return true;
                case 'stop':
                  AUTO.running = false; report();
                  return true;
                case 'status':
                  report();
                  return true;
              }
              return false;
            }

            function relay(d) {
              try {
                var fs = document.querySelectorAll('iframe');
                for (var i = 0; i < fs.length; i++) { try { fs[i].contentWindow.postMessage(d, '*'); } catch (e) {} }
              } catch (e) {}
            }

            window.addEventListener('message', function (e) {
              var d = e.data;
              if (!d || !d.__archeoLuckyCmd) { return; }
              if (d.id && seen[d.id]) { return; }
              if (d.id) { seen[d.id] = 1; }
              var handled = handleCmd(d);
              if (!handled) { relay(d); }
            });

            // ------------------------------------------------ vigia do saldo
            var href = String(location.href || '');
            if (/archeo/i.test(href) && /(pridebet|splitthepot|playdemo)/i.test(href)) {
              post({ type: 'hello', url: href });
            }

            var last = null;
            function scan() {
              try {
                var txt = (document.body && document.body.innerText) ? document.body.innerText : '';
                if (!txt) { return; }
                var m = txt.match(/Saldo\s*([0-9][0-9\.\s]*,[0-9]{2})\s*FUN/i);
                if (m) {
                  var v = m[1].replace(/\s/g, '');
                  if (v !== last) { last = v; post({ type: 'saldo', value: v, url: String(location.href || '') }); }
                }
              } catch (e) {}
            }

            scan();
            setInterval(scan, 500);
            document.addEventListener('DOMContentLoaded', scan);
            document.addEventListener('click', function () { setTimeout(scan, 400); }, true);
          } catch (e) {}
        })();
        """.trimIndent()
    }
}
