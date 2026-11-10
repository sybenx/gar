package org.stypox.dicio.gar.conversation

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.stypox.dicio.MockSkillInfo
import org.stypox.dicio.gar.FakeOutput
import org.stypox.dicio.gar.settings.GarSettingsSerializer
import org.stypox.dicio.gar.stt.GarWhisperHallucinations
import org.stypox.dicio.io.graphical.ErrorSkillOutput
import org.stypox.dicio.io.graphical.MissingPermissionsSkillOutput
import org.stypox.dicio.skills.conversation.ConversationStopOutput
import org.stypox.dicio.skills.fallback.text.TextFallbackOutput
import org.stypox.dicio.skills.small_talk.SmallTalkAnswer
import org.stypox.dicio.skills.small_talk.SmallTalkOutput
import java.util.Locale

/** The facts of a spoken weather answer on an unlocked phone with GAR on screen: a window opens. */
private val OPENS = GarWindowFacts(
    settingOn = true,
    spokenInput = true,
    planReopens = false,
    spoke = true,
    conversationEnds = false,
    skillId = "weather",
    onScreen = true,
    lockedAtStart = false,
    lockedNow = false,
    ringing = false,
    call = false,
    speakerMusic = false,
    whisper = true,
    sttListening = false,
)

/** The skills that act outward, write, ask their own questions or leave GAR. */
private val NEVER_OPENS_AFTER = listOf(
    "telephone", "sms", "messages", "email", "call_log", "notes", "lists", "reminders",
    "share_location", "navigation", "open", "media", "song", "camera", "search", "small_talk",
)

