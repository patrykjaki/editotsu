package ani.dantotsu.settings.data

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * MAX MERGE M3: user-facing storage formatting, shared by torrent cache,
 * temporary cache and storage summaries. Tiny values read as `<1 KB`;
 * larger values use one decimal without byte-level noise.
 * Framework-free, unit-proved (StorageFormatTest).
 */
fun formatStorageSize(bytes: Long): String {
    if (bytes < 1024) return "<1 KB"
    val units = arrayOf("KB", "MB", "GB", "TB")
    val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(1, units.size)
    val value = bytes / 1024.0.pow(group.toDouble())
    return String.format(Locale.US, "%.1f %s", value, units[group - 1])
}
