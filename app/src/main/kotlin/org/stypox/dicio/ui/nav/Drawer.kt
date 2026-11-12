package org.stypox.dicio.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.garble.GarbleMark // GAR
import org.stypox.dicio.gar.ui.garble.GarbleMarkState // GAR
import org.stypox.dicio.gar.ui.garble.GarbleType // GAR
import org.stypox.dicio.gar.ui.garble.LocalGarbleColors // GAR
import androidx.compose.ui.unit.sp // GAR

@Composable
fun DrawerContent(
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onSpeechToTextPopupClick: () -> Unit,
    closeDrawer: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.widthIn(max = 280.dp)
    ) {
        DrawerHeader(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )

        DrawerItem(
            icon = Icons.Default.Settings,
            label = R.string.settings,
            onClick = {
                onSettingsClick()
                closeDrawer()
            },
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .testTag("settings_drawer_item"),
        )

        DrawerItem(
            icon = Icons.Default.RecordVoiceOver,
            label = R.string.stt_popup,
            onClick = {
                onSpeechToTextPopupClick()
                closeDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )

        DrawerItem(
            icon = Icons.Default.Info,
            label = R.string.about,
            onClick = {
                onAboutClick()
                closeDrawer()
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@Preview
@Composable
private fun DrawerContentPreview() {
    DrawerContent(onSettingsClick = {}, onAboutClick = {}, onSpeechToTextPopupClick = {}, closeDrawer = {})
}

@Preview
@Composable
private fun DrawerHeader(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer, // GAR: neutral card, was primary
        modifier = modifier.clearAndSetSemantics {}
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Column(
                modifier = Modifier.weight(1.0f)
            ) {
                Text(
                    text = GarbleType.WORDMARK, // GAR: the lowercase wordmark, was app_name
                    style = GarbleType.wordmark(28.sp), // GAR: was headlineMedium
                    color = LocalGarbleColors.current.text, // GAR: was onPrimary
                    maxLines = 1,
                )
                Text(
                    text = stringResource(R.string.drawer_header_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface, // GAR: was onPrimary
                )
            }

            // GAR: the Garble mark in its logo pose, was the launcher icon's glyph
            GarbleMark(GarbleMarkState.Logo, Modifier.size(48.dp))
        }
    }
}

@Composable
fun DrawerItem(
    icon: ImageVector,
    @StringRes label: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationDrawerItem(
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(label),
            )
        },
        label = {
            Text(
                text = stringResource(label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        selected = false,
        onClick = onClick,
        modifier = modifier,
    )
}
