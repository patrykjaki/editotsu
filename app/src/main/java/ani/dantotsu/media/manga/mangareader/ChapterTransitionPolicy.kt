package ani.dantotsu.media.manga.mangareader

import ani.dantotsu.settings.CurrentReaderSettings.Directions

/*
 * Chapter order rules for the manga reader (pure, unit-tested).
 *
 * The reader's chapter list is in reading order: index + 1 is the next
 * chapter in every reading direction. The direction only decides which side
 * of the screen the next chapter is on (continuous mode lays the list out
 * reversed for right-to-left and bottom-to-top, so items appended to the
 * adapter still sit further along the reading direction). It never changes
 * which chapter is next.
 */

/** Right-to-left and bottom-to-top: the reader lays pages out reversed. */
fun isReversedReadingDirection(direction: Directions): Boolean =
    direction == Directions.RIGHT_TO_LEFT || direction == Directions.BOTTOM_TO_TOP

/** The chapter after [currentIndex], or null at the end or for an unknown index. */
fun nextChapterIndex(currentIndex: Int, chapterCount: Int): Int? =
    if (currentIndex !in 0 until chapterCount) null
    else (currentIndex + 1).takeIf { it < chapterCount }

/** The chapter before [currentIndex], or null at the start or for an unknown index. */
fun previousChapterIndex(currentIndex: Int, chapterCount: Int): Int? =
    if (currentIndex !in 0 until chapterCount) null
    else (currentIndex - 1).takeIf { it >= 0 }

/**
 * Continuous mode preloads both neighbours, so a chapter change is not proof
 * the user finished the outgoing chapter. Only a move to a later chapter is
 * forward, in every reading direction.
 */
fun isForwardChapterTransition(fromIndex: Int, toIndex: Int): Boolean {
    if (fromIndex < 0 || toIndex < 0) return false
    return toIndex > fromIndex
}

/** What to do with tracker progress after a chapter is finished. */
enum class ChapterProgressSync { NONE, ASK, UPDATE }

/**
 * The tracker progress gate shared by the chapter buttons and the
 * continuous-mode chapter boundary. Logged-out users get nothing (no request,
 * no toast); users who chose to be asked get the dialog; otherwise progress
 * is sent when saving is allowed for this title.
 */
fun chapterProgressSync(
    loggedIn: Boolean,
    askIndividually: Boolean,
    incognito: Boolean,
    saveProgress: Boolean,
    isAdult: Boolean,
    updateForAdult: Boolean,
): ChapterProgressSync = when {
    !loggedIn -> ChapterProgressSync.NONE
    askIndividually && !incognito -> ChapterProgressSync.ASK
    !incognito && saveProgress && (!isAdult || updateForAdult) -> ChapterProgressSync.UPDATE
    else -> ChapterProgressSync.NONE
}
