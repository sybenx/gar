package org.stypox.dicio.gar.stt

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.stypox.dicio.R
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.ui.home.MessageCard
import org.stypox.dicio.ui.theme.AppTheme
import javax.inject.Inject
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import org.stypox.dicio.gar.ui.garble.GarbleTextButton // GAR

/**
 * The home screen's one-time offer to download the Whisper speech model (the lead's decision for
 * an unset input device: Vosk stays until a Whisper model exists, then the device resolver picks
 * Whisper). Shown until the user dismisses it or a Whisper model was on the phone once.
 */
object GarSttOffer {
    /**
     * Whether the card shows: not dismissed, no model yet, and Whisper would be used (see
     * [GarSttModelManager.whisperWanted]). [hidden] and [wanted] are null until read, so that
     * the card doesn't flash.
     */
    fun shows(hidden: Boolean?, installed: Boolean, wanted: Boolean?): Boolean =
        hidden == false && !installed && wanted == true

    /** Whether to hide the card for good: the model is on the phone now. */
    fun hidesForGood(hidden: Boolean?, installed: Boolean): Boolean =
        hidden == false && installed
}

/** The card's setting in [GarSettings], apart from the view model so that tests can run it. */
class GarSttOfferStore(private val dataStore: DataStore<GarSettings>) {
    val hidden: Flow<Boolean> = dataStore.data.map { it.sttOfferHidden }

    suspend fun hide() {
        dataStore.updateData {
            if (it.sttOfferHidden) it else it.toBuilder().setSttOfferHidden(true).build()
        }
    }
}

@HiltViewModel
class GarSttOfferViewModel @Inject constructor(
    dataStore: DataStore<GarSettings>,
    userSettings: DataStore<UserSettings>,
    localeManager: LocaleManager,
    private val models: GarSttModelManager,
) : ViewModel() {
    private val store = GarSttOfferStore(dataStore)

    /** Null until the settings are read. */
    val hidden: StateFlow<Boolean?> = store.hidden
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Null until the settings are read. */
    val wanted: StateFlow<Boolean?> =
        combine(userSettings.data, localeManager.sentencesLanguage) { user, language ->
            GarSttModelManager.whisperWanted(user.inputDevice, language)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val installed: StateFlow<Boolean> = models.installed
    val transfer: StateFlow<SttTransfer> = models.transfer

    /** Bytes the download fetches, null until the settings are read. */
    val downloadSize: StateFlow<Long?> = dataStore.data
        .map { SttModelCatalog.downloadSize(it.sttModel) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun download() = models.download()

    fun hide() {
        viewModelScope.launch { store.hide() }
    }
}

/**
 * Offers once to download the speech model, on the home screen (one GAR line in Dicio's
 * `InteractionList`, below the assistant card). Shows nothing once dismissed or downloaded, and
 * in previews.
 */
@Composable
fun GarSttOfferCard() {
    if (LocalInspectionMode.current) {
        return // previews have no Hilt
    }
    val viewModel: GarSttOfferViewModel = hiltViewModel()
    val hidden by viewModel.hidden.collectAsState()
    val wanted by viewModel.wanted.collectAsState()
    val installed by viewModel.installed.collectAsState()
    val transfer by viewModel.transfer.collectAsState()
    val downloadSize by viewModel.downloadSize.collectAsState()

    LaunchedEffect(hidden, installed) {
        if (GarSttOffer.hidesForGood(hidden, installed)) {
            viewModel.hide()
        }
    }

    if (GarSttOffer.shows(hidden, installed, wanted)) {
        GarSttOfferCardImpl(
            transfer = transfer,
            downloadSize = downloadSize,
            onDownload = viewModel::download,
            onDismiss = viewModel::hide,
        )
    }
}

/** The card itself, in the style of GAR's assistant card. */
@Composable
fun GarSttOfferCardImpl(
    transfer: SttTransfer,
    downloadSize: Long?,
    onDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.gar_stt_offer_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.gar_stt_offer_text),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when (transfer) {
                is SttTransfer.Running -> stringResource(
                    R.string.gar_status_downloading,
                    if (transfer.totalBytes > 0) {
                        (transfer.currentBytes * 100 / transfer.totalBytes).toInt()
                    } else {
                        0
                    },
                )
                is SttTransfer.Failed -> stringResource(R.string.gar_error_download_failed)
                SttTransfer.Idle -> stringResource(R.string.gar_stt_settings_privacy)
            },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GarbleTextButton(onClick = onDismiss) { // GAR
                Text(text = stringResource(R.string.gar_assistant_card_dismiss))
            }
            GarbleSecondaryButton( // GAR
                onClick = onDownload,
                enabled = transfer !is SttTransfer.Running,
            ) {
                Text(
                    text = downloadSize
                        ?.let {
                            stringResource(
                                R.string.gar_stt_offer_download,
                                Formatter.formatShortFileSize(context, it),
                            )
                        }
                        ?: stringResource(R.string.gar_pref_download)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Preview
@Composable
private fun GarSttOfferCardPreview() {
    AppTheme {
        GarSttOfferCardImpl(
            transfer = SttTransfer.Idle,
            downloadSize = SttModelCatalog.downloadSize(SttModelCatalog.BASE_EN.setting!!),
            onDownload = {},
            onDismiss = {},
        )
    }
}
