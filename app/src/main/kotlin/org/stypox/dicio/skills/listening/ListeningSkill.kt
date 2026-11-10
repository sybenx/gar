package org.stypox.dicio.skills.listening

import cc.aaro.gar.router.Utterances
import kotlinx.coroutines.flow.first
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.io.wake.WakeService
import org.stypox.dicio.sentences.Sentences.Listening
import org.stypox.dicio.settings.datastore.WakeDevice

class ListeningSkill(val listeningInfo: ListeningInfo, data: StandardRecognizerData<Listening>) :
    StandardRecognizerSkill<Listening>(listeningInfo, data) {

    // GAR: in English, only an input that names what this skill turns on and off, see
    // [namesListening]
    override fun score(ctx: SkillContext, input: String): Pair<Score, Listening> {
        val (score, sentence) = super.score(ctx, input)
        val english = ctx.sentencesLanguage.substringBefore('-').substringBefore('_') == "en"
        return Pair(if (!english || namesListening(input)) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Listening): SkillOutput {
        if (listeningInfo.dataStore.data.first().wakeDevice == WakeDevice.WAKE_DEVICE_NOTHING) {
            return ListeningOutput(false, false, false)
        }

        val previouslyRunning = WakeService.isRunning()
        val shouldBeRunning = when (inputData) {
            is Listening.Start -> true
            is Listening.Stop -> false
        }
        if (shouldBeRunning) {
            WakeService.start(ctx.android)
        } else {
            WakeService.stop(ctx.android)
        }
        return ListeningOutput(true, previouslyRunning, shouldBeRunning)
    }
}

// GAR: Dicio scores a sentence with a word left out above its thresholds, so GAR's lines in
// listening.yml ("(turn|switch) off (the|my)? mic|mike|microphone", "... wake up? word") took
// "switch off the music", "switch on the lights" and "turn off my wake up alarm", and "change the
// wake word" stopped listening. A match needs the microphone, the wake word, listening or Dicio,
// and an on/off word; upstream's bare "mute" and "unmute" ("mute microphone?") stay.
private val LISTENING_NAMES = setOf(
    "mic", "mike", "microphone", "listen", "listening", "hotword", "wakeword", "dicio",
)
private val LISTENING_PHRASES = listOf(
    listOf("wake", "word"), listOf("wake", "up", "word"), listOf("wakeup", "word"), listOf("hot", "word"),
)
private val LISTENING_SWITCHES = setOf(
    "on", "off", "mute", "unmute", "enable", "disable", "stop", "start", "quit",
)
private val BARE_LISTENING_INPUTS = setOf(listOf("mute"), listOf("unmute"), listOf("on", "mute"))

internal fun namesListening(input: String): Boolean {
    val words = Utterances.words(Utterances.normalize(input))
    if (words in BARE_LISTENING_INPUTS) {
        return true
    }
    val names = words.any { it in LISTENING_NAMES } ||
        LISTENING_PHRASES.any { phrase -> words.windowed(phrase.size).any { it == phrase } }
    return names && words.any { it in LISTENING_SWITCHES }
}
