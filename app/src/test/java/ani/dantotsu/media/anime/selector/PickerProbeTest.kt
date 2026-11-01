package ani.dantotsu.media.anime.selector

import ani.dantotsu.parsers.VideoType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** Temporary probe: prints what the picker builds for real Torrentio rows. */
class PickerProbeTest {
    @Test
    fun probe() {
        val text = javaClass.classLoader!!.getResource("picker/torrentio_rows_2026-11-01.json")!!.readText()
        val rows = Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }
        val sb = StringBuilder()
        for (q in rows) {
            val vm = SourceRowSummarizer.build(q, q, VideoType.CONTAINER, "magnet:?xt=urn:btih:00")
            sb.appendLine("IN : " + q.lineSequence().take(2).joinToString(" || ").take(170))
            sb.appendLine("HDR: " + vm.header + "   SUB: " + vm.cleanSubtitle)
            sb.appendLine("PIL: " + vm.pills.joinToString(" ") { "[" + it.text + "]" } + "   AUD: " + AudioKindDetector.detect(q))
            sb.appendLine("REL: " + vm.details.firstOrNull { it.label?.contains("Release", true) == true }?.value?.take(120))
            sb.appendLine()
        }
        java.io.File("/tmp/picker_probe.txt").writeText(sb.toString())
    }
}
