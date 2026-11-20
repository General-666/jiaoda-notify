package com.ecjtu.notify

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var adapter: ArrayAdapter<String>
    private val titles = ArrayList<String>()
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.status)
        val listView = findViewById<ListView>(R.id.list)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, titles)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, _, _ ->
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PortalApi.HOME_URL)))
        }

        findViewById<Button>(R.id.btnLogin).setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
        }
        findViewById<Button>(R.id.btnCheck).setOnClickListener {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<NoticeWorker>().build())
            Toast.makeText(this, "已开始检查，几秒后看通知栏", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnTime).setOnClickListener { pickHour() }

        requestNotificationPermission()
        schedule(this, Store.getHour(this))
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
        }
    }

    private fun loggedIn(): Boolean {
        if (Store.isLogged(this)) return true
        val ck = CookieManager.getInstance().getCookie(PortalApi.PORTAL)
        return !ck.isNullOrBlank() && ck.contains("JSESSIONID")
    }

    private fun refreshStatus() {
        val hour = Store.getHour(this)
        val last = Store.lastCheck(this)
        val lastText = if (last <= 0) "还没检查过" else
            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(last))

        statusText.text = buildString {
            append(if (loggedIn()) "状态：已登录" else "状态：未登录（点「登录」输入学号密码）")
            append("\n每天 ").append(hour).append(":00 自动检查 · 上次：").append(lastText)
        }

        if (loading || !loggedIn()) return
        loading = true
        Thread {
            val list = try {
                Notices.loadBlocking(this, 30000)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                loading = false
                if (list != null) {
                    titles.clear()
                    list.take(10).forEach { titles.add(it.title + "\n" + it.unit + " · " + it.timeText()) }
                    adapter.notifyDataSetChanged()
                    statusText.text = buildString {
                        append("状态：已登录 · 已读取 ").append(list.size).append(" 条")
                        append("\n每天 ").append(hour).append(":00 自动检查 · 上次：").append(lastText)
                    }
                }
            }
        }.start()
    }

    private fun pickHour() {
        val h = Store.getHour(this)
        TimePickerDialog(this, { _, hour, _ ->
            Store.setHour(this, hour)
            schedule(this, hour)
            refreshStatus()
            Toast.makeText(this, "已设置为每天 " + hour + ":00 检查", Toast.LENGTH_SHORT).show()
        }, h, 0, true).show()
    }
}

fun schedule(ctx: Context, hour: Int) {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (target.timeInMillis <= now.timeInMillis) {
        target.add(Calendar.DAY_OF_YEAR, 1)
    }
    val req = PeriodicWorkRequestBuilder<NoticeWorker>(1, TimeUnit.DAYS)
        .setInitialDelay(target.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
        .build()
    WorkManager.getInstance(ctx)
        .enqueueUniquePeriodicWork("daily_notice", ExistingPeriodicWorkPolicy.UPDATE, req)
}
