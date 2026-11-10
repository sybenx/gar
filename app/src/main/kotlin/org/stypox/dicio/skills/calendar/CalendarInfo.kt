package org.stypox.dicio.skills.calendar

import android.Manifest
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.ui.ListSetting
import org.stypox.dicio.util.getNonGrantedPermissions

val PERMISSION_READ_CALENDAR = Permission.NormalPermission(
    name = R.string.perm_read_calendar,
    id = Manifest.permission.READ_CALENDAR,
)
val PERMISSION_WRITE_CALENDAR = Permission.NormalPermission(
    name = R.string.perm_write_calendar,
    id = Manifest.permission.WRITE_CALENDAR,
)

object CalendarInfo : SkillInfo("calendar") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_calendar)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_calendar)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Event)

    override val neededPermissions: List<Permission> =
        listOf(PERMISSION_READ_CALENDAR, PERMISSION_WRITE_CALENDAR)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Calendar[ctx.sentencesLanguage] ?: return null
        val yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage] ?: return null
        // dates and times are resolved with dicio-numbers
        if (ctx.parserFormatter == null) return null
        return CalendarSkill(CalendarInfo, data, yesNoData)
    }

    override val renderSettings: @Composable () -> Unit get() = @Composable {
        val context = LocalContext.current
        val dataStore = context.calendarSettingsDataStore
        val data by dataStore.data.collectAsState(SkillSettingsCalendarSerializer.defaultValue)
        val scope = rememberCoroutineScope()

        val granted = getNonGrantedPermissions(listOf(PERMISSION_READ_CALENDAR)).isEmpty()
        val calendars by produceState<List<CalendarEntry>?>(null, granted) {
            value = if (granted) {
                withContext(Dispatchers.IO) {
                    try {
                        CalendarStore.calendars(context.contentResolver)
                    } catch (e: Exception) {
                        listOf()
                    }
                }
            } else {
                null
            }
        }
        val writable = calendars?.let(CalendarChoice::writable).orEmpty()

        ListSetting(
            title = stringResource(R.string.pref_calendar_target),
            description = when {
                !granted -> stringResource(R.string.pref_calendar_target_no_permission)
                calendars != null && writable.isEmpty() ->
                    stringResource(R.string.pref_calendar_target_none_writable)
                else -> null
            },
            possibleValues = listOf(
                ListSetting.Value(
                    value = CalendarChoice.AUTOMATIC,
                    name = stringResource(R.string.pref_calendar_target_automatic),
                    description = calendars?.let(CalendarChoice::default)?.name,
                )
            ) + writable.map {
                ListSetting.Value(value = it.id, name = it.name, description = it.accountName)
            },
        ).Render(
            // a calendar that is gone or read-only now falls back to automatic, as in the skill
            value = data.targetCalendarId.takeIf { id -> writable.any { it.id == id } }
                ?: CalendarChoice.AUTOMATIC,
            onValueChange = { id ->
                scope.launch {
                    dataStore.updateData {
                        it.toBuilder().setTargetCalendarId(id).build()
                    }
                }
            },
        )
    }
}
