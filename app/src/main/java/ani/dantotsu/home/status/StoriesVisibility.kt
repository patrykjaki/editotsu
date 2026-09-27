package ani.dantotsu.home.status

/**
 * MAX MERGE M6 HOLD (Blocker 3): stories-rail visibility contract.
 *
 * - Loading (`users == null`) + enabled: container + progress visible.
 * - Non-empty: container + recycler visible, progress gone.
 * - Empty (resolved, valid): everything gone — never an empty rail.
 * - Disabled: everything gone.
 *
 * Framework-free so the contract is unit-proved (StoriesVisibilityTest).
 */
data class StoriesVisibility(
    val containerVisible: Boolean,
    val progressVisible: Boolean,
    val recyclerVisible: Boolean,
)

internal fun storiesVisibility(users: Collection<*>?, storiesEnabled: Boolean): StoriesVisibility {
    if (!storiesEnabled) {
        return StoriesVisibility(
            containerVisible = false,
            progressVisible = false,
            recyclerVisible = false
        )
    }
    if (users == null) {
        return StoriesVisibility(
            containerVisible = true,
            progressVisible = true,
            recyclerVisible = false
        )
    }
    if (users.isEmpty()) {
        return StoriesVisibility(
            containerVisible = false,
            progressVisible = false,
            recyclerVisible = false
        )
    }
    return StoriesVisibility(
        containerVisible = true,
        progressVisible = false,
        recyclerVisible = true
    )
}
