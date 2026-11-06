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
 * Archeo Lucky Browser  ·  v1.2
 * =============================
 *  1. Abre o Google num WebView;
 *  2. Detecta e anuncia os jogos:
 *       · ARCHÉO      -> toast "jogo Lindo com sucesso ✅✅"
 *       · MI MINES    -> toast "Mi Mines Carregado ✅✅✅"
 *  3. HUD com o SALDO em tempo real (FUN no Archéo, MZN no Mi Mines);
 *  4. Janela flutuante "G" com stake, nº de cliques, **nº de minas** e operação automática.
 *
 * Engines automáticos (rodam DENTRO do frame do jogo):
 *   · Archéo   (SplitThePot, Angular+SVG) -> button[tile] / COMEÇAR / CASHOUT
 *   · Mi Mines (Spribe, canvas 5x5)       -> canvas hit-test por coordenada + BET / CASH OUT
 */
class MainActivity : android.app.Activity() {

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
    private lateinit var minesValue: TextView
    private lateinit var autoStatus: TextView
    private lateinit var btnAuto: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var lastToastGame: String? = null
    private var detectedGame: String? = null
    private var lastBalance: String? = null
    private var mainFrameFallbackRunning = false

    private var stake = 1.0
    private var clicksPerSession = 3
    private var minesCount = 3
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
        minesValue = findViewById(R.id.minesValue)
        autoStatus = findViewById(R.id.autoStatus)
        btnAuto = findViewById(R.id.btnAuto)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupWebView()
        installRealtimeBridge()
        setupFloatingPanel()

