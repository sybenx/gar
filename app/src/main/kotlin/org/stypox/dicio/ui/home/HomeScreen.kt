package org.stypox.dicio.ui.home

import androidx.activity.compose.LocalActivity // GAR
import androidx.compose.foundation.layout.Arrangement // GAR
import androidx.compose.foundation.layout.Column // GAR
import androidx.compose.foundation.layout.Row // GAR
import androidx.compose.foundation.layout.Spacer // GAR
import androidx.compose.foundation.layout.fillMaxWidth // GAR
import androidx.compose.foundation.layout.height // GAR
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size // GAR
import androidx.compose.material.icons.filled.Keyboard // GAR
import androidx.compose.material3.MaterialTheme // GAR
import androidx.compose.material3.Surface // GAR
import androidx.compose.runtime.LaunchedEffect // GAR
import androidx.compose.runtime.MutableState // GAR
import androidx.compose.runtime.State // GAR
import androidx.compose.runtime.rememberUpdatedState // GAR
import androidx.compose.ui.Alignment // GAR
import androidx.compose.ui.unit.dp // GAR
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.hilt.navigation.compose.hiltViewModel
import dev.shreyaspatil.permissionflow.compose.rememberPermissionFlowRequestLauncher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.gar.perf.GarJank // GAR
import org.stypox.dicio.gar.stt.rememberGarVoiceActivity // GAR
import org.stypox.dicio.gar.ui.GarChip // GAR
import org.stypox.dicio.gar.ui.GarChipRow // GAR
import org.stypox.dicio.gar.ui.GarChips // GAR
import org.stypox.dicio.gar.ui.GarMicIndicator // GAR
import org.stypox.dicio.gar.ui.GarMicState // GAR
import org.stypox.dicio.gar.ui.GarStateLabel // GAR
import org.stypox.dicio.gar.ui.garTapStopsSpeech // GAR
import org.stypox.dicio.gar.ui.rememberGarMicState // GAR
import org.stypox.dicio.gar.ui.rememberGarStarterChips // GAR
import org.stypox.dicio.gar.ui.tag // GAR
import org.stypox.dicio.io.input.VoiceActivity // GAR
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.ui.nav.SearchTopAppBar
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.InteractionLogPreviews
import org.stypox.dicio.ui.util.SkillInfoPreviews
import org.stypox.dicio.ui.util.SttStatesPreviews
import org.stypox.dicio.util.checkPermissions
import org.stypox.dicio.util.getNonGrantedSecurePermissions
import kotlin.math.abs

