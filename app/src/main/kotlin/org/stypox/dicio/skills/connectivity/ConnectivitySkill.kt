package org.stypox.dicio.skills.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.sentences.Sentences.Connectivity
import org.stypox.dicio.util.checkPermissions

class ConnectivitySkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Connectivity>,
) : StandardRecognizerSkill<Connectivity>(correspondingSkillInfo, data) {

    // Dicio scores a sentence with a word left out above its thresholds, so "switch off the music"
    // matched "switch off the wifi": a match needs a word naming the radio or the network
    override fun score(ctx: SkillContext, input: String): Pair<Score, Connectivity> {
        val (score, sentence) = super.score(ctx, input)
        val english = ctx.sentencesLanguage.substringBefore('-').substringBefore('_') == "en"
        return Pair(if (!english || namesConnectivity(input)) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Connectivity): SkillOutput {
        val (radio, state) = ConnectivityLogic.radioAndState(inputData)
        return ConnectivityOutput(execute(ctx.android, radio, state))
    }

    companion object {
        private const val TAG = "ConnectivitySkill"
        private val NO_STATE = BluetoothState(present = true, enabled = null)

        /**
         * Opens the system's controls for [radio] (or answers right away), as a command of this
         * skill does. Also used by the device settings skill for the model's Wi-Fi and Bluetooth
         * calls.
         */
        fun execute(context: Context, radio: Radio, state: RadioState): ConnectivityResult {
            val plan = ConnectivityLogic.plan(
                radio = radio,
                state = state,
                sdkInt = Build.VERSION.SDK_INT,
                bluetooth = if (radio == Radio.BLUETOOTH) bluetoothState(context) else NO_STATE,
                canRequestBluetooth = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                    checkPermissions(context, Manifest.permission.BLUETOOTH_CONNECT),
            )
            return when (plan) {
                is ConnectivityPlan.Answer -> plan.result
                is ConnectivityPlan.Open -> plan.options
                    .firstOrNull { (intent, _) -> intent.start(context) }
                    ?.second
                    ?: ConnectivityResult.OPEN_FAILED
            }
        }

        @SuppressLint("MissingPermission") // isEnabled needs no permission on API 31+, see below
        fun bluetoothState(context: Context): BluetoothState {
            val adapter = context.getSystemService<BluetoothManager>()?.adapter
                ?: return BluetoothState(present = false, enabled = null)
            val enabled = try {
                adapter.isEnabled
            } catch (e: SecurityException) {
                // API < 31 asks for the BLUETOOTH permission, which GAR doesn't declare
                Log.w(TAG, "Can't read the Bluetooth state", e)
                null
            }
            return BluetoothState(present = true, enabled = enabled)
        }
    }
}

private val CONNECTIVITY_NAMES = setOf(
    "wifi", "wireless", "internet", "network", "networks", "bluetooth", "hotspot",
)
private val CONNECTIVITY_PHRASES = listOf(listOf("wi", "fi"), listOf("blue", "tooth"))

/** Whether [input] names Wi-Fi, Bluetooth or the network, see [ConnectivitySkill.score]. */
internal fun namesConnectivity(input: String): Boolean {
    val words = Utterances.words(Utterances.normalize(input))
    return words.any { it in CONNECTIVITY_NAMES } ||
        CONNECTIVITY_PHRASES.any { phrase -> words.windowed(phrase.size).any { it == phrase } }
}
