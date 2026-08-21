package ani.dantotsu.media.anime.player

import android.content.Context
import `is`.xyz.mpv.MPV
import java.util.concurrent.atomic.AtomicBoolean

object MpvDiagnosticProbes {

    fun runZeroOptionProbe(
        context: Context? = null,
        clientFactory: MpvClientFactory,
        runGeneration: Long = 0L,
        onComplete: (FinalProbeResult) -> Unit
    ): Long {
        val appContext = context?.applicationContext ?: context
        val cancelled = AtomicBoolean(false)

        return MpvNativeRuntime.runEphemeralTransaction(
            engineGeneration = runGeneration,
            cancelled = cancelled,
            onComplete = onComplete
        ) { _ ->
            val mpvClient = clientFactory.createFresh()
            var createReturned = false
            var primaryResult: ProbePrimaryResult = ProbePrimaryResult.Success
            var teardownResult: ProbeTeardownResult = ProbeTeardownResult.Success
            var currentStep = ProbeStep.CREATE

            try {
                nativeMarker("01 before nativeCreate")
                mpvClient.create(appContext)
                createReturned = true
                nativeMarker("02 after nativeCreate-return")

                currentStep = ProbeStep.INIT
                nativeMarker("03 before nativeInit (zero Dantotsu options)")
                mpvClient.init()
                nativeMarker("04 after nativeInit-return")

                currentStep = ProbeStep.SANITY
                nativeMarker("05 before post-init sanity query")
                val version = mpvClient.getPropertyString("mpv-version")
                if (version.isNullOrBlank()) {
                    nativeMarker("06 SANITY FAIL: mpv-version is null or blank")
                    primaryResult = ProbePrimaryResult.SanityFail(ProbeStep.SANITY, "mpv-version unavailable")
                } else {
                    nativeMarker("06 SANITY PASS: mpv-version=$version")
                }
            } catch (t: Throwable) {
                primaryResult = ProbePrimaryResult.JavaFailure(currentStep, t.javaClass.simpleName)
            } finally {
                if (createReturned) {
                    try {
                        nativeMarker("07 before nativeDestroy")
                        mpvClient.destroy()
                        nativeMarker("08 after nativeDestroy-return")
                    } catch (t: Throwable) {
                        teardownResult = ProbeTeardownResult.JavaFailure(ProbeStep.DESTROY, t.javaClass.simpleName)
                    }
                }
            }

            ProbeBodyResult(primaryResult, teardownResult)
        }
    }

    fun runSinglePrefixProbe(
        context: Context? = null,
        clientFactory: MpvClientFactory,
        effectivePreInitOptions: List<MpvInitOption>,
        prefixLength: Int,
        runGeneration: Long,
        onComplete: (FinalProbeResult) -> Unit
    ): Long {
        val appContext = context?.applicationContext ?: context
        val cancelled = AtomicBoolean(false)
        val currentPrefix = effectivePreInitOptions.take(prefixLength)

        return MpvNativeRuntime.runEphemeralTransaction(
            engineGeneration = runGeneration,
            cancelled = cancelled,
            onComplete = onComplete
        ) { _ ->
            val mpvClient = clientFactory.createFresh()
            var created = false
            var primaryResult: ProbePrimaryResult = ProbePrimaryResult.Success
            var teardownResult: ProbeTeardownResult = ProbeTeardownResult.Success
            var currentStep = ProbeStep.CREATE

            try {
                nativeMarker("01 before nativeCreate")
                mpvClient.create(appContext)
                created = true
                nativeMarker("02 after nativeCreate-return")

                currentStep = ProbeStep.SET_OPTION
                currentPrefix.forEachIndexed { optIndex, option ->
                    nativeMarker("OPT[$optIndex/${currentPrefix.size}] before ${option.name}")
                    applySetting(mpvClient, option)
                    verifySetting(mpvClient, option, VerificationTiming.IMMEDIATE)
                }

                currentStep = ProbeStep.INIT
                nativeMarker("03 before nativeInit")
                mpvClient.init()
                nativeMarker("04 after nativeInit-return")

                currentStep = ProbeStep.VERIFY_SETTING
                currentPrefix.forEach { option ->
                    verifySetting(mpvClient, option, VerificationTiming.AFTER_INIT)
                }

                currentStep = ProbeStep.SANITY
                nativeMarker("05 before post-init sanity query")
                val version = mpvClient.getPropertyString("mpv-version")
                if (version.isNullOrBlank()) {
                    nativeMarker("06 SANITY FAIL: mpv-version is null or blank")
                    primaryResult = ProbePrimaryResult.SanityFail(ProbeStep.SANITY, "mpv-version unavailable")
                } else {
                    nativeMarker("06 SANITY PASS: mpv-version=$version")
                }
            } catch (t: MpvOptionApplyException) {
                primaryResult = ProbePrimaryResult.OptionRejected(ProbeStep.SET_OPTION, t.optionName, t.rc)
            } catch (t: MpvSettingVerificationException) {
                primaryResult = ProbePrimaryResult.SettingVerificationFail(
                    step = ProbeStep.VERIFY_SETTING,
                    settingName = t.settingName,
                    timing = t.timing.name,
                    expected = t.expected,
                    actual = t.actual
                )
            } catch (t: Throwable) {
                primaryResult = ProbePrimaryResult.JavaFailure(currentStep, t.javaClass.simpleName)
            } finally {
                if (created) {
                    try {
                        nativeMarker("07 before nativeDestroy")
                        mpvClient.destroy()
                        nativeMarker("08 after nativeDestroy-return")
                    } catch (t: Throwable) {
                        teardownResult = ProbeTeardownResult.JavaFailure(ProbeStep.DESTROY, t.javaClass.simpleName)
                    }
                }
            }

            ProbeBodyResult(primaryResult, teardownResult)
        }
    }

