package ani.dantotsu.media.anime.selector

/**
 * Source Picker v3 Final: pure list logic (no Android types).
 *
 * The fragment turns every (extractor, video) pair into a [PickerRow].
 * [PickerListBuilder] then:
 *  - merges STREAM rows that share provider + group name + audio kind
 *    into one [PickerEntry.StreamGroup] with one button per quality;
 *  - keeps every RELEASE (torrent) row as its own [PickerEntry.Release];
 *  - picks a Best match with a visible reason ([BestMatch]);
 *  - moves the Best match, then other rows from the remembered release
 *    family, to the top. Everything else keeps the extension's order.
 */

/** Position of one playable video: extractor index + video index inside it. */
data class RowRef(val extractor: Int, val video: Int)

enum class AudioKind { SUB, DUB, DUAL }

data class PickerRow(
    val ref: RowRef,
    /** Extension package (or another stable provider id); null if unknown. */
    val provider: String?,
    val presentation: SourcePresentationKind,
    /** Card header from the summarizer: host/group for streams, release group for torrents. */
    val header: String,
    /** Vertical resolution in pixels, if known (extension data first, then parsed text). */
    val resolution: Int?,
    /** "HLS", "MP4", "DASH" or "Torrent"; null if unknown. */
    val transport: String?,
    val audioKind: AudioKind?,
    /** Extension marked this video/server as preferred. */
    val preferred: Boolean = false,
    /** Matches the remembered exact selection for this anime. */
    val rememberedExact: Boolean = false,
    /** Matches the remembered release family (provider + group) for this anime. */
    val rememberedFamily: Boolean = false,
)

data class QualityVariant(val ref: RowRef, val label: String, val resolution: Int?, val transport: String?)

sealed class PickerEntry {
    abstract val audioKind: AudioKind?
    abstract val refs: List<RowRef>

    data class StreamGroup(
        val name: String,
        /** Short audio/language tag shown next to the name, e.g. "Sub · English". */
        val audioTag: String?,
        override val audioKind: AudioKind?,
        /** One button per quality, highest first. */
        val primary: List<QualityVariant>,
        /** Second links for a quality already in [primary] (e.g. MP4 next to HLS). */
        val alternates: List<QualityVariant>,
        /** Shared transport of the alternates ("MP4"), or null when mixed/unknown. */
        val alternateLabel: String?,
    ) : PickerEntry() {
        override val refs: List<RowRef> get() = primary.map { it.ref } + alternates.map { it.ref }
    }

    data class Release(val row: PickerRow) : PickerEntry() {
        override val audioKind: AudioKind? get() = row.audioKind
        override val refs: List<RowRef> get() = listOf(row.ref)
    }
}

enum class BestReason { REMEMBERED, SAME_GROUP, EXTENSION_PREFERRED }

data class BestPick(val ref: RowRef, val reason: BestReason)

data class PickerList(
    val entries: List<PickerEntry>,
    val best: BestPick?,
    /** Index in [entries] of the entry holding [best], or -1. */
    val bestIndex: Int,
    /** A family is remembered for this anime, but no row belongs to it. */
    val rememberedFamilyMissing: Boolean,
)

object AudioKindDetector {
    private val DUAL = Regex("""(?i)\b(dual[\s._-]?audio|dual|multi[\s._-]?audio)\b""")
    private val DUB = Regex("""(?i)\b(dub|dubbed|english[\s._-]?dub)\b""")
    private val SUB = Regex("""(?i)\b(sub|subs|subbed|softsub|hardsub|raw[\s._-]?sub)\b""")

    /** Dual wins over Dub, Dub over Sub. Null when the text names none. */
    fun detect(text: String): AudioKind? = when {
        DUAL.containsMatchIn(text) -> AudioKind.DUAL
        DUB.containsMatchIn(text) -> AudioKind.DUB
        SUB.containsMatchIn(text) -> AudioKind.SUB
        else -> null
    }

