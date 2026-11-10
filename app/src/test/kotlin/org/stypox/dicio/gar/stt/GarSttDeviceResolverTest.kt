package org.stypox.dicio.gar.stt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_EXTERNAL_POPUP
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_NOTHING
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_UNSET
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_VOSK
import org.stypox.dicio.settings.datastore.InputDevice.INPUT_DEVICE_WHISPER
import org.stypox.dicio.settings.datastore.InputDevice.UNRECOGNIZED
import java.util.Locale

private fun resolve(
    setting: InputDevice,
    english: Boolean = true,
    whisper: Boolean = false,
    vosk: Boolean = false,
) = GarSttDeviceResolver.resolve(setting, english, whisper) { vosk }

class GarSttDeviceResolverTest : StringSpec({
    "an explicit choice stays" {
        for (setting in listOf(INPUT_DEVICE_WHISPER, INPUT_DEVICE_VOSK,
                INPUT_DEVICE_EXTERNAL_POPUP, INPUT_DEVICE_NOTHING)) {
            resolve(setting) shouldBe setting
            resolve(setting, english = false, whisper = true, vosk = true) shouldBe setting
        }
    }

    "UNSET is Vosk for other languages" {
        resolve(INPUT_DEVICE_UNSET, english = false, whisper = true) shouldBe INPUT_DEVICE_VOSK
        resolve(UNRECOGNIZED, english = false) shouldBe INPUT_DEVICE_VOSK
    }

    "UNSET is Whisper once its files are there, even with Vosk unzipped" {
        resolve(INPUT_DEVICE_UNSET, whisper = true, vosk = true) shouldBe INPUT_DEVICE_WHISPER
    }

    "UNSET keeps an unzipped Vosk until Whisper is installed" {
        resolve(INPUT_DEVICE_UNSET, vosk = true) shouldBe INPUT_DEVICE_VOSK
    }

    "UNSET on a new install is Whisper, so Vosk is never downloaded" {
        resolve(INPUT_DEVICE_UNSET) shouldBe INPUT_DEVICE_WHISPER
        resolve(UNRECOGNIZED) shouldBe INPUT_DEVICE_WHISPER
    }

    "the flow follows the locale and the Whisper files" {
        val locale = MutableStateFlow(Locale.US)
        val installed = MutableStateFlow(false)
        var voskPresent = true
        val resolver = GarSttDeviceResolver(locale, { locale.value }, installed) { voskPresent }

        resolver.resolve(INPUT_DEVICE_UNSET) shouldBe INPUT_DEVICE_VOSK
        resolver.resolved(INPUT_DEVICE_UNSET).first() shouldBe INPUT_DEVICE_VOSK
        installed.value = true
        resolver.resolve(INPUT_DEVICE_UNSET) shouldBe INPUT_DEVICE_WHISPER
        resolver.resolved(INPUT_DEVICE_UNSET).first() shouldBe INPUT_DEVICE_WHISPER
        locale.value = Locale.GERMANY
        resolver.resolve(INPUT_DEVICE_UNSET) shouldBe INPUT_DEVICE_VOSK
        voskPresent = false
        locale.value = Locale.UK
        installed.value = false
        resolver.resolve(INPUT_DEVICE_UNSET) shouldBe INPUT_DEVICE_WHISPER
    }
})
