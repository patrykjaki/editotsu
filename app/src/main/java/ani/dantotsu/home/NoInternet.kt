package ani.dantotsu.home

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnAttach
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.dantotsu.MainActivity
import ani.dantotsu.R
import ani.dantotsu.ZoomOutPageTransformer
import ani.dantotsu.databinding.ActivityNoInternetBinding
import ani.dantotsu.download.anime.OfflineAnimeFragment
import ani.dantotsu.download.manga.OfflineMangaFragment
import ani.dantotsu.initActivity
import ani.dantotsu.isOnline
import ani.dantotsu.navBarHeight
import ani.dantotsu.offline.LocalFragment
import ani.dantotsu.selectedOption
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.settings.saving.PrefName
import ani.dantotsu.snackString
import ani.dantotsu.themes.ThemeManager
import com.google.android.material.snackbar.Snackbar
import nl.joery.animatedbottombar.AnimatedBottomBar

class NoInternet : AppCompatActivity() {
    private lateinit var binding: ActivityNoInternetBinding
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var goOnlineSnack: Snackbar? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ThemeManager(this).applyTheme()

        binding = ActivityNoInternetBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bottomBar = findViewById<AnimatedBottomBar>(R.id.navbar)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {

            val backgroundDrawable = bottomBar.background as GradientDrawable
            val currentColor = backgroundDrawable.color?.defaultColor ?: 0
            val semiTransparentColor = (currentColor and 0x00FFFFFF) or 0xE8000000.toInt()
            backgroundDrawable.setColor(semiTransparentColor)
            bottomBar.background = backgroundDrawable
        }
        bottomBar.background = ContextCompat.getDrawable(this, R.drawable.bottom_nav_gray)


        // First back shows "back again to exit" and turns this off for 2 s; the second
        // back is the system's, so it gets the predictive back-to-home preview.
        onBackPressedDispatcher.addCallback(this) {
            isEnabled = false
            val callback = this
            snackString(this@NoInternet.getString(R.string.back_to_exit))
            Handler(Looper.getMainLooper()).postDelayed({ callback.isEnabled = true }, 2000)
        }

        binding.root.doOnAttach {
            initActivity(this)

            binding.includedNavbar.navbarContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = navBarHeight
            }
        }
        selectedOption = OfflinePages.startPage(
            intent.getStringExtra("FRAGMENT_CLASS_NAME"),
            PrefManager.getVal(PrefName.DefaultStartUpTab)
        )
        val navbar = binding.includedNavbar.navbar
        ani.dantotsu.bottomBar = navbar
        navbar.visibility = View.VISIBLE
        val mainViewPager = binding.viewpager
        mainViewPager.isUserInputEnabled = false
        mainViewPager.adapter = ViewPagerAdapter(supportFragmentManager, lifecycle)
        mainViewPager.setPageTransformer(ZoomOutPageTransformer())
        navbar.setOnTabSelectListener(object :
            AnimatedBottomBar.OnTabSelectListener {
            override fun onTabSelected(
                lastIndex: Int,
                lastTab: AnimatedBottomBar.Tab?,
                newIndex: Int,
                newTab: AnimatedBottomBar.Tab
            ) {
                navbar.animate().translationZ(12f).setDuration(200).start()
                selectedOption = newIndex
                mainViewPager.setCurrentItem(newIndex, false)
            }
        })
        navbar.selectTabAt(selectedOption)
        mainViewPager.setCurrentItem(selectedOption, false)
    }

    override fun onStart() {
        super.onStart()
        watchNetwork()
    }

    override fun onStop() {
        super.onStop()
        networkCallback?.let {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        }
        networkCallback = null
        goOnlineSnack?.dismiss()
        goOnlineSnack = null
    }

    /**
     * When a network with internet is up: without offline mode chosen, go straight back
     * to the online app; with it chosen, offer "Go online".
     */
    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { onNetworkBack() }
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                runOnUiThread { onNetworkBack() }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onSuccess { networkCallback = callback }
    }

    private fun onNetworkBack() {
        // Same test MainActivity uses, so going back can't bounce straight here again.
        if (isFinishing || isDestroyed || networkCallback == null || !isOnline(this)) return
        if (!PrefManager.getVal<Boolean>(PrefName.OfflineMode)) {
            goOnline()
            return
        }
        if (goOnlineSnack?.isShown == true) return
        goOnlineSnack = Snackbar.make(binding.root, R.string.offline_mode, Snackbar.LENGTH_INDEFINITE)
            .setAction(R.string.go_online) {
                PrefManager.setVal(PrefName.OfflineMode, false)
                goOnline()
            }
            .setAnchorView(binding.includedNavbar.navbarContainer)
            .also { it.show() }
    }

    private fun goOnline() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.viewpager.adapter = null
        ani.dantotsu.clearBottomBar(binding.includedNavbar.navbar)
    }

    private class ViewPagerAdapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) :
        FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> OfflineAnimeFragment()
                1 -> LocalFragment()
                else -> OfflineMangaFragment()
            }
        }
    }
}