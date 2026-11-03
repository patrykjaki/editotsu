package ani.dantotsu.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SharedRefreshTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a second pull while one runs joins it instead of fetching again`() = runBlocking {
        val refresh = SharedRefresh<String, Int>(scope)
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val first = refresh.start("anime") { calls.incrementAndGet(); gate.await(); 3 }
        val second = refresh.start("anime") { calls.incrementAndGet(); 9 }
        assertSame(first, second)
        assertSame(first, refresh.running("anime"))
        gate.complete(Unit)
        assertEquals(3, second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `types refresh independently`() = runBlocking {
        val refresh = SharedRefresh<String, Int>(scope)
        val gate = CompletableDeferred<Unit>()
        val anime = refresh.start("anime") { gate.await(); 1 }
        val manga = refresh.start("manga") { 2 }
        assertEquals(2, manga.await())
        gate.complete(Unit)
        assertEquals(1, anime.await())
    }

    @Test
    fun `a finished refresh is not reused`() = runBlocking {
        val refresh = SharedRefresh<String, Int>(scope)
        assertEquals(1, refresh.start("novel") { 1 }.await())
        assertNull(refresh.running("novel"))
        assertEquals(2, refresh.start("novel") { 2 }.await())
    }
}
