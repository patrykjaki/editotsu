package ani.dantotsu.media.anime.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP3-B02-1..6: beta02 stale server-subtitle intent ordering.
 *
 * Pure generation-gate semantics: an older async server-subtitle download must
 * never register itself as newer than a superseding Off/Track/newer-server
 * intent. The production [ServerSubIntentGate] backs
 * PlayerSubtitleManager's download completions; these tests pin the contract.
 */
class ServerSubIntentGateTest {

    // CP3-B02-1: old explicit server A in flight -> newer Off -> A completion dropped.
    @Test
    fun `old request superseded by Off is stale`() {
        val gate = ServerSubIntentGate()
        val genA = gate.newRequest()
        gate.supersede() // user picks Off
        assertFalse(gate.isCurrent(genA))
    }

    // CP3-B02-2: old explicit A -> newer Track(5) -> A completion dropped.
    @Test
    fun `old request superseded by explicit track is stale`() {
        val gate = ServerSubIntentGate()
        val genA = gate.newRequest()
        gate.supersede() // user picks Track(5)
        assertFalse(gate.isCurrent(genA))
    }

    // CP3-B02-3: old non-explicit A -> newer Track(5) -> A completion dropped.
    @Test
    fun `old non-explicit request superseded by track is stale`() {
        val gate = ServerSubIntentGate()
        val genA = gate.newRequest()
        gate.supersede()
        assertFalse(gate.isCurrent(genA))
        // A second supersede (e.g. another tap) keeps A stale.
        gate.supersede()
        assertFalse(gate.isCurrent(genA))
    }

    // CP3-B02-4: A then newer server B, adversarial completion orders -> B wins.
    @Test
    fun `newer server request wins regardless of completion order`() {
        val gate = ServerSubIntentGate()
        val genA = gate.newRequest()
        val genB = gate.newRequest()
        // A finishes late: stale.
        assertFalse(gate.isCurrent(genA))
        // B finishes (first or last): current.
        assertTrue(gate.isCurrent(genB))
    }

    // CP3-B02-5: unsuperseded explicit server selection stays current.
    @Test
    fun `unsuperseded request stays current`() {
        val gate = ServerSubIntentGate()
        val genA = gate.newRequest()
        assertTrue(gate.isCurrent(genA))
    }

    // CP3-B02-6: non-explicit fallback with current generation proceeds.
    @Test
    fun `fresh gate request is current until superseded`() {
        val gate = ServerSubIntentGate()
        assertTrue(gate.isCurrent(gate.newRequest()))
        gate.supersede() // Off becomes current intent
        assertFalse(gate.isCurrent(gate.current() - 1))
        assertTrue(gate.isCurrent(gate.current()))
    }
}
