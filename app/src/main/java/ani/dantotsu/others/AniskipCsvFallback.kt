package ani.dantotsu.others

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ani.dantotsu.client
import ani.dantotsu.settings.saving.PrefManager
import ani.dantotsu.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local SQLite fallback for AniSkip skip times, automatically synced in the background
 * every 7 days from the public dump at:
 * https://raw.githubusercontent.com/aniskip/sanitize_db_dump/refs/heads/main/skip_times_public.csv
 */
class AniskipCsvFallback(context: Context) {

    companion object {
        private const val TAG = "AniskipCsvFallback"
        private const val CSV_URL =
            "https://raw.githubusercontent.com/aniskip/sanitize_db_dump/refs/heads/main/skip_times_public.csv"

        private const val DB_NAME = "aniskip_fallback.db"
        private const val DB_VERSION = 2

        private const val TABLE_LIVE = "skip_times"
        private const val TABLE_STAGING = "skip_times_staging"

        private const val COL_ANIME_ID = "anime_id"
        private const val COL_EPISODE = "episode_number"
        private const val COL_PROVIDER = "provider_name"
        private const val COL_TYPE = "skip_type"
        private const val COL_VOTES = "votes"
        private const val COL_START = "start_time"
        private const val COL_END = "end_time"
        private const val COL_EP_LENGTH = "episode_length"

        private const val BATCH_SIZE = 2000
        private const val PREF_LAST_UPDATED = "aniskip_csv_last_updated"
        private const val SYNC_INTERVAL_MS = 7 * 24 * 60 * 60 * 1000L // 7 days

        private val VALID_TYPES = setOf("op", "ed", "recap", "mixed-op", "mixed-ed")

        @Volatile
        private var instance: AniskipCsvFallback? = null
        private val isSyncing = AtomicBoolean(false)

        fun getInstance(context: Context): AniskipCsvFallback =
            instance ?: synchronized(this) {
                instance ?: AniskipCsvFallback(context.applicationContext).also { instance = it }
            }
        /**
         * Parses one public-dump CSV line, or null when the line is not a
         * usable candidate (bad shape, unknown type, malformed numbers).
         * Interval-shape validation happens at selection time.
         */
        fun parseCsvRow(line: String): CsvRow? {
            val cols = line.split(",")
            if (cols.size < 8) return null
            val animeId = cols[0].toLongOrNull() ?: return null
            val episode = cols[1].toIntOrNull() ?: return null
            val provider = cols[2].trim().ifEmpty { return null }
            val skipType = cols[3].trim()
            if (skipType !in VALID_TYPES) return null
            val votes = cols[4].toDoubleOrNull() ?: 0.0
            val start = cols[5].toDoubleOrNull() ?: return null
            val end = cols[6].toDoubleOrNull() ?: return null
            val episodeLength = cols[7].toDoubleOrNull() ?: 0.0
            return CsvRow(animeId, episode, provider, skipType, votes, start, end, episodeLength)
        }

        /**
         * Deterministic per-type selection over raw provider rows:
         * 1. discard invalid ranges (reversed/negative/non-finite);
         * 2. when any non-negative-vote row exists, negative rows lose;
         * 3. prefer the candidate whose episode_length is closest to the
         *    known playback duration (unknown lengths rank last);
         * 4. tie-break by higher votes, then provider name, then start —
         *    fully input-order independent;
         * 5. at most one stamp per skip type, ordered by start.
         */
        fun selectCsvStamps(
            rows: List<CsvRow>,
            durationSec: Long?,
            malId: Int,
            episodeNumber: Int
        ): List<AniSkip.Stamp> {
            val duration = durationSec?.takeIf { it > 0 }?.toDouble()
            return rows.groupBy { it.skipType }.values.mapNotNull { group ->
                var pool = group.filter {
                    it.start.isFinite() && it.end.isFinite() &&
                        it.start >= 0 && it.end > it.start
                }
                if (pool.isEmpty()) return@mapNotNull null
                if (pool.any { it.votes >= 0 }) {
                    pool = pool.filter { it.votes >= 0 }
                }
                val best = pool.sortedWith(
                    compareBy<CsvRow> { row ->
                        if (duration == null) 0.0
                        else if (row.episodeLength > 0) kotlin.math.abs(row.episodeLength - duration)
                        else Double.MAX_VALUE
                    }.thenByDescending { it.votes }
                        .thenBy { it.provider }
                        .thenBy { it.start }
                ).first()
                AniSkip.Stamp(
                    interval = AniSkip.AniSkipInterval(startTime = best.start, endTime = best.end),
                    skipType = best.skipType,
                    skipId = "csv_${best.provider}_${best.skipType}_${malId}_$episodeNumber",
                    episodeLength = best.episodeLength
                )
            }.sortedWith(compareBy({ it.interval.startTime }, { it.skipType }))
        }
    }

