package ani.dantotsu.media.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale

class CalendarWeekTest {
    private fun day(offset: Int) = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 14, 16, 17, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }.time

    private fun key(offset: Int, locale: Locale) = DateFormat.getDateInstance(DateFormat.FULL, locale).format(day(offset))

    @Test
    fun `the strip has one cell per day, with weekday, number and today marked`() {
        for (locale in listOf(Locale.US, Locale("pl", "PL"), Locale.JAPAN)) {
            val counts = linkedMapOf(key(-1, locale) to 3, key(0, locale) to 5, key(1, locale) to 0)
            val days = CalendarWeek.days(counts, day(0), locale)
            assertEquals(locale.toString(), listOf("13", "14", "15"), days.map { it.number })
            assertEquals(listOf(false, true, false), days.map { it.today })
            assertEquals(listOf(3, 5, 0), days.map { it.count })
            assertTrue(days.all { it.weekday.isNotBlank() })
            assertEquals(1, CalendarWeek.startIndex(days))
            assertTrue(CalendarWeek.isPast(key(-1, locale), day(0), locale))
            assertFalse(CalendarWeek.isPast(key(0, locale), day(0), locale))
        }
        assertEquals("Sat", CalendarWeek.days(mapOf(key(0, Locale.US) to 1), day(0), Locale.US).single().weekday)
    }

    @Test
    fun `a key that is not a date still gets a cell`() {
        val days = CalendarWeek.days(mapOf("Unknown day" to 2), day(0), Locale.US)
        assertEquals("Unk", days.single().weekday)
        assertEquals("", days.single().number)
        assertEquals(0, CalendarWeek.startIndex(days))
    }

    @Test
    fun `episode and time are read from the relation text`() {
        assertEquals(CalendarWeek.Slot("Episode 7", "13:00"), CalendarWeek.slot("Episode 7\n13:00"))
        assertEquals(CalendarWeek.Slot("Episode 7", ""), CalendarWeek.slot("Episode 7"))
        assertEquals(CalendarWeek.Slot("", ""), CalendarWeek.slot(null))
    }
}