    fun label(kind: AudioKind): String = when (kind) {
        AudioKind.SUB -> "Sub"
        AudioKind.DUB -> "Dub"
        AudioKind.DUAL -> "Dual"
    }
}

object StreamNameCleaner {
    private val RESOLUTION = Regex("""(?i)(\b\d{3,4}\s?p\b|\b[48]k\b|\b\d{3,4}x\d{3,4}\b)""")
    private val SIZE = Regex("""(?i)\(?\b\d+(?:[.,]\d+)?\s?(?:kb|mb|gb)\b\)?""")
    private val TRANSPORT = Regex("""(?i)\b(hls|m3u8|mp4|dash|mkv)\b""")
    private val AUDIO_PAREN = Regex("""(?i)\(\s*((?:sub|dub|dual|softsub|hardsub)[^)]*)\)""")
    private val EMPTY_PARENS = Regex("""\(\s*\)|\[\s*]""")
    private val EDGE_SEPARATORS = Regex("""^[\s\-–—·•|:,/]+|[\s\-–—·•|:,/]+$""")
    private val SPACES = Regex("""\s{2,}""")

    /**
     * Name used both for display and for grouping: resolution, size and
     * transport words are removed so "MTBB 360p" and "MTBB 1080p" merge.
     * Falls back to the trimmed input when cleaning would empty it.
     */
    fun groupName(header: String): String {
        val withoutAudio = AUDIO_PAREN.replace(header, " ")
        val cleaned = withoutAudio
            .replace(RESOLUTION, " ")
            .replace(SIZE, " ")
            .replace(TRANSPORT, " ")
            .replace(EMPTY_PARENS, " ")
            .replace(SPACES, " ")
            .replace(EDGE_SEPARATORS, "")
            .trim()
        return cleaned.ifEmpty { header.trim() }
    }

    /** "MTBB (Sub/English)" -> "Sub · English"; null when there is no such tag. */
    fun audioTag(header: String): String? {
        val inner = AUDIO_PAREN.find(header)?.groupValues?.get(1) ?: return null
        val parts = inner.split('/', ',', '|', '-').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        return parts.joinToString(" · ") { p -> p.replaceFirstChar { it.uppercaseChar() } }
    }
}

object ResolutionLabel {
    private val DIGITS = Regex("""(\d{3,4})\s?p""", RegexOption.IGNORE_CASE)
    private val FOUR_K = Regex("""(?i)\b4k\b""")

    fun label(resolution: Int?): String? = when {
        resolution == null || resolution <= 0 -> null
        resolution >= 2000 -> "4K"
        else -> "${resolution}p"
    }

    /** Extension-supplied value wins; otherwise parse "1080p" / "4K" from text. */
    fun resolve(extensionResolution: Int?, text: String?): Int? {
        if (extensionResolution != null && extensionResolution > 0) return extensionResolution
        if (text.isNullOrBlank()) return null
        DIGITS.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (FOUR_K.containsMatchIn(text)) return 2160
        return null
    }
}

object BestMatch {
    /**
     * Remembered exact pick first, then a single row from the remembered
     * family, then a single extension-preferred row. Ties at any level
     * mean no Best match: the picker never guesses between equals.
     */
    fun pick(rows: List<PickerRow>): BestPick? {
        rows.singleOrNull { it.rememberedExact }?.let { return BestPick(it.ref, BestReason.REMEMBERED) }
        if (rows.count { it.rememberedExact } > 1) return null
        val family = rows.filter { it.rememberedFamily }
        if (family.size == 1) return BestPick(family[0].ref, BestReason.SAME_GROUP)
        if (family.size > 1) return null
        rows.singleOrNull { it.preferred }?.let { return BestPick(it.ref, BestReason.EXTENSION_PREFERRED) }
        return null
    }
}

