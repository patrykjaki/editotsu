package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTrackChoiceTest {
    private fun audio(id: Int, lang: String?, name: String?, selected: Boolean = false) =
        PlayerTrack(id = id, type = TrackType.AUDIO, name = name, language = lang, selected = selected)

    private val dual = listOf(audio(1, "jpn", "Japanese", selected = true), audio(2, "eng", "English Dub"))

    @Test
    fun `saved english dub is restored on the next episode`() {
        val saved = AudioTrackChoice.encode(audio(5, "eng", "English Dub"))
        assertEquals(2, AudioTrackChoice.restore(dual, saved)?.id)
    }

    @Test
    fun `language matches across code forms`() {
        val tracks = listOf(audio(1, "ja", "Main", selected = true), audio(2, "en", "Dub"))
        assertEquals(2, AudioTrackChoice.restore(tracks, "eng|English Dub")?.id)
    }

    @Test
    fun `title and language beat language alone`() {
        val tracks = listOf(audio(1, "eng", "Commentary", selected = true), audio(2, "eng", "English Dub"), audio(3, "jpn", null))
        assertEquals(2, AudioTrackChoice.restore(tracks, "eng|English Dub")?.id)
    }

    @Test
    fun `nothing to do when already selected, unknown or single track`() {
        assertNull(AudioTrackChoice.restore(dual, "jpn|Japanese"))
        assertNull(AudioTrackChoice.restore(dual, "spa|Latino"))
        assertNull(AudioTrackChoice.restore(listOf(audio(1, "eng", "Dub")), "jpn|Japanese"))
        assertNull(AudioTrackChoice.restore(dual, null))
    }
}