@Composable
fun HomeScreen(
    navigationIcon: @Composable () -> Unit,
    // GAR: set by the sheet's EXTRA_TYPE hand-off (GarAppHandOffs); cleared here once handled
    typeRequested: MutableState<Boolean> = remember { mutableStateOf(false) },
) {
    val channel = remember { Channel<Boolean>() }
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberPermissionFlowRequestLauncher { isGranted ->
        coroutineScope.launch {
            channel.send(isGranted.values.all { it })
        }
    }
    val context = LocalContext.current

    suspend fun requestPermissions(permissions: List<Permission>): Boolean {
        val nonGrantedSecurePermissions = getNonGrantedSecurePermissions(
                context,
                permissions.filterIsInstance<Permission.SecurePermission>()
        )
        if (nonGrantedSecurePermissions.isNotEmpty()) {
            // do not request secure permissions directly, it would be confusing, so ask explicitly
            // instead
            return false
        }

        val normalPermissions = permissions.filterIsInstance<Permission.NormalPermission>()
            .map { it.id }.toTypedArray()
        if (checkPermissions(context, *normalPermissions)) {
            // permissions already granted
            return true
        }

        // some permission is not already granted, need to request it
        launcher.launch(normalPermissions)
        return channel.receive()
    }

    val viewModel: HomeScreenViewModel = hiltViewModel()
    // keep assigning permissionRequester at every recomposition because `launcher` changes when
    // the activity is recreated (no rememberSaveable is available)
    viewModel.skillEvaluator.permissionRequester = ::requestPermissions

    val enabledSkillsInfo = viewModel.skillHandler.enabledSkillsInfo.collectAsState()
    val interactionsState = viewModel.skillEvaluator.state.collectAsState()
    val sttState = viewModel.sttInputDevice.uiState.collectAsState()
    val wakeState = viewModel.wakeDevice.state.collectAsState()

    // GAR: the mic's state, the starter chips and the sheet's typing hand-off (gar/DESIGN.md,
    // "Home and chips")
    val micState by rememberGarMicState()
    val starterChips by rememberGarStarterChips()
    var typeRequests by remember { mutableIntStateOf(0) }
    LaunchedEffect(typeRequested.value) {
        if (typeRequested.value) {
            typeRequested.value = false
            typeRequests += 1
        }
    }
    val activity = LocalActivity.current
    LaunchedEffect(micState.tag) {
        activity?.window?.let { GarJank.state(it, "mic", micState.tag) }
    }

    HomeScreen(
        skillContext = viewModel.skillContext,
        skills = enabledSkillsInfo.value,
        interactionLog = interactionsState.value,
        sttState = sttState.value,
        onSttClick = {
            // GAR: the voice log's start (ignored when the tap ends a capture)
            org.stypox.dicio.gar.voicelog.GarVoiceLog.listen(
                org.stypox.dicio.gar.voicelog.GarVoiceStart.MIC_BUTTON
            ) {
                viewModel.sttInputDevice.onClick(viewModel.skillEvaluator::processInputEvent)
            }
        },
        wakeState = wakeState.value,
        onWakeDownload = {
            viewModel.wakeDevice.download()
        },
        onWakeDisable = viewModel::disableWakeWord,
        onManualUserInput = {
            org.stypox.dicio.gar.voicelog.GarVoiceLog.hint( // GAR: the voice log's start
                org.stypox.dicio.gar.voicelog.GarVoiceStart.TYPED)
            viewModel.skillEvaluator.processInputEvent(InputEvent.Final(listOf(Pair(it, 1.0f))))
        },
        navigationIcon = navigationIcon,
        snackbarHost = {
            SnackbarHost(hostState = viewModel.snackbarHostState)
        },
        // GAR
        micState = micState,
        onStopSpeaking = viewModel.speechOutputDevice::stopSpeaking,
        starterChips = starterChips,
        onChip = { GarChips.send(viewModel.skillEvaluator, it) },
        typeRequests = typeRequests,
    )
}

@Composable
fun HomeScreen(
    skillContext: SkillContext,
    // will be null when skills have not been initialized yet
    skills: List<SkillInfo>?,
    interactionLog: InteractionLog,
    // if the STT state is null, it means the user disabled the STT
    sttState: SttState?,
    onSttClick: () -> Unit,
    wakeState: WakeState?,
    onWakeDownload: () -> Unit,
    onWakeDisable: () -> Unit,
    onManualUserInput: (String) -> Unit,
    navigationIcon: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    // GAR: the mic's state, the starter chips, chip taps, and requests to open the text field
    micState: GarMicState = GarMicState.Idle,
    onStopSpeaking: () -> Unit = {},
    starterChips: List<GarChip> = listOf(),
    onChip: (GarChip) -> Unit = {},
    typeRequests: Int = 0,
    voiceActivity: State<VoiceActivity?> = rememberGarVoiceActivity(),
) {
    var searchString by rememberSaveable { mutableStateOf<String?>(null) }
    // GAR: the keyboard button, and the sheet's hand-off to typing (EXTRA_TYPE)
    LaunchedEffect(typeRequests) {
        if (typeRequests > 0 && searchString == null) {
            searchString = ""
        }
    }
    val currentMicState by rememberUpdatedState(micState)

    Scaffold(
        topBar = {
            SearchTopAppBar(
                searchString = searchString,
                setSearchString = { searchString = it },
                onSearch = onManualUserInput,
                hint = stringResource(R.string.text_input_hint),
                title = {
                    Text(text = stringResource(R.string.app_name))
                },
                searchIcon = {
                    Icon(
                        imageVector = Icons.Default.QuestionAnswer,
                        contentDescription = stringResource(R.string.text_input_hint),
                    )
                },
                navigationIcon = navigationIcon,
            )
        },
        content = { paddingValues ->
            InteractionList(
                skillContext = skillContext,
                skills = skills,
                interactionLog = interactionLog,
                onConfirmedQuestionClick = {
                    searchString = it
                    // GAR: an edited transcript marks its turn in the voice log
                    org.stypox.dicio.gar.voicelog.GarVoiceLog.editing(it)
                },
                wakeState = wakeState,
                onWakeDownload = onWakeDownload,
                onWakeDisable = onWakeDisable,
                modifier = Modifier
                    .padding(paddingValues)
                    // GAR: while GAR speaks, a tap on the list only stops the speech
                    .garTapStopsSpeech(
                        speaking = { currentMicState == GarMicState.Speaking },
                        stopSpeaking = onStopSpeaking,
                    ),
                // GAR
                thinking = micState as? GarMicState.Thinking,
                onChip = onChip,
            )
        },
        // GAR: the mic, its state and the keyboard in a bottom bar (with the starter chips on an
        // empty home), instead of Dicio's floating button, which stays for the unavailable states
        bottomBar = {
            GarHomeBottomBar(
                sttState = sttState,
                micState = micState,
                voiceActivity = voiceActivity,
                starterChips = starterChips.takeIf {
                    interactionLog.interactions.isEmpty() && interactionLog.pendingQuestion == null
                } ?: listOf(),
                onChip = onChip,
                onSttClick = onSttClick,
                onStopSpeaking = onStopSpeaking,
                onType = { if (searchString == null) searchString = "" },
            )
        },
        snackbarHost = snackbarHost,
    )
}

