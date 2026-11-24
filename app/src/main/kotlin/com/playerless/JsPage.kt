package com.playerless

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private const val LOAD_TIMEOUT_MS = 15_000L

// One browser for the page and for the BotGuard requests made on its behalf, as in Convx.
const val CHROME_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

// A hidden WebView that runs a page of YouTube's own JavaScript, the only engine the phone already
// has. Every call blocks until the page answers, so never call from the main thread: the WebView
// lives there.
class JsPage private constructor(context: Context, private val onSave: ((String) -> Unit)?) {
    private class Call {
        val done = CountDownLatch(1)
        @Volatile var result: String? = null
        @Volatile var error: String? = null
    }

    private val calls = ConcurrentHashMap<Int, Call>()
    private val ids = AtomicInteger()
    private val loaded = CountDownLatch(1)

    // Android may kill the WebView's renderer to free memory; the page is then gone for good.
    @Volatile var dead = false
        private set

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private val web = WebView(context).apply {
        settings.javaScriptEnabled = true
        // The page only computes; Kotlin makes the requests.
        settings.blockNetworkLoads = true
        // The solver reads the player from the app's files.
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowFileAccessFromFileURLs = true
        settings.userAgentString = CHROME_UA
        addJavascriptInterface(Bridge(), "Bridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = loaded.countDown()

            // The page must never leave: navigating would drop everything it has set up. The
            // solver, written for Deno, assigns a fake location, which in a browser navigates.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            // Unhandled, a killed renderer takes the whole app down with it, music included.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                dead = true
                calls.keys.forEach { finish(it, null, Lang.get(R.string.webview_killed)) }
                view.destroy()
                return true
            }
        }
    }

    // Called by the page, on a WebView thread.
    private inner class Bridge {
        @JavascriptInterface
        fun done(id: Int, result: String) = finish(id, result, null)

        @JavascriptInterface
        fun fail(id: Int, error: String) = finish(id, null, error)

        @JavascriptInterface
        fun save(text: String) {
            onSave?.invoke(text)
        }
    }

    private fun finish(id: Int, result: String?, error: String?) {
        val call = calls[id] ?: return
        call.result = result
        call.error = error
        call.done.countDown()
    }

    // Evaluates a JavaScript expression, waiting for it if it's a promise.
    fun call(expression: String, timeoutMs: Long = 30_000): String {
        if (dead) throw YtException(Lang.get(R.string.webview_killed))
        val id = ids.incrementAndGet()
        val call = Call()
        calls[id] = call
        val js = "Promise.resolve().then(function(){return $expression})" +
            ".then(function(r){Bridge.done($id,String(r))},function(e){Bridge.fail($id,String(e&&e.message||e))})"
        main.post { if (!dead) web.evaluateJavascript(js, null) }
        try {
            if (!call.done.await(timeoutMs, TimeUnit.MILLISECONDS)) throw YtException(Lang.get(R.string.js_timeout))
        } finally {
            calls.remove(id)
        }
        call.error?.let { throw YtException(it) }
        return call.result.orEmpty()
    }

    fun close() {
        main.post { if (!dead) web.destroy() }
    }

    companion object {
        private val main = Handler(Looper.getMainLooper())

        // Blocking: builds the page on the main thread and waits until it has loaded.
        fun open(context: Context, baseUrl: String, html: String, onSave: ((String) -> Unit)? = null): JsPage {
            var page: JsPage? = null
            var failure: Throwable? = null
            val created = CountDownLatch(1)
            main.post {
                try {
                    page = JsPage(context.applicationContext, onSave).also {
                        it.web.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
                    }
                } catch (e: Throwable) {
                    // No usable WebView on this phone.
                    failure = e
                }
                created.countDown()
            }
            if (!created.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw YtException(Lang.get(R.string.webview_timeout))
            val opened = page ?: throw YtException(Lang.get(R.string.webview_missing, failure?.message.orEmpty()))
            if (!opened.loaded.await(LOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                opened.close()
                throw YtException(Lang.get(R.string.js_page_failed))
            }
            return opened
        }
    }
}
