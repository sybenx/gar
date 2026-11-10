package org.stypox.dicio.gar

import cc.aaro.gar.router.tools.ToolCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeTypeOf
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.online.GarCheckedOnlineSkill
import org.stypox.dicio.gar.online.GarOnlineGateSkill
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Alarm
import org.stypox.dicio.sentences.Sentences.Listening
import org.stypox.dicio.settings.datastore.UserSettingsModule
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.alarm.AlarmInfo
import org.stypox.dicio.skills.alarm.AlarmSentenceMatch
import org.stypox.dicio.skills.calculator.CalculatorInfo
import org.stypox.dicio.skills.calendar.CalendarInfo
import org.stypox.dicio.skills.connectivity.ConnectivityInfo
import org.stypox.dicio.skills.current_time.CurrentTimeInfo
import org.stypox.dicio.skills.date.DateInfo
import org.stypox.dicio.skills.do_not_disturb.DoNotDisturbInfo
import org.stypox.dicio.skills.flashlight.FlashlightInfo
import org.stypox.dicio.skills.flashlight.FlashlightSkill
import org.stypox.dicio.skills.joke.JokeInfo
import org.stypox.dicio.skills.listening.ListeningInfo
import org.stypox.dicio.skills.lyrics.LyricsInfo
import org.stypox.dicio.skills.media.MediaInfo
import org.stypox.dicio.skills.navigation.NavigationInfo
import org.stypox.dicio.skills.notify.NotifyInfo
import org.stypox.dicio.skills.online_skills.OnlineSkillsInfo
import org.stypox.dicio.skills.open.OpenInfo
import org.stypox.dicio.skills.search.SearchInfo
import org.stypox.dicio.skills.sms.SmsInfo
import org.stypox.dicio.skills.song.SongInfo
import org.stypox.dicio.skills.stopwatch.StopwatchInfo
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.timer.TimerInfo
import org.stypox.dicio.skills.translation.TranslationInfo
import org.stypox.dicio.skills.volume.VolumeInfo
import org.stypox.dicio.skills.weather.WeatherInfo
import java.io.File

/**
 * Every English skill of the app (`SkillHandler.allSkillInfoList`), ranked like the evaluator does.
 * The flashlight's info needs the camera to build, so its skill is made directly.
 */
internal fun allEnglishSkills(): List<Skill<*>> {
    val ctx = FakeSkillContext()
    val infos = listOf(
        WeatherInfo, SearchInfo, LyricsInfo, OpenInfo, CalculatorInfo, NavigationInfo, TelephoneInfo,
        TimerInfo, CurrentTimeInfo, MediaInfo, JokeInfo,
        ListeningInfo(UserSettingsModule.newDataStoreForPreviews()), TranslationInfo, NotifyInfo,
        AlarmInfo, CalendarInfo, SmsInfo, VolumeInfo, DoNotDisturbInfo, ConnectivityInfo,
        org.stypox.dicio.skills.device_settings.DeviceSettingsInfo, // gar:settings
        StopwatchInfo, DateInfo, SongInfo, OnlineSkillsInfo,
        org.stypox.dicio.skills.conversation.ConversationInfo, // gar:conversation
        // gar:answers
        org.stypox.dicio.skills.battery.BatteryInfo,
        org.stypox.dicio.skills.world_time.WorldTimeInfo,
        org.stypox.dicio.skills.units.UnitsInfo, org.stypox.dicio.skills.math.MathInfo,
        org.stypox.dicio.skills.random.RandomInfo, org.stypox.dicio.skills.spelling.SpellingInfo,
        org.stypox.dicio.skills.holiday.HolidayInfo,
        org.stypox.dicio.skills.small_talk.SmallTalkInfo,
        // gar:timer (its score only reads the timers this process loaded: none in tests)
        org.stypox.dicio.skills.timer.TimerControlInfo,
        // gar:calls
        org.stypox.dicio.skills.calls.CallLogInfo, org.stypox.dicio.skills.calls.CallControlInfo,
        // gar:messages
        org.stypox.dicio.skills.messages.MessagesInfo, org.stypox.dicio.skills.messages.EmailInfo,
        // gar:media (nothing plays in tests: the listener isn't running)
        org.stypox.dicio.skills.media.MediaPlayInfo, org.stypox.dicio.skills.media.MediaControlsInfo,
        // gar:places
        org.stypox.dicio.skills.places.PlacesInfo, org.stypox.dicio.skills.places.ShareLocationInfo,
        org.stypox.dicio.skills.website.WebsiteInfo,
        // gar:screen
        org.stypox.dicio.skills.camera.CameraInfo, org.stypox.dicio.skills.screen.ScreenshotInfo,
        org.stypox.dicio.skills.screen.ClearNotificationsInfo,
        // gar:knowledge (online: ranked last by GarOnlineSkills.forRanking)
        org.stypox.dicio.skills.knowledge.KnowledgeInfo,
        org.stypox.dicio.skills.dictionary.DictionaryInfo,
        org.stypox.dicio.skills.currency.CurrencyInfo,
        // gar:notes (the facts skill only takes a question that a remembered fact answers: none
        // are remembered in tests, NotesRankingTest has some)
        org.stypox.dicio.skills.notes.ListsInfo, org.stypox.dicio.skills.notes.NotesInfo,
        org.stypox.dicio.skills.notes.FactsInfo, org.stypox.dicio.skills.agenda.AgendaInfo,
        org.stypox.dicio.skills.agenda.RemindersInfo,
        org.stypox.dicio.skills.contact_info.ContactInfoInfo,
    )
    return infos.map { info ->
        info.build(ctx) ?: throw AssertionError("${info.id} doesn't build in English")
    } + FlashlightSkill(FlashlightInfo, Sentences.Flashlight["en"]!!) +
        // gar:knowledge: Offline Translator isn't installed in tests (its info asks the phone)
        org.stypox.dicio.skills.offline_translator.OfflineTranslatorSkill(
            org.stypox.dicio.skills.offline_translator.OfflineTranslatorInfo,
            Sentences.Translation["en"]!!, isInstalled = { false },
        )
}

