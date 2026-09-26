package com.aliya2qq.bridge.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.aliya2qq.bridge.databinding.ActivityWebLoginBinding
import com.aliya2qq.bridge.engine.NapCatManager

/** NapCat WebUI 登录（扫码 / WebUi Token）。 */
class WebLoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWebLoginBinding

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val url = intent.getStringExtra("url") ?: NapCatManager.webUiUrl(this)
        Log.i("bridge", "WebLogin load $url")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                view?.evaluateJavascript(
                    """
                    (function(){
                      try {
                        if (navigator.serviceWorker) {
                          navigator.serviceWorker.register = function(){ return Promise.resolve({ unregister: function(){} }); };
                        }
                      } catch (e) {}
                    })();
                    """.trimIndent(),
                    null,
                )
                super.onPageStarted(view, url, favicon)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    Log.e("bridge", "WebUI error ${error?.errorCode} ${error?.description}")
                }
                super.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                Log.i("bridge", "WebUI page finished $url")
                val token = Regex("token=([0-9a-fA-F]+)").find(url ?: "")?.groupValues?.get(1)
                    ?: intent.getStringExtra("url")?.let { Regex("token=([0-9a-fA-F]+)").find(it)?.groupValues?.get(1) }
                val fillJs = if (!token.isNullOrBlank()) {
                    """
                    (function(){
                      try {
                        if (navigator.serviceWorker) {
                          navigator.serviceWorker.getRegistrations().then(function(rs){
                            rs.forEach(function(r){ r.unregister(); });
                          });
                        }
                        if (window.caches && caches.keys) {
                          caches.keys().then(function(ks){ ks.forEach(function(k){ caches.delete(k); }); });
                        }
                        var t = ${jsonString(token)};
                        var inputs = document.querySelectorAll('input');
                        for (var i = 0; i < inputs.length; i++) {
                          var el = inputs[i];
                          var ph = (el.placeholder || '') + (el.type || '');
                          if (ph.indexOf('token') >= 0 || ph.indexOf('Token') >= 0 || el.type === 'password') {
                            var proto = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                            if (proto && proto.set) proto.set.call(el, t); else el.value = t;
                            el.dispatchEvent(new Event('input', { bubbles: true }));
                            el.dispatchEvent(new Event('change', { bubbles: true }));
                            break;
                          }
                        }
                      } catch (e) {}
                    })();
                    """.trimIndent()
                } else {
                    """
                    (function(){
                      try {
                        if (navigator.serviceWorker) {
                          navigator.serviceWorker.getRegistrations().then(function(rs){
                            rs.forEach(function(r){ r.unregister(); });
                          });
                        }
                        if (window.caches && caches.keys) {
                          caches.keys().then(function(ks){ ks.forEach(function(k){ caches.delete(k); }); });
                        }
                      } catch (e) {}
                    })();
                    """.trimIndent()
                }
                view?.evaluateJavascript(fillJs, null)
                super.onPageFinished(view, url)
            }
        }
        binding.webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                val m = consoleMessage ?: return super.onConsoleMessage(null)
                Log.i(
                    "WebUI",
                    "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}",
                )
                return true
            }
        }
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // 桌面 UA：部分 WebUI 构建在移动 UA 下布局/模块加载异常
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }
        WebView.setWebContentsDebuggingEnabled(true)
        binding.webView.loadUrl(url)
        binding.btnReload.setOnClickListener { binding.webView.reload() }
        binding.btnBack.setOnClickListener { finish() }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack() else super.onBackPressed()
    }
}
