package ani.dantotsu.media.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * LANE-B P1 (#72 EpisodeSubtitleStore): pure seam matrix — key/filename
 * shaping and tolerant decode. Android-coupled persistence (prefs,
 * filesDir staging, cleanup) is covered by compile + device validation;
 * ownership/cleanup semantics are asserted here through the helpers.
 */
class EpisodeSubtitleStoreTest {

    @Test
    fun `pref keys isolate media and episode`() {
        val a = subtitlePrefKey(1, "12")
        val b = subtitlePrefKey(1, "13")
        val c = subtitlePrefKey(2, "12")
        assertTrue(setOf(a, b, c).size == 3)
        assertTrue(a.startsWith("saved_ep_online_sub_"))
    }

    @Test
    fun `staged filenames isolate episode and sub id`() {
        val a = stagedSubtitleFileName(1, "12", "wyzie-1", "srt")
        val b = stagedSubtitleFileName(1, "12", "wyzie-2", "srt")
        val c = stagedSubtitleFileName(1, "13", "wyzie-1", "srt")
        assertTrue(setOf(a, b, c).size == 3)
        assertTrue(a.endsWith(".srt"))
        assertTrue(a.contains("1_12_"))
    }

    @Test
    fun `decode round trip preserves record`() {
        val record = EpisodeSubtitleStore.SavedEpisodeSubtitle(
            id = "wyzie-1",
            displayName = "English",
            provider = "Wyzie",
            language = "en",
            filePath = "/cache/saved_online_subs/sub_1_12_x.srt",
            url = null
        )
        val decoded = decodeSavedSubtitle(
            kotlinx.serialization.json.Json.encodeToString(
                EpisodeSubtitleStore.SavedEpisodeSubtitle.serializer(), record
            )
        )
        assertEquals(record, decoded)
    }

    @Test
    fun `decode tolerates unknown keys`() {
        val decoded = decodeSavedSubtitle(
            """{"id":"a","displayName":"b","provider":"c","language":"d","future":"x"}"""
        )
        assertEquals("a", decoded?.id)
    }

    @Test
    fun `decode rejects corrupt payloads`() {
        assertNull(decodeSavedSubtitle("not json"))
        assertNull(decodeSavedSubtitle("""{"id":1}"""))
        assertNull(decodeSavedSubtitle(""))
    }

    // ---- HOLD1 item 4: precedence resolver ----

    @Test
    fun `live active choice always wins`() {
        assertEquals(
            SubtitleSourceWin.ACTIVE_ONLINE,
            resolveSubtitleSource("wyzie-1", true, "None", null)
        )
        assertEquals(
            SubtitleSourceWin.ACTIVE_ONLINE,
            resolveSubtitleSource("x", false, "English", 3)
        )
    }

    @Test
    fun `saved record wins without live choice`() {
        assertEquals(
            SubtitleSourceWin.SAVED_RECORD,
            resolveSubtitleSource(null, true, "None", null)
        )
        assertEquals(
            SubtitleSourceWin.SAVED_RECORD,
            resolveSubtitleSource(null, true, "English", 2)
        )
    }

    @Test
    fun `explicit off needs no online and no legacy selection`() {
        assertEquals(SubtitleSourceWin.OFF, resolveSubtitleSource(null, false, "None", 2))
        assertEquals(SubtitleSourceWin.OFF, resolveSubtitleSource(null, false, "None", null))
        assertEquals(SubtitleSourceWin.OFF, resolveSubtitleSource(null, false, null, null))
    }

    @Test
    fun `legacy server selection is not off`() {
        // Previous selectedSubtitle semantics: a chosen server index keeps
        // Off un-highlighted even with no saved language.
        assertEquals(SubtitleSourceWin.LEGACY, resolveSubtitleSource(null, false, null, 3))
        assertEquals(SubtitleSourceWin.LEGACY, resolveSubtitleSource(null, false, "English", null))
    }

    // ---- HOLD1 item 3: stored-file ownership ----

    private fun ownedDir(): java.io.File {
        val base = java.io.File(System.getProperty("java.io.tmpdir"), "owned-subs-test")
        val dir = java.io.File(base, SAVED_SUBS_DIR)
        dir.mkdirs()
        return dir
    }

    @Test
    fun `canonical staged copy is owned`() {
        val dir = ownedDir()
        assertTrue(isOwnedSubtitleFile(dir, File(dir, "sub_1_12_12345.srt").absolutePath))
        assertTrue(isOwnedSubtitleFile(dir, File(dir, "sub_1_12_-99.VTT").absolutePath))
    }

    @Test
    fun `foreign absolute paths are never owned`() {
        val dir = ownedDir()
        assertTrue(!isOwnedSubtitleFile(dir, "/sdcard/evil.srt"))
        assertTrue(!isOwnedSubtitleFile(dir, "/sdcard/fake_saved_online_subs/sub_1_12_1.srt"))
        assertTrue(!isOwnedSubtitleFile(dir, null))
        assertTrue(!isOwnedSubtitleFile(dir, ""))
    }

    @Test
    fun `traversal and shape violations are never owned`() {
        val dir = ownedDir()
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "../sub_1_12_1.srt").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "notes.txt").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "sub_1_12_1.mp4").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "sub_1_12_1").absolutePath))
        assertTrue(!isOwnedSubtitleFile(dir, File(dir, "other_1_12_1.srt").absolutePath))
    }

    @Test
    fun `replacement retires only differing previous path`() {
        assertEquals("/a/old.srt", staleOwnedFile("/a/old.srt", "/a/new.srt"))
        assertNull(staleOwnedFile("/a/same.srt", "/a/same.srt"))
        assertNull(staleOwnedFile(null, "/a/new.srt"))
        // Null replacement still supersedes: previous retires.
        assertEquals("/a/old.srt", staleOwnedFile("/a/old.srt", null))
    }

    // ---- HOLD1 item 2: async persistence ownership ----

    @Test
    fun `current request on current episode persists and attaches`() {
        assertEquals(
            PersistDecision.PERSIST_AND_ATTACH,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", 1, "12")
        )
    }

    @Test
    fun `superseded generation drops entirely`() {
        // Newer request started (Off/select/another fetch): late A drops,
        // never resurrecting under any episode.
        assertEquals(
            PersistDecision.DROP,
            shouldPersistCompletedRequest(7L, 9L, 1, "12", 1, "12")
        )
        assertEquals(
            PersistDecision.DROP,
            shouldPersistCompletedRequest(7L, 9L, 1, "12", 1, "13")
        )
    }

    @Test
    fun `episode move persists under captured identity without attaching`() {
        // A finishes after switch to B: record belongs to A, player shows B.
        assertEquals(
            PersistDecision.PERSIST_ONLY,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", 1, "13")
        )
        assertEquals(
            PersistDecision.PERSIST_ONLY,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", 2, "12")
        )
    }

    @Test
    fun `dead player persists without attaching`() {
        assertEquals(
            PersistDecision.PERSIST_ONLY,
            shouldPersistCompletedRequest(7L, 7L, 1, "12", null, null)
        )
    }

    // ---- HOLD3: serialized commit ordering ----

    @Test
    fun `stale guard refuses commit before any Android I-O`() {
        // The freshness re-check precedes every prefs/filesystem touch:
        // with a stale generation this returns false using only a plain
        // temp dir (no Context/SharedPreferences involved). Proves the
        // check-then-commit order for scenarios 1, 2 and 5-refusal: an
        // Off/newer selection landing mid-copy cannot resurrect a record.
        val dir = Files.createTempDirectory("store-guard").toFile()
        try {
            var evaluations = 0
            val committed = EpisodeSubtitleStore.saveSubtitleIfCurrent(
                filesDir = dir,
                mediaId = 1,
                episodeNumber = "12",
                sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                    id = "wyzie-1", displayName = "English",
                    provider = "Wyzie", language = "en"
                ),
                sourceFile = null,
                isCurrent = {
                    evaluations += 1
                    false
                }
            )
            assertEquals(false, committed)
            assertEquals(1, evaluations)
            assertTrue(dir.resolve(SAVED_SUBS_DIR).exists().not())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `commit lock serializes concurrent commits and clears`() {
        // The exact lock production commits and clears share: concurrent
        // entrants never overlap (max observed concurrency == 1).
        var inside = 0
        var maxInside = 0
        val gate = Object()
        val threads = (1..16).map {
            Thread {
                withSubtitleStoreCommit {
                    synchronized(gate) {
                        inside += 1
                        if (inside > maxInside) maxInside = inside
                    }
                    Thread.sleep(2)
                    synchronized(gate) {
                        inside -= 1
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertEquals(1, maxInside)
    }

    // ---- HOLD2 blocker 2: ordered interleavings (fake gate + episodes) ----

    private class FakeGate(var current: Long = 0L) {
        fun newRequest(): Long {
            current += 1
            return current
        }

        fun supersede() {
            current += 1
        }
    }

    @Test
    fun `A download finishes then Off before Main dispatch`() {
        val gate = FakeGate()
        val req = gate.newRequest()
        // IO completion: current, same episode → persist + proceed.
        assertEquals(
            PersistDecision.PERSIST_AND_ATTACH,
            shouldPersistCompletedRequest(req, gate.current, 1, "12", 1, "12")
        )
        // Off lands before Main dispatch (supersede + record cleared).
        gate.supersede()
        // Main attach re-check: stale → denied. Final: newer Off wins.
        assertEquals(
            false,
            shouldAttachCompletedFetch(req, gate.current, 1, "12", 1, "12")
        )
    }

    @Test
    fun `B download finishes then embedded selection before attach`() {
        val gate = FakeGate()
        val req = gate.newRequest()
        assertEquals(
            PersistDecision.PERSIST_AND_ATTACH,
            shouldPersistCompletedRequest(req, gate.current, 1, "12", 1, "12")
        )
        gate.supersede() // embedded click supersedes like Off
        assertEquals(
            false,
            shouldAttachCompletedFetch(req, gate.current, 1, "12", 1, "12")
        )
    }

    @Test
    fun `C download A then episode B then return to A`() {
        val gate = FakeGate()
        val req = gate.newRequest()
        // Completion runs while B is showing: persist under A only.
        assertEquals(
            PersistDecision.PERSIST_ONLY,
            shouldPersistCompletedRequest(req, gate.current, 1, "12", 1, "13")
        )
        // Back on A with no newer intent: attach allowed.
        assertEquals(
            true,
            shouldAttachCompletedFetch(req, gate.current, 1, "12", 1, "12")
        )
        // Still on B: attach denied.
        assertEquals(
            false,
            shouldAttachCompletedFetch(req, gate.current, 1, "12", 1, "13")
        )
    }

    @Test
    fun `D two online requests finish out of order`() {
        val gate = FakeGate()
        val first = gate.newRequest()
        val second = gate.newRequest()
        // Late first completion: dropped entirely, never persists.
        assertEquals(
            PersistDecision.DROP,
            shouldPersistCompletedRequest(first, gate.current, 1, "12", 1, "12")
        )
        assertEquals(
            false,
            shouldAttachCompletedFetch(first, gate.current, 1, "12", 1, "12")
        )
        // Second (newest) proceeds.
        assertEquals(
            PersistDecision.PERSIST_AND_ATTACH,
            shouldPersistCompletedRequest(second, gate.current, 1, "12", 1, "12")
        )
        assertEquals(
            true,
            shouldAttachCompletedFetch(second, gate.current, 1, "12", 1, "12")
        )
    }

    @Test
    fun `E Off while persistence is in progress skips the commit`() {
        val gate = FakeGate()
        val req = gate.newRequest()
        // Fetch done; Off lands before the persist commit re-check.
        gate.supersede()
        // The commit boundary re-check (same predicate as the IO gate)
        // now refuses: nothing is written, cleared record stays cleared.
        assertEquals(
            PersistDecision.DROP,
            shouldPersistCompletedRequest(req, gate.current, 1, "12", 1, "12")
        )
        // Attach likewise denied.
        assertEquals(
            false,
            shouldAttachCompletedFetch(req, gate.current, 1, "12", 1, "12")
        )
    }

    // ---- HOLD4: persistence failure handling ----

    private fun newTempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    private fun writeTextFile(parent: File, name: String, content: String): File {
        parent.mkdirs()
        return File(parent, name).apply { writeText(content) }
    }

    private fun encodeRecord(record: EpisodeSubtitleStore.SavedEpisodeSubtitle): String =
        kotlinx.serialization.json.Json.encodeToString(
            EpisodeSubtitleStore.SavedEpisodeSubtitle.serializer(), record
        )

    private fun dirNames(dir: File): Set<String> =
        dir.listFiles()?.map { it.name }?.toSet() ?: emptySet()

    @Test
    fun `unwritable destination reports failure without pref write`() {
        // filesDir is a plain file, not a directory: staging cannot succeed.
        // Failure lands before any preference write.
        val filesDir = File.createTempFile("store-unwritable", ".bin")
        try {
            val srcDir = newTempDir("store-src")
            try {
                val source = writeTextFile(srcDir, "cap.srt", "1\n00:00:01 --> 00:00:02\nHi\n")
                val sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                    id = "wyzie-1", displayName = "English",
                    provider = "Wyzie", language = "en"
                )
                var prefWrites = 0
                assertEquals(
                    false,
                    EpisodeSubtitleStore.saveSubtitleIfCurrentWithPrefs(
                        filesDir = filesDir,
                        mediaId = 1,
                        episodeNumber = "12",
                        sub = sub,
                        sourceFile = source,
                        isCurrent = { true },
                        readPrevious = { null },
                        writeJson = { prefWrites += 1 }
                    )
                )
                assertEquals(0, prefWrites)
                // Production entry propagates the same failure (it fails
                // before prefs, so this stays JVM-safe without Android).
                assertEquals(
                    false,
                    EpisodeSubtitleStore.saveSubtitleIfCurrent(
                        filesDir = filesDir,
                        mediaId = 1,
                        episodeNumber = "12",
                        sub = sub,
                        sourceFile = source,
                        isCurrent = { true }
                    )
                )
            } finally {
                srcDir.deleteRecursively()
            }
        } finally {
            filesDir.delete()
        }
    }

    @Test
    fun `missing source reports failure and preserves previous`() {
        val filesDir = newTempDir("store-missing")
        try {
            val key = subtitlePrefKey(1, "12")
            val prevFile = writeTextFile(
                File(filesDir, SAVED_SUBS_DIR),
                stagedSubtitleFileName(1, "12", "old-1", "srt"), "OLD\n"
            )
            val prev = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                id = "old-1", displayName = "Old",
                provider = "Wyzie", language = "en", filePath = prevFile.absolutePath
            )
            val prefs = mutableMapOf(key to encodeRecord(prev))
            val missing = File(filesDir, "gone.srt") // never created
            val sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                id = "wyzie-9", displayName = "English",
                provider = "Wyzie", language = "en"
            )
            var writes = 0
            assertEquals(
                false,
                EpisodeSubtitleStore.saveSubtitleIfCurrentWithPrefs(
                    filesDir = filesDir,
                    mediaId = 1,
                    episodeNumber = "12",
                    sub = sub,
                    sourceFile = missing,
                    isCurrent = { true },
                    readPrevious = { prefs[key]?.let { decodeSavedSubtitle(it) } },
                    writeJson = { writes += 1; prefs[key] = it }
                )
            )
            assertEquals(0, writes)
            assertEquals(prevFile.absolutePath, decodeSavedSubtitle(prefs[key]!!)?.filePath)
            assertEquals("OLD\n", prevFile.readText())
            assertEquals(setOf(prevFile.name), dirNames(File(filesDir, SAVED_SUBS_DIR)))
            // Production entry likewise refuses before prefs.
            assertEquals(
                false,
                EpisodeSubtitleStore.saveSubtitleIfCurrent(
                    filesDir = filesDir,
                    mediaId = 1,
                    episodeNumber = "12",
                    sub = sub,
                    sourceFile = missing,
                    isCurrent = { true }
                )
            )
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `pref-write failure preserves previous record and file and removes staged orphan`() {
        val filesDir = newTempDir("store-preffail")
        try {
            val key = subtitlePrefKey(1, "12")
            val prevFile = writeTextFile(
                File(filesDir, SAVED_SUBS_DIR),
                stagedSubtitleFileName(1, "12", "old-1", "srt"), "OLD\n"
            )
            val prev = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                id = "old-1", displayName = "Old",
                provider = "Wyzie", language = "en", filePath = prevFile.absolutePath
            )
            val prefs = mutableMapOf(key to encodeRecord(prev))
            val srcDir = newTempDir("store-preffail-src")
            try {
                val source = writeTextFile(srcDir, "new.srt", "NEW\n")
                assertEquals(
                    false,
                    EpisodeSubtitleStore.saveSubtitleIfCurrentWithPrefs(
                        filesDir = filesDir,
                        mediaId = 1,
                        episodeNumber = "12",
                        sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                            id = "wyzie-2", displayName = "English",
                            provider = "Wyzie", language = "en"
                        ),
                        sourceFile = source,
                        isCurrent = { true },
                        readPrevious = { prefs[key]?.let { decodeSavedSubtitle(it) } },
                        writeJson = { throw java.io.IOException("injected pref failure") }
                    )
                )
                // Previous record + bytes survive; the staged orphan is gone.
                assertEquals(prevFile.absolutePath, decodeSavedSubtitle(prefs[key]!!)?.filePath)
                assertEquals("OLD\n", prevFile.readText())
                assertEquals(setOf(prevFile.name), dirNames(File(filesDir, SAVED_SUBS_DIR)))
            } finally {
                srcDir.deleteRecursively()
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `successful replacement commits new record and removes previous file`() {
        val filesDir = newTempDir("store-success")
        try {
            val key = subtitlePrefKey(1, "12")
            val prevFile = writeTextFile(
                File(filesDir, SAVED_SUBS_DIR),
                stagedSubtitleFileName(1, "12", "old-1", "srt"), "OLD\n"
            )
            val prev = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                id = "old-1", displayName = "Old",
                provider = "Wyzie", language = "en", filePath = prevFile.absolutePath
            )
            val prefs = mutableMapOf(key to encodeRecord(prev))
            val srcDir = newTempDir("store-success-src")
            try {
                val source = writeTextFile(srcDir, "new.srt", "NEW\n")
                assertEquals(
                    true,
                    EpisodeSubtitleStore.saveSubtitleIfCurrentWithPrefs(
                        filesDir = filesDir,
                        mediaId = 1,
                        episodeNumber = "12",
                        sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                            id = "wyzie-2", displayName = "English",
                            provider = "Wyzie", language = "en"
                        ),
                        sourceFile = source,
                        isCurrent = { true },
                        readPrevious = { prefs[key]?.let { decodeSavedSubtitle(it) } },
                        writeJson = { prefs[key] = it }
                    )
                )
                val committed = decodeSavedSubtitle(prefs[key]!!)!!
                assertTrue(committed.filePath != prevFile.absolutePath)
                assertEquals("NEW\n", File(committed.filePath!!).readText())
                assertTrue(!prevFile.exists())
                val names = dirNames(File(filesDir, SAVED_SUBS_DIR))
                assertEquals(1, names.size)
                assertTrue(names.none { it.endsWith(".tmp") || it.endsWith(".bak") })
            } finally {
                srcDir.deleteRecursively()
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `same-path pref failure restores previous bytes then success rewrites`() {
        val filesDir = newTempDir("store-samepath")
        try {
            val key = subtitlePrefKey(1, "12")
            val destName = stagedSubtitleFileName(1, "12", "wyzie-1", "srt")
            val destFile = writeTextFile(File(filesDir, SAVED_SUBS_DIR), destName, "OLD\n")
            val prev = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                id = "wyzie-1", displayName = "English",
                provider = "Wyzie", language = "en", filePath = destFile.absolutePath
            )
            val prefs = mutableMapOf(key to encodeRecord(prev))
            val sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                id = "wyzie-1", displayName = "English",
                provider = "Wyzie", language = "en"
            )
            val srcDir = newTempDir("store-samepath-src")
            try {
                val source = writeTextFile(srcDir, "new.srt", "NEW\n")
                // Phase 1: pref fails — previous bytes + record survive.
                assertEquals(
                    false,
                    EpisodeSubtitleStore.saveSubtitleIfCurrentWithPrefs(
                        filesDir = filesDir,
                        mediaId = 1,
                        episodeNumber = "12",
                        sub = sub,
                        sourceFile = source,
                        isCurrent = { true },
                        readPrevious = { prefs[key]?.let { decodeSavedSubtitle(it) } },
                        writeJson = { throw java.io.IOException("injected pref failure") }
                    )
                )
                assertEquals(destFile.absolutePath, decodeSavedSubtitle(prefs[key]!!)?.filePath)
                assertEquals("OLD\n", destFile.readText())
                assertTrue(dirNames(File(filesDir, SAVED_SUBS_DIR)).none {
                    it.endsWith(".tmp") || it.endsWith(".bak")
                })
                // Phase 2: pref succeeds — bytes rewrite, same path kept.
                assertEquals(
                    true,
                    EpisodeSubtitleStore.saveSubtitleIfCurrentWithPrefs(
                        filesDir = filesDir,
                        mediaId = 1,
                        episodeNumber = "12",
                        sub = sub,
                        sourceFile = source,
                        isCurrent = { true },
                        readPrevious = { prefs[key]?.let { decodeSavedSubtitle(it) } },
                        writeJson = { prefs[key] = it }
                    )
                )
                assertEquals(destFile.absolutePath, decodeSavedSubtitle(prefs[key]!!)?.filePath)
                assertEquals("NEW\n", destFile.readText())
                assertTrue(dirNames(File(filesDir, SAVED_SUBS_DIR)).none {
                    it.endsWith(".tmp") || it.endsWith(".bak")
                })
            } finally {
                srcDir.deleteRecursively()
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun `stale generation refuses before any filesystem or pref I-O even with source present`() {
        val filesDir = newTempDir("store-stale-src")
        try {
            val srcDir = newTempDir("store-stale-srcfiles")
            try {
                val source = writeTextFile(srcDir, "cap.srt", "NEW\n")
                var reads = 0
                var writes = 0
                assertEquals(
                    false,
                    EpisodeSubtitleStore.saveSubtitleIfCurrentWithPrefs(
                        filesDir = filesDir,
                        mediaId = 1,
                        episodeNumber = "12",
                        sub = EpisodeSubtitleStore.SavedEpisodeSubtitle(
                            id = "wyzie-1", displayName = "English",
                            provider = "Wyzie", language = "en"
                        ),
                        sourceFile = source,
                        isCurrent = { false },
                        readPrevious = { reads += 1; null },
                        writeJson = { writes += 1 }
                    )
                )
                assertEquals(0, reads)
                assertEquals(0, writes)
                assertTrue(!File(filesDir, SAVED_SUBS_DIR).exists())
            } finally {
                srcDir.deleteRecursively()
            }
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
