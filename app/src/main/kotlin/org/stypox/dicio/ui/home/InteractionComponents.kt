package org.stypox.dicio.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember // GAR
import androidx.compose.runtime.withFrameMillis // GAR
import androidx.compose.foundation.gestures.scrollBy // GAR
import androidx.compose.foundation.layout.Column // GAR
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.gar.ui.GarAnswerCard // GAR
import org.stypox.dicio.gar.ui.GarChip // GAR
import org.stypox.dicio.gar.ui.GarChipRow // GAR
import org.stypox.dicio.gar.ui.GarChips // GAR
import org.stypox.dicio.gar.ui.GarMicState // GAR
import org.stypox.dicio.gar.ui.GarThinkingCard // GAR
import org.stypox.dicio.gar.ui.GarUserBubble // GAR
import org.stypox.dicio.io.wake.WakeState
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.InteractionLogPreviews
import org.stypox.dicio.ui.util.SkillInfoPreviews
import org.stypox.dicio.ui.util.SkillOutputPreviews
import org.stypox.dicio.ui.util.UserInputPreviews
import org.stypox.dicio.ui.util.WakeStatesPreviews
import kotlin.math.absoluteValue

@Composable
fun InteractionList(
    skillContext: SkillContext,
    // will be null when skills have not been initialized yet
    skills: List<SkillInfo>?,
    interactionLog: InteractionLog,
    onConfirmedQuestionClick: (String) -> Unit,
    wakeState: WakeState?,
    onWakeDownload: () -> Unit,
    onWakeDisable: () -> Unit,
    modifier: Modifier = Modifier,
    // GAR: the thinking card's state (null: not thinking yet), and the latest answer's chips
    thinking: GarMicState.Thinking? = null,
    onChip: ((GarChip) -> Unit)? = null,
) {
    var totalItemCount = 0
    var lastAnchorIndex = 0
    fun LazyListScope.countedItem(
        canBeAnchor: Boolean,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        if (canBeAnchor) {
            lastAnchorIndex = totalItemCount
        }
        item(content = content)
        totalItemCount += 1
    }

    val interactions = interactionLog.interactions
    val pendingQuestion = interactionLog.pendingQuestion
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // GAR: the latest answer gets chips while nothing is pending; their item's index, to bring
    // it into view when it appears
    val latestAnswer = interactions.lastOrNull()?.questionsAnswers?.lastOrNull()
        ?.answer?.takeIf { pendingQuestion == null && onChip != null }
    val latestChips = remember(latestAnswer) {
        latestAnswer?.let { GarChips.forOutput(it, skillContext) } ?: listOf()
    }
    var chipsIndex = -1

    LazyColumn(
        modifier = modifier.testTag("interaction_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = listState,
    ) {
        // GAR: the first-run cards in one item, 12 dp apart (a card that shows nothing takes
        // no space)
        countedItem(canBeAnchor = false) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (wakeState != null) {
                    WakeWordWidget(
                        wakeState = wakeState,
                        onWakeDownload = onWakeDownload,
                        onWakeDisable = onWakeDisable,
                    )
                }

                // GAR: the card that asks once to make GAR the digital assistant
                org.stypox.dicio.gar.assist.GarAssistantCard()

                // GAR: the card that offers once to download the speech model (Whisper)
                org.stypox.dicio.gar.stt.GarSttOfferCard()

                // GAR: the card that asks once for the user's first name (Whisper's prompt)
                org.stypox.dicio.gar.stt.GarSttOwnNameCard()
            }
        }

        countedItem(canBeAnchor = false) {
            if (skills != null) {
                // avoid showing anything if skills are null, i.e. not ready yet
                WhatICanDo(skills = skills, firstShown = GAR_HOME_SKILLS) // GAR: a few first
            }
        }

        interactions.forEach { interaction ->
            countedItem(canBeAnchor = false) {
                // GAR: with the list's 8 dp on both sides, 24 dp between interactions
                Spacer(modifier = Modifier.height(8.dp))
            }
            interaction.questionsAnswers.forEach {
                if (it.question != null) {
                    // can be null if this QA contains only an error output
                    // (and therefore the error was not caused by the input, but e.g. by the STT)
                    countedItem(canBeAnchor = true) {
                        // GAR: the user's bubble, right-aligned; a tap edits it
                        GarUserBubble(
                            text = it.question,
                            pending = false,
                            onClick = { onConfirmedQuestionClick(it.question) },
                        )
                    }
                }
                countedItem(canBeAnchor = it.question == null) {
                    // GAR: GAR's answer card, with the skill on top unless only an error
                    GarAnswerCard(
                        skill = if (it.question != null) interaction.skill else null,
                        output = it.answer,
                        ctx = skillContext,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                val answer = it.answer // GAR
                if (answer === latestAnswer && latestChips.isNotEmpty()) {
                    chipsIndex = totalItemCount
                    countedItem(canBeAnchor = false) {
                        var tapped by remember(answer) { mutableStateOf(false) }
                        GarChipRow(
                            chips = latestChips,
                            enabled = !tapped,
                            onChip = { chip ->
                                tapped = true
                                onChip?.invoke(chip)
                            },
                        )
                    }
                }
            }
        }

        if (pendingQuestion != null) {
            if (interactions.isEmpty() || !pendingQuestion.continuesLastInteraction) {
                countedItem(canBeAnchor = false) {
                    Spacer(modifier = Modifier.height(8.dp)) // GAR: 24 dp, as above
                }
            }

            if (pendingQuestion.skillBeingEvaluated == null) {
                countedItem(canBeAnchor = true) {
                    // GAR: the bubble, in italics while still pending
                    GarUserBubble(text = pendingQuestion.userInput, pending = true, onClick = null)
                }
            } else {
                countedItem(canBeAnchor = true) {
                    GarUserBubble( // GAR
                        text = pendingQuestion.userInput,
                        pending = false,
                        onClick = { onConfirmedQuestionClick(pendingQuestion.userInput) },
                    )
                }
            }
            countedItem(canBeAnchor = false) {
                // GAR: the thinking card once the mic state says Thinking; until then a spacer of
                // about its height, so that when the pending question first appears and the
                // LaunchedEffect below scrolls to it, there is room for the card below it
                if (thinking != null) {
                    GarThinkingCard(state = thinking, modifier = Modifier.padding(horizontal = 16.dp))
                } else {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }

        countedItem(canBeAnchor = false) {
            // GAR: the mic sits in the Scaffold's bottom bar, which the list doesn't go under
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // TODO check if this causes performance problems
    var prevHasPending by rememberSaveable { mutableStateOf(false) }
    var prevInteractionCount by rememberSaveable { mutableIntStateOf(0) }
    val newHasPending = pendingQuestion != null
    val newInteractionCount = interactions.size
    LaunchedEffect(key1 = newHasPending, key2 = newInteractionCount) {
        if ((!prevHasPending && newHasPending) || newInteractionCount > prevInteractionCount) {
            scope.launch {
                listState.scrollToItem(lastAnchorIndex)
            }
        }
        prevHasPending = newHasPending
        prevInteractionCount = newInteractionCount
    }

    // GAR: when chips appear under the latest answer, bring them into view (after the scroll
    // above has been laid out), unless the user has scrolled above them
    LaunchedEffect(latestAnswer) {
        if (latestAnswer == null || latestChips.isEmpty()) {
            return@LaunchedEffect
        }
        withFrameMillis {}
        val index = chipsIndex
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@LaunchedEffect
        if (index < 0 || index < info.visibleItemsInfo.first().index) {
            return@LaunchedEffect
        }
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (item != null) {
            val overflow = item.offset + item.size - info.viewportEndOffset
            if (overflow > 0) {
                listState.scrollBy(overflow.toFloat())
            }
        } else if (index > lastVisible) {
            // put the chips at the bottom of the list's window
            listState.scrollToItem(index)
            val shown = listState.layoutInfo
            val size = shown.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
            listState.scrollBy(-(shown.viewportEndOffset - shown.viewportStartOffset - size).toFloat())
        }
    }
}

@Preview
@Composable
fun InteractionListPreview(
    @PreviewParameter(InteractionLogPreviews::class) interactionLog: InteractionLog,
) {
    val wakeStatesPreviews = WakeStatesPreviews().values.toList()
    AppTheme {
        InteractionList(
            skillContext = SkillContextImpl.newForPreviews(LocalContext.current),
            skills = SkillInfoPreviews().values.toList(),
            interactionLog = interactionLog,
            wakeState = wakeStatesPreviews[interactionLog.hashCode().absoluteValue % wakeStatesPreviews.size],
            onWakeDownload = {},
            onWakeDisable = {},
            onConfirmedQuestionClick = {},
            onChip = {}, // GAR: the latest answer's chips
        )
    }
}

@Preview
@Composable
fun PendingQuestionCard(@PreviewParameter(UserInputPreviews::class) userInput: String) {
    MessageCard(containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
            )
            Text(
                text = userInput,
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Normal),
            )
        }
    }
}

@Preview
@Composable
fun ConfirmedQuestionCard(
    @PreviewParameter(UserInputPreviews::class) userInput: String,
    onClick: (String) -> Unit = {},
) {
    MessageCard(
        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        onClick = { onClick(userInput) },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
            )
            Text(
                text = userInput,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Preview
@Composable
fun LoadingAnswerCard(@PreviewParameter(SkillInfoPreviews::class) skill: SkillInfo) {
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Icon(
                painter = skill.icon(),
                contentDescription = null,
                tint = ProgressIndicatorDefaults.circularColor,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            CircularProgressIndicator()
        }
    }
}

@Composable
fun SkillAnswerCard(content: @Composable BoxScope.() -> Unit) {
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            content = content,
        )
    }
}

@Preview
@Composable
fun SkillAnswerCardPreview(@PreviewParameter(SkillOutputPreviews::class) skillOutput: SkillOutput) {
    SkillAnswerCard {
        skillOutput.GraphicalOutput(ctx = SkillContextImpl.newForPreviews(LocalContext.current))
    }
}

@Composable
fun MessageCard(
    containerColor: Color,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp) // GAR: the home screen's 16 dp gutter
    if (onClick == null) {
        Card(colors = colors, modifier = modifier, content = content)
    } else {
        Card(onClick = onClick, modifier = modifier, colors = colors, content = content)
    }
}
