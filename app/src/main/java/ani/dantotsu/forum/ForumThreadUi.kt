package ani.dantotsu.forum

/**
 * MAX MERGE M2 correction (HOLD2 Blocker 2, adjacent integrity): pure
 * render-state seams for the flattened nested-comment list.
 *
 * - [indentForDepth]: px indent for a nesting depth. Production bind
 *   applies it UNCONDITIONALLY (including zero) so recycled views can
 *   never retain a stale child margin.
 * - [threadSubtreeEnd]: end-exclusive index of the rendered subtree
 *   rooted at [pos] — rows while depth stays deeper than the root.
 *   Production delete removes exactly [pos, end) so descendants never
 *   orphan.
 *
 * Unit-proved (ForumThreadUiTest).
 */
fun indentForDepth(depth: Int, density: Float): Int =
    (depth.coerceAtLeast(0) * 16 * density).toInt()

fun threadSubtreeEnd(depths: List<Int>, pos: Int): Int {
    val root = depths.getOrNull(pos) ?: return pos
    var end = pos + 1
    while (end < depths.size && depths[end] > root) end++
    return end
}
