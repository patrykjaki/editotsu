package ani.dantotsu.media.manga.mangareader

/**
 * MAX MERGE M4 correction (HOLD2 C1): forward/backward chapter-transition
 * policy (pure, unit-tested).
 *
 * Continuous mode preloads both next AND previous chapters, so a chapter
 * identity change is not proof the user finished the outgoing chapter.
 * Only a genuine forward transition — index increases in normal order,
 * decreases when the reading direction is reversed (RTL / bottom-to-top) —
 * may complete/publish the outgoing chapter. This mirrors the discrete
 * chapter-spinner path's `isForward` rule so both paths agree.
 */
fun isForwardChapterTransition(fromIndex: Int, toIndex: Int, directionRLBT: Boolean): Boolean {
    if (fromIndex < 0 || toIndex < 0 || fromIndex == toIndex) return false
    return if (directionRLBT) toIndex < fromIndex else toIndex > fromIndex
}

/**
 * Full completion gate: [progressAllowed] carries the existing
 * incognito / save-progress / adult-update gates evaluated by the caller.
 * Backward transitions never complete, regardless of gates.
 */
fun shouldCompleteOutgoingChapter(
    fromIndex: Int,
    toIndex: Int,
    directionRLBT: Boolean,
    progressAllowed: Boolean,
): Boolean = progressAllowed && isForwardChapterTransition(fromIndex, toIndex, directionRLBT)
