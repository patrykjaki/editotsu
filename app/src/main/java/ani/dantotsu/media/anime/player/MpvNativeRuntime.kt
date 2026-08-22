package ani.dantotsu.media.anime.player

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import androidx.annotation.VisibleForTesting
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class ProbeStep {
    ACQUIRE_OWNER,
    CREATE,
    SET_OPTION,
    INIT,
    VERIFY_SETTING,
    SANITY,
    POST_INIT,
    OBSERVER_REGISTER,
    OBSERVER_UNREGISTER,
    DESTROY,
    RELEASE_OWNER,
    RUNTIME_TRANSACTION
}

enum class RuntimeRejectReason {
    CANCELLED,
    POISONED
}

sealed interface ProbePrimaryResult {
    data object Success : ProbePrimaryResult
    data object Cancelled : ProbePrimaryResult
    data object RuntimePoisoned : ProbePrimaryResult
    data class OptionRejected(val step: ProbeStep, val optionName: String, val rc: Int) : ProbePrimaryResult
    data class SettingVerificationFail(
        val step: ProbeStep,
        val settingName: String,
        val timing: String,
        val expected: String? = null,
        val actual: String? = null
    ) : ProbePrimaryResult
    data class SanityFail(val step: ProbeStep, val reason: String) : ProbePrimaryResult
    data class JavaFailure(val step: ProbeStep, val type: String) : ProbePrimaryResult
}

sealed interface ProbeTeardownResult {
    data object Success : ProbeTeardownResult
    data class JavaFailure(val step: ProbeStep, val type: String) : ProbeTeardownResult
}

sealed interface OwnerReleaseResult {
    data object Released : OwnerReleaseResult
    data object NotAcquired : OwnerReleaseResult
    data class Mismatch(val expectedGeneration: Long, val actualGeneration: Long?) : OwnerReleaseResult
}

sealed interface OwnerDispatchResult {
    data object Enqueued : OwnerDispatchResult
    data object StaleOwner : OwnerDispatchResult
    data object Poisoned : OwnerDispatchResult
}

sealed interface OwnerCallResult<out T> {
    data class Executed<T>(val value: T) : OwnerCallResult<T>
    data object StaleOwner : OwnerCallResult<Nothing>
    data object Poisoned : OwnerCallResult<Nothing>
    data class OperationFailure(val error: PlaybackError) : OwnerCallResult<Nothing>
}

data class ProbeBodyResult(
    val primary: ProbePrimaryResult,
    val teardown: ProbeTeardownResult
)

data class FinalProbeResult(
    val body: ProbeBodyResult,
    val ownerRelease: OwnerReleaseResult
) {
    val isPass: Boolean
        get() = body.primary is ProbePrimaryResult.Success &&
                body.teardown is ProbeTeardownResult.Success &&
                ownerRelease is OwnerReleaseResult.Released
}


private enum class StartOutcome {
    STARTED,
    CONSUMED_CANCELLED,
    CONSUMED_FAILED,
    NOT_STARTED
}

class MpvOwnerToken internal constructor(val generation: Long)

data class OwnershipRequest(
    val requestId: Long,
    val engineGeneration: Long,
    val cancelled: AtomicBoolean,
    val onGranted: (MpvOwnerToken) -> Unit,
    val onRejected: ((RuntimeRejectReason) -> Unit)? = null
)

fun nativeMarker(message: String) {
    try {
        android.util.Log.e("EditotsuMPV", message)
    } catch (_: Throwable) {
        System.err.println("EditotsuMPV: $message")
    }
}

private fun logRuntimeError(message: String, cause: Throwable? = null) {
    try {
        if (cause != null) {
            android.util.Log.e("EditotsuMPV", message, cause)
        } else {
            android.util.Log.e("EditotsuMPV", message)
        }
    } catch (_: Throwable) {
        if (cause != null) {
            System.err.println("EditotsuMPV: $message - ${cause.message}")
        } else {
            System.err.println("EditotsuMPV: $message")
        }
    }
}

interface MpvRuntimeDispatcher {
    fun post(action: () -> Unit)
    fun isRuntimeThread(): Boolean
    fun postToMain(action: () -> Unit)
    fun shutdown() {}
}