class GarContinuedListeningTest : StringSpec({
    "a window opens after a spoken answer with every condition met" {
        GarContinuedListening.reason(OPENS).shouldBeNull()
        GarContinuedListening.decide(OPENS).shouldBeTrue()
    }

    "each condition alone keeps the window shut" {
        for ((facts, reason) in listOf(
            OPENS.copy(settingOn = false) to "setting",
            OPENS.copy(spokenInput = false) to "typed",
            OPENS.copy(planReopens = true) to "question",
            OPENS.copy(spoke = false) to "silent",
            OPENS.copy(conversationEnds = true) to "ended",
            OPENS.copy(skillId = "telephone") to "skill",
            OPENS.copy(onScreen = false) to "off_screen",
            OPENS.copy(lockedNow = true) to "locked",
            OPENS.copy(ringing = true) to "ringing",
            OPENS.copy(call = true) to "call",
            OPENS.copy(speakerMusic = true) to "music",
            OPENS.copy(whisper = false) to "not_whisper",
            OPENS.copy(sttListening = true) to "listening",
        )) {
            withClue(reason) {
                GarContinuedListening.reason(facts) shouldBe reason
                GarContinuedListening.decide(facts).shouldBeFalse()
            }
        }
    }

    "a phone locked when the turn started keeps its window, one that locked since gets none" {
        GarContinuedListening.decide(OPENS.copy(lockedAtStart = true, lockedNow = true))
            .shouldBeTrue()
        GarContinuedListening.decide(OPENS.copy(lockedAtStart = false, lockedNow = true))
            .shouldBeFalse()
        GarContinuedListening.decide(OPENS.copy(lockedAtStart = true, lockedNow = false))
            .shouldBeTrue()
    }

    "the allow-list opens after answers and settings, never after what acts outward" {
        for (skill in GarContinuedListening.OPENS_AFTER) {
            withClue(skill) { GarContinuedListening.decide(OPENS.copy(skillId = skill)).shouldBeTrue() }
        }
        for (skill in NEVER_OPENS_AFTER) {
            withClue(skill) {
                (skill in GarContinuedListening.OPENS_AFTER).shouldBeFalse()
                GarContinuedListening.decide(OPENS.copy(skillId = skill)).shouldBeFalse()
            }
        }
        GarContinuedListening.OPENS_AFTER.size shouldBe 30
    }

    "stop, thanks, errors, the permission card and the fallback end the conversation" {
        GarContinuedListening.endsWindow(ConversationStopOutput(null)).shouldBeTrue()
        GarContinuedListening.endsWindow(SmallTalkOutput(SmallTalkAnswer.THANKS)).shouldBeTrue()
        GarContinuedListening.endsWindow(ErrorSkillOutput(Exception(), true)).shouldBeTrue()
        GarContinuedListening.endsWindow(MissingPermissionsSkillOutput(MockSkillInfo)).shouldBeTrue()
        GarContinuedListening.endsWindow(TextFallbackOutput(askToRepeat = true)).shouldBeTrue()
        GarContinuedListening.endsWindow(FakeOutput(null)).shouldBeFalse()
    }

    "in a continued turn the fallback is shown only" {
        GarContinuedListening.silentInContinued(TextFallbackOutput(askToRepeat = false))
            .shouldBeTrue()
        GarContinuedListening.silentInContinued(TextFallbackOutput(askToRepeat = true))
            .shouldBeTrue()
        GarContinuedListening.silentInContinued(FakeOutput(null)).shouldBeFalse()
    }

    "the setting is on by default and stored the other way round" {
        GarContinuedListening.settingOn(GarSettingsSerializer.defaultValue).shouldBeTrue()
        GarContinuedListening.settingOn(
            GarSettingsSerializer.defaultValue.toBuilder().setContinuedListeningOff(true).build()
        ).shouldBeFalse()
    }

    "a window is stale once anything spoke or came in after it was scheduled" {
        val scheduled = GarContinuedListening.sequenceNow()
        GarContinuedListening.onSpeak()
        var closed = 0
        GarContinuedListening.opened(scheduled) { closed++ }.shouldBeFalse()
        closed shouldBe 1 // closed at once

        val again = GarContinuedListening.sequenceNow()
        GarContinuedListening.onInput()
        GarContinuedListening.opened(again) { closed++ }.shouldBeFalse()
        closed shouldBe 2
    }

    "new speech or input closes an open window, a window that ended doesn't close again" {
        var closed = 0
        val first = GarContinuedListening.sequenceNow()
        GarContinuedListening.opened(first) { closed++ }.shouldBeTrue()
        closed shouldBe 0
        GarContinuedListening.onSpeak() // a timer's announcement, the song's late result
        closed shouldBe 1
        GarContinuedListening.onSpeak()
        closed shouldBe 1

        // the screen turning off closes it through the same function
        val second = GarContinuedListening.sequenceNow()
        GarContinuedListening.opened(second) { closed++ }.shouldBeTrue()
        GarContinuedListening.onInput()
        closed shouldBe 2

        val third = GarContinuedListening.sequenceNow()
        GarContinuedListening.opened(third) { closed++ }.shouldBeTrue()
        GarContinuedListening.closed(third) // it heard something, or nothing
        GarContinuedListening.onInput()
        closed shouldBe 2
    }

    "Whisper's silence phrases are silence, in each language" {
        for ((locale, text) in listOf(
            Locale.US to "Thank you.",
            Locale.US to "thanks for watching!",
            Locale.US to "Thank you for watching.",
            Locale.US to "you",
            Locale.US to "Bye.",
            Locale.US to "[Music]",
            Locale.US to "music",
            Locale.US to "[BLANK_AUDIO]",
            Locale.US to "blank audio",
            Locale.US to "♪",
            Locale.US to "♪♪ ♪",
            Locale.US to "(upbeat music)",
            Locale.US to "*laughs*",
            Locale.US to "...",
            Locale.US to "",
            Locale.GERMANY to "Untertitel der Amara.org-Community",
            Locale.GERMANY to "Vielen Dank fürs Zuschauen!",
            Locale.FRANCE to "Sous-titres réalisés par la communauté d'Amara.org",
            Locale.FRANCE to "Merci d'avoir regardé.",
            Locale("es") to "Subtítulos por la comunidad de Amara.org",
            Locale("es") to "Gracias por ver.",
            Locale.ITALY to "Sottotitoli creati dalla comunità Amara.org",
            Locale("pt", "BR") to "Legendas pela comunidade Amara.org",
            Locale("nl") to "Ondertiteling door de Amara.org gemeenschap",
            Locale.GERMANY to "[Musik]",
        )) {
            withClue("$locale: $text") {
                GarWhisperHallucinations.isSilence(locale, text).shouldBeTrue()
            }
        }
    }

    "follow-ups and other languages' phrases are not silence" {
        for ((locale, text) in listOf(
            Locale.US to "what about tomorrow",
            Locale.US to "thank you very much for the weather",
            Locale.US to "turn it off",
            Locale.US to "you too",
            Locale.US to "musical chairs",
            Locale.US to "untertitel", // English windows keep German words
            Locale.GERMANY to "und morgen?",
            Locale.GERMANY to "thank you", // the English phrases are English only
            Locale.FRANCE to "et demain",
        )) {
            withClue("$locale: $text") {
                GarWhisperHallucinations.isSilence(locale, text).shouldBeFalse()
            }
        }
    }

    "the ending words of other languages end the conversation, not other inputs" {
        for ((locale, text) in listOf(
            Locale.GERMANY to "Danke.",
            Locale.GERMANY to "danke schön",
            Locale.GERMANY to "Das war's!",
            Locale.GERMANY to "nichts",
            Locale.FRANCE to "Merci.",
            Locale.FRANCE to "C'est tout.",
            Locale.FRANCE to "rien",
            Locale("es") to "Gracias",
            Locale("es") to "eso es todo",
            Locale.ITALY to "Grazie!",
            Locale.ITALY to "è tutto",
            Locale("pt") to "Obrigada.",
            Locale("pt") to "é tudo",
            Locale("nl") to "Dank je",
            Locale("nl") to "dat was het",
        )) {
            withClue("$locale: $text") {
                GarContinuedListening.endsConversation(locale, text).shouldBeTrue()
            }
        }
        for ((locale, text) in listOf(
            Locale.GERMANY to "und morgen",
            Locale.GERMANY to "danke für das wetter in berlin",
            Locale.FRANCE to "merci de mettre un minuteur",
            Locale.US to "thanks", // English goes through the conversation skill
            Locale.US to "that's all",
        )) {
            withClue("$locale: $text") {
                GarContinuedListening.endsConversation(locale, text).shouldBeFalse()
            }
        }
    }
})