        refreshStakeLabel()
        refreshClicksLabel()
        refreshMinesLabel()

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
                val g = gameFromUrl(url)
                if (g != null) notifyGameOpened(g) else lastToastGame = null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                gameFromUrl(url)?.let { notifyGameOpened(it) }
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
        gameFromUrl(url)?.let { notifyGameOpened(it) }
    }

    private fun gameFromUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val u = url.lowercase(Locale.ROOT)
        return when {
            u.contains("mines-6") -> GAME_MIMINES
            u.contains("spribegaming") && u.contains("mines") -> GAME_MIMINES
            u.contains("archeo") && (u.contains("pridebet") || u.contains("splitthepot") ||
                u.contains("playdemo") || u.contains("/games/archeo")) -> GAME_ARCHEO
            else -> null
        }
    }

    private fun notifyGameOpened(game: String) {
        val changed = detectedGame != game
        detectedGame = game
        if (changed) {
            autoStatus.text = getString(
                if (game == GAME_MIMINES) R.string.mines_toast else R.string.game_toast
            )
        }
        if (lastToastGame == game) return
        lastToastGame = game
        val res = if (game == GAME_MIMINES) R.string.mines_toast else R.string.game_toast
        Toast.makeText(this, res, Toast.LENGTH_LONG).show()
        liveStatus.text = getString(R.string.status_live)
        liveStatus.setTextColor(getColor(R.color.green))
        statusLine.text = if (game == GAME_MIMINES) "Mi Mines · lendo saldo…" else "Archéo · lendo saldo…"
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
                    val g = json.optString("game")
                    if (g == GAME_MIMINES || g == GAME_ARCHEO) {
                        notifyGameOpened(g)
                        // o board acabou de ficar pronto: reenvia as configurações
                        // (antes disso o frame do jogo ainda não existia e o comando era descartado)
                        pushSettings()
                    }
                }

                "saldo" -> {
                    val value = json.optString("value")
                    val cur = json.optString("currency", "FUN")
                    if (value.isNotBlank()) {
                        updateBalance(value, cur)
                    }
                }

                "auto" -> handleAutoReport(json)
            }
        } catch (_: Exception) {
            // não é JSON: ignora
        }
    }

    private fun handleAutoReport(json: JSONObject) {
        val g = json.optString("game")
        if (g == GAME_MIMINES || g == GAME_ARCHEO) detectedGame = g
        autoRunning = json.optBoolean("running", autoRunning)
        val sessions = json.optInt("sessions", 0)
        val wins = json.optInt("wins", 0)
        val losses = json.optInt("losses", 0)
        val st = json.optDouble("stake", stake)
        val ck = json.optInt("clicks", clicksPerSession)
        val mn = json.optInt("mines", minesCount)
        val game = if (detectedGame == GAME_MIMINES) "Mi Mines" else "Archéo"

        panelSummary.text = "W$wins · L$losses"
        autoStatus.text = buildString {
            append(if (autoRunning) "● rodando" else "○ parado")
            append("  ·  ").append(game)
            append("\nsessões: ").append(sessions)
            append("   W: ").append(wins).append("   L: ").append(losses)
            append("\nstake ").append(money(st)).append(" · ").append(ck).append(" cliques")
            if (detectedGame == GAME_MIMINES) append(" · ").append(mn).append(" minas")
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
                    "(function(){var t=document.body?document.body.innerText:'';" +
                        "var m=t.match(/Saldo\\s*([0-9.]+,\\d{2})\\s*FUN/i);return m?m[1]:'';})()"
                ) { result ->
                    val v = result?.replace("\"", "")?.trim()
                    if (!v.isNullOrBlank() && v != "null") updateBalance(v, "FUN")
                }
                ui.postDelayed(this, 1000)
            }
        }
        ui.postDelayed(tick, 1000)
    }

    private fun updateBalance(value: String, currency: String) {
        val key = "$value $currency"
        if (key == lastBalance) return
        lastBalance = key
        saldoValue.text = "$value $currency"
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
        lastToastGame = null
        detectedGame = null
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

        findViewById<TextView>(R.id.btnClose).setOnClickListener { panel.visibility = View.GONE }
        findViewById<TextView>(R.id.btnMinimize).setOnClickListener { toggleMinimize() }
        panelHeader.setOnClickListener { toggleMinimize() }
        panelHeader.setOnTouchListener(DragListener())

        findViewById<TextView>(R.id.btnStakeMinus).setOnClickListener {
            stake = ((stake - 0.5).coerceAtLeast(0.5) * 100).roundToInt() / 100.0
            refreshStakeLabel(); pushSettings()
        }
        findViewById<TextView>(R.id.btnStakePlus).setOnClickListener {
            stake = ((stake + 0.5).coerceAtMost(100.0) * 100).roundToInt() / 100.0
            refreshStakeLabel(); pushSettings()
        }
        findViewById<TextView>(R.id.btnClicksMinus).setOnClickListener {
            clicksPerSession = (clicksPerSession - 1).coerceAtLeast(1)
            refreshClicksLabel(); pushSettings()
        }
        findViewById<TextView>(R.id.btnClicksPlus).setOnClickListener {
            clicksPerSession = (clicksPerSession + 1).coerceAtMost(10)
            refreshClicksLabel(); pushSettings()
        }
        findViewById<TextView>(R.id.btnMinesMinus).setOnClickListener {
            minesCount = (minesCount - 1).coerceAtLeast(1)
            refreshMinesLabel(); pushSettings()
        }
        findViewById<TextView>(R.id.btnMinesPlus).setOnClickListener {
            minesCount = (minesCount + 1).coerceAtMost(20)
            refreshMinesLabel(); pushSettings()
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
            if (detectedGame == null) {
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
        sendToGame("setMines", JSONObject().put("value", minesCount))
    }

    private fun refreshStakeLabel() {
        stakeValue.text = "${money(stake)} FUN"
    }

    private fun refreshClicksLabel() {
        clicksValue.text = if (clicksPerSession == 1) "1 clique" else "$clicksPerSession cliques"
    }

    private fun refreshMinesLabel() {
        minesValue.text = if (minesCount == 1) "1 mina" else "$minesCount minas"
    }

    private fun money(v: Double): String =
        String.format(Locale.US, "%.2f", v).replace(".", ",")

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

    private inner class DragListener : View.OnTouchListener {
        private var dx = 0f
        private var dy = 0f
        private var downX = 0f
        private var downY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    dx = panel.x - e.rawX; dy = panel.y - e.rawY
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
        private const val GAME_ARCHEO = "archeo"
        private const val GAME_MIMINES = "mimines"

        private val WATCHER_JS = """
        (function () {
          try {
            if (window.__archeoLuckyWatcher) { return; }
            window.__archeoLuckyWatcher = true;

            var BRIDGE = 'ArcheoBridge';
            var AUTO = { running: false, stake: 1, clicks: 3, mines: 3,
                         sessions: 0, wins: 0, losses: 0, busy: false };
            var seen = {};
            var announced = null;
            var lastBal = null;

            function post(o) {
              try { var b = window[BRIDGE]; if (b && b.postMessage) { b.postMessage(JSON.stringify(o)); } } catch (e) {}
            }
            function all(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }
            function sleep(ms) { return new Promise(function (r) { setTimeout(r, ms); }); }
            function money2(v) {
              var s = Number(v).toFixed(2), p = s.split('.');
              return p[0].replace(/\B(?=(\d{3})+(?!\d))/g, '.') + ',' + p[1];
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
            function shuffle(a) {
              for (var i = a.length - 1; i > 0; i--) {
                var j = Math.floor(Math.random() * (i + 1));
                var t = a[i]; a[i] = a[j]; a[j] = t;
              }
              return a;
            }

            // ------------------------------------------------ deteccao do jogo
            function archeoBoard() { return document.querySelector('div.level'); }
            function minesBoard() {
              return document.querySelector('.number-of-mines') && document.querySelector('canvas');
            }
            function game() {
              if (archeoBoard()) { return 'archeo'; }
              if (minesBoard()) { return 'mimines'; }
              return null;
            }

            // ------------------------------------------------ leitura do saldo
            function archeoBalance() {
              var t = (document.body && document.body.innerText) ? document.body.innerText : '';
              var m = t.match(/Saldo\s*([0-9][0-9\.\s]*,[0-9]{2})\s*FUN/i);
              return m ? { v: m[1].replace(/\s/g, ''), cur: 'FUN' } : null;
            }
            function minesBalance() {
              var els = document.querySelectorAll('div,span,p');
              for (var i = 0; i < els.length; i++) {
                var n = els[i];
                var p = n.parentElement ? ((n.parentElement.innerText || '').trim().replace(/\s+/g, ' ')) : '';
                if (/^[0-9][0-9,]*\.[0-9]{2} MZN$/.test(p)) {
                  var num = parseFloat(p.split(' ')[0].replace(/,/g, ''));
                  if (!isNaN(num)) { return { v: money2(num), cur: 'MZN' }; }
                }
              }
              return null;
            }

            function report() {
              post({ type: 'auto', game: game(), running: AUTO.running, sessions: AUTO.sessions,
                     wins: AUTO.wins, losses: AUTO.losses, stake: AUTO.stake,
                     clicks: AUTO.clicks, mines: AUTO.mines });
            }

            // ================================================= ARCHÉO
            function arcActive() {
              var ls = all('div.level');
              for (var i = 0; i < ls.length; i++) {
                var a = ls[i].querySelector('.level-active');
                if (!a) { continue; }
                var vis = Array.prototype.slice.call(a.querySelectorAll('button[tile]')).filter(function (b) {
                  return b.getBoundingClientRect().width > 0;
                });
                if (vis.length >= 4) { return { idx: i, tiles: vis }; }
              }
              return null;
            }
            function arcOpened() {
              return all('stp-gold-digger-level-opened').filter(function (e) {
                return getComputedStyle(e).display !== 'none';
              }).length;
            }
            function arcBtn(re) {
              var bs = all('button');
              for (var i = 0; i < bs.length; i++) { if (re.test((bs[i].innerText || '').trim())) { return bs[i]; } }
              return null;
            }
            function arcCashVal() {
              var b = arcBtn(/CASHOUT/i);
              if (!b) { return 0; }
              var m = (b.innerText || '').match(/([0-9\.]+,[0-9]{2})/);
              return m ? parseFloat(m[1].replace(/\./g, '').replace(',', '.')) : 0;
            }
            function simClick(el) {
              if (!el) { return false; }
              try {
                var r = el.getBoundingClientRect();
                var o = { bubbles: true, cancelable: true, view: window,
                          clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
                try { el.dispatchEvent(new PointerEvent('pointerdown', o)); } catch (e) {}
                try { el.dispatchEvent(new MouseEvent('mousedown', o)); } catch (e) {}
                try { el.dispatchEvent(new PointerEvent('pointerup', o)); } catch (e) {}
                try { el.dispatchEvent(new MouseEvent('mouseup', o)); } catch (e) {}
                el.click();
                return true;
              } catch (e) { return false; }
            }
            function arcSetStake(v) {
              try {
                var inp = document.querySelector('input.stake-input') || document.querySelector('input[type=number]');
                if (!inp) { return false; }
                var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                setter.call(inp, Number(v).toFixed(2));
                inp.dispatchEvent(new Event('input', { bubbles: true }));
                inp.dispatchEvent(new Event('change', { bubbles: true }));
                inp.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true }));
                inp.dispatchEvent(new Event('blur', { bubbles: true }));
                return true;
              } catch (e) { return false; }
            }
            function arcDismiss() {
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
            async function arcWaitPick(prevIdx, timeout) {
              var t0 = Date.now();
              while (Date.now() - t0 < timeout) {
                var a = arcActive();
                if (a && a.idx > prevIdx) { return { ok: true, idx: a.idx }; }
                if (!a && arcOpened() > 0) { return { ok: false, dead: true }; }
                if (!a && arcCashVal() > 0) { return { ok: true, idx: prevIdx }; }
                await sleep(150);
              }
              return { ok: false, timeout: true };
            }
            async function arcSession() {
              arcDismiss();
              if (arcActive() && arcCashVal() > 0) {
                simClick(arcBtn(/CASHOUT/i));
                await waitFor(function () { return !arcActive(); }, 5000);
              }
              if (!AUTO.running) { return; }
              arcSetStake(AUTO.stake);
              await sleep(200);
              var startBtn = arcBtn(/COME[CÇ]AR/i);
              if (!startBtn) { return; }
              simClick(startBtn);
              var s = await waitFor(arcActive, 7000);
              if (!s) { return; }
              var prev = s.idx, alive = true;
              for (var i = 0; i < AUTO.clicks; i++) {
                if (!AUTO.running) { break; }
                var a = arcActive();
                if (!a) { alive = false; break; }
                simClick(a.tiles[Math.floor(Math.random() * a.tiles.length)]);
                var res = await arcWaitPick(prev, 7000);
                if (res && res.ok) { prev = res.idx; } else { alive = false; break; }
              }
              if (alive && arcCashVal() > 0 && arcActive()) {
                simClick(arcBtn(/CASHOUT/i)); AUTO.wins++;
                await waitFor(function () { return !arcActive(); }, 6000);
              } else if (alive) {
                AUTO.wins++;
              } else {
                AUTO.losses++;
                await waitFor(function () { return !arcActive(); }, 4000);
              }
              AUTO.sessions++;
              report();
            }

            // ================================================= MI MINES (Spribe / canvas)
            function mmBetBtn() {
              var bs = all('button');
              for (var i = 0; i < bs.length; i++) {
                if (/^bet$/i.test((bs[i].innerText || '').trim())) { return bs[i]; }
              }
              return null;
            }
            function mmCashBtn() {
              var bs = all('button');
              for (var i = 0; i < bs.length; i++) {
                if (/cash\s*out/i.test(bs[i].innerText || '')) { return bs[i]; }
              }
              return null;
            }
            function mmCashVal() {
              var b = mmCashBtn();
              if (!b) { return null; }
              var m = (b.innerText || '').match(/([0-9][0-9,]*\.[0-9]{2})/);
              return m ? parseFloat(m[1].replace(/,/g, '')) : null;
            }
            function mmSetMines(n) {
              try {
                var opts = all('.number-of-mines .dropdown-list-number button');
                for (var i = 0; i < opts.length; i++) {
                  if ((opts[i].innerText || '').trim() === String(n)) { opts[i].click(); return true; }
                }
                return false;
              } catch (e) { return false; }
            }
            function mmClickCell(i, j) {
              var cv = document.querySelector('canvas');
              if (!cv) { return false; }
              try {
                var r = cv.getBoundingClientRect();
                var x = r.left + r.width * ((i + 0.5) / 5);
                var y = r.top + r.height * ((j + 0.5) / 5);
                var o = { bubbles: true, cancelable: true, view: window, clientX: x, clientY: y,
                          button: 0, buttons: 1, pointerId: 1, pointerType: 'mouse', isPrimary: true };
                try { cv.dispatchEvent(new PointerEvent('pointermove', o)); } catch (e) {}
                try { cv.dispatchEvent(new PointerEvent('pointerdown', o)); } catch (e) {}
                try { cv.dispatchEvent(new MouseEvent('mousedown', o)); } catch (e) {}
                try { cv.dispatchEvent(new PointerEvent('pointerup', o)); } catch (e) {}
                try { cv.dispatchEvent(new MouseEvent('mouseup', o)); } catch (e) {}
                try { cv.dispatchEvent(new MouseEvent('click', o)); } catch (e) {}
                return true;
              } catch (e) { return false; }
            }
            async function mmSession() {
              if (!AUTO.running) { return; }
              if (mmCashBtn()) {
                mmCashBtn().click();
                await waitFor(mmBetBtn, 8000);
              }
              if (!AUTO.running) { return; }
              mmSetMines(AUTO.mines);
              await sleep(300);
              var bet = mmBetBtn();
              if (!bet) { return; }
              bet.click();
              var opened = await waitFor(mmCashBtn, 8000);
              if (!opened) { return; }

              var cells = [];
              for (var i = 0; i < 5; i++) { for (var j = 0; j < 5; j++) { cells.push([i, j]); } }
              shuffle(cells);

              var alive = true;
              for (var k = 0; k < AUTO.clicks && k < cells.length; k++) {
                if (!AUTO.running) { break; }
                var prevCash = mmCashVal();
                mmClickCell(cells[k][0], cells[k][1]);
                var res = await waitFor(function () {
                  if (mmBetBtn() && !mmCashBtn()) { return 'dead'; }
                  var v = mmCashVal();
                  if (v !== null && v !== prevCash) { return 'alive'; }
                  return null;
                }, 8000);
                if (res === 'alive') { await sleep(300); } else { alive = false; break; }
              }

              if (alive && mmCashBtn()) {
                mmCashBtn().click(); AUTO.wins++;
                await waitFor(mmBetBtn, 8000);
              } else {
                AUTO.losses++;
                await waitFor(mmBetBtn, 4000);
              }
              AUTO.sessions++;
              report();
            }

            // ================================================= ciclo
            async function runSession() {
              var g = game();
              if (g === 'mimines') { return mmSession(); }
              if (g === 'archeo') { return arcSession(); }
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
              if (!game()) { return false; }
              switch (d.cmd) {
                case 'setStake':
                  AUTO.stake = Number(d.value) || 1;
                  if (game() === 'archeo') { arcSetStake(AUTO.stake); }
                  report(); return true;
                case 'setClicks':
                  AUTO.clicks = Math.min(24, Math.max(1, Number(d.value) || 3));
                  report(); return true;
                case 'setMines':
                  AUTO.mines = Math.min(20, Math.max(1, Number(d.value) || 3));
                  if (game() === 'mimines') { mmSetMines(AUTO.mines); }
                  report(); return true;
                case 'start':
                  if (!AUTO.running) { AUTO.running = true; report(); loop(); }
                  return true;
                case 'stop':
                  AUTO.running = false; report(); return true;
                case 'status':
                  report(); return true;
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
              if (!handleCmd(d)) { relay(d); }
            });

            // ------------------------------------------------ vigia (saldo + anuncio)
            function scan() {
              try {
                var g = game();
                if (g && g !== announced) {
                  announced = g;
                  post({ type: 'hello', game: g, url: String(location.href || '') });
                }
                if (!g) { return; }
                var b = (g === 'mimines') ? minesBalance() : archeoBalance();
                if (b && b.v !== lastBal) {
                  lastBal = b.v;
                  post({ type: 'saldo', value: b.v, currency: b.cur });
                }
              } catch (e) {}
            }
            scan();
            setInterval(scan, 500);
            document.addEventListener('DOMContentLoaded', scan);
            document.addEventListener('click', function () { setTimeout(scan, 350); }, true);
          } catch (e) {}
        })();
        """.trimIndent()
    }
}
