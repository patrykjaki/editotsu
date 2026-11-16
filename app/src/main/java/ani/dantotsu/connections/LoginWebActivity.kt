package ani.dantotsu.connections

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import ani.dantotsu.connections.anilist.AnilistCallback
import ani.dantotsu.connections.mal.MalCallback
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * AniList / MAL sign-in inside the app. The redirect is caught here, so the app never leaves
 * for a browser, Android never asks which app should open the link (the upstream app claims
 * the same MAL link), and the account loads under a progress bar instead of a blank screen.
 */
class LoginWebActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var progress: LinearProgressIndicator
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager(this).applyTheme()
        val url = intent.getStringExtra(EXTRA_URL)
        if (url == null) {
            finish()
            return
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    catchRedirect(request.url)

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    if (!finishing) this@LoginWebActivity.progress.visibility = View.VISIBLE
                }

                override fun onPageFinished(view: WebView, url: String) {
                    if (!finishing) this@LoginWebActivity.progress.visibility = View.GONE
                }
            }
        }
        progress = LinearProgressIndicator(this).apply { isIndeterminate = true }
        val root = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }
        web.loadUrl(url)
    }

    private fun catchRedirect(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "editotsu" && scheme != "dantotsu") return false
        val host = uri.host?.lowercase()
        if (host != "mal" && host != "anilist") return false
        if (finishing) return true
        finishing = true
        web.visibility = View.INVISIBLE
        progress.visibility = View.VISIBLE
        if (host == "mal") MalCallback.handle(this, uri) else AnilistCallback.handle(this, uri.toString())
        return true
    }

    override fun onDestroy() {
        if (::web.isInitialized) web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"

        /** Opens [url] in the in-app sign-in page; false when that is not possible (no WebView). */
        fun open(context: android.content.Context, url: String): Boolean = try {
            context.startActivity(
                android.content.Intent(context, LoginWebActivity::class.java).putExtra(EXTRA_URL, url)
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