object PickerListBuilder {
    fun build(rows: List<PickerRow>): PickerList {
        val entries = group(rows)
        val best = if (rows.size > 1) BestMatch.pick(rows) else null
        val familyRefs = rows.filter { it.rememberedFamily }.map { it.ref }.toSet()
        val ordered = order(entries, best, familyRefs)
        val bestIndex = best?.let { b -> ordered.indexOfFirst { b.ref in it.refs } } ?: -1
        return PickerList(
            entries = ordered,
            best = best,
            bestIndex = bestIndex,
            rememberedFamilyMissing = false,
        )
    }

    /** Same as [build], plus whether a remembered family exists but has no row here. */
    fun build(rows: List<PickerRow>, hasRememberedFamily: Boolean): PickerList {
        val list = build(rows)
        return list.copy(
            rememberedFamilyMissing = hasRememberedFamily && rows.isNotEmpty() && rows.none { it.rememberedFamily },
        )
    }

    fun group(rows: List<PickerRow>): List<PickerEntry> {
        val out = mutableListOf<PickerEntry>()
        val groupIndex = mutableMapOf<String, Int>()
        val groupRows = mutableMapOf<String, MutableList<PickerRow>>()
        for (row in rows) {
            if (row.presentation != SourcePresentationKind.STREAM) {
                out += PickerEntry.Release(row)
                continue
            }
            val name = StreamNameCleaner.groupName(row.header)
            val key = listOf(row.provider.orEmpty(), name.lowercase(), row.audioKind?.name.orEmpty())
                .joinToString("\u0000")
            val existing = groupIndex[key]
            if (existing == null) {
                groupIndex[key] = out.size
                groupRows[key] = mutableListOf(row)
                out += PickerEntry.Release(row) // placeholder, replaced below
            } else {
                groupRows.getValue(key) += row
            }
        }
        for ((key, index) in groupIndex) {
            out[index] = streamGroup(groupRows.getValue(key))
        }
        return out
    }

    private fun streamGroup(rows: List<PickerRow>): PickerEntry.StreamGroup {
        val first = rows.first()
        // Highest first; unknown resolution last; stable within equal values.
        val sorted = rows.withIndex()
            .sortedWith(compareByDescending<IndexedValue<PickerRow>> { it.value.resolution ?: -1 }.thenBy { it.index })
            .map { it.value }
        val primary = mutableListOf<QualityVariant>()
        val alternates = mutableListOf<QualityVariant>()
        val seen = mutableSetOf<Int?>()
        for (row in sorted) {
            val variant = QualityVariant(
                ref = row.ref,
                label = ResolutionLabel.label(row.resolution) ?: row.transport ?: "Play",
                resolution = row.resolution,
                transport = row.transport,
            )
            // Unknown resolutions never collapse into each other.
            if (row.resolution != null && !seen.add(row.resolution)) alternates += variant else primary += variant
        }
        val altTransports = alternates.map { it.transport }.distinct()
        val primaryTransports = primary.map { it.transport }.toSet()
        val alternateLabel = altTransports.singleOrNull()
            ?.takeIf { it !in primaryTransports || primaryTransports.size > 1 }
        return PickerEntry.StreamGroup(
            name = StreamNameCleaner.groupName(first.header),
            audioTag = StreamNameCleaner.audioTag(first.header)
                ?: first.audioKind?.let { AudioKindDetector.label(it) },
            audioKind = first.audioKind,
            primary = primary,
            alternates = alternates,
            alternateLabel = alternateLabel,
        )
    }

    fun order(entries: List<PickerEntry>, best: BestPick?, familyRefs: Set<RowRef>): List<PickerEntry> {
        if (best == null && familyRefs.isEmpty()) return entries
        val bestEntries = entries.filter { e -> best != null && best.ref in e.refs }
        val familyEntries = entries.filter { e -> e !in bestEntries && e.refs.any { it in familyRefs } }
        val rest = entries.filter { it !in bestEntries && it !in familyEntries }
        return bestEntries + familyEntries + rest
    }
}

