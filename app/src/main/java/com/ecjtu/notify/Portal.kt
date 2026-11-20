package com.ecjtu.notify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class Notice(
    val id: String,
    val title: String,
    val unit: String,
    val timeMs: Long,
    val viewCount: Int
) {
    fun timeText(): String =
        if (timeMs <= 0) "" else SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(timeMs))
}

object PortalApi {

    const val PORTAL = "http://portal.ecjtu.edu.cn"
    const val HOME_URL = "$PORTAL/dcp/forward.action?path=/portal/portal&p=pim&ac=0"
    const val PIM_PATH = "/dcp/pim/pim.action"

    private const val PIM_URL = "$PORTAL$PIM_PATH"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    val PAYLOAD: String = """
{"javaClass":"java.util.HashMap","map":{"method":"getAllPimList","params":{"javaClass":"java.util.ArrayList","list":["","","","","",""]},"pim":{"javaClass":"com.neusoft.education.edp.client.PageManager","pageSize":10,"pageNo":"1","totalCount":-1,"filters":{"javaClass":"com.neusoft.education.edp.client.QueryFilter","parameters":{"javaClass":"java.util.HashMap","map":{}}}}}
    """.trim()

    fun fetchRaw(cookie: String): String {
        val conn = (URL(PIM_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            setRequestProperty("Clienttype", "json")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Referer", HOME_URL)
            setRequestProperty("User-Agent", UA)
        }
        try {
            conn.outputStream.use { it.write(PAYLOAD.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    fun parse(body: String): List<Notice> {
        val arr = JSONObject(body).optJSONArray("list") ?: return emptyList()
        val out = ArrayList<Notice>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i)?.optJSONObject("map") ?: continue
            val id = m.optString("RESOURCE_ID")
            val title = m.optString("PIM_TITLE")
            if (id.isEmpty() || title.isEmpty()) continue
            out.add(
                Notice(
                    id = id,
                    title = title,
                    unit = m.optString("BELONG_UNIT_NAME"),
                    timeMs = m.optJSONObject("CREATE_TIME")?.optLong("time") ?: 0L,
                    viewCount = m.optInt("VIEW_COUNT")
                )
            )
        }
        return out
    }
}

object WebFetch {

    private val JS_TEMPLATE = """
        (function(){
          try{
            var xhr = new XMLHttpRequest();
            xhr.open('POST', '/dcp/pim/pim.action', true);
            xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
            xhr.setRequestHeader('Clienttype', 'json');
            xhr.onreadystatechange = function(){
              if (xhr.readyState === 4) {
                var body = (xhr.responseText || '').substring(0, 200000);
                AndroidBridge.result(xhr.status + '|' + body);
              }
            };
            xhr.send(__BODY__);
          }catch(e){ AndroidBridge.result('0|' + e); }
        })();
    """.trimIndent()

    private val BODY_LITERAL: String = "'" + PortalApi.PAYLOAD + "'"

    class Session(val web: WebView, val destroyOnFinish: Boolean, val callback: (String?) -> Unit) {
        private var done = false

        fun finishWith(body: String?) {
            if (done) return
            done = true
            if (body != null) Store.setLogged(web.context, true)
            if (destroyOnFinish) {
                try {
                    web.destroy()
                } catch (_: Throwable) {
                }
            }
            callback(body)
        }

        @JavascriptInterface
        fun result(raw: String) {
            val body = if (raw.startsWith("200|")) raw.substring(4) else null
            Handler(Looper.getMainLooper()).post { finishWith(body) }
        }
    }

    fun probe(web: WebView) {
        web.evaluateJavascript(JS_TEMPLATE.replace("__BODY__", BODY_LITERAL), null)
    }

    fun attach(web: WebView, destroyOnFinish: Boolean = false, onResult: (String?) -> Unit): Session {
        val session = Session(web, destroyOnFinish, onResult)
        web.addJavascriptInterface(session, "AndroidBridge")
        return session
    }

    fun fetch(ctx: Context, timeoutMs: Long, callback: (String?) -> Unit) {
        val app = ctx.applicationContext
        Handler(Looper.getMainLooper()).post {
            val web = WebView(app)
            val session = Session(web, true, callback)
            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            web.settings.databaseEnabled = true
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
            web.addJavascriptInterface(session, "AndroidBridge")
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view == null || url == null) return
                    if (url.contains("portal.ecjtu.edu.cn")) {
                        probe(view)
                    } else if (url.contains("cas.ecjtu.edu.cn")) {
                        session.finishWith(null)
                    }
                }
            }
            Handler(Looper.getMainLooper()).postDelayed({ session.finishWith(null) }, timeoutMs)
            web.loadUrl(PortalApi.HOME_URL)
        }
    }
}

object Notices {

    fun loadBlocking(ctx: Context, timeoutMs: Long = 40000): List<Notice>? {
        val cookie = CookieManager.getInstance().getCookie(PortalApi.PORTAL)
        if (!cookie.isNullOrBlank() && cookie.contains("JSESSIONID")) {
            try {
                val body = PortalApi.fetchRaw(cookie)
                val list = PortalApi.parse(body)
                if (list.isNotEmpty()) {
                    Store.setLogged(ctx, true)
                    return list
                }
            } catch (_: Exception) {
            }
        }

        var body: String? = null
        val latch = CountDownLatch(1)
        WebFetch.fetch(ctx, timeoutMs) {
            body = it
            latch.countDown()
        }
        latch.await(timeoutMs + 5000, TimeUnit.MILLISECONDS)
        val raw = body ?: return null
        return try {
            PortalApi.parse(raw).also { Store.setLogged(ctx, true) }
        } catch (_: Exception) {
            null
        }
    }
}

object Store {
    private const val PREF = "store"

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getHour(c: Context): Int = p(c).getInt("hour", 8)
    fun setHour(c: Context, h: Int) = p(c).edit().putInt("hour", h).apply()

    fun lastCheck(c: Context): Long = p(c).getLong("last", 0L)
    fun setLastCheck(c: Context, t: Long) = p(c).edit().putLong("last", t).apply()

    fun isFirstDone(c: Context): Boolean = p(c).getBoolean("first", false)
    fun setFirstDone(c: Context) = p(c).edit().putBoolean("first", true).apply()

    fun isLogged(c: Context): Boolean = p(c).getBoolean("logged", false)
    fun setLogged(c: Context, ok: Boolean) = p(c).edit().putBoolean("logged", ok).apply()

    fun seen(c: Context): MutableSet<String> = p(c).getStringSet("seen", emptySet())!!.toMutableSet()

    fun saveSeen(c: Context, ids: Collection<String>) =
        p(c).edit().putStringSet("seen", ids.toList().takeLast(400).toSet()).apply()
}
