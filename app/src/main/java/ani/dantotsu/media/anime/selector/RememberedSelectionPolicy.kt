package ani.dantotsu.media.anime.selector

/**
 * Pure remembered-selection decision policy.
 *
 * Extracted so that the MakeDefault gating logic can be unit-tested
 * without an Android runtime. The production fragment calls these
 * methods; the tests exercise the same methods directly.
 *
 * The exact-slot policy is a three-state transaction:
 *   - MakeDefault OFF                -> NO_CHANGE (never touch the slot)
 *   - MakeDefault ON + exact present -> WRITE the current exact key
 *   - MakeDefault ON + exact null    -> CLEAR the slot (a stale key
 *     from a previous manual choice must not survive a manual
 *     choice the persistence layer cannot key)
 * The family policy is unchanged:
 *   - MakeDefault OFF                        -> never touch family
 *   - MakeDefault ON + confident family      -> replace stored family
 *   - MakeDefault ON + no confident family   -> preserve existing family
 */
object RememberedSelectionPolicy {

    /** Exact-slot transaction for one manual remembered selection. */
    enum class ExactSlotAction {
        /** Leave the persisted slot untouched. */
        NO_CHANGE,
        /** Persist the current candidate's exact key. */
        WRITE,
        /** Remove any previously persisted exact key. */
        CLEAR,
    }

    /**
     * Exact-slot action for this manual remembered selection.
     * A null/blank `exactKey` with MakeDefault ON clears the slot
     * so a stale key from an older manual choice cannot be
     * recovered later ahead of the user's current choice.
     */
    fun exactSlotAction(makeDefault: Boolean, exactKey: String?): ExactSlotAction {
        if (!makeDefault) return ExactSlotAction.NO_CHANGE
        return if (!exactKey.isNullOrBlank()) ExactSlotAction.WRITE
        else ExactSlotAction.CLEAR
    }

    /**
     * Should the exact key be written for this remembered selection?
     * True only when `makeDefault` is enabled and a non-blank
     * `exactKey` is provided. Equivalent to
     * `exactSlotAction(...) == WRITE`.
     */
    fun shouldWriteExactKey(makeDefault: Boolean, exactKey: String?): Boolean {
        return exactSlotAction(makeDefault, exactKey) == ExactSlotAction.WRITE
    }

    /** Family-slot transaction for one manual remembered selection. */
    enum class FamilySlotAction {
        /** Leave the persisted family preference untouched. */
        NO_CHANGE,
        /** Replace the stored family payload with the new one. */
        REPLACE,
        /**
         * Keep the existing stored family preference. Used when
         * the manual candidate carries no confident family, so
         * parser uncertainty cannot silently delete a previous
         * family choice.
         */
        PRESERVE,
    }

    /**
     * Persistence decision for one successful remembered
     * AUTO-recovery (exact-name hit, stable exact-key hit, or
     * family-continuity hit). The runtime handle
     * (`Episode.selectedExtractor` / `selectedVideo`) is ALWAYS
     * applied by the caller; this helper decides only the
     * remembered-persistence side:
     *
     *   MakeDefault ON  -> persist the legacy Selected.server/video
     *     choice and apply ExactSlotAction to the resolved key
     *     (real key WRITE, null key CLEAR).
     *   MakeDefault OFF -> persist nothing new: no legacy server
     *     choice, exact NO_CHANGE, family NO_CHANGE.
     *
     * The family soft payload is never rewritten by auto-recovery
     * in either case.
     */
    data class AutoRecoveryPersistence(
        val persistLegacyServer: Boolean,
        val exactAction: ExactSlotAction,
    )

    fun autoRecoveryPersistence(
        makeDefault: Boolean,
        exactKey: String?,
    ): AutoRecoveryPersistence {
        if (!makeDefault) {
            return AutoRecoveryPersistence(
                persistLegacyServer = false,
                exactAction = ExactSlotAction.NO_CHANGE,
            )
        }
        return AutoRecoveryPersistence(
            persistLegacyServer = true,
            exactAction = exactSlotAction(true, exactKey),
        )
    }

    /**
     * Family-slot action for this manual remembered selection.
     * Independent of the exact-slot action:
     *
     *   MakeDefault OFF              -> NO_CHANGE
     *   MakeDefault ON + confident   -> REPLACE
     *   MakeDefault ON + unconfident -> PRESERVE
     *
     * In particular ON + exact=null + no confident family yields
     * exact CLEAR alongside family PRESERVE.
     */
    fun familySlotAction(
        makeDefault: Boolean,
        familyCandidate: FamilyCandidate?,
    ): FamilySlotAction {
        if (!makeDefault) return FamilySlotAction.NO_CHANGE
        return if (familyCandidate != null) FamilySlotAction.REPLACE
        else FamilySlotAction.PRESERVE
    }

    /**
     * Should the stored family payload be REPLACED for this
     * remembered selection? True only when `makeDefault` is enabled
     * and a confident `familyCandidate` is provided. Equivalent to
     * `familySlotAction(...) == REPLACE`.
     */
    fun shouldReplaceFamily(
        makeDefault: Boolean,
        familyCandidate: FamilyCandidate?,
    ): Boolean {
        return familySlotAction(makeDefault, familyCandidate) ==
            FamilySlotAction.REPLACE
    }

    /**
     * Should the existing family preference be left UNCHANGED for
     * this remembered selection? True when `makeDefault` is
     * enabled and no confident family candidate is present,
     * regardless of whether the exact key is writable. Equivalent
     * to `familySlotAction(...) == PRESERVE`.
     */
    fun shouldPreserveExistingFamily(
        makeDefault: Boolean,
        familyCandidate: FamilyCandidate?,
    ): Boolean {
        return familySlotAction(makeDefault, familyCandidate) ==
            FamilySlotAction.PRESERVE
    }
}
