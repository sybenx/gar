package org.stypox.dicio.gar.sms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stypox.dicio.R
import org.stypox.dicio.gar.ui.garble.GarbleIconButton
import org.stypox.dicio.gar.ui.garble.GarblePrimaryButton
import org.stypox.dicio.gar.ui.garble.GarbleSecondaryButton
import org.stypox.dicio.gar.ui.garble.LocalGarbleColors
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsDraft

/**
 * True inside a card that already draws a title row for its output (the sheet's answer card), so
 * that [GarSmsConfirmCard] doesn't draw its own "Text message" row.
 */
val LocalGarCardHasTitle = staticCompositionLocalOf { false }

/**
 * The inside of the SMS confirm card (the approved Garble canvas): the contact's name (26sp),
 * "Mobile, from your contacts" muted, the message (21sp), then Send (the only accent), Edit and a
 * cancel circle. The sheet's answer card around it supplies "Text message" and "not sent yet";
 * elsewhere (GAR's conversation screen) the card draws that row itself.
 *
 * @param enabled whether the buttons work: while the card's conversation is the latest one
 */
@Composable
fun GarSmsConfirmCard(
    draft: SmsDraft,
    enabled: Boolean,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val g = LocalGarbleColors.current
    val context = LocalContext.current
    val recipient = draft.recipient
    val name = remember(recipient) {
        GarSmsRecipientLines.name(recipient) { GarSmsRecipientLines.formatNumber(context, it) }
    }
    // null until the contacts provider answered: the line then shows once, final, so that the
    // sheet's live region doesn't announce the card twice
    val line by produceState(GarSmsRecipientLines.cached(recipient), recipient) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { GarSmsRecipientLines.load(context, recipient) }
        }
    }
    val lineText = GarSmsRecipientLines.text(
        line ?: GarSmsRecipientLines.initial(recipient) {
            GarSmsRecipientLines.formatNumber(context, it)
        }
    )

    Column(modifier = modifier.fillMaxWidth()) {
        if (!LocalGarCardHasTitle.current) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
            ) {
                Icon(
                    imageVector = Icons.Filled.Sms,
                    contentDescription = null,
                    tint = g.text,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.gar_sheet_sms_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = g.text,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (draft.isPending) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.gar_sheet_sms_not_sent),
                        fontSize = 13.sp,
                        color = g.muted,
                        maxLines = 1,
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Text(
                text = name,
                fontSize = 26.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Medium,
                color = g.text,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(lineText.res, *lineText.args.toTypedArray()),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = g.muted,
                // keeps its height while loading, unseen and unread
                modifier = if (line == null) {
                    Modifier.alpha(0f).clearAndSetSemantics {}
                } else {
                    Modifier
                },
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = draft.message,
            fontSize = 21.sp,
            lineHeight = 28.sp,
            color = g.text,
        )

        SmsCardOutput.statusText(draft.state)?.let {
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = stringResource(it), fontSize = 14.sp, lineHeight = 20.sp, color = g.muted)
        }

        Spacer(modifier = Modifier.height(20.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            GarblePrimaryButton(
                onClick = onSend,
                enabled = enabled && draft.isPending,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.skill_sms_send), fontSize = 16.sp)
            }
            GarbleSecondaryButton(
                onClick = onEdit,
                enabled = enabled && draft.canEdit,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.skill_sms_edit), fontSize = 16.sp)
            }
            GarbleIconButton(
                icon = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.skill_sms_cancel),
                onClick = onDiscard,
                enabled = enabled && draft.isPending,
            )
        }
    }
}
