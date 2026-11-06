package mz.kikocodex.archeolucky

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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

/**
 * Archeo Lucky Browser
 * ---------------------
 * App 100% Kotlin que:
 *  1. Abre o Google num WebView;
 *  2. Detecta quando o jogo ARCHÉO (PrideBet MZ) é aberto e mostra o toast
 *     "jogo Lindo com sucesso ✅✅";
 *  3. Mostra o SALDO do jogo, em tempo real, no canto superior da tela.
 *
 * Como o saldo é lido:
 *  O Archeo roda dentro de um iframe de outro domínio (splitthepot.games).
 *  Por isso não basta o evaluateJavascript (que só enxerga o frame principal).
 *  A solução usa as APIs do androidx.webkit:
 *    - addDocumentStartJavaScript("*")  -> injeta um observador em TODOS os frames
 *    - addWebMessageListener("*")       -> recebe as mensagens vindas de qualquer frame
 *  O observador faz um poll de ~500ms no texto do frame e envia o valor do "Saldo"
 *  para o app, que atualiza o HUD. Se o WebView do aparelho for antigo
 *  (sem essas features), há fallback por evaluateJavascript no frame principal.
 */
class MainActivity : android.app.Activity() {

    private lateinit var webView: WebView
    private lateinit var saldoValue: TextView
    private lateinit var liveStatus: TextView
    private lateinit var liveDot: View
    private lateinit var statusLine: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var gameToastAlreadyShown = false
    private var lastBalance: String? = null
    private var mainFrameFallbackRunning = false

    private val startUrl = "https://www.google.com/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        saldoValue = findViewById(R.id.saldoValue)
        liveStatus = findViewById(R.id.liveStatus)
        liveDot = findViewById(R.id.liveDot)
        statusLine = findViewById(R.id.statusLine)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<TextView>(R.id.btnGoogle).setOnClickListener {
            resetHud()
            webView.loadUrl(startUrl)
        }

        setupWebView()
        installRealtimeBridge()

        // Se o app foi aberto por um link direto (ex.: pridebet.co.mz/games/Archeo-...)
        val deepLink = intent?.dataString
        webView.loadUrl(if (!deepLink.isNullOrBlank()) deepLink else startUrl)
    }

    // ------------------------------------------------------------------ WebView

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

        // Alguns sites bloqueiam o UA de WebView ("; wv"). Remove o marcador.
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
                // Rede de segurança: garante o observador no frame principal
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

    /** Reconhece qualquer URL do jogo Archéo (na PrideBet ou no provedor). */
    private fun isArcheo(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val u = url.lowercase(Locale.ROOT)
        if (!u.contains("archeo")) return false
        return u.contains("pridebet") ||
            u.contains("splitthepot") ||
            u.contains("playdemo") ||
            u.contains("/games/archeo")
    }

    // ------------------------------------------------------------------ Toast

    private fun notifyGameOpened() {
        if (gameToastAlreadyShown) return
        gameToastAlreadyShown = true
        Toast.makeText(this, R.string.game_toast, Toast.LENGTH_LONG).show()
        liveStatus.text = getString(R.string.status_live)
        liveStatus.setTextColor(getColor(R.color.green))
        statusLine.text = "Archéo detectado · lendo saldo…"
    }

    // ------------------------------------------------- Ponte do saldo (real-time)

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
                    val frameUrl = json.optString("url")
                    if (isArcheo(frameUrl)) notifyGameOpened()
                }

                "saldo" -> {
                    val value = json.optString("value")
                    if (value.isNotBlank()) {
                        notifyGameOpened()
                        updateBalance(value)
                    }
                }
            }
        } catch (_: Exception) {
            // mensagem que não é JSON: ignora
        }
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
        saldoValue.text = getString(R.string.saldo_empty)
        liveStatus.text = getString(R.string.status_offline)
        liveStatus.setTextColor(getColor(R.color.muted))
        statusLine.text = getString(R.string.app_name)
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            resetHud()
            super.onBackPressed()
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
         * Observador injetado em TODOS os frames (inclusive o iframe cross-origin do jogo).
         * Faz poll do texto do frame, extrai o "Saldo" e envia para o app.
         */
        private val WATCHER_JS = """
        (function () {
          try {
            if (window.__archeoLuckyWatcher) { return; }
            window.__archeoLuckyWatcher = true;

            var BRIDGE = 'ArcheoBridge';
            function post(obj) {
              try {
                var b = window[BRIDGE];
                if (b && b.postMessage) { b.postMessage(JSON.stringify(obj)); }
              } catch (e) {}
            }

            var href = String(location.href || '');
            var isGame = /archeo/i.test(href) && /(pridebet|splitthepot|playdemo)/i.test(href);
            if (isGame) { post({ type: 'hello', url: href }); }

            var last = null;
            function scan() {
              try {
                var txt = (document.body && document.body.innerText) ? document.body.innerText : '';
                if (!txt) { return; }
                var m = txt.match(/Saldo\s*([0-9][0-9\.\s]*,[0-9]{2})\s*FUN/i);
                if (m) {
                  var v = m[1].replace(/\s/g, '');
                  if (v !== last) {
                    last = v;
                    post({ type: 'saldo', value: v, url: String(location.href || '') });
                  }
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
