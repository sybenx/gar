package org.stypox.dicio.gar.stt

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import cc.aaro.gar.router.resolve.NameMatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stypox.dicio.R
import org.stypox.dicio.gar.GarKeepScreenOn
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarYou
import org.stypox.dicio.gar.settings.YouChoice
import org.stypox.dicio.settings.datastore.InputDevice
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.skills.sms.AndroidOwnContact
import org.stypox.dicio.skills.sms.OwnAnswer
import org.stypox.dicio.skills.sms.OwnContactAnswers
import org.stypox.dicio.ui.home.MessageCard
import org.stypox.dicio.ui.theme.AppTheme
import javax.inject.Inject
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton // GAR
import org.stypox.dicio.gar.ui.garble.GarbleTextButton // GAR

/**
 * "What's your first name?", asked once on the home screen so that Whisper's prompt starts with
 * the user's own name ([GarSttPromptNames]): on the Pixel a prompt without it turned "Call
 * Aaron." into "Call Youran". Typed, not spoken, since speech is what gets the name wrong.
 */
object GarSttOwnNameAsk {
    /**
     * Whether the card asks: not asked before, Whisper in use, the contacts read for the prompt
     * (the hint on and `READ_CONTACTS` granted) and no source gave the owner's name (GAR's own
     * contact, a typed name, the profile: "You", [GarYou]) and the phone not [locked]: over the
     * lock screen (a wake word opened GAR) anyone could answer it and see contact names. Null
     * values are not read yet, so the card doesn't flash.
     */
    fun shows(
        asked: Boolean?,
        whisperInUse: Boolean?,
        state: PromptNamesState?,
        locked: Boolean?,
    ): Boolean = locked == false && asked == false && whisperInUse == true &&
        state != null && state.read && state.owner == null

    /** Whether Whisper is the input device that runs ([GarSttDeviceResolver]) and installed. */
    fun whisperInUse(resolved: InputDevice, installed: Boolean): Boolean =
        resolved == InputDevice.INPUT_DEVICE_WHISPER && installed

    /** The typed name to save: trimmed, single spaces; null when it has no name in it. */
    fun typed(text: String): String? =
        text.trim().replace(WHITESPACE, " ").takeIf { ContactPromptNames.clean(it) != null }

    /**
     * The contacts that may be the user, called [name] or sharing a whole word with it ("Alex"
     * for Alex Herbert), at most [OwnContactAnswers.MAX_OPTIONS], to offer as GAR's own contact
     * (the one "text myself" uses). A contact only close to it ("Alec" for "Alex") is not offered.
     */
    fun contactOffer(name: String, contactNames: () -> List<String>): List<String> =
        when (val found = OwnContactAnswers.find(name, emptyList(), contactNames)) {
            is OwnAnswer.One -> if (found.sure) listOf(found.name) else emptyList()
            is OwnAnswer.Several -> found.names.filter { NameMatcher.sharesWord(name, it) }
            OwnAnswer.NotFound -> emptyList()
        }

    private val WHITESPACE = Regex("\\s+")
}

/**
 * The card's settings in [GarSettings], apart from the view model so that tests can run it. The
 * answers go through [GarYou.set], as in the "You" setting.
 */
class GarSttOwnNameStore(private val dataStore: DataStore<GarSettings>) {
    val asked: Flow<Boolean> = dataStore.data.map { it.sttOwnNameAsked }

    /** The typed name is who the user is ("You"); GAR doesn't ask again. */
    suspend fun save(name: String) {
        GarYou.set(dataStore, YouChoice.Name(name))
    }

    /** The user said the contact [name] is them, after typing their name. */
    suspend fun chooseContact(name: String) {
        GarYou.set(dataStore, YouChoice.Contact(name))
    }

    /** The user skipped the question: GAR doesn't ask again. */
    suspend fun skip() {
        dataStore.updateData {
            if (it.sttOwnNameAsked) it else it.toBuilder().setSttOwnNameAsked(true).build()
        }
    }
}

