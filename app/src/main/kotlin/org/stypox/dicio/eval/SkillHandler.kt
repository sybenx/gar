package org.stypox.dicio.eval

import android.content.Context
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.di.LocaleManager
import org.stypox.dicio.di.SkillContextImpl
import org.stypox.dicio.di.SkillContextInternal
import org.stypox.dicio.settings.datastore.UserSettings
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.fallback.text.TextFallbackInfo
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.lyrics.LyricsInfo
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.notify.NotifyInfo
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.translation.TranslationInfo
import org.stypox.dicio.skills.weather.WeatherInfo
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.flashlight.FlashlightInfo
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillHandler @Inject constructor(
    private val dataStore: DataStore<UserSettings>,
    private val localeManager: LocaleManager,
    private val skillContext: SkillContextInternal,
) {
    // TODO improve id handling (maybe just use an int that can point to an Android resource)
    val allSkillInfoList = listOf(
        // gar:followup (first: it scores below any later whole sentence, see GarFollowUpSkill)
        org.stypox.dicio.gar.followup.GarFollowUpInfo,

        WeatherInfo,
        SearchInfo,
        LyricsInfo,
        OpenInfo,
        CalculatorInfo,
        NavigationInfo,
        TelephoneInfo,
        TimerInfo,
        CurrentTimeInfo,
        MediaInfo,
        JokeInfo,
        ListeningInfo(dataStore),
        TranslationInfo,
        NotifyInfo,
        FlashlightInfo,
        // GAR skills (Step 3): each group replaces only its own marker line
        // gar:alarm
        org.stypox.dicio.skills.alarm.AlarmInfo,

        org.stypox.dicio.skills.calendar.CalendarInfo,

        // gar:sms
        org.stypox.dicio.skills.sms.SmsInfo,

        // gar:device (qualified names, so the imports above stay as they are)
        org.stypox.dicio.skills.volume.VolumeInfo,
        org.stypox.dicio.skills.do_not_disturb.DoNotDisturbInfo,
        org.stypox.dicio.skills.connectivity.ConnectivityInfo,

        // gar:settings (English sentences; scores worst unless the input names its setting)
        org.stypox.dicio.skills.device_settings.DeviceSettingsInfo,

        // gar:clock (fully qualified, so the groups' imports don't conflict)
        org.stypox.dicio.skills.stopwatch.StopwatchInfo,
        org.stypox.dicio.skills.date.DateInfo,

        // gar:song
        org.stypox.dicio.skills.song.SongInfo,

        // gar:online
        org.stypox.dicio.skills.online_skills.OnlineSkillsInfo,

        // gar:conversation (not first: it scores worst for most inputs, see
        // GarOnlineSkills.forRanking)
        org.stypox.dicio.skills.conversation.ConversationInfo,

        // gar:answers (everyday offline answers, English only; each scores worst unless its
        // input makes sense to it)
        org.stypox.dicio.skills.battery.BatteryInfo,
        org.stypox.dicio.skills.world_time.WorldTimeInfo,
        org.stypox.dicio.skills.units.UnitsInfo,
        org.stypox.dicio.skills.math.MathInfo,
        org.stypox.dicio.skills.random.RandomInfo,
        org.stypox.dicio.skills.spelling.SpellingInfo,
        org.stypox.dicio.skills.holiday.HolidayInfo,
        org.stypox.dicio.skills.small_talk.SmallTalkInfo,

        // gar:timer (not first: it scores worst unless an input is about a timer, or stops the
        // clock app's ringing one)
        org.stypox.dicio.skills.timer.TimerControlInfo,

        // gar:calls (English only; each scores worst unless the whole input is one of its
        // requests)
        org.stypox.dicio.skills.calls.CallLogInfo,
        org.stypox.dicio.skills.calls.CallControlInfo,

        // gar:messages (English only; each scores worst unless the input is about messages or
        // says email)
        org.stypox.dicio.skills.messages.MessagesInfo,
        org.stypox.dicio.skills.messages.EmailInfo,

        // gar:media (English only: play by name, more media controls and the sleep timer; each
        // scores worst unless its own reading of the input takes it)
        org.stypox.dicio.skills.media.MediaPlayInfo,
        org.stypox.dicio.skills.media.MediaControlsInfo,

        // gar:places (English only: where am I, nearby, home and work, sharing the location,
        // websites; each scores worst unless its own reading of the input takes it)
        org.stypox.dicio.skills.places.PlacesInfo,
        org.stypox.dicio.skills.places.ShareLocationInfo,
        org.stypox.dicio.skills.website.WebsiteInfo,

        // gar:screen (English only: the camera's modes, screenshots, clearing notifications)
        org.stypox.dicio.skills.camera.CameraInfo,
        org.stypox.dicio.skills.screen.ScreenshotInfo,
        org.stypox.dicio.skills.screen.ClearNotificationsInfo,

        // gar:knowledge (English only, read in Kotlin; each scores worst unless the whole input
        // is one of its requests). Translation on the phone ranks with the other skills, before
        // Dicio's online translation; knowledge, dictionary and currency are online skills,
        // ranked last behind GAR's gate (GarOnlineSkills.forRanking)
        org.stypox.dicio.skills.offline_translator.OfflineTranslatorInfo,
        org.stypox.dicio.skills.knowledge.KnowledgeInfo,
        org.stypox.dicio.skills.dictionary.DictionaryInfo,
        org.stypox.dicio.skills.currency.CurrencyInfo,

        // gar:notes (English only: GAR's own lists, notes and remembered facts, the next event
        // and changes to events, GAR's reminders, contacts' details and new contacts; each
        // scores worst unless its own reading of the whole input takes it, and the facts skill
        // takes a question only when a remembered fact answers it)
        org.stypox.dicio.skills.notes.ListsInfo,
        org.stypox.dicio.skills.notes.NotesInfo,
        org.stypox.dicio.skills.notes.FactsInfo,
        org.stypox.dicio.skills.agenda.AgendaInfo,
        org.stypox.dicio.skills.agenda.RemindersInfo,
        org.stypox.dicio.skills.contact_info.ContactInfoInfo,
    )

    private val fallbackSkillInfoList = listOf(
        TextFallbackInfo,
    )

    private val scope = CoroutineScope(Dispatchers.Default)

    // will be null when it has not been initialized yet
    private val _enabledSkillsInfo: MutableStateFlow<List<SkillInfo>?> = MutableStateFlow(null)
    val enabledSkillsInfo: StateFlow<List<SkillInfo>?> = _enabledSkillsInfo

    private val _skillRanker = MutableStateFlow(
        // an initial dummy value, will be overwritten directly by the launched job
        SkillRanker(listOf(), fallbackSkillInfoList[0].build(skillContext)!!)
    )
    val skillRanker: StateFlow<SkillRanker> = _skillRanker

    init {
        scope.launch {
            localeManager.locale
                .combine(dataStore.data) { locale, data -> Pair(locale, data.enabledSkillsMap) }
                .distinctUntilChanged()
                .collectLatest { (_, enabledSkills) ->
                    // locale is not used here, because the skills directly use the sections locale

                    val newEnabledSkillsInfo = allSkillInfoList
                        .filter { enabledSkills.getOrDefault(it.id, true) }
                        .mapNotNull { info -> info.build(skillContext)?.let { skill -> Pair(info, skill) } }

                    _enabledSkillsInfo.value = newEnabledSkillsInfo.map { (info, _skill) -> info }
                    // GAR: the follow-ups' memory, forgotten on a change of language or skills
                    org.stypox.dicio.gar.followup.GarConversationMemory.enabled = newEnabledSkillsInfo
                        .any { (info, _) -> info.id == org.stypox.dicio.gar.followup.GarFollowUpInfo.id }
                    _skillRanker.value = SkillRanker(
                        // GAR: online skills behind GAR's input check, and a gate that asks once
                        // for each one that is off (gar/DESIGN.md, "Online skills")
                        org.stypox.dicio.gar.online.GarOnlineSkills.rankedSkills(
                            allSkillInfoList, enabledSkills, newEnabledSkillsInfo, skillContext),
                        fallbackSkillInfoList[0].build(skillContext)!!,
                    )
                }
        }
    }

    companion object {
        fun newForPreviews(context: Context): SkillHandler {
            return SkillHandler(
                UserSettingsModule.newDataStoreForPreviews(),
                LocaleManager.newForPreviews(context),
                SkillContextImpl.newForPreviews(context),
            )
        }
    }
}
