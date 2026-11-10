package org.stypox.dicio.skills.connectivity

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Connectivity

private const val API_36 = 36
private const val API_28 = 28

private val PRESENT_OFF = BluetoothState(present = true, enabled = false)
private val PRESENT_ON = BluetoothState(present = true, enabled = true)
private val PRESENT_UNKNOWN = BluetoothState(present = true, enabled = null)

private fun plan(
    radio: Radio,
    state: RadioState,
    sdkInt: Int = API_36,
    bluetooth: BluetoothState = PRESENT_OFF,
    canRequestBluetooth: Boolean = true,
) = ConnectivityLogic.plan(radio, state, sdkInt, bluetooth, canRequestBluetooth)

private fun open(vararg options: Pair<ActivityIntent, ConnectivityResult>) =
    ConnectivityPlan.Open(options.toList())

private fun sentence(input: String): Connectivity =
    Sentences.Connectivity["en"]!!.score(FakeSkillContext().forInput(input), input).second

class ConnectivityTest : StringSpec({
    "the intents' actions" {
        ConnectivityLogic.INTERNET_PANEL shouldBe
                ActivityIntent("android.settings.panel.action.INTERNET_CONNECTIVITY")
        ConnectivityLogic.WIFI_SETTINGS shouldBe ActivityIntent("android.settings.WIFI_SETTINGS")
        ConnectivityLogic.REQUEST_BLUETOOTH shouldBe
                ActivityIntent("android.bluetooth.adapter.action.REQUEST_ENABLE")
        ConnectivityLogic.BLUETOOTH_SETTINGS shouldBe
                ActivityIntent("android.settings.BLUETOOTH_SETTINGS")
    }

    "Wi-Fi opens the internet panel, or Wi-Fi settings before API 29" {
        plan(Radio.WIFI, RadioState.ON) shouldBe open(
            ConnectivityLogic.INTERNET_PANEL to ConnectivityResult.WIFI_ON_PANEL,
            ConnectivityLogic.WIFI_SETTINGS to ConnectivityResult.WIFI_SETTINGS,
        )
        plan(Radio.WIFI, RadioState.OFF) shouldBe open(
            ConnectivityLogic.INTERNET_PANEL to ConnectivityResult.WIFI_OFF_PANEL,
            ConnectivityLogic.WIFI_SETTINGS to ConnectivityResult.WIFI_SETTINGS,
        )
        plan(Radio.WIFI, RadioState.SETTINGS) shouldBe open(
            ConnectivityLogic.INTERNET_PANEL to ConnectivityResult.WIFI_PANEL,
            ConnectivityLogic.WIFI_SETTINGS to ConnectivityResult.WIFI_SETTINGS,
        )
        plan(Radio.WIFI, RadioState.ON, sdkInt = API_28) shouldBe
                open(ConnectivityLogic.WIFI_SETTINGS to ConnectivityResult.WIFI_SETTINGS)
    }

    "Bluetooth on asks the system, falling back to Bluetooth settings" {
        plan(Radio.BLUETOOTH, RadioState.ON) shouldBe open(
            ConnectivityLogic.REQUEST_BLUETOOTH to ConnectivityResult.BLUETOOTH_ASKED,
            ConnectivityLogic.BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_ON_SETTINGS,
        )
        plan(Radio.BLUETOOTH, RadioState.ON, bluetooth = PRESENT_UNKNOWN) shouldBe open(
            ConnectivityLogic.REQUEST_BLUETOOTH to ConnectivityResult.BLUETOOTH_ASKED,
            ConnectivityLogic.BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_ON_SETTINGS,
        )
        plan(Radio.BLUETOOTH, RadioState.ON, canRequestBluetooth = false) shouldBe
                open(ConnectivityLogic.BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_ON_SETTINGS)
        plan(Radio.BLUETOOTH, RadioState.ON, bluetooth = PRESENT_ON) shouldBe
                ConnectivityPlan.Answer(ConnectivityResult.BLUETOOTH_ALREADY_ON)
    }

    "Bluetooth off and settings open Bluetooth settings" {
        plan(Radio.BLUETOOTH, RadioState.OFF, bluetooth = PRESENT_ON) shouldBe
                open(ConnectivityLogic.BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_OFF_SETTINGS)
        plan(Radio.BLUETOOTH, RadioState.OFF, bluetooth = PRESENT_UNKNOWN) shouldBe
                open(ConnectivityLogic.BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_OFF_SETTINGS)
        plan(Radio.BLUETOOTH, RadioState.OFF, bluetooth = PRESENT_OFF) shouldBe
                ConnectivityPlan.Answer(ConnectivityResult.BLUETOOTH_ALREADY_OFF)
        plan(Radio.BLUETOOTH, RadioState.SETTINGS, bluetooth = PRESENT_ON) shouldBe
                open(ConnectivityLogic.BLUETOOTH_SETTINGS to ConnectivityResult.BLUETOOTH_SETTINGS)
    }

    "a phone without Bluetooth says so" {
        for (state in RadioState.entries) {
            plan(Radio.BLUETOOTH, state, bluetooth = BluetoothState(false, null)) shouldBe
                    ConnectivityPlan.Answer(ConnectivityResult.NO_BLUETOOTH)
        }
    }

    "a match needs a word naming the radio or the network" {
        for (input in listOf(
            "switch off the wifi", "turn on wi fi", "open network settings", "disable blue tooth",
            "turn bluetooth on", "connect to the internet",
        )) {
            namesConnectivity(input) shouldBe true
        }
        // Dicio's matcher let these through with one word of the sentence left out
        for (input in listOf("switch off the music", "turn on the lights", "switch the tv off")) {
            namesConnectivity(input) shouldBe false
        }
    }

    "sentences map to radio and state" {
        ConnectivityLogic.radioAndState(Connectivity.WifiOff) shouldBe Pair(Radio.WIFI, RadioState.OFF)
        ConnectivityLogic.radioAndState(Connectivity.BluetoothSettings) shouldBe
                Pair(Radio.BLUETOOTH, RadioState.SETTINGS)
    }

    "the English sentences" {
        sentence("turn on wifi") shouldBe Connectivity.WifiOn
        sentence("turn the wi fi off") shouldBe Connectivity.WifiOff
        sentence("disconnect from the wifi") shouldBe Connectivity.WifiOff
        sentence("show me the available wifi networks") shouldBe Connectivity.WifiSettings
        sentence("open wi fi settings") shouldBe Connectivity.WifiSettings
        sentence("turn on bluetooth") shouldBe Connectivity.BluetoothOn
        sentence("switch bluetooth off") shouldBe Connectivity.BluetoothOff
        sentence("bluetooth settings") shouldBe Connectivity.BluetoothSettings
        sentence("pair a new bluetooth device") shouldBe Connectivity.BluetoothSettings
    }
})