    private val appContext = context.applicationContext

    private inner class DatabaseHelper : SQLiteOpenHelper(appContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            createLiveTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_LIVE")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_STAGING")
            createLiveTable(db)
        }
    }

    private fun createLiveTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_LIVE (
                $COL_ANIME_ID INTEGER NOT NULL,
                $COL_EPISODE  INTEGER NOT NULL,
                $COL_PROVIDER TEXT    NOT NULL DEFAULT '',
                $COL_TYPE     TEXT    NOT NULL,
                $COL_VOTES    REAL    NOT NULL DEFAULT 0,
                $COL_START    REAL    NOT NULL,
                $COL_END      REAL    NOT NULL,
                $COL_EP_LENGTH REAL   NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_${TABLE_LIVE}_lookup " +
                    "ON $TABLE_LIVE ($COL_ANIME_ID, $COL_EPISODE)"
        )
    }

    fun getLastUpdated(): Long =
        PrefManager.getNullableCustomVal(PREF_LAST_UPDATED, 0L, Long::class.java) ?: 0L

    fun isUpdateNeeded(): Boolean {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (!dbFile.exists() || dbFile.length() == 0L) return true
        val lastUpdated = getLastUpdated()
        return (System.currentTimeMillis() - lastUpdated) > SYNC_INTERVAL_MS
    }

    suspend fun syncIfNeeded() = withContext(Dispatchers.IO) {
        if (!isUpdateNeeded()) return@withContext
        if (!isSyncing.compareAndSet(false, true)) return@withContext

        Logger.log("$TAG: Starting background sync of AniSkip CSV dump...")
        var okClient: okhttp3.OkHttpClient? = null
        try {
            val httpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build().also { okClient = it }
            val request = Request.Builder().url(CSV_URL).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Logger.log("$TAG: Failed to download CSV, HTTP ${response.code}")
                    return@withContext
                }

                val body = response.body ?: run {
                    Logger.log("$TAG: Empty response body")
                    return@withContext
                }

                val helper = DatabaseHelper()
                try {
                    val db = helper.writableDatabase
                    db.execSQL("DROP TABLE IF EXISTS $TABLE_STAGING")
                    db.execSQL(
                        """
                        CREATE TABLE $TABLE_STAGING (
                            $COL_ANIME_ID INTEGER NOT NULL,
                            $COL_EPISODE  INTEGER NOT NULL,
                            $COL_PROVIDER TEXT    NOT NULL DEFAULT '',
                            $COL_TYPE     TEXT    NOT NULL,
                            $COL_VOTES    REAL    NOT NULL DEFAULT 0,
                            $COL_START    REAL    NOT NULL,
                            $COL_END      REAL    NOT NULL,
                            $COL_EP_LENGTH REAL   NOT NULL DEFAULT 0
                        )
                        """.trimIndent()
                    )

                    var totalRows = 0
                    var isFirstLine = true

                    val reader = java.io.BufferedReader(java.io.InputStreamReader(body.byteStream()))
                    try {
                        val insertSql =
                            "INSERT INTO $TABLE_STAGING ($COL_ANIME_ID, $COL_EPISODE, $COL_PROVIDER, $COL_TYPE, $COL_VOTES, $COL_START, $COL_END, $COL_EP_LENGTH) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                        val statement = db.compileStatement(insertSql)
                        var rowsInBatch = 0
                        var line: String?

                        db.beginTransaction()
                        try {
                            while (true) {
                                line = reader.readLine() ?: break
                                if (isFirstLine) {
                                    isFirstLine = false
                                    continue
                                }
                                if (line.isNullOrBlank()) continue

                                // Public dump schema: anime_id, episode_number,
                                // provider_name, skip_type, votes, start_time,
                                // end_time, episode_length, submit_date.
                                val row = parseCsvRow(line) ?: continue

                                statement.clearBindings()
                                statement.bindLong(1, row.animeId)
                                statement.bindLong(2, row.episode.toLong())
                                statement.bindString(3, row.provider)
                                statement.bindString(4, row.skipType)
                                statement.bindDouble(5, row.votes)
                                statement.bindDouble(6, row.start)
                                statement.bindDouble(7, row.end)
                                statement.bindDouble(8, row.episodeLength)
                                statement.executeInsert()

                                totalRows++
                                rowsInBatch++

                                if (rowsInBatch >= BATCH_SIZE) {
                                    db.setTransactionSuccessful()
                                    db.endTransaction()
                                    db.beginTransaction()
                                    rowsInBatch = 0
                                }
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            if (db.inTransaction()) db.endTransaction()
                            statement.close()
                        }
                    } finally {
                        reader.close()
                    }

                    // Sanity gate: never swap an empty staging table over a
                    // useful live DB (HTTP-200 error payloads, schema drift).
                    if (totalRows == 0) {
                        Logger.log("$TAG: Import yielded zero usable rows; keeping previous live table.")
                        return@withContext
                    }

                    db.beginTransaction()
                    try {
                        db.execSQL("DROP TABLE IF EXISTS $TABLE_LIVE")
                        db.execSQL("ALTER TABLE $TABLE_STAGING RENAME TO $TABLE_LIVE")
                        db.setTransactionSuccessful()
                    } finally {
                        db.endTransaction()
                    }

                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS idx_${TABLE_LIVE}_lookup " +
                                "ON $TABLE_LIVE ($COL_ANIME_ID, $COL_EPISODE)"
                    )

                    PrefManager.setCustomVal(PREF_LAST_UPDATED, System.currentTimeMillis())
                    Logger.log("$TAG: Successfully imported $totalRows AniSkip timestamps into local database.")
                } finally {
                    helper.close()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("$TAG: Sync failed: ${e.message}")
        } finally {
            okClient?.dispatcher?.executorService?.shutdown()
            okClient?.connectionPool?.evictAll()
            okClient = null
            isSyncing.set(false)
        }
    }

    /**
     * One parsed dump row. Framework-free; see [selectCsvStamps] for the
     * deterministic per-type selection policy.
     */
    data class CsvRow(
        val animeId: Long,
        val episode: Int,
        val provider: String,
        val skipType: String,
        val votes: Double,
        val start: Double,
        val end: Double,
        val episodeLength: Double
    )


    suspend fun lookup(
        malId: Int,
        episodeNumber: Int,
        durationSec: Long? = null
    ): List<AniSkip.Stamp>? = withContext(Dispatchers.IO) {
        val dbFile = appContext.getDatabasePath(DB_NAME)
        if (!dbFile.exists() || dbFile.length() == 0L) return@withContext null

        val helper = DatabaseHelper()
        try {
            helper.readableDatabase.use { db ->
                db.query(
                    TABLE_LIVE,
                    arrayOf(COL_PROVIDER, COL_TYPE, COL_VOTES, COL_START, COL_END, COL_EP_LENGTH),
                    "$COL_ANIME_ID = ? AND $COL_EPISODE = ?",
                    arrayOf(malId.toString(), episodeNumber.toString()),
                    null, null, null
                ).use { cursor ->
                    val rows = mutableListOf<CsvRow>()
                    // Old v1 rows lack the new columns; read defensively.
                    val hasProvider = cursor.getColumnIndex(COL_PROVIDER) >= 0
                    while (cursor.moveToNext()) {
                        if (hasProvider) {
                            rows.add(
                                CsvRow(
                                    animeId = malId.toLong(),
                                    episode = episodeNumber,
                                    provider = cursor.getString(0).orEmpty().ifEmpty { "unknown" },
                                    skipType = cursor.getString(1).orEmpty(),
                                    votes = cursor.getDouble(2),
                                    start = cursor.getDouble(3),
                                    end = cursor.getDouble(4),
                                    episodeLength = cursor.getDouble(5)
                                )
                            )
                        } else {
                            // v1 schema fallback: type/start/end only.
                            rows.add(
                                CsvRow(
                                    animeId = malId.toLong(),
                                    episode = episodeNumber,
                                    provider = "unknown",
                                    skipType = cursor.getString(0).orEmpty(),
                                    votes = 0.0,
                                    start = cursor.getDouble(1),
                                    end = cursor.getDouble(2),
                                    episodeLength = 0.0
                                )
                            )
                        }
                    }
                    val selected = selectCsvStamps(rows, durationSec, malId, episodeNumber)
                    if (selected.isNotEmpty()) selected else null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.log("$TAG: Lookup error: ${e.message}")
            null
        } finally {
            helper.close()
        }
    }
}