class HandlerRuntimeDispatcher : MpvRuntimeDispatcher {
    private val thread = HandlerThread("MpvNativeRuntime").apply { start() }
    private val handler: Handler
    private val mainHandler: Handler

    init {
        val looper = thread.looper ?: throw IllegalStateException("HandlerThread looper is null")
        handler = Handler(looper)
        mainHandler = try {
            val mainLooper = Looper.getMainLooper() ?: looper
            Handler(mainLooper)
        } catch (_: Throwable) {
            handler
        }
    }

    override fun post(action: () -> Unit) {
        handler.post(action)
    }

    override fun isRuntimeThread(): Boolean = Looper.myLooper() == thread.looper

    override fun postToMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    override fun shutdown() {
        thread.quitSafely()
    }
}

class ExecutorRuntimeDispatcher : MpvRuntimeDispatcher {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MpvNativeRuntime").apply { isDaemon = true }
    }
    @Volatile private var runtimeThread: Thread? = null

    init {
        executor.submit { runtimeThread = Thread.currentThread() }
    }

    override fun post(action: () -> Unit) {
        executor.execute(action)
    }

    override fun isRuntimeThread(): Boolean = Thread.currentThread() == runtimeThread

    override fun postToMain(action: () -> Unit) {
        action()
    }

    override fun shutdown() {
        executor.shutdown()
    }
}

object MpvNativeRuntime {
    @Volatile
    private var runtimeDispatcher: MpvRuntimeDispatcher = createDefaultDispatcher()

    private fun createDefaultDispatcher(): MpvRuntimeDispatcher {
        return try {
            HandlerRuntimeDispatcher()
        } catch (_: Throwable) {
            ExecutorRuntimeDispatcher()
        }
    }

    private val requestIds = AtomicLong(0L)

    // Accessed strictly on MpvNativeRuntime thread
    private var activeOwner: MpvOwnerToken? = null
    private val waitingQueue = ArrayDeque<OwnershipRequest>()
    private var ownerGeneration = 0L
    private var isPoisoned = false
    private var poisonCause: Throwable? = null

    fun isRuntimeThread(): Boolean = runtimeDispatcher.isRuntimeThread()

    fun post(action: () -> Unit) {
        runtimeDispatcher.post(action)
    }

    fun postWithOwner(token: MpvOwnerToken, action: () -> Unit): OwnerDispatchResult {
        if (isPoisoned) {
            return OwnerDispatchResult.Poisoned
        }
        runtimeDispatcher.post {
            if (isPoisoned) return@post
            if (activeOwner !== token) {
                logRuntimeError("MpvNativeRuntime: stale owner action dropped for token generation ${token.generation}, activeOwner=${activeOwner?.generation}")
                return@post
            }
            try {
                action()
            } catch (e: MpvOperationException) {
                logRuntimeError("MpvNativeRuntime: operation exception in postWithOwner (${e.operation}): ${e.message}")
            } catch (t: Throwable) {
                logRuntimeError("MpvNativeRuntime: unexpected exception in postWithOwner; poisoning runtime", t)
                poisonFromOwner(token, t)
            }
        }
        return OwnerDispatchResult.Enqueued
    }

    fun <T> executeWithOwner(token: MpvOwnerToken, block: () -> T): OwnerCallResult<T> {
        if (isPoisoned) {
            return OwnerCallResult.Poisoned
        }
        if (activeOwner !== token) {
            return OwnerCallResult.StaleOwner
        }
        return try {
            OwnerCallResult.Executed(block())
        } catch (e: MpvOperationException) {
            logRuntimeError("MpvNativeRuntime: operation exception in executeWithOwner (${e.operation}): ${e.message}")
            OwnerCallResult.OperationFailure(
                PlaybackError(
                    category = ErrorCategory.UNKNOWN,
                    message = e.message ?: "MPV operation failed"
                )
            )
        } catch (t: Throwable) {
            logRuntimeError("MpvNativeRuntime: unexpected exception in executeWithOwner; poisoning runtime", t)
            poisonFromOwner(token, t)
            OwnerCallResult.Poisoned
        }
    }

