package org.stypox.dicio.ui.home

import org.stypox.dicio.gar.ui.garble.GarbleTextButton // GAR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue // GAR
import androidx.compose.runtime.mutableStateOf // GAR
import androidx.compose.runtime.saveable.rememberSaveable // GAR
import androidx.compose.runtime.setValue // GAR
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.ui.theme.AppTheme
import org.stypox.dicio.ui.util.SkillInfoPreviews

/** GAR: how many skills the home screen's card shows before "See all". */
const val GAR_HOME_SKILLS = 4

/**
 * GAR: [firstShown] skills are shown until the user taps "See all" (the home screen passes
 * [GAR_HOME_SKILLS] so its first screen stays short and its first frame cheap, gar/DESIGN.md,
 * "Frame rate"); the default shows every skill, as Dicio does.
 */
@Composable
fun WhatICanDo(skills: List<SkillInfo>, firstShown: Int = Int.MAX_VALUE) {
    var expanded by rememberSaveable { mutableStateOf(false) } // GAR
    MessageCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
        if (skills.isEmpty()) {
            NoEnabledSkills()

        } else {
            Text(
                text = stringResource(R.string.here_is_what_i_can_do),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )

            // GAR: only the first few until expanded
            val collapsible = skills.size > firstShown
            for (skill in if (collapsible && !expanded) skills.take(firstShown) else skills) {
                SkillRow(
                    skill = skill,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (collapsible) {
                GarbleTextButton( // GAR: neutral, not the accent
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = if (expanded) {
                            stringResource(R.string.gar_what_i_can_do_fewer)
                        } else {
                            stringResource(R.string.gar_what_i_can_do_all, skills.size)
                        },
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Preview
@Composable
private fun SkillRow(
    @PreviewParameter(SkillInfoPreviews::class) skill: SkillInfo,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = skill.icon(),
            contentDescription = null,
            modifier = Modifier.size(36.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = skill.name(LocalContext.current),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = skill.sentenceExample(LocalContext.current),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NoEnabledSkills() {
    Text(
        text = stringResource(R.string.all_skills_disabled_title),
        style = MaterialTheme.typography.headlineMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
    )
    Text(
        text = stringResource(R.string.all_skills_disabled_description),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    )
}

@Preview
@Composable
private fun WhatICanDoPreview() {
    AppTheme {
        WhatICanDo(skills = SkillInfoPreviews().values.toList())
    }
}

// this preview is useful to take screenshots for presentations
@Preview
@Composable
private fun WhatICanDoAllPreview() {
    AppTheme {
        WhatICanDo(skills = SkillHandler.newForPreviews(LocalContext.current).allSkillInfoList)
    }
}

// GAR: the home screen's collapsed card
@Preview
@Composable
private fun WhatICanDoFirstPreview() {
    AppTheme {
        WhatICanDo(
            skills = SkillHandler.newForPreviews(LocalContext.current).allSkillInfoList,
            firstShown = GAR_HOME_SKILLS,
        )
    }
}

@Preview
@Composable
private fun NoEnabledSkillsPreview() {
    AppTheme {
        WhatICanDo(skills = listOf())
    }
}

// this preview is useful to take screenshots for presentations
@Preview(device = "spec:width=2400px,height=2340px,dpi=440")
@Composable
private fun SkillChipsPreview() {
    AppTheme {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            val skills = SkillHandler.newForPreviews(LocalContext.current).allSkillInfoList
            for (skill in skills) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = skill.icon(),
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = skill.name(LocalContext.current),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

// this preview is useful to take screenshots for presentations
@Preview(device = "spec:width=500px,height=2340px,dpi=440")
@Composable
private fun SkillIconsPreview() {
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        horizontalArrangement = Arrangement.Center
    ) {
        val skills = SkillHandler.newForPreviews(LocalContext.current).allSkillInfoList
        for (skill in skills) {
            Icon(
                painter = skill.icon(),
                contentDescription = null,
                tint = Color(0xFF6D9861),
                modifier = Modifier.size(36.dp)
            )
        }
    }
}
