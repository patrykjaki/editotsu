package ani.dantotsu.connections.discord.rpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Manual [PresenceScheduler] for deterministic coordinator tests. */
class ManualScheduler : PresenceScheduler {
    var dueAction: (() -> Unit)? = null

    override fun schedule(delayMs: Long, action: () -> Unit): PresenceScheduler.PresenceTask {
        dueAction = action
        return object : PresenceScheduler.PresenceTask {
            override fun cancel() { dueAction = null }
        }
    }

    fun runDue() {
        dueAction?.let { it() }
        dueAction = null
    }
}

/** Recording [PresenceTransport] for controller/routing tests. */
class FakeTransport : PresenceTransport {
    val setPresenceCalls = mutableListOf<DiscordPresence>()
    var clearPresenceCount = 0
    var shutdownCount = 0

    override fun setPresence(presence: DiscordPresence) {
        setPresenceCalls.add(presence)
    }

    override fun clearPresence() {
        clearPresenceCount += 1
    }

    override fun shutdown() {
        shutdownCount += 1
    }
}

fun assertOwnerKind(actual: DiscordPresenceOwnership.OwnerToken, expected: DiscordPresenceOwnership.OwnerKind) {
    assertEquals(expected, actual.kind)
}
