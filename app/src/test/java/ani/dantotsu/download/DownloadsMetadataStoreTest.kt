package ani.dantotsu.download

import ani.dantotsu.media.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * Handoff-B regressions: [DownloadsMetadataStore] serializes in-memory
 * mutation + snapshot serialization + persistence into one atomic critical
 * section.
 *
 * The old shape (plain list + ad-hoc `saveDownloads()`) let two concurrent
 * completions interleave add/serialize/write so a stale JSON snapshot
 * overwrote a newer one, losing valid COMPLETE entries after restart — and let
 * a completion racing a title/purge cleanup lose either side.
 *
 * Concurrency is forced with start-gate barriers (never sleeps); every
 * assertion holds under all interleaves because the lock serializes the
 * critical sections.
 */
class DownloadsMetadataStoreTest {

    private fun entry(
        title: String,
        chapter: String = "1",
        type: MediaType = MediaType.MANGA,
    ) = DownloadedType(title, chapter, type)

    /** Runs [block] on N threads released simultaneously; joins all. */
    private fun race(threads: Int, block: (Int) -> Unit) {
        val gate = CountDownLatch(1)
        val workers = List(threads) { index ->
            Thread {
                gate.await()
                block(index)
            }.also { it.start() }
        }
        gate.countDown()
        workers.forEach { it.join(10_000) }
        assertFalse("worker hung", workers.any { it.isAlive })
    }

    // B1: two different Manga COMPLETE commits race -> both persisted.
    @Test
    fun b1_concurrentCommits_bothPersisted() {
        val persisted = Collections.synchronizedList(mutableListOf<List<DownloadedType>>())
        val store = DownloadsMetadataStore(emptyList()) { persisted.add(it) }
        val subjects = listOf(entry("A"), entry("B"))
        race(2) { index ->
            store.transact { it.add(subjects[index]) }
        }
        val final = store.snapshot()
        assertTrue(final.containsAll(listOf(entry("A"), entry("B"))))
        assertEquals("persisted snapshot must equal store state", final, persisted.last())
    }

    // B2: title X delete races unrelated title Y COMPLETE -> X absent, Y present.
    @Test
    fun b2_deleteRacesUnrelatedComplete_bothSurvive() {
        val persisted = Collections.synchronizedList(mutableListOf<List<DownloadedType>>())
        val store = DownloadsMetadataStore(listOf(entry("X"))) { persisted.add(it) }
        race(2) { index ->
            if (index == 0) {
                store.transact { it.removeAll { e -> e.titleName == "X" } }
            } else {
                store.transact { it.add(entry("Y", chapter = "2")) }
            }
        }
        // Either order yields the same state: X removed whenever its removal
        // runs, Y added whenever its add runs; neither undoes the other.
        val final = store.snapshot()
        assertFalse(final.any { it.titleName == "X" })
        assertTrue(final.any { it.titleName == "Y" })
        assertEquals(final, persisted.last())
    }

    // B3: purge metadata cleanup cannot be overwritten by a stale pre-purge save.
    // Every persist carries the post-mutation state, so no stale snapshot exists.
    @Test
    fun b3_purgeRacesUnrelatedAdd_noStaleOverwrite() {
        val persisted = Collections.synchronizedList(mutableListOf<List<DownloadedType>>())
        val store = DownloadsMetadataStore(
            listOf(entry("X"), entry("Y"))
        ) { persisted.add(it) }
        race(2) { index ->
            if (index == 0) {
                store.transact { it.removeAll { e -> e.type == MediaType.MANGA } }
            } else {
                // Unrelated non-manga completion racing the purge.
                store.transact { it.add(entry("Z", type = MediaType.ANIME)) }
            }
        }
        val final = store.snapshot()
        assertFalse(final.any { it.type == MediaType.MANGA })
        assertTrue(final.any { it.titleName == "Z" })
        // Every persisted snapshot was written under the lock with current state;
        // the last one always equals the store — stale overwrite impossible.
        assertEquals(final, persisted.last())
    }

    // B4: a failed operation performs zero metadata mutation: a throwing block
    // rolls membership back and never persists.
    @Test
    fun b4_throwingBlock_zeroMutation() {
        val persisted = Collections.synchronizedList(mutableListOf<List<DownloadedType>>())
        val store = DownloadsMetadataStore(listOf(entry("X"))) { persisted.add(it) }
        try {
            store.transact {
                it.add(entry("Y"))
                throw IllegalStateException("simulated failure")
            }
            fail("exception must propagate")
        } catch (_: IllegalStateException) {
            // Expected.
        }
        assertEquals(listOf(entry("X")), store.snapshot())
        assertTrue("failed operation must not persist", persisted.isEmpty())
    }

    // B5: concurrent snapshots iterate safely and always observe atomic states.
    @Test
    fun b5_concurrentSnapshot_safeAndConsistent() {
        val persisted = Collections.synchronizedList(mutableListOf<List<DownloadedType>>())
        val store = DownloadsMetadataStore(listOf(entry("seed"))) { persisted.add(it) }
        val gate = CountDownLatch(1)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val writer = Thread {
            gate.await()
            repeat(200) { i ->
                store.transact {
                    it.add(entry("W$i"))
                    if (it.size > 50) it.removeFirst()
                }
            }
        }
        val readers = List(2) {
            Thread {
                gate.await()
                repeat(200) {
                    try {
                        // Iteration must never hit a mutation hazard; each
                        // snapshot is one atomic store state.
                        val titles = store.snapshot().map { e -> e.titleName }
                        assertTrue(titles.none { t -> t.isEmpty() })
                    } catch (e: Throwable) {
                        errors.add(e)
                    }
                }
            }
        }
        (listOf(writer) + readers).forEach { it.start() }
        gate.countDown()
        (listOf(writer) + readers).forEach { it.join(30_000) }
        val all = listOf(writer) + readers
        assertFalse("worker hung", all.any { it.isAlive })
        assertTrue("reader errors: $errors", errors.isEmpty())
        assertEquals(store.snapshot(), persisted.last())
    }
}