/**
 * The skills of a new install as the user's phone ranks them: GAR turns the online skills off
 * ([UserSettingsSerializer]), so each of them is behind GAR's gate, last
 * ([GarOnlineSkills.forRanking], as `SkillHandler` ranks them).
 */
private fun defaultEnglishSkills(): List<Skill<*>> =
    GarOnlineSkills.forRanking(allEnglishSkills(), UserSettingsSerializer.defaultValue.enabledSkillsMap)

private val ranker = SkillRanker(defaultEnglishSkills(), MockSkill(Specificity.LOW, 0.0f))

/** Every skill on, as after "turn on all online skills": the online ones behind GAR's check. */
private val rankerWithOnlineSkills =
    SkillRanker(GarOnlineSkills.forRanking(allEnglishSkills(), mapOf()), MockSkill(Specificity.LOW, 0.0f))

internal fun garRankingWinner(input: String): Pair<String?, Any?> = winner(input)

private fun best(input: String, ranker: SkillRanker = org.stypox.dicio.gar.ranker) =
    ranker.getBest(FakeSkillContext().forInput(input), input)

private fun winner(input: String): Pair<String?, Any?> {
    val best = best(input)
    return Pair(best?.skill?.correspondingSkillInfo?.id, best?.inputData)
}

private val catalogTools = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
).tools

/**
 * For each input, the skill Dicio picks and whether [GarMatchCheck] rejects that match, with
 * the running timers named [timers], ranked by the default skills and by all of them.
 */
private fun checkMatches(
    expected: List<Pair<String, String>>,
    rejected: Boolean,
    timers: List<String?> = listOf(),
    rankers: List<SkillRanker> = listOf(ranker, rankerWithOnlineSkills),
) {
    val state = GarMatchCheck.State(catalogTools, timers, ENGLISH_PARSER_FORMATTER)
    for (ranker in rankers) {
        for ((input, skill) in expected) {
            val skills = if (ranker === org.stypox.dicio.gar.ranker) "default" else "all"
            withClue("$input ($skills skills, timers $timers)") {
                val match = best(input, ranker)
                Pair(match?.skill?.correspondingSkillInfo?.id, match?.let {
                    GarMatchCheck.rejects(state, it, input)
                }) shouldBe Pair(skill, rejected)
            }
        }
    }
}

/**
 * Inputs that Dicio's timer cancel sentence ("silence|(shut|turn off)|quiet|mute the? .name.?
 * timer") scored above Dicio's thresholds with the timer word left out, so the timer took them
 * before GAR's model could. GAR's sentences (the alarm's, the listening skill's GAR lines) must
 * win them; Dicio's timer sentences stay as they are.
 */
