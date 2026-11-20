package com.ecjtu.notify

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class LoginActivity : AppCompatActivity() {

    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val web = findViewById<WebView>(R.id.web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        WebFetch.attach(web, destroyOnFinish = false) { body ->
            checking = false
            val ok = body != null && (body.contains("RESOURCE_ID") || body.contains("totalCount"))
            if (ok) {
                CookieManager.getInstance().flush()
                Store.setLogged(this, true)
                Toast.makeText(this, "登录成功", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (view == null || url == null) return
                if (!url.contains("portal.ecjtu.edu.cn")) return
                if (checking) return
                checking = true
                WebFetch.probe(view)
            }
        }

        web.loadUrl(PortalApi.HOME_URL)
    }
}