    fun poisonFromOwner(token: MpvOwnerToken, cause: Throwable) {
        if (!runtimeDispatcher.isRuntimeThread()) {
            runtimeDispatcher.post { poisonFromOwner(token, cause) }
            return
        }
        if (activeOwner === token) {
            poisonRuntime(cause)
        } else {
            logRuntimeError("MpvNativeRuntime: ignoring poison attempt from non-active owner token gen=${token.generation}, active=${activeOwner?.generation}", cause)
        }
    }

    fun requestLongLivedOwner(
        engineGeneration: Long,
        cancelled: AtomicBoolean,
        onGranted: (MpvOwnerToken) -> Unit,
        onRejected: ((RuntimeRejectReason) -> Unit)? = null
    ): Long {
        val reqId = requestIds.incrementAndGet()
        val request = OwnershipRequest(reqId, engineGeneration, cancelled, onGranted, onRejected)

        runtimeDispatcher.post {
            if (isPoisoned) {
                logRuntimeError("MpvNativeRuntime is poisoned; rejecting request $reqId")
                request.onRejected?.invoke(RuntimeRejectReason.POISONED)
                return@post
            }
            if (request.cancelled.get()) {
                request.onRejected?.invoke(RuntimeRejectReason.CANCELLED)
                return@post
            }
            if (activeOwner == null) {
                tryStart(request)
            } else {
                waitingQueue.addLast(request)
            }
        }
        return reqId
    }

    fun runEphemeralTransaction(
        engineGeneration: Long,
        cancelled: AtomicBoolean,
        onComplete: (FinalProbeResult) -> Unit,
        transaction: (MpvOwnerToken) -> ProbeBodyResult
    ): Long {
        return requestLongLivedOwner(
            engineGeneration = engineGeneration,
            cancelled = cancelled,
            onGranted = { token ->
                val bodyResult = try {
                    transaction(token)
                } catch (t: Throwable) {
                    logRuntimeError("Unexpected exception escaped ephemeral transaction; poisoning runtime", t)
                    poisonRuntime(t)
                    val poisonedResult = FinalProbeResult(
                        ProbeBodyResult(ProbePrimaryResult.RuntimePoisoned, ProbeTeardownResult.Success),
                        OwnerReleaseResult.NotAcquired
                    )
                    emitFinalResult(engineGeneration, poisonedResult, onComplete)
                    return@requestLongLivedOwner
                }

                finishOwnerOnRuntime(token) { releaseResult ->
                    val finalResult = FinalProbeResult(bodyResult, releaseResult)
                    emitFinalResult(engineGeneration, finalResult, onComplete)
                }
            },
            onRejected = { reason ->
                val primaryOutcome = when (reason) {
                    RuntimeRejectReason.CANCELLED -> ProbePrimaryResult.Cancelled
                    RuntimeRejectReason.POISONED -> ProbePrimaryResult.RuntimePoisoned
                }
                val rejectedResult = FinalProbeResult(
                    ProbeBodyResult(primaryOutcome, ProbeTeardownResult.Success),
                    OwnerReleaseResult.NotAcquired
                )
                emitFinalResult(engineGeneration, rejectedResult, onComplete)
            }
        )
    }

    private fun emitFinalResult(
        generation: Long,
        result: FinalProbeResult,
        onComplete: (FinalProbeResult) -> Unit
    ) {
        val marker = if (result.body.primary is ProbePrimaryResult.RuntimePoisoned) {
            "MPV_RUN_POISONED gen=$generation pass=false primary=RuntimePoisoned teardown=${result.body.teardown.javaClass.simpleName} owner=${result.ownerRelease.javaClass.simpleName}"
        } else {
            "MPV_RUN_FINAL gen=$generation pass=${result.isPass} primary=${result.body.primary.javaClass.simpleName} teardown=${result.body.teardown.javaClass.simpleName} owner=${result.ownerRelease.javaClass.simpleName}"
        }
        nativeMarker(marker)
        runtimeDispatcher.postToMain { onComplete(result) }
    }

    fun cancel(requestId: Long) {
        runtimeDispatcher.post {
            val iterator = waitingQueue.iterator()
            while (iterator.hasNext()) {
                val request = iterator.next()
                if (request.requestId == requestId) {
                    iterator.remove()
                    request.onRejected?.invoke(RuntimeRejectReason.CANCELLED)
                    return@post
                }
            }
        }
    }

