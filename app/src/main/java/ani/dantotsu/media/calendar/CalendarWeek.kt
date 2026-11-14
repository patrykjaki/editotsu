package ani.dantotsu.media.calendar

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The release calendar as a week of days, each with the episodes airing on it. */
object CalendarWeek {
    /** one cell of the week strip */
    data class Day(
        /** the calendar data's key for this day: the date in the long local format */
        val key: String,
        /** "Sat" */
        val weekday: String,
        /** "14" */
        val number: String,
        val today: Boolean,
        val count: Int,
        /** spoken label: the full date and how many episodes */
        val description: String,
    )

    /** what a row shows next to the title: which episode and when */
    data class Slot(val episode: String, val time: String)

    /**
     * The view model writes "Episode 7\n13:00" into a title's relation text. Either part can
     * be missing (the fallback schedule has no times for some titles).
     */
    fun slot(relation: String?): Slot {
        val lines = relation.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
        return Slot(lines.getOrNull(0).orEmpty(), lines.getOrNull(1).orEmpty())
    }

    /**
     * The strip's cells for the calendar's day keys, in the data's order. A key that isn't a
     * date in this locale's long format still gets a cell, labelled with its first letters,
     * so no day can disappear from the strip.
     */
    fun days(counts: Map<String, Int>, now: Date = Date(), locale: Locale = Locale.getDefault()): List<Day> {
        val full = DateFormat.getDateInstance(DateFormat.FULL, locale)
        val weekday = SimpleDateFormat("EEE", locale)
        val number = SimpleDateFormat("d", locale)
        val todayKey = full.format(now)
        return counts.map { (key, count) ->
            val date = runCatching { full.parse(key) }.getOrNull()
            Day(
                key = key,
                weekday = date?.let { weekday.format(it).trimEnd('.') } ?: key.take(3),
                number = date?.let(number::format).orEmpty(),
                today = key == todayKey,
                count = count,
                description = "$key, $count",
            )
        }
    }

    /** the day to open on: today, else the first day */
    fun startIndex(days: List<Day>): Int = days.indexOfFirst { it.today }.coerceAtLeast(0)

    /** true when [key]'s date is before today (its episodes have aired) */
    fun isPast(key: String, now: Date = Date(), locale: Locale = Locale.getDefault()): Boolean {
        val date = runCatching { DateFormat.getDateInstance(DateFormat.FULL, locale).parse(key) }.getOrNull() ?: return false
        val startOfToday = Calendar.getInstance().apply {
            time = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time
        return date.before(startOfToday)
    }
}
