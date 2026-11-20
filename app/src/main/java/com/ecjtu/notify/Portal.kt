package com.ecjtu.notify

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private const val PIM_URL = "$PORTAL/dcp/pim/pim.action"

    private val PAYLOAD: String = """
{"javaClass":"java.util.HashMap","map":{"method":"getAllPimList","params":{"javaClass":"java.util.ArrayList","list":["","","","","",""]},"pim":{"javaClass":"com.neusoft.education.edp.client.PageManager","pageSize":10,"pageNo":"1","totalCount":-1,"filters":{"javaClass":"com.neusoft.education.edp.client.QueryFilter","parameters":{"javaClass":"java.util.HashMap","map":{}}}}}
""".trim()

    fun fetch(cookie: String): List<Notice> {
        val conn = (URL(PIM_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            setRequestProperty("Clienttype", "json")
            setRequestProperty("Cookie", cookie)
            setRequestProperty("Referer", HOME_URL)
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0.0.0 Mobile Safari/537.36"
            )
        }
        try {
            conn.outputStream.use { it.write(PAYLOAD.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
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

object Store {
    private const val PREF = "store"

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun getHour(c: Context): Int = p(c).getInt("hour", 8)
    fun setHour(c: Context, h: Int) = p(c).edit().putInt("hour", h).apply()

    fun lastCheck(c: Context): Long = p(c).getLong("last", 0L)
    fun setLastCheck(c: Context, t: Long) = p(c).edit().putLong("last", t).apply()

    fun isFirstDone(c: Context): Boolean = p(c).getBoolean("first", false)
    fun setFirstDone(c: Context) = p(c).edit().putBoolean("first", true).apply()

    fun seen(c: Context): MutableSet<String> = p(c).getStringSet("seen", emptySet())!!.toMutableSet()

    fun saveSeen(c: Context, ids: Collection<String>) =
        p(c).edit().putStringSet("seen", ids.toList().takeLast(400).toSet()).apply()
}
