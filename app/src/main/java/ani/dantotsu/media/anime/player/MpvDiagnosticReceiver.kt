
package ani.dantotsu.media.anime.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MpvDiagnosticReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_RUN_ZERO_OPTION = "ani.dantotsu.RUN_ZERO_OPTION_PROBE"
        const val ACTION_RUN_PREFIX_PROBE = "ani.dantotsu.RUN_PREFIX_PROBE"
        const val ACTION_RUN_FULL_BOOTSTRAP = "ani.dantotsu.RUN_FULL_BOOTSTRAP_PROBE"
        const val EXTRA_PREFIX_LENGTH = "prefix_length"
        const val EXTRA_GENERATION = "generation"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val gen = intent.getLongExtra(EXTRA_GENERATION, System.currentTimeMillis())
        val clientFactory = RealMpvClientFactory()

        when (action) {
            ACTION_RUN_ZERO_OPTION -> {
                nativeMarker("MpvDiagnosticReceiver: received ACTION_RUN_ZERO_OPTION gen=$gen")
                MpvDiagnosticProbes.runZeroOptionProbe(
                    context = context,
                    clientFactory = clientFactory,
                    runGeneration = gen
                ) { result ->
                    nativeMarker("MpvDiagnosticReceiver: zero option probe result isPass=${result.isPass}")
                }
            }

            ACTION_RUN_PREFIX_PROBE -> {
                val prefixLength = intent.getIntExtra(EXTRA_PREFIX_LENGTH, 1)
                val allSettings = buildCurrentBootstrapSettings(context)
                val effectivePreInitOptions = allSettings
                    .filterIsInstance<ResolvedInitSetting.Apply>()
                    .filter { it.phase == BootstrapPhase.PRE_INIT }
                    .map { it.option }

                nativeMarker("MpvDiagnosticReceiver: received ACTION_RUN_PREFIX_PROBE prefix=$prefixLength/${effectivePreInitOptions.size} gen=$gen")
                MpvDiagnosticProbes.runSinglePrefixProbe(
                    context = context,
                    clientFactory = clientFactory,
                    effectivePreInitOptions = effectivePreInitOptions,
                    prefixLength = prefixLength,
                    runGeneration = gen
                ) { result ->
                    nativeMarker("MpvDiagnosticReceiver: prefix probe result prefix=$prefixLength isPass=${result.isPass}")
                }
            }

            ACTION_RUN_FULL_BOOTSTRAP -> {
                val allSettings = buildCurrentBootstrapSettings(context)
                nativeMarker("MpvDiagnosticReceiver: received ACTION_RUN_FULL_BOOTSTRAP gen=$gen")
                MpvDiagnosticProbes.runFullBootstrapProbe(
                    context = context,
                    clientFactory = clientFactory,
                    settings = allSettings,
                    runGeneration = gen
                ) { result ->
                    nativeMarker("MpvDiagnosticReceiver: full bootstrap probe result isPass=${result.isPass}")
                }
            }
        }
    }
}
