package ani.dantotsu.settings.data

import ani.dantotsu.settings.saving.internal.Location
import org.junit.Assert.assertEqualsimport org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAX MERGE M3 (upstream e954d9f1): auto-backup cadence, retention, and
 * the secret-exclusion rule.
 */
class AutoBackupPolicyTest {

    @Test
    fun `interval index maps to hours, off is zero`() {
        assertEquals(0L, autoBackupIntervalHours(0))
        assertEquals(6L, autoBackupIntervalHours(1))
        assertEquals(12L, autoBackupIntervalHours(2))
        assertEquals(24L, autoBackupIntervalHours(3))
        assertEquals(48L, autoBackupIntervalHours(4))
        assertEquals(168L, autoBackupIntervalHours(5))
        assertEquals(0L, autoBackupIntervalHours(6))
        assertEquals(0L, autoBackupIntervalHours(-1))
    }

    @Test
    fun `scheduled payload excludes protected secrets`() {
        val locations = autoBackupLocations()
        assertTrue(locations.isNotEmpty())
        assertFalse(locations.contains(Location.Protected))
        assertTrue(locations.all { it.exportable })
    }

    @Test
    fun `plaintext allowlist is explicit and excludes unclassified extension prefs`() {
        assertEquals(
            setOf(
                Location.General,
                Location.UI,
                Location.Player,
                Location.Reader,
                Location.NovelReader
            ),
            plaintextBackupAllowlist().toSet()
        )
        assertFalse(plaintextBackupAllowlist().contains(Location.Protected))
        assertFalse(plaintextBackupAllowlist().contains(Location.ExtensionSettings))
        assertEquals(plaintextBackupAllowlist().toSet(), autoBackupLocations().toSet())
        // The encrypted `.sani` flow is untouched: Protected stays
        // exportable so full backup can still include it with a password.
        assertTrue(Location.Protected.exportable)
    }

    @Test
    fun `retention prunes oldest beyond max copies`() {
        val backups = listOf(
            BackupCandidate("b1", 100),
            BackupCandidate("b2", 200),
            BackupCandidate("b3", 300),
            BackupCandidate("b4", 400)
        )
        assertEquals(
            listOf(BackupCandidate("b1", 100), BackupCandidate("b2", 200)),
            selectBackupsToPrune(backups, 2)
        )
    }

    @Test
    fun `retention keeps all within limit and at least one`() {
        val backups = listOf(BackupCandidate("b1", 100), BackupCandidate("b2", 200))
        assertTrue(selectBackupsToPrune(backups, 3).isEmpty())
        assertTrue(selectBackupsToPrune(backups, 2).isEmpty())
        assertEquals(1, selectBackupsToPrune(backups, 0).size)
        assertEquals(1, selectBackupsToPrune(backups, -5).size)
    }
}