object AudioKindFilter {
    /** Kinds present, in fixed order. The filter row shows only when there are two or more. */
    fun kinds(entries: List<PickerEntry>): List<AudioKind> {
        val present = entries.mapNotNull { it.audioKind }.toSet()
        return AudioKind.values().filter { it in present }
    }

    fun shouldShow(entries: List<PickerEntry>): Boolean = kinds(entries).size >= 2

    /** Null selection = All. Entries with an unknown kind appear only under All. */
    fun apply(entries: List<PickerEntry>, selected: AudioKind?): List<PickerEntry> =
        if (selected == null) entries else entries.filter { it.audioKind == selected }

    fun count(entries: List<PickerEntry>, kind: AudioKind?): Int = apply(entries, kind).size
}

object SeederHealth {
    const val LOW_THRESHOLD = 10
    private val NUMBER = Regex("""\d[\d,.\s]*""")

    fun isLow(seeders: Int?): Boolean = seeders != null && seeders < LOW_THRESHOLD

    fun parse(text: String?): Int? =
        text?.let { NUMBER.find(it)?.value?.filter(Char::isDigit)?.toIntOrNull() }

    fun label(seeders: Int): String =
        if (isLow(seeders)) "$seeders seeds · may be slow" else "$seeders seeds"
}

object TagLineBudget {
    /**
     * How many tags fit on one line. Tags keep their priority order; when
     * not all fit, a "+N" chip is reserved at the end. At least one tag is
     * always kept so the quality never disappears.
     *
     * @param widths measured tag widths, in priority order
     * @param gap space between neighbours
     * @param available width of the line
     * @param plusWidth width of the "+N" chip for a given N
     */
    fun fit(widths: List<Int>, gap: Int, available: Int, plusWidth: (Int) -> Int): Int {
        val n = widths.size
        if (n == 0) return 0
        fun used(k: Int): Int = widths.take(k).sum() + gap * (k - 1).coerceAtLeast(0)
        if (used(n) <= available) return n
        for (k in n - 1 downTo 1) {
            val total = used(k) + gap + plusWidth(n - k)
            if (total <= available) return k
        }
        return 1
    }
}

object TransportLabel {
    fun of(url: String?, formatName: String?): String? {
        val u = url.orEmpty()
        if (u.startsWith("magnet:", ignoreCase = true) || u.substringBefore('?').endsWith(".torrent", ignoreCase = true)) {
            return "Torrent"
        }
        return when (formatName?.uppercase()) {
            "M3U8" -> "HLS"
            "DASH" -> "DASH"
            "CONTAINER" -> "MP4"
            else -> null
        }
    }
}

object CompactDetails {
    /**
     * Short details panel: group, tracker, the file that will play (only
     * when it differs from the release name, i.e. batches and season
     * packs), then the raw release name. Never the link itself.
     */
    fun lines(
        group: String?,
        tracker: String?,
        fileName: String?,
        rawName: String?,
        transport: String?,
    ): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        group?.takeIf { it.isNotBlank() }?.let { out += "Release group" to it }
        tracker?.takeIf { it.isNotBlank() }?.let { out += "Tracker" to it }
        transport?.takeIf { it.isNotBlank() }?.let { out += "Type" to it }
        val file = fileName?.substringAfterLast('/')?.trim()
        if (!file.isNullOrBlank() && file != rawName?.trim()) out += "Plays file" to file
        rawName?.takeIf { it.isNotBlank() }?.let { out += "Release name" to it }
        return out
    }

    /**
     * Torrentio-style text: line 1 is the release name; when a second,
     * non-metadata line ends in a video extension it is the file to play.
     */
    fun fileLine(qualityText: String): String? {
        val lines = qualityText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val candidate = lines[1]
        return candidate.takeIf { VIDEO_EXT.containsMatchIn(it) }
    }

    private val VIDEO_EXT = Regex("""(?i)\.(mkv|mp4|avi|m2ts|ts|webm)\s*$""")
}
