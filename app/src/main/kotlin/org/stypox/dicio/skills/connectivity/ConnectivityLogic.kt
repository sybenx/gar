package org.stypox.dicio.skills.connectivity

import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.provider.Settings
import org.stypox.dicio.sentences.Sentences.Connectivity

enum class Radio { WIFI, BLUETOOTH }

enum class RadioState { ON, OFF, SETTINGS }

/** What the user hears after a connectivity command. */
enum class ConnectivityResult {
    WIFI_ON_PANEL,
    WIFI_OFF_PANEL,
    WIFI_PANEL,
    WIFI_SETTINGS,
    BLUETOOTH_ASKED,
    BLUETOOTH_ALREADY_ON,
    BLUETOOTH_ALREADY_OFF,
    BLUETOOTH_ON_SETTINGS,
    BLUETOOTH_OFF_SETTINGS,
    BLUETOOTH_SETTINGS,
    NO_BLUETOOTH,
    OPEN_FAILED,
}

/** What to do for a command: answer right away, or open the first page that opens. */
sealed interface ConnectivityPlan {
    data class Answer(val result: ConnectivityResult) : ConnectivityPlan

    /** Tries each intent in order; the result of the one that opened is said. */
    data class Open(val options: List<Pair<ActivityIntent, ConnectivityResult>>) : ConnectivityPlan
}

/**
 * The Bluetooth adapter as the skill sees it: [present] false on phones without Bluetooth,
 * [enabled] null if Android didn't say.
 */
data class BluetoothState(val present: Boolean, val enabled: Boolean?)

/**
 * Android doesn't let apps switch Wi-Fi (since API 29) or turn Bluetooth on or off silently
 * (since API 33), so the skill opens the system's own controls: the internet panel for Wi-Fi, the
 * system's "turn on Bluetooth?" dialog, and Bluetooth settings for the rest.
 */
object ConnectivityLogic {
    val INTERNET_PANEL = ActivityIntent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
    val WIFI_SETTINGS = ActivityIntent(Settings.ACTION_WIFI_SETTINGS)
    val REQUEST_BLUETOOTH = ActivityIntent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    val BLUETOOTH_SETTINGS = ActivityIntent(Settings.ACTION_BLUETOOTH_SETTINGS)

    fun radioAndState(sentence: Connectivity): Pair<Radio, RadioState> = when (sentence) {
        Connectivity.WifiOn -> Pair(Radio.WIFI, RadioState.ON)
        Connectivity.WifiOff -> Pair(Radio.WIFI, RadioState.OFF)
        Connectivity.WifiSettings -> Pair(Radio.WIFI, RadioState.SETTINGS)
        Connectivity.BluetoothOn -> Pair(Radio.BLUETOOTH, RadioState.ON)
        Connectivity.BluetoothOff -> Pair(Radio.BLUETOOTH, RadioState.OFF)
        Connectivity.BluetoothSettings -> Pair(Radio.BLUETOOTH, RadioState.SETTINGS)
    }

    /**
     * @param canRequestBluetooth whether the "turn on Bluetooth?" dialog may be requested
     * (BLUETOOTH_CONNECT is granted on API 31+)
     */
    fun plan(
        radio: Radio,
        state: RadioState,
        sdkInt: Int,
        bluetooth: BluetoothState,
        canRequestBluetooth: Boolean,
    ): ConnectivityPlan = when (radio) {
        Radio.WIFI -> {
            val panelResult = when (state) {
                RadioState.ON -> ConnectivityResult.WIFI_ON_PANEL
                RadioState.OFF -> ConnectivityResult.WIFI_OFF_PANEL
                RadioState.SETTINGS -> ConnectivityResult.WIFI_PANEL
            }
            if (sdkInt >= Build.VERSION_CODES.Q) {
                ConnectivityPlan.Open(
                    listOf(
                        INTERNET_PANEL to panelResult,
                        WIFI_SETTINGS to ConnectivityResult.WIFI_SETTINGS,
                    )
                )
            } else {
                ConnectivityPlan.Open(listOf(WIFI_SETTINGS to ConnectivityResult.WIFI_SETTINGS))
            }
        }

        Radio.BLUETOOTH -> when {
            !bluetooth.present -> ConnectivityPlan.Answer(ConnectivityResult.NO_BLUETOOTH)
            state == RadioState.ON && bluetooth.enabled == true ->
                ConnectivityPlan.Answer(ConnectivityResult.BLUETOOTH_ALREADY_ON)
            state == RadioState.OFF && bluetooth.enabled == false ->
                ConnectivityPlan.Answer(ConnectivityResult.BLUETOOTH_ALREADY_OFF)
            state == RadioState.ON -> ConnectivityPlan.Open(
                listOfNotNull(
                    (REQUEST_BLUETOOTH to ConnectivityResult.BLUETOOTH_ASKED)
                        .takeIf { canRequestBluetooth },
                    BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_ON_SETTINGS,
                )
            )
            state == RadioState.OFF -> ConnectivityPlan.Open(
                listOf(BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_OFF_SETTINGS)
            )
            else -> ConnectivityPlan.Open(
                listOf(BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_SETTINGS)
            )
        }
    }
}