/**
 * GAR: the home screen's bottom bar: the starter chips (when given), then the mic's state in a
 * pill over the 72 dp mic with the keyboard button next to it. In the unavailable states Dicio's
 * button takes the mic's place (it has the permission launcher and the download states). A tap
 * on the mic while GAR speaks only stops the speech.
 */
@Composable
private fun GarHomeBottomBar(
    sttState: SttState?,
    micState: GarMicState,
    voiceActivity: State<VoiceActivity?>,
    starterChips: List<GarChip>,
    onChip: (GarChip) -> Unit,
    onSttClick: () -> Unit,
    onStopSpeaking: () -> Unit,
    onType: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        if (starterChips.isNotEmpty()) {
            GarChipRow(
                chips = starterChips,
                enabled = true,
                onChip = onChip,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        val keyboardButton = @Composable {
            IconButton(onClick = onType) {
                Icon(
                    imageVector = Icons.Default.Keyboard,
                    contentDescription = stringResource(R.string.gar_mic_type),
                )
            }
        }
        if (micState is GarMicState.Unavailable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // if the STT state is null, it means the user disabled the STT
                if (sttState != null) {
                    SttFab(state = sttState, onClick = onSttClick)
                }
                keyboardButton()
            }
        } else {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                GarStateLabel(
                    state = micState,
                    announce = micState !is GarMicState.Listening && micState != GarMicState.Starting,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.size(48.dp)) // balances the keyboard button
                GarMicIndicator(
                    state = micState,
                    voiceActivity = voiceActivity,
                    onClick = {
                        if (micState == GarMicState.Speaking) onStopSpeaking() else onSttClick()
                    },
                )
                keyboardButton()
            }
        }
    }
}

@Preview
@Composable
private fun HomeScreenPreview(@PreviewParameter(InteractionLogPreviews::class) interactionLog: InteractionLog) {
    val sttStatesPreviews = remember { SttStatesPreviews().values.toList() }
    var i by remember { mutableIntStateOf(abs(interactionLog.hashCode())) }

    AppTheme {
        HomeScreen(
            skillContext = SkillContextImpl.newForPreviews(LocalContext.current),
            skills = SkillInfoPreviews().values.toList(),
            interactionLog = interactionLog,
            sttState = sttStatesPreviews[i % sttStatesPreviews.size],
            onSttClick = { i += 1 },
            wakeState = null,
            onWakeDownload = {},
            onWakeDisable = {},
            onManualUserInput = {},
            navigationIcon = {
                IconButton(onClick = {}) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = null,
                    )
                }
            },
            snackbarHost = {},
        )
    }
}
