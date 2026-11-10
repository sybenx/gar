package org.stypox.dicio.gar.stt

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.settings.datastore.InputDevice
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which speech input runs for Dicio's "Input method" setting (design 4.2). An explicit choice
 * stays; UNSET (and an unknown value) resolves to:
 * - Vosk for a non-English locale: the Whisper models are English only, and GAR routes English;
 * - Whisper once its files are installed;
 * - Vosk if its model is already unzipped (phones that used Vosk before Whisper), so nobody loses
 *   a working mic before downloading Whisper;
 * - otherwise Whisper, so new installs never download Vosk.
 *
 * The input device wrapper rebuilds the device when the result changes, and Settings shows it for
 * UNSET.
 */
@Singleton
class GarSttDeviceResolver(
    private val locale: Flow<Locale>,
    private val currentLocale: () -> Locale,
    private val whisperInstalled: StateFlow<Boolean>,
    private val voskModelPresent: () -> Boolean,
) {
    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        localeManager: LocaleManager,
        models: SttModels,
    ) : this(
        locale = localeManager.locale,
        currentLocale = { localeManager.locale.value },
        whisperInstalled = models.installed,
        voskModelPresent = { voskModelCheckFile(appContext.filesDir).isDirectory },
    )

    /** The device for [setting] now. */
    fun resolve(setting: InputDevice): InputDevice = resolve(
        setting = setting,
        english = isEnglish(currentLocale()),
        whisperInstalled = whisperInstalled.value,
        voskModelPresent = { voskModelPresent() },
    )

    /** [resolve] for [setting], again whenever the locale or the Whisper files change. */
    fun resolved(setting: InputDevice): Flow<InputDevice> =
        combine(locale, whisperInstalled) { locale, installed ->
            resolve(setting, isEnglish(locale), installed) { voskModelPresent() }
        }.distinctUntilChanged()

    /** What [resolve] depends on besides the setting: emits when the result may have changed. */
    val changes: Flow<Unit> =
        combine(locale, whisperInstalled) { _, _ -> }

    companion object {
        /** Vosk's own check that its model is unzipped (`VoskInputDevice.modelExistFileCheck`). */
        fun voskModelCheckFile(filesDir: File) = File(filesDir, "vosk-model/ivector")

        fun isEnglish(locale: Locale) = locale.language == "en"

        fun resolve(
            setting: InputDevice,
            english: Boolean,
            whisperInstalled: Boolean,
            voskModelPresent: () -> Boolean,
        ): InputDevice = when (setting) {
            InputDevice.INPUT_DEVICE_WHISPER,
            InputDevice.INPUT_DEVICE_VOSK,
            InputDevice.INPUT_DEVICE_EXTERNAL_POPUP,
            InputDevice.INPUT_DEVICE_NOTHING -> setting
            InputDevice.INPUT_DEVICE_UNSET,
            InputDevice.UNRECOGNIZED -> when {
                !english -> InputDevice.INPUT_DEVICE_VOSK
                whisperInstalled -> InputDevice.INPUT_DEVICE_WHISPER
                voskModelPresent() -> InputDevice.INPUT_DEVICE_VOSK
                else -> InputDevice.INPUT_DEVICE_WHISPER
            }
        }
    }
}

/** The input device that runs for [setting], as Settings > Input method shows it. */
@Composable
fun rememberResolvedInputDevice(setting: InputDevice): InputDevice {
    if (LocalInspectionMode.current) {
        // previews have no Hilt graph
        return GarSttDeviceResolver.resolve(setting, true, false) { false }
    }
    val appContext = LocalContext.current.applicationContext
    val resolver = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, GarSttEntryPoint::class.java)
            .sttDeviceResolver()
    }
    val resolved by remember(resolver, setting) { resolver.resolved(setting) }
        .collectAsState(initial = remember(resolver, setting) { resolver.resolve(setting) })
    return resolved
}
