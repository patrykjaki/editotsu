package ani.dantotsu.media.anime.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * MAX MERGE M4 correction (HOLD3 D3): URI-aware header routing for DRM
 * playback. The shared media factory carries `PlaybackRequest.headers`
 * (same-host manifest/segments/subs), while per-subtitle entries on other
 * hosts keep their own header context. URIs without an override pass
 * through untouched.
 */
@UnstableApi
class HeaderRoutingDataSource(
    private val upstream: DataSource,
    private val headersFor: (uri: String) -> Map<String, String>,
) : DataSource by upstream {

    override fun open(dataSpec: DataSpec): Long {
        val extra = headersFor(dataSpec.uri.toString())
        return if (extra.isEmpty()) upstream.open(dataSpec)
        else upstream.open(dataSpec.withAdditionalHeaders(extra))
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val headersFor: (uri: String) -> Map<String, String>,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            HeaderRoutingDataSource(upstream.createDataSource(), headersFor)
    }
}
