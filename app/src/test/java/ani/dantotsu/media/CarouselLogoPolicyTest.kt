package ani.dantotsu.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 FINAL CLOSE (V4 Blocker 1): no MAL identity may enter an AniList-ID
 * mapper. AniList mode allows it; MAL-constructed and Rescue media forbid
 * it. A known idTMDB is identity-free — the production TMDB path consults
 * it before (and independently of) the mapper gate, in either mode.
 */
class CarouselLogoPolicyTest {

    @Test
    fun `anilist mode allows the anilist-id mapper`() {
        assertTrue(mayUseAnilistIdMapper(rescueMode = false, id = 100, idMAL = 200))
        assertTrue(mayUseAnilistIdMapper(rescueMode = false, id = 100, idMAL = null))
    }

    @Test
    fun `mal-constructed media forbids the anilist-id mapper`() {
        // MAL builds carry the MAL identity in id (idMAL == id).
        assertTrue(isMalIdentityMode(rescueMode = false, id = 300, idMAL = 300))
        assertFalse(mayUseAnilistIdMapper(rescueMode = false, id = 300, idMAL = 300))
    }

    @Test
    fun `rescue mode forbids the anilist-id mapper for any ids`() {
        assertTrue(isMalIdentityMode(rescueMode = true, id = 100, idMAL = 200))
        assertFalse(mayUseAnilistIdMapper(rescueMode = true, id = 100, idMAL = 200))
        assertFalse(mayUseAnilistIdMapper(rescueMode = true, id = 100, idMAL = null))
    }

    @Test
    fun `anilist mode is not mal mode`() {
        assertFalse(isMalIdentityMode(rescueMode = false, id = 100, idMAL = 200))
    }
}
