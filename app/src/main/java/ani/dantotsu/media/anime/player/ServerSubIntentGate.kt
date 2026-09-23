package ani.dantotsu.media.anime.player

import java.util.concurrent.atomic.AtomicLong

/**
 * Beta03 CP3 safety seam: subtitle-intent generation gate.
 *
 * The beta02 server-subtitle path downloads asynchronously and only calls
 * `engine.addExternalSubtitle` on completion. Without ownership tracking, an
 * OLD request finishing after a NEWER explicit choice (Off / Track(id) /
 * newer server selection) would register itself as the newest intent,
 * violating frozen CP3 ("latest explicit subtitle action wins").
 *
 * Every subtitle-intent source calls [newRequest] (its own download) or
 * [supersede] (any newer intent: Off, embedded/online/local selection, newer
 * server choice). A completion applies only while [isCurrent] holds for the
 * generation captured at request time. Pure and deterministically testable;
 * see CP3-B02-1..6. Thread-confinement: call from a single thread/dispatcher
 * (PlayerSubtitleManager uses Main for intent calls).
 */
class ServerSubIntentGate {

    // Backed by AtomicLong: intent calls arrive from IO download completions
    // as well as Main UI taps.
    private val generation = AtomicLong(0L)

    /** Start a new async subtitle fetch; returns its generation token. */
    fun newRequest(): Long {
        return generation.incrementAndGet()
    }

    /** Record a newer superseding intent; returns its generation token. */
    fun supersede(): Long {
        return generation.incrementAndGet()
    }

    /** True only if no newer intent has been recorded since [gen]. */
    fun isCurrent(gen: Long): Boolean = gen == generation.get()

    fun current(): Long = generation.get()
}
