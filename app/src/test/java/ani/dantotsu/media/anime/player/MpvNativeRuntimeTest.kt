package ani.dantotsu.media.anime.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MpvNativeRuntimeTest {

    @Before
    @After
    fun cleanup() {
        MpvNativeRuntime.resetForTesting()
    }

    @Test
    fun testSequentialOwnershipGrantAndRelease() {
        val firstGranted = CountDownLatch(1)
        val secondGranted = CountDownLatch(1)
        var firstToken: MpvOwnerToken? = null
        var secondToken: MpvOwnerToken? = null

        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 1L,
            cancelled = AtomicBoolean(false),
            onGranted = { token ->
                firstToken = token
                firstGranted.countDown()
            }
        )

        assertTrue(firstGranted.await(2, TimeUnit.SECONDS))
        assertNotNull(firstToken)
        assertEquals(1L, firstToken!!.generation)

        // Request second owner while first is active -> must be queued
        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 2L,
            cancelled = AtomicBoolean(false),
            onGranted = { token ->
                secondToken = token
                secondGranted.countDown()
            }
        )

        assertFalse(secondGranted.await(200, TimeUnit.MILLISECONDS))
        assertEquals(1, MpvNativeRuntime.getWaitingQueueSizeForTesting())

        // Release first owner -> second must be granted
        MpvNativeRuntime.finishOwnerOnRuntime(firstToken!!)

        assertTrue(secondGranted.await(2, TimeUnit.SECONDS))
        assertNotNull(secondToken)
        assertEquals(2L, secondToken!!.generation)

        MpvNativeRuntime.finishOwnerOnRuntime(secondToken!!)
    }

    @Test
    fun testPoisonWithExistingQueuedWaiters_DrainsAndRejectsAllWaitersTerminally() {
        val zGranted = CountDownLatch(1)
        var zToken: MpvOwnerToken? = null

        val aGranted = CountDownLatch(1)
        val bRejectedReason = AtomicInteger(-1)
        val cRejectedReason = AtomicInteger(-1)
        val bLatch = CountDownLatch(1)
        val cLatch = CountDownLatch(1)

        // Z active
        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 10L,
            cancelled = AtomicBoolean(false),
            onGranted = { token ->
                zToken = token
                zGranted.countDown()
            }
        )
        assertTrue(zGranted.await(2, TimeUnit.SECONDS))

        // A, B, C queued
        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 11L,
            cancelled = AtomicBoolean(false),
            onGranted = { token ->
                aGranted.countDown()
                // A escapes unexpectedly without cleaning up
                throw IllegalStateException("Unexpected crash during A onGranted")
            }
        )

        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 12L,
            cancelled = AtomicBoolean(false),
            onGranted = {
                org.junit.Assert.fail("B should never be granted after poison")
            },
            onRejected = { reason ->
                bRejectedReason.set(reason.ordinal)
                bLatch.countDown()
            }
        )

        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 13L,
            cancelled = AtomicBoolean(false),
            onGranted = {
                org.junit.Assert.fail("C should never be granted after poison")
            },
            onRejected = { reason ->
                cRejectedReason.set(reason.ordinal)
                cLatch.countDown()
            }
        )

        assertEquals(3, MpvNativeRuntime.getWaitingQueueSizeForTesting())

        // Z finishes -> A starts and throws -> poisonRuntime occurs
        MpvNativeRuntime.finishOwnerOnRuntime(zToken!!)

        assertTrue(aGranted.await(2, TimeUnit.SECONDS))
        assertTrue("B was not rejected in time", bLatch.await(2, TimeUnit.SECONDS))
        assertTrue("C was not rejected in time", cLatch.await(2, TimeUnit.SECONDS))

        assertEquals(RuntimeRejectReason.POISONED.ordinal, bRejectedReason.get())
        assertEquals(RuntimeRejectReason.POISONED.ordinal, cRejectedReason.get())
        assertTrue(MpvNativeRuntime.isPoisonedForTesting())
        assertEquals(0, MpvNativeRuntime.getWaitingQueueSizeForTesting())
    }

    @Test
    fun testNewRequestAfterPoison_ImmediatelyRejectedWithPoisoned() {
        val poisonLatch = CountDownLatch(1)

        // Cause poison
        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 1L,
            cancelled = AtomicBoolean(false),
            onGranted = {
                poisonLatch.countDown()
                throw RuntimeException("Fatal failure")
            }
        )
        assertTrue(poisonLatch.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue(MpvNativeRuntime.isPoisonedForTesting())

        val newRequestRejected = AtomicInteger(-1)
        val newRequestLatch = CountDownLatch(1)

        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 2L,
            cancelled = AtomicBoolean(false),
            onGranted = {
                org.junit.Assert.fail("Request after poison must not be granted")
            },
            onRejected = { reason ->
                newRequestRejected.set(reason.ordinal)
                newRequestLatch.countDown()
            }
        )

        assertTrue(newRequestLatch.await(2, TimeUnit.SECONDS))
        assertEquals(RuntimeRejectReason.POISONED.ordinal, newRequestRejected.get())
        assertEquals(0, MpvNativeRuntime.getWaitingQueueSizeForTesting())
    }

    @Test
    fun testUnexpectedEphemeralTransactionEscape_PoisonsRuntimeAndFailsClosed() {
        val resultLatch = CountDownLatch(1)
        var finalResult: FinalProbeResult? = null

        val waiterRejected = AtomicInteger(-1)
        val waiterLatch = CountDownLatch(1)

        // Queue a waiter
        MpvNativeRuntime.runEphemeralTransaction(
            engineGeneration = 100L,
            cancelled = AtomicBoolean(false),
            onComplete = { res ->
                finalResult = res
                resultLatch.countDown()
            }
        ) { _ ->
            // Queue waiter while this ephemeral transaction is in progress
            MpvNativeRuntime.requestLongLivedOwner(
                engineGeneration = 101L,
                cancelled = AtomicBoolean(false),
                onGranted = {
                    org.junit.Assert.fail("Waiter should never run after ephemeral escape")
                },
                onRejected = { reason ->
                    waiterRejected.set(reason.ordinal)
                    waiterLatch.countDown()
                }
            )

            // Unexpected escape outside probe body cleanup
            throw IllegalStateException("Unexpected crash inside ephemeral body")
        }

        assertTrue(resultLatch.await(2, TimeUnit.SECONDS))
        assertTrue(waiterLatch.await(2, TimeUnit.SECONDS))

        assertNotNull(finalResult)
        assertTrue(finalResult!!.body.primary is ProbePrimaryResult.RuntimePoisoned)
        assertEquals(OwnerReleaseResult.NotAcquired, finalResult!!.ownerRelease)
        assertFalse(finalResult!!.isPass)

        assertEquals(RuntimeRejectReason.POISONED.ordinal, waiterRejected.get())
        assertTrue(MpvNativeRuntime.isPoisonedForTesting())
    }

    @Test
    fun testNormalEphemeralFailure_ReleasesOwnerAndAllowsNextTransaction() {
        val firstResultLatch = CountDownLatch(1)
        var firstResult: FinalProbeResult? = null

        val secondResultLatch = CountDownLatch(1)
        var secondResult: FinalProbeResult? = null

        // First transaction handles expected error inside body and returns ProbeBodyResult
        MpvNativeRuntime.runEphemeralTransaction(
            engineGeneration = 200L,
            cancelled = AtomicBoolean(false),
            onComplete = { res ->
                firstResult = res
                firstResultLatch.countDown()
            }
        ) { _ ->
            // Expected failure caught and returned cleanly
            ProbeBodyResult(
                primary = ProbePrimaryResult.OptionRejected(ProbeStep.SET_OPTION, "vo", -1),
                teardown = ProbeTeardownResult.Success
            )
        }

        // Second transaction queued behind first
        MpvNativeRuntime.runEphemeralTransaction(
            engineGeneration = 201L,
            cancelled = AtomicBoolean(false),
            onComplete = { res ->
                secondResult = res
                secondResultLatch.countDown()
            }
        ) { _ ->
            ProbeBodyResult(
                primary = ProbePrimaryResult.Success,
                teardown = ProbeTeardownResult.Success
            )
        }

        assertTrue(firstResultLatch.await(2, TimeUnit.SECONDS))
        assertTrue(secondResultLatch.await(2, TimeUnit.SECONDS))

        assertNotNull(firstResult)
        assertTrue(firstResult!!.body.primary is ProbePrimaryResult.OptionRejected)
        assertEquals(OwnerReleaseResult.Released, firstResult!!.ownerRelease)
        assertFalse(firstResult!!.isPass)

        assertNotNull(secondResult)
        assertTrue(secondResult!!.body.primary is ProbePrimaryResult.Success)
        assertEquals(OwnerReleaseResult.Released, secondResult!!.ownerRelease)
        assertTrue(secondResult!!.isPass)

        assertFalse(MpvNativeRuntime.isPoisonedForTesting())
    }

    @Test
    fun testCancelInQueue_RemovesRequestAndInvokesOnRejectedWithCancelled() {
        val activeLatch = CountDownLatch(1)
        var activeToken: MpvOwnerToken? = null

        // Hold active owner
        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 1L,
            cancelled = AtomicBoolean(false),
            onGranted = { token ->
                activeToken = token
                activeLatch.countDown()
            }
        )
        assertTrue(activeLatch.await(2, TimeUnit.SECONDS))

        val cancelRejected = AtomicInteger(-1)
        val cancelLatch = CountDownLatch(1)

        val reqId = MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 2L,
            cancelled = AtomicBoolean(false),
            onGranted = {
                org.junit.Assert.fail("Cancelled request must not be granted")
            },
            onRejected = { reason ->
                cancelRejected.set(reason.ordinal)
                cancelLatch.countDown()
            }
        )

        assertEquals(1, MpvNativeRuntime.getWaitingQueueSizeForTesting())

        // Cancel the queued request
        MpvNativeRuntime.cancel(reqId)

        assertTrue(cancelLatch.await(2, TimeUnit.SECONDS))
        assertEquals(RuntimeRejectReason.CANCELLED.ordinal, cancelRejected.get())
        assertEquals(0, MpvNativeRuntime.getWaitingQueueSizeForTesting())

        MpvNativeRuntime.finishOwnerOnRuntime(activeToken!!)
    }

    @Test
    fun testPreGrantCancellation_InvokesCancelledImmediately() {
        val cancelRejected = AtomicInteger(-1)
        val cancelLatch = CountDownLatch(1)

        val cancelled = AtomicBoolean(true)
        MpvNativeRuntime.requestLongLivedOwner(
            engineGeneration = 1L,
            cancelled = cancelled,
            onGranted = {
                org.junit.Assert.fail("Pre-cancelled request must not be granted")
            },
            onRejected = { reason ->
                cancelRejected.set(reason.ordinal)
                cancelLatch.countDown()
            }
        )

        assertTrue(cancelLatch.await(2, TimeUnit.SECONDS))
        assertEquals(RuntimeRejectReason.CANCELLED.ordinal, cancelRejected.get())
    }
}