    fun runFullBootstrapProbe(
        context: Context? = null,
        clientFactory: MpvClientFactory,
        settings: List<ResolvedInitSetting>,
        runGeneration: Long,
        onComplete: (FinalProbeResult) -> Unit
    ): Long {
        val appContext = context?.applicationContext ?: context
        val cancelled = AtomicBoolean(false)

        val preInitOptions = settings.filterIsInstance<ResolvedInitSetting.Apply>()
            .filter { it.phase == BootstrapPhase.PRE_INIT }
            .map { it.option }

        val postInitOptions = settings.filterIsInstance<ResolvedInitSetting.Apply>()
            .filter { it.phase == BootstrapPhase.POST_INIT }
            .map { it.option }

        return MpvNativeRuntime.runEphemeralTransaction(
            engineGeneration = runGeneration,
            cancelled = cancelled,
            onComplete = onComplete
        ) { _ ->
            val mpvClient = clientFactory.createFresh()
            var created = false
            var observerRegistered = false
            var primaryResult: ProbePrimaryResult = ProbePrimaryResult.Success
            var teardownResult: ProbeTeardownResult = ProbeTeardownResult.Success
            var currentStep = ProbeStep.CREATE

            val dummyObserver = object : MPV.EventObserver {
                override fun eventProperty(property: String) {}
                override fun eventProperty(property: String, value: Long) {}
                override fun eventProperty(property: String, value: Boolean) {}
                override fun eventProperty(property: String, value: String) {}
                override fun eventProperty(property: String, value: Double) {}
                override fun eventProperty(property: String, value: `is`.xyz.mpv.MPVNode) {}
                override fun event(eventId: Int, data: `is`.xyz.mpv.MPVNode) {}
            }

            try {
                nativeMarker("01 before nativeCreate")
                mpvClient.create(appContext)
                created = true
                nativeMarker("02 after nativeCreate-return")

                currentStep = ProbeStep.SET_OPTION
                preInitOptions.forEach { option ->
                    applySetting(mpvClient, option)
                    verifySetting(mpvClient, option, VerificationTiming.IMMEDIATE)
                }

                currentStep = ProbeStep.INIT
                nativeMarker("03 before nativeInit")
                mpvClient.init()
                nativeMarker("04 after nativeInit-return")

                currentStep = ProbeStep.VERIFY_SETTING
                preInitOptions.forEach { option ->
                    verifySetting(mpvClient, option, VerificationTiming.AFTER_INIT)
                }

                currentStep = ProbeStep.SANITY
                val version = mpvClient.getPropertyString("mpv-version")
                if (version.isNullOrBlank()) {
                    primaryResult = ProbePrimaryResult.SanityFail(ProbeStep.SANITY, "mpv-version unavailable")
                }

                currentStep = ProbeStep.POST_INIT
                postInitOptions.forEach { option ->
                    applySetting(mpvClient, option)
                    verifySetting(mpvClient, option, VerificationTiming.AFTER_BOOTSTRAP)
                }

                currentStep = ProbeStep.OBSERVER_REGISTER
                mpvClient.addObserver(dummyObserver)
                observerRegistered = true

                currentStep = ProbeStep.OBSERVER_UNREGISTER
                mpvClient.removeObserver(dummyObserver)
                observerRegistered = false

            } catch (t: MpvOptionApplyException) {
                primaryResult = ProbePrimaryResult.OptionRejected(currentStep, t.optionName, t.rc)
            } catch (t: MpvSettingVerificationException) {
                primaryResult = ProbePrimaryResult.SettingVerificationFail(
                    step = currentStep,
                    settingName = t.settingName,
                    timing = t.timing.name,
                    expected = t.expected,
                    actual = t.actual
                )
            } catch (t: Throwable) {
                primaryResult = ProbePrimaryResult.JavaFailure(currentStep, t.javaClass.simpleName)
            } finally {
                if (observerRegistered) {
                    try {
                        mpvClient.removeObserver(dummyObserver)
                    } catch (_: Throwable) {}
                }
                if (created) {
                    try {
                        nativeMarker("07 before nativeDestroy")
                        mpvClient.destroy()
                        nativeMarker("08 after nativeDestroy-return")
                    } catch (t: Throwable) {
                        teardownResult = ProbeTeardownResult.JavaFailure(ProbeStep.DESTROY, t.javaClass.simpleName)
                    }
                }
            }

            ProbeBodyResult(primaryResult, teardownResult)
        }
    }
}
