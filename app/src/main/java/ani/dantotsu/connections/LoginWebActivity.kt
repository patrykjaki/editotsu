package ani.dantotsu.connections

import ani.dantotsu.getThemeColor
import ani.dantotsu.themes.followThemeStatusBarIcons
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
import ani.dantotsu.R
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
    private lateinit var signingIn: View

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
            // Present as the phone's Chrome (drop the WebView markers) and keep Cloudflare's
            // cookies: AniList's login check otherwise refuses to load in the page.
            settings.userAgentString = settings.userAgentString
                .replace("; wv)", ")")
                .replace(Regex("""Version/\d+(\.\d+)* """), "")
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
        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        progress = LinearProgressIndicator(this).apply { isIndeterminate = true }
        val dp = resources.displayMetrics.density
        fun iconButton(icon: Int, label: Int, onClick: () -> Unit) = android.widget.ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(
                getThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            contentDescription = getString(label)
            val outValue = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
            setBackgroundResource(outValue.resourceId)
            setOnClickListener { onClick() }
        }
        val bar = FrameLayout(this).apply {
            val size = (48 * dp).toInt()
            addView(iconButton(R.drawable.ic_round_close_24, R.string.close) { finish() },
                FrameLayout.LayoutParams(size, size, android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL)
                    .apply { marginStart = (4 * dp).toInt() })
            // If the page will not load here, finish the login in the browser instead.
            addView(iconButton(R.drawable.ic_round_open_in_new_24, R.string.open_in_browser) { openInBrowser(url) },
                FrameLayout.LayoutParams(size, size, android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL)
                    .apply { marginEnd = (4 * dp).toInt() })
        }
        // Shown once the service answers, while the account loads: the service's logo over a
        // spinner instead of an empty page.
        signingIn = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            visibility = View.GONE
            val logo = if (url.contains("myanimelist")) R.drawable.ic_myanimelist else R.drawable.ic_anilist
            addView(android.widget.ImageView(context).apply { setImageResource(logo) },
                android.widget.LinearLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt()))
            addView(com.google.android.material.progressindicator.CircularProgressIndicator(context).apply {
                isIndeterminate = true
            }, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (24 * dp).toInt() })
        }
        val page = FrameLayout(this).apply {
            addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(signingIn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(bar, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * dp).toInt()))
            addView(page, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        setContentView(root)
        followThemeStatusBarIcons()
        onBackPressedDispatcher.addCallback(this) {
            if (web.canGoBack()) web.goBack() else finish()
        }
        web.loadUrl(url)
    }

    private fun openInBrowser(url: String) {
        try {
            androidx.browser.customtabs.CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
        } catch (_: android.content.ActivityNotFoundException) {
            ani.dantotsu.openLinkInBrowser(url)
        }
        finish()
    }

    private fun catchRedirect(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "editotsu" && scheme != "dantotsu") return false
        val host = uri.host?.lowercase()
        if (host != "mal" && host != "anilist") return false
        if (finishing) return true
        finishing = true
        web.visibility = View.INVISIBLE
        progress.visibility = View.GONE
        signingIn.visibility = View.VISIBLE
        if (host == "mal") MalCallback.handle(this, uri, quiet = true) else AnilistCallback.handle(this, uri.toString())
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