    private fun poisonRuntime(cause: Throwable) {
        if (isPoisoned) return
        isPoisoned = true
        poisonCause = cause
        logRuntimeError("MpvNativeRuntime poisoned due to invariant violation", cause)

        while (waitingQueue.isNotEmpty()) {
            val pending = waitingQueue.removeFirst()
            pending.onRejected?.invoke(RuntimeRejectReason.POISONED)
        }
    }

    private fun tryStart(request: OwnershipRequest): StartOutcome {
        if (isPoisoned) {
            request.onRejected?.invoke(RuntimeRejectReason.POISONED)
            return StartOutcome.NOT_STARTED
        }
        if (request.cancelled.get()) {
            request.onRejected?.invoke(RuntimeRejectReason.CANCELLED)
            return StartOutcome.NOT_STARTED
        }
        if (activeOwner != null) return StartOutcome.NOT_STARTED

        val token = MpvOwnerToken(++ownerGeneration)
        activeOwner = token

        if (request.cancelled.get()) {
            finishOwnerOnRuntime(token)
            request.onRejected?.invoke(RuntimeRejectReason.CANCELLED)
            return StartOutcome.CONSUMED_CANCELLED
        }

        try {
            request.onGranted(token)
            return StartOutcome.STARTED
        } catch (t: Throwable) {
            logRuntimeError("Runtime transaction grant threw unexpected exception; poisoning runtime to fail closed", t)
            poisonRuntime(t)
            return StartOutcome.CONSUMED_FAILED
        }
    }

    private fun grantNext() {
        if (isPoisoned) return
        while (activeOwner == null && waitingQueue.isNotEmpty()) {
            val next = waitingQueue.removeFirst()
            if (next.cancelled.get()) {
                next.onRejected?.invoke(RuntimeRejectReason.CANCELLED)
                continue
            }
            when (tryStart(next)) {
                StartOutcome.STARTED,
                StartOutcome.CONSUMED_CANCELLED -> return
                StartOutcome.CONSUMED_FAILED -> return
                StartOutcome.NOT_STARTED -> continue
            }
        }
    }

    fun finishOwnerOnRuntime(token: MpvOwnerToken, onReleaseComplete: ((OwnerReleaseResult) -> Unit)? = null) {
        if (!runtimeDispatcher.isRuntimeThread()) {
            runtimeDispatcher.post { finishOwnerOnRuntime(token, onReleaseComplete) }
            return
        }
        val releaseResult = if (activeOwner === token) {
            activeOwner = null
            OwnerReleaseResult.Released
        } else {
            logRuntimeError("Ownership release mismatch: requested=${token.generation}, actual=${activeOwner?.generation}")
            OwnerReleaseResult.Mismatch(token.generation, activeOwner?.generation)
        }

        try {
            onReleaseComplete?.invoke(releaseResult)
        } finally {
            runtimeDispatcher.post { grantNext() }
        }
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        val latch = CountDownLatch(1)
        runtimeDispatcher.post {
            isPoisoned = false
            poisonCause = null
            activeOwner = null
            waitingQueue.clear()
            ownerGeneration = 0L
            latch.countDown()
        }
        latch.await()
    }

    @VisibleForTesting
    internal fun isPoisonedForTesting(): Boolean {
        var poisoned = false
        val latch = CountDownLatch(1)
        runtimeDispatcher.post {
            poisoned = isPoisoned
            latch.countDown()
        }
        latch.await()
        return poisoned
    }

    @VisibleForTesting
    internal fun getActiveOwnerForTesting(): MpvOwnerToken? {
        var owner: MpvOwnerToken? = null
        val latch = CountDownLatch(1)
        runtimeDispatcher.post {
            owner = activeOwner
            latch.countDown()
        }
        latch.await()
        return owner
    }

    @VisibleForTesting
    internal fun getWaitingQueueSizeForTesting(): Int {
        var size = 0
        val latch = CountDownLatch(1)
        runtimeDispatcher.post {
            size = waitingQueue.size
            latch.countDown()
        }
        latch.await()
        return size
    }
}
