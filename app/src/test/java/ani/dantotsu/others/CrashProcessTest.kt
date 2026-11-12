package ani.dantotsu.others

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashProcessTest {
    @Test
    fun `crash process is recognised`() {
        assertTrue(CrashProcess.isCrashProcess("ani.editotsu.beta:error_process"))
    }

    @Test
    fun `main and unknown processes are not`() {
        assertFalse(CrashProcess.isCrashProcess("ani.editotsu.beta"))
        assertFalse(CrashProcess.isCrashProcess("ani.editotsu.beta:other"))
        assertFalse(CrashProcess.isCrashProcess(null))
    }
}
