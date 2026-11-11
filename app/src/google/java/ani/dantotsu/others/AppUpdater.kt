package ani.dantotsu.others

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import ani.dantotsu.BuildConfig
import ani.dantotsu.Mapper
import ani.dantotsu.R
import ani.dantotsu.buildMarkwon
import ani.dantotsu.client
import ani.dantotsu.currContext
import ani.dantotsu.decodeBase64ToString
import ani.dantotsu.logError
import ani.dantotsu.openLinkInBrowser
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.snackString
import ani.dantotsu.toast
import ani.dantotsu.tryWithSuspend
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import java.text.SimpleDateFormat
import java.util.Locale

object AppUpdater {

    private val isBetaApp get() = AppUpdatePolicy.isBetaApplication(BuildConfig.APPLICATION_ID)

    private suspend fun fetchDecision(repo: String): AppUpdatePolicy.Decision? {
        return try {
            val releases = client.get("https://api.github.com/repos/$repo/releases")
                .parsed<JsonArray>().map {
                    Mapper.json.decodeFromJsonElement<GithubResponse>(it)
                }.map {
                    AppUpdatePolicy.ReleaseInfo(
                        tag = it.tagName,
                        prerelease = it.prerelease,
                        createdAt = it.createdAt,
                        body = it.body,
                        assetUrls = it.assets?.mapNotNull { a -> a.browserDownloadURL } ?: emptyList()
                    )
                }
            // Channel safety: the beta app updates from prereleases only, stable from stable
            // releases only (never cross-install); see AppUpdatePolicy.decide.
            AppUpdatePolicy.decide(releases, BuildConfig.VERSION_NAME, BuildConfig.APPLICATION_ID)
        } catch (e: Exception) {
            Logger.log("Github fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchApkUrl(repo: String, version: String): String? {
        return try {
            fetchApkUrlFromGithub(repo, version)
        } catch (e: Exception) {
            Logger.log("Github APK fetch failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchApkUrlFromGithub(repo: String, version: String): String? {
        val tag = if (version.startsWith("v")) version else "v$version"
        val urls = client.get("https://api.github.com/repos/$repo/releases/tags/$tag")
            .parsed<GithubResponse>().assets?.mapNotNull {
                it.browserDownloadURL
            } ?: return null
        // Deterministic flavor/ABI selection (never API-list position):
        // Google arm64 on arm64 devices, else Google universal. fdroid
        // assets are never offered to Google-flavor users.
        return AppUpdatePolicy.selectGoogleApkAsset(
            urls,
            android.os.Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        )
    }

    /**
     * Read-only latest-version probe for the settings "outdated" indicator: the newer
     * version of this app's channel, or a newer stable release for the beta app.
     * Quiet null on any failure; never starts a download.
     */
    suspend fun latestVersionIfNewer(activity: FragmentActivity): String? {
        return try {
            when (val d = fetchDecision(activity.getString(R.string.repo))) {
                is AppUpdatePolicy.Decision.Update -> d.version
                is AppUpdatePolicy.Decision.StableNotice -> d.version
                else -> null
            }
        } catch (e: Exception) {
            Logger.log("Outdated check quiet-fail: ${e.message}")
            null
        }
    }

    suspend fun check(activity: FragmentActivity, post: Boolean = false) {
        if (post) snackString(currContext()?.getString(R.string.checking_for_update))
        val repo = activity.getString(R.string.repo)
        tryWithSuspend {
            when (val decision = fetchDecision(repo) ?: return@tryWithSuspend) {
                is AppUpdatePolicy.Decision.Update -> showUpdate(activity, repo, decision)
                is AppUpdatePolicy.Decision.StableNotice -> showStableNotice(activity, repo, decision, post)
                AppUpdatePolicy.Decision.None ->
                    if (post) snackString(currContext()?.getString(R.string.no_update_found))
            }
            Unit
        }
    }

    private fun showUpdate(activity: FragmentActivity, repo: String, decision: AppUpdatePolicy.Decision.Update) {
        val version = decision.version
        val md = decision.release.body ?: ""
        Logger.log("Git Version : $version")
        val dontShow = PrefManager.getCustomVal("dont_ask_for_update_$version", false)
        if (dontShow || activity.isDestroyed) return
        activity.runOnUiThread {
            CustomBottomDialog.newInstance().apply {
                setTitleText(
                    "${if (isBetaApp) "Beta " else ""}Update " + activity.getString(R.string.available)
                )
                addView(
                    TextView(activity).apply {
                        val markWon = try {
                            buildMarkwon(activity, false)
                        } catch (e: IllegalArgumentException) {
                            return@runOnUiThread
                        }
                        markWon.setMarkdown(this, md)
                    }
                )

                setCheck(activity.getString(R.string.dont_show_again, version), false) { isChecked ->
                    if (isChecked) {
                        PrefManager.setCustomVal("dont_ask_for_update_$version", true)
                    }
                }
                setPositiveButton(activity.getString(R.string.lets_go)) {
                    MainScope().launch(Dispatchers.IO) {
                        try {
                            val apkUrl = fetchApkUrl(repo, version)
                            if (apkUrl != null) {
                                activity.downloadUpdate(version, apkUrl)
                            } else {
                                openLinkInBrowser(releasePage(repo, decision.release))
                            }
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }
                    dismiss()
                }
                setNegativeButton(activity.getString(R.string.cope)) {
                    dismiss()
                }
                show(activity.supportFragmentManager, "dialog")
            }
        }
    }

    /**
     * The beta app (`ani.editotsu.beta`) cannot install a stable release (`ani.editotsu`) over
     * itself; it only points to the release page.
     */
    private fun showStableNotice(
        activity: FragmentActivity,
        repo: String,
        decision: AppUpdatePolicy.Decision.StableNotice,
        post: Boolean
    ) {
        val version = decision.version
        val key = "dont_show_stable_notice_$version"
        if ((PrefManager.getCustomVal(key, false) && !post) || activity.isDestroyed) return
        activity.runOnUiThread {
            CustomBottomDialog.newInstance().apply {
                setTitleText(activity.getString(R.string.stable_release_title, version))
                addView(TextView(activity).apply {
                    text = activity.getString(R.string.stable_release_info, version)
                })
                setCheck(activity.getString(R.string.dont_show_again, version), false) { isChecked ->
                    if (isChecked) PrefManager.setCustomVal(key, true)
                }
                setPositiveButton(activity.getString(R.string.open_release_page)) {
                    openLinkInBrowser(releasePage(repo, decision.release))
                    dismiss()
                }
                setNegativeButton(activity.getString(R.string.close)) {
                    dismiss()
                }
                show(activity.supportFragmentManager, "dialog")
            }
        }
    }

    private fun releasePage(repo: String, release: AppUpdatePolicy.ReleaseInfo) =
        "https://github.com/$repo/releases/tag/${release.tag}"

    // Download flow based on CloudStream's in-app updater (InAppUpdater.kt).
    private fun Activity.downloadUpdate(version: String, url: String) {
        toast(getString(R.string.downloading_update, version))

        // Application context: the download outlives this screen, and the install prompt must
        // still open after the user leaves it.
        val app = applicationContext
        val downloadManager = app.getSystemService<DownloadManager>()!!

        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType("application/vnd.android.package-archive")
            .setTitle("Downloading Editotsu $version")
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "Editotsu $version.apk"
            )
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setAllowedOverRoaming(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val id = try {
            downloadManager.enqueue(request)
        } catch (e: Exception) {
            logError(e)
            -1
        }
        if (id == -1L) return
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                @SuppressLint("Range")
                override fun onReceive(context: Context?, intent: Intent?) {
                    // Other DownloadManager downloads (theme songs, for one) finish through the
                    // same broadcast; only this update's id opens the installer.
                    val downloadId = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
                    if (!AppUpdatePolicy.isOwnDownload(id, downloadId)) return
                    try {
                        app.unregisterReceiver(this)
                    } catch (e: IllegalArgumentException) {
                        Logger.log("Update receiver already unregistered")
                    }
                    try {
                        downloadManager.getUriForDownloadedFile(id)?.let { openApk(app, it) }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun openApk(context: Context, uri: Uri) {
        try {
            uri.path?.let {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    // Started from the application context, so it needs its own task.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    data = uri
                }
                context.startActivity(installIntent)
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

    @Serializable
    data class GithubResponse(
        @SerialName("html_url")
        val htmlUrl: String,
        @SerialName("tag_name")
        val tagName: String,
        val prerelease: Boolean,
        @SerialName("created_at")
        val createdAt: String,
        val body: String? = null,
        val assets: List<Asset>? = null
    ) {
        @Serializable
        data class Asset(
            @SerialName("browser_download_url")
            val browserDownloadURL: String
        )

        fun timeStamp(): Long {
            return dateFormat.parse(createdAt)!!.time
        }
    }
}