@HiltViewModel
class GarSttOwnNameViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<GarSettings>,
    userSettings: DataStore<UserSettings>,
    resolver: GarSttDeviceResolver,
    models: SttModels,
    promptNames: GarSttPromptNames,
) : ViewModel() {
    private val store = GarSttOwnNameStore(dataStore)

    /** Null until the settings are read. */
    val asked: StateFlow<Boolean?> = store.asked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Null until the settings are read. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val whisperInUse: StateFlow<Boolean?> = userSettings.data
        .map { it.inputDevice }
        .distinctUntilChanged()
        .flatMapLatest { resolver.resolved(it) }
        .combine(models.installed) { device, installed ->
            GarSttOwnNameAsk.whisperInUse(device, installed)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Null until the prompt's names were built once. */
    val state: StateFlow<PromptNamesState?> = promptNames.state

    private val lockChecks = MutableStateFlow(0)

    /**
     * Whether the phone is locked ([KeyguardManager.isDeviceLocked], so a swipe-only keyguard
     * isn't), read again at every unlock and whenever [checkLocked] is called; null until read.
     */
    val locked: StateFlow<Boolean?> = combine(GarKeepScreenOn.unlocks(context), lockChecks) { _, _ ->
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _offer = MutableStateFlow<List<OwnContactOffer>>(emptyList())

    /** The contacts offered as the user's own after they typed their name, empty when none. */
    val offer: StateFlow<List<OwnContactOffer>> = _offer

    init {
        // MainActivity's acquire builds them too; this covers a card shown before that
        promptNames.refreshIfStale()
        // the contacts offered are not left for the lock screen
        viewModelScope.launch {
            locked.collect { if (it != false) _offer.value = emptyList() }
        }
    }

    /** Reads the lock state again, e.g. when the screen comes back after the phone was locked. */
    fun checkLocked() {
        lockChecks.value += 1
    }

    fun save(text: String) {
        val name = GarSttOwnNameAsk.typed(text) ?: return
        viewModelScope.launch {
            val own = AndroidOwnContact(context, dataStore)
            val offer = withContext(Dispatchers.IO) {
                GarSttOwnNameAsk.contactOffer(name, own::contactNames)
                    .map { OwnContactOffer(it, own.card(it)?.numbers.orEmpty()) }
            }
            if (locked.value == false) {
                _offer.value = offer
            }
            store.save(name)
        }
    }

    fun skip() {
        viewModelScope.launch { store.skip() }
    }

    /** The user said the contact [name] is them: set as the "You" setting sets it. */
    fun chooseContact(name: String) {
        if (locked.value != false) {
            return
        }
        viewModelScope.launch {
            store.chooseContact(name)
            _offer.value = emptyList()
        }
    }

    fun declineContact() {
        _offer.value = emptyList()
    }
}

/**
 * Asks once for the user's first name, on the home screen (one GAR line in Dicio's
 * `InteractionList`, below the speech model offer); after a name that matches contacts, offers
 * to make one of them GAR's own contact. Shows nothing otherwise, over the lock screen, and in
 * previews.
 */
@Composable
fun GarSttOwnNameCard() {
    if (LocalInspectionMode.current) {
        return // previews have no Hilt
    }
    val viewModel: GarSttOwnNameViewModel = hiltViewModel()
    val asked by viewModel.asked.collectAsState()
    val whisperInUse by viewModel.whisperInUse.collectAsState()
    val state by viewModel.state.collectAsState()
    val offer by viewModel.offer.collectAsState()
    val locked by viewModel.locked.collectAsState()
    // the phone may have locked while GAR was in the background, and a wake word opens it again
    LifecycleResumeEffect(null) {
        viewModel.checkLocked()
        onPauseOrDispose {}
    }

    if (locked != false) {
        return
    } else if (offer.isNotEmpty()) {
        GarSttOwnContactOfferImpl(
            contacts = offer,
            onChoose = viewModel::chooseContact,
            onDecline = viewModel::declineContact,
        )
    } else if (GarSttOwnNameAsk.shows(asked, whisperInUse, state, locked)) {
        GarSttOwnNameCardImpl(onSave = viewModel::save, onSkip = viewModel::skip)
    }
}

/** The question with its text field, in the style of the speech model offer. */
@Composable
fun GarSttOwnNameCardImpl(onSave: (String) -> Unit, onSkip: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSave = GarSttOwnNameAsk.typed(text) != null
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.gar_stt_own_name_question),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.gar_stt_own_name_hint)) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { if (canSave) onSave(text) }),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GarbleTextButton(onClick = onSkip) { // GAR
                Text(text = stringResource(R.string.gar_stt_own_name_skip))
            }
            GarbleSecondaryButton(onClick = { onSave(text) }, enabled = canSave) { // GAR
                Text(text = stringResource(R.string.gar_stt_own_name_save))
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/** A contact offered as the user's own, with its numbers so that same-named contacts differ. */
data class OwnContactOffer(val name: String, val numbers: List<String>) {
    /** "435-555-0100", "435-555-0100, 435-555-0199", or "" without a number. */
    val numbersText: String get() = numbers.take(MAX_NUMBERS).joinToString(", ")

    companion object {
        const val MAX_NUMBERS = 2
    }
}

/** "Is Alex Herbert you?" after the name was saved: a button per contact, and No. */
@Composable
fun GarSttOwnContactOfferImpl(
    contacts: List<OwnContactOffer>,
    onChoose: (String) -> Unit,
    onDecline: () -> Unit,
) {
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (contacts.size == 1) {
                stringResource(R.string.gar_stt_own_contact_offer_one, contacts[0].name)
            } else {
                stringResource(R.string.gar_stt_own_contact_offer_several)
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )

        if (contacts.size == 1 && contacts[0].numbersText.isNotEmpty()) {
            Text(
                text = contacts[0].numbersText,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (contacts.size > 1) {
            for (contact in contacts) {
                GarbleTextButton( // GAR
                    onClick = { onChoose(contact.name) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = contact.name)
                        if (contact.numbersText.isNotEmpty()) {
                            Text(
                                text = contact.numbersText,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GarbleTextButton(onClick = onDecline) { // GAR
                Text(text = stringResource(R.string.gar_stt_own_contact_offer_no))
            }
            if (contacts.size == 1) {
                GarbleSecondaryButton(onClick = { onChoose(contacts[0].name) }) { // GAR
                    Text(text = stringResource(R.string.gar_stt_own_contact_offer_yes))
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Preview
@Composable
private fun GarSttOwnNameCardPreview() {
    AppTheme {
        GarSttOwnNameCardImpl(onSave = {}, onSkip = {})
    }
}

@Preview
@Composable
private fun GarSttOwnContactOfferPreview() {
    AppTheme {
        GarSttOwnContactOfferImpl(
            contacts = listOf(OwnContactOffer("Alex Herbert", listOf("435-555-0100"))),
            onChoose = {},
            onDecline = {},
        )
    }
}