class GarRankingTest : StringSpec({
    "every skill builds" {
        allEnglishSkills().size shouldBe 35 +
            1 + // gar:settings
            2 + // gar:calls
            2 + // gar:messages
            2 + // gar:media
            3 + // gar:places
            3 + // gar:screen
            4 + // gar:knowledge
            6 // gar:notes
    }

    "the microphone and the wake word go to the listening skill" {
        for ((input, start) in listOf(
            "mute the mic" to false,
            "mute my microphone" to false,
            "turn off the mic" to false,
            "turn off the microphone please" to false,
            "can you turn the microphone off" to false,
            "turn off the wake up word" to false,
            "disable the wake word" to false,
            "turn off the hotword" to false,
            "stop listening for hey dicio" to false,
            "stop listening" to false,
            "turn the mic back on" to true,
            "unmute the mic" to true,
            "turn on the wake word" to true,
            "start listening for the wake word" to true,
        )) {
            withClue(input) {
                val (skill, data) = winner(input)
                skill shouldBe "listening"
                if (start) data.shouldBeInstanceOf<Listening.Start>() else data.shouldBeInstanceOf<Listening.Stop>()
            }
        }
    }

    "turning off an alarm goes to the alarm skill" {
        for (input in listOf(
            "turn off tomorrow's alarm",
            "turn off the alarm for tomorrow",
            "switch off tonight's alarm",
            "turn off my alarm",
            "turn off my seven am alarm",
        )) {
            withClue(input) {
                val (skill, data) = winner(input)
                skill shouldBe AlarmInfo.id
                data.shouldBeInstanceOf<AlarmSentenceMatch>().sentence.shouldBeInstanceOf<Alarm.Cancel>()
            }
        }
    }

    "the timer keeps its own commands" {
        for (input in listOf(
            "stop the timer", "turn off the timer", "mute the timer", "silence the timer",
            "cancel the pasta timer", "shut off the egg timer", "set a timer for ten minutes",
        )) {
            withClue(input) { winner(input).first shouldBe TimerInfo.id }
        }
    }

    "other skills keep theirs" {
        for ((input, skill) in listOf(
            "turn off the flashlight" to FlashlightInfo.id,
            "mute the music" to VolumeInfo.id,
            "turn off do not disturb" to DoNotDisturbInfo.id,
            "turn off wifi" to ConnectivityInfo.id,
            "stop the stopwatch" to StopwatchInfo.id,
            "call mike" to TelephoneInfo.id,
            "set an alarm for seven am" to AlarmInfo.id,
            "delete all my alarms" to AlarmInfo.id,
        )) {
            withClue(input) { winner(input).first shouldBe skill }
        }
    }

    "the new listening lines take nothing else" {
        val inputs = listOf(
            // "mute" alone was Dicio's own "mute microphone?" before GAR's lines
            "turn it off", "turn off the tv", "mute the tv", "turn off the lights",
            "turn off the volume", "turn off the sound", "what's the word for dog",
            "wake me up at seven", "turn off my phone", "turn on the radio",
            "say the word", "call mike", "text mike", "turn off the alarm",
            // Dicio's matcher leaves a word out: "(turn|switch) off (the|my)? mic", "turn off the
            // wake up word" and "disable the wake word" took these before ListeningSkill checked
            // that the input names the microphone or the wake word
            "switch off the music", "switch the music off", "switch off the tv", "switch the tv off",
            "switch off the lights", "switch on the lights", "switch off the radio",
            "switch on the radio", "switch off my phone", "switch off the hot plate",
            "switch off the hot spot", "turn off my wake up alarm", "switch off the wakeup alarm",
            "change the wake word", "what's the wake word", "is my mic on",
        )
        val taken = inputs.associateWith { winner(it).first }.filterValues { it == "listening" }
        taken shouldBe mapOf()
    }

    "upstream's own listening inputs stay" {
        for ((input, start) in listOf(
            "mute" to false, "unmute" to true, "stop listening" to false, "start listening" to true,
            "turn off the microphone" to false, "turn on microphone" to true,
        )) {
            withClue(input) {
                val (skill, data) = winner(input)
                skill shouldBe "listening"
                if (start) data.shouldBeInstanceOf<Listening.Start>() else data.shouldBeInstanceOf<Listening.Stop>()
            }
        }
    }

    "the ranker has the skills of a new install, with the online skills behind gates" {
        val skills = defaultEnglishSkills()
        skills.filter { it !is GarOnlineGateSkill }.map { it.correspondingSkillInfo.id }.toSet() shouldBe
            allEnglishSkills().map { it.correspondingSkillInfo.id }.toSet() -
            GarOnlineSkill.ids.toSet()
        skills.takeLast(8).map { it.shouldBeInstanceOf<GarOnlineGateSkill>().online.id } shouldBe
            listOf("weather", "search", "lyrics", "Joke", "translation", "knowledge", "dictionary", "currency")
    }

    "Dicio matches that name none of their skill's keywords go to the model" {
        // the skill's own sentences, as people say them, still run
        checkMatches(rejected = false, expected = listOf(
            "turn on the flashlight" to "flashlight", "switch on the light" to "flashlight",
            "can you turn on the flashlight" to "flashlight",
            "what time is it" to "current_time", "read my notifications" to "notify",
            "read my notifications please" to "notify",
            "set an alarm for seven" to "alarm", "show my clock" to "alarm",
            "open the clock app" to "alarm", "cancel my next alarm" to "alarm",
            "turn off my seven am alarm" to "alarm", "delete all my alarms" to "alarm",
            "wake me up at seven" to "alarm",
            "turn on wifi" to "connectivity", "bluetooth settings" to "connectivity",
            "start the stopwatch" to "stopwatch", "new lap" to "stopwatch",
            "whats the date" to "date", "what day is it today" to "date",
            "what date is friday" to "date", "what is today" to "date",
            "turn off the microphone" to "listening", "mute mike" to "listening",
            "pause the music" to "media", "continue playing" to "media", "stop the video" to "media",
            "what is two plus two" to "calculator", "calculate 12 divided by 4" to "calculator",
        ))
        // one-word-off matches of another device or app
        checkMatches(rejected = true, expected = listOf(
            "switch off the music" to "flashlight", "switch the tv off" to "flashlight",
            "turn on the lights" to "media", "mute" to "listening",
        ))
    }

    "timer matches need the timer's words or a running timer" {
        checkMatches(rejected = false, expected = listOf(
            "cancel the timer" to "timer", "cancel the pasta timer" to "timer",
            "how much time is left on the pasta timer" to "timer",
            "start a timer for five minutes" to "timer", "ping me in ten minutes" to "timer",
            "turn off the sound" to "timer",
            // "beep" and "buzz", and a duration with "alarm" (alarms can't ring in five minutes)
            "beep me in 5 minutes" to "timer", "buzz me in 5 minutes" to "timer",
            "set a 5 minute alarm" to "timer", "set a ten minute alarm" to "timer",
            // STT drops the r of "timer"; "time" counts only for a new timer with a duration
            "set a time for five minutes" to "timer",
        ))
        checkMatches(rejected = true, expected = listOf(
            "turn off the tv" to "timer",
            // ("turn off airplane mode" and "silence the phone" are gar:settings' now)
            "stop the alarm" to "timer",
            // "time" isn't the timer's word: these cancelled the running timer or asked about it
            "the time" to "timer", "the current time" to "timer", "cancel the time" to "timer",
            "turn off screen time" to "timer", "stop wasting my time" to "timer",
            "turn off the time limit" to "timer", "how much time is left on the battery" to "timer",
            "how much time is left on my phone" to "timer",
            // a duration alone doesn't make a timer
            "set 5 minutes" to "timer", "set a 30 minute meeting" to "timer",
            "create a 1 hour event" to "timer", "start recording for 10 minutes" to "timer",
            // the timer's name in the capture doesn't count ("beeping")
            "turn off the beeping" to "timer", "silence the buzzer" to "timer",
            // without a running timer Dicio would only say there is none
            "cancel the pasta" to "timer", "how long is left" to "timer",
            "how much time is left" to "timer", "when will the pasta expire" to "timer",
        ))
        // with a pasta timer and an unnamed one running
        val timers = listOf(" pasta ", null)
        checkMatches(timers = timers, rejected = false, expected = listOf(
            "cancel the pasta" to "timer", "stop the pasta" to "timer",
            "turn off the pasta" to "timer", "cancel the pasta one" to "timer",
            "how long is left on the pasta" to "timer", "when will the pasta expire" to "timer",
            "how long is left" to "timer", "how much time is left" to "timer",
            "how much is left" to "timer",
        ))
        checkMatches(timers = timers, rejected = true, expected = listOf(
            "cancel the eggs" to "timer", "the time" to "timer", "turn off the tv" to "timer",
            "how much time is left on the battery" to "timer", "how long is left on it" to "timer",
        ))
    }

    "media matches need a noun unless they are exact" {
        checkMatches(rejected = false, expected = listOf(
            "next" to "media", "previous" to "media", "rewind" to "media",
            "play the next" to "media", "play the previous" to "media",
            "go back to the last song" to "media", "next track" to "media",
            "go to the next one" to "media", "play the previous one" to "media",
            "skip to the next one" to "media", "the next one" to "media",
            "skip this song" to "media", "put the podcast on hold" to "media",
        ))
        checkMatches(rejected = true, expected = listOf(
            "go to the next page" to "media", "switch to the next tab" to "media",
            "go to the next photo" to "media", "go to the previous photo" to "media",
            "go back to the previous menu" to "media", "move forward to the next step" to "media",
            "play the last voicemail" to "media", "replay the last message" to "media",
            "start the video call" to "media", "stop the video call" to "media",
        ))
    }

    "a keyword in a date's capture doesn't count" {
        checkMatches(rejected = true, rankers = listOf(ranker), expected = listOf(
            // ("what will the weather be tomorrow" and "... on friday" are gar:weather's now,
            // WeatherRankingTest)
            "what will the traffic be tomorrow" to "date",
        ))
    }

    "the calculator needs two numbers, or one alone" {
        // "what is .calculation." takes any question on a new install (no lyrics or weather; their
        // gates only take inputs about what they do, and "what's the weather on the 5th" goes to
        // the weather's gate)
        checkMatches(rejected = true, rankers = listOf(ranker), expected = listOf(
            // ("what's the date of easter" and "what's the time in london" are gar:answers' now)
            "what's the clock say" to "calculator",
            // ("what is the capital of france" is gar:knowledge's now, KnowledgeTest)
            // (with one number and other words, the calculator says the number back; "what's the
            // forecast on the 5th" is gar:weather's now, WeatherRankingTest)
            // ("what is the square root of sixty four" is gar:answers' math now)
        ))
        checkMatches(rejected = false, expected = listOf(
            "what is two plus two" to "calculator", "calculate 12 divided by 4" to "calculator",
            "what's five times six" to "calculator", "what is seven plus nine" to "calculator",
            "what is twelve divided by four" to "calculator",
            "what is two times three" to "calculator", "what is 10 minus 4" to "calculator",
            // a date with an ordinal goes to the date skill first
            "what's the date of the fourth of july" to DateInfo.id,
        ))
        // "score" is twenty to the number parser: the calculator would answer 20 times 49 ("of"
        // means times) and leave "ers game" over
        checkMatches(rejected = true, rankers = listOf(ranker), expected = listOf(
            "what is the score of the 49ers game" to "calculator",
        ))
    }

    "matches of skills whose tools have no keywords are never rejected" {
        // checking them would send their sentences to the model; the volume skill checks that its
        // input names the volume itself (VolumeTool.mentionsVolume)
        checkMatches(rejected = false, expected = listOf(
            "mute the music" to VolumeInfo.id, "turn up the volume" to VolumeInfo.id,
            "turn off do not disturb" to DoNotDisturbInfo.id,
            "call mike" to TelephoneInfo.id,
            "open spotify" to OpenInfo.id, "launch the camera app" to OpenInfo.id,
            "navigate to the train station" to NavigationInfo.id,
            "take me to the airport" to NavigationInfo.id,
            "what's on my calendar tomorrow" to CalendarInfo.id,
            "add dentist to my calendar" to CalendarInfo.id,
        ))
        for (skill in listOf(
            VolumeInfo.id, DoNotDisturbInfo.id, TelephoneInfo.id, OpenInfo.id, NavigationInfo.id,
            CalendarInfo.id,
        )) {
            withClue(skill) { GarMatchCheck.keywordsFor(catalogTools, skill) shouldBe null }
        }
    }

    "what song is this goes to the song skill" { // gar:song
        val inputs = listOf(
            "what song is this", "what's this song", "what song is playing", "what's playing",
            "name this song", "identify this song", "who sings this", "what's the name of this song",
            "shazam this", "what am i listening to",
            "what is this song", "what song is that", "what's that song", "which song is this",
            "what song is playing right now", "what is playing", "what's playing right now",
            "what music is this", "what track is this", "what's this tune", "who is singing this",
            "who sings this song", "who sang this", "what is the name of this song",
            "can you identify this song", "recognize this song", "shazam this song", "shazam",
            "what am i listening to right now", "what song am i listening to", "what's the song",
            "who's singing", "name that tune", "what was that song", "who sang it",
            "who sings that song",
        )
        checkMatches(rejected = false, expected = inputs.map { it to "song" })
    }

    "the song sentences take nothing else" { // gar:song
        // media, volume, lyrics (online) and other skills keep theirs
        for ((input, skill) in listOf(
            "play the song" to "media", "pause the music" to "media", "skip this song" to "media",
            "play the next song" to "media", "continue playing" to "media",
            "go back to the last song" to "media", "mute the music" to VolumeInfo.id,
            "what time is it" to "current_time", "whats the date" to "date",
        )) {
            withClue(input) { winner(input).first shouldBe skill }
        }
        val inputs = listOf(
            "play some music", "play a song", "sing me a song", "what are the lyrics of this song",
            "what's playing at the cinema tonight", "what's on tv", "what is this",
            "who is this", "who sings bohemian rhapsody", "what's the name of this restaurant",
            "identify this plant", "name this dog", "what am i doing tomorrow",
            "who is calling", "track my package", "what's playing on netflix tonight",
            "listen to this", "who is the singer of queen", "set a timer for ten minutes",
            "what's the weather", "what's the capital of france",
            // questions about something else that Dicio matched with a word swapped or added
            // (review probe, 2026-10-26): "who sings" and a one-word title, the next song, the
            // volume, other things that play, and questions about a song that aren't its name
            "who sings hello", "who sings jolene", "who sang thriller", "who sings",
            "what movie is playing", "what game is playing", "what's the music volume",
            "what's the next song", "what's the next track", "what's playing next",
            "what song is next", "what's the track record", "what is music",
            "what is this song about", "who wrote this song", "who produced this track",
            "what genre is this song", "what's the tempo of this song",
            "what year is this song from", "who sang that song yesterday",
        )
        for (skills in listOf(ranker, rankerWithOnlineSkills)) {
            val taken = inputs.associateWith { best(it, skills)?.skill?.correspondingSkillInfo?.id }
                .filterValues { it == "song" }
            taken shouldBe mapOf()
        }
    }

    "matches with a word that names another thing" {
        checkMatches(rejected = true, expected = listOf(
            "read my notification settings" to "notify",
            "what are my notification settings" to "notify",
            "turn on wireless charging" to "connectivity", "turn on wifi calling" to "connectivity",
            "turn off wifi calling" to "connectivity", "enable wireless debugging" to "connectivity",
            // ("turn on light mode" and "turn on the light theme" are gar:settings' dark theme now,
            // "turn on the blue light filter" its night light)
            "activate the light sensor" to "flashlight",
            "enable the notification light" to "flashlight",
            // an alarm cancel without a verb is a question; sentence matches aren't confirmed
            "next alarm" to "alarm",
        ))
    }
    "online skills that are off go to their gates" { // gar:online
        for ((input, skill) in listOf(
            "what's the weather like in paris" to "weather", "what's the weather like" to "weather",
            "is it raining" to "weather", "how is it outside" to "weather",
            "what's the weather on the 5th" to "weather",
            "search for cats" to "search", "look up the weather" to "search",
            "search the web for pizza places" to "search",
            "what are the lyrics of yesterday" to "lyrics", "bohemian rhapsody lyrics" to "lyrics",
            "what's the song that goes we will we will rock you" to "lyrics",
            "translate hello into spanish" to "translation",
            "how do you say hello in french" to "translation",
            "what is hello in spanish" to "translation",
            "tell me a joke" to "Joke", "make me laugh" to "Joke", "say something funny" to "Joke",
        )) {
            withClue(input) {
                best(input).shouldNotBeNull().skill.shouldBeInstanceOf<GarOnlineGateSkill>()
                    .online.id shouldBe skill
                // the same skill takes it once it is on, behind GAR's check (and no gate)
                best(input, rankerWithOnlineSkills).shouldNotBeNull().skill
                    .shouldBeTypeOf<GarCheckedOnlineSkill>().online.id shouldBe skill
            }
        }
    }

    "online skills only take inputs about what they do, on or off" { // gar:online
        // Dicio's matcher gave these to an online skill with a word left out or swapped ("lyrics|
        // text .song." took "text dad", "find .what. online" "find my phone"); they stay with the
        // skills that take them on a new install
        for ((input, skill) in listOf(
            "text dad" to SmsInfo.id, "text mom i'm on my way" to SmsInfo.id,
            "send a text" to SmsInfo.id, "show me my alarms" to AlarmInfo.id,
            "get me directions to grandma's house" to NavigationInfo.id,
            "find out what song is playing" to SongInfo.id, "what's that song going" to SongInfo.id,
            "what month is it" to CurrentTimeInfo.id,
        )) {
            for (skills in listOf(ranker, rankerWithOnlineSkills)) {
                withClue(input) { best(input, skills)?.skill?.correspondingSkillInfo?.id shouldBe skill }
            }
        }
        // and these go to the model (or the calculator, whose match the model gets)
        val inputs = listOf(
            "how late is it", "could you switch the torch on for me", "can you check the time for me",
            "could you tell me what time it is", "how do i get to the airport", "find my phone",
            "find me a route to the nearest gas station", "sing me a song", "show me the settings",
            "get me to forty second street", "what's in the news", "read my last text message",
            "can you remind me in twenty minutes to check the oven",
            "what's the time right now", "what's the song on the radio right now", "get me back online",
            // ("what's the capital of france" is a question for gar:knowledge's Wikipedia now)
        )
        for (skills in listOf(ranker, rankerWithOnlineSkills)) {
            val taken = inputs.associateWith { best(it, skills)?.skill?.correspondingSkillInfo?.id }
                .filterValues { it in GarOnlineSkill.ids }
            taken shouldBe mapOf()
        }
    }

    "turn on all online skills goes to its skill" { // gar:online
        for ((input, on) in listOf(
            "turn on all online skills" to true, "enable all online skills" to true,
            "activate the online skills" to true, "switch on all internet skills" to true,
            "turn on online features" to true, "turn the online skills on" to true,
            "turn on the skills that use the internet" to true, "allow online services" to true,
            "turn off all online skills" to false, "disable the online skills" to false,
            "switch off all internet features" to false, "turn the internet skills off" to false,
            "deactivate online services" to false,
            // polite requests are requests
            "can you turn on the online skills" to true, "please turn on all online skills" to true,
        )) {
            for (skills in listOf(ranker, rankerWithOnlineSkills)) {
                withClue(input) {
                    val match = best(input, skills).shouldNotBeNull()
                    match.skill.correspondingSkillInfo.id shouldBe OnlineSkillsInfo.id
                    match.inputData shouldBe
                        if (on) Sentences.OnlineSkills.TurnOn else Sentences.OnlineSkills.TurnOff
                }
            }
        }
    }

    "turn on all online skills takes nothing else" { // gar:online
        val inputs = listOf(
            "turn on all the lights", "turn on wifi", "turn off all alarms", "turn on bluetooth",
            "turn on do not disturb", "turn on the flashlight", "turn off the internet",
            "turn on all notifications", "turn off all timers", "enable all skills",
            "turn on online banking", "turn on the internet", "disable all features",
            "switch on all the lights", "turn off all the lights",
            // questions and negations: Dicio's matcher scores them above its thresholds
            "are the online skills on", "is the online feature on",
            "are the internet features turned on", "why are the online skills off",
            "should I turn on online skills", "how do I turn on the online skills",
            "don't turn on the online skills", "do not enable online features",
            "do not turn on the online skills", "never turn on online skills",
            "no, don't enable online features",
        )
        for (skills in listOf(ranker, rankerWithOnlineSkills)) {
            val taken = inputs.associateWith { best(it, skills)?.skill?.correspondingSkillInfo?.id }
                .filterValues { it == OnlineSkillsInfo.id }
            taken shouldBe mapOf()
        }
    }
})
