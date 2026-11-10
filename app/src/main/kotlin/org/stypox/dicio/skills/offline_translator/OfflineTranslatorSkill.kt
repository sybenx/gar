package org.stypox.dicio.skills.offline_translator

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.stypox.dicio.cldr.CldrLanguages
import org.stypox.dicio.cldr.CldrLanguages.LocaleAndTranslation
import org.stypox.dicio.gar.GarFallbackImpl
import org.stypox.dicio.gar.online.GarOnlineInputs
import org.stypox.dicio.gar.online.GarOnlineSkill
import org.stypox.dicio.sentences.Sentences.Translation
import org.stypox.dicio.util.getLocaleByLanguageName

/**
 * What to translate with Offline Translator: [text], and its languages as Offline Translator's
 * codes (null: English for the source, its default for the target) with their names for the card.
 */
data class OfflineTranslationRequest(
    val text: String,
    val from: String?,
    val to: String?,
    val source: LocaleAndTranslation?,
    val target: LocaleAndTranslation?,
)

/**
 * Translation on the phone through Offline Translator (gar/PARITY.md, batch 5), before Dicio's
 * online translation skill (Lingva, behind GAR's ask-once gate): "translate good morning to
 * spanish", "how do you say thank you in french". It takes Dicio's own translation sentences, with
 * the same English check as the online skill (`GarOnlineInputs`: "translate" or a language's
 * name), while Offline Translator is installed and knows the languages; otherwise it scores worst
 * and the request goes where it went before (the online skill or its gate). It isn't an online
 * skill: nothing leaves the phone. It ranks before the online skills, so it wins their ties.
 *
 * It speaks the translation that Offline Translator's service sends back; when the service can't
 * translate (a language pack not downloaded, say), it shows the text in Offline Translator's
 * popup: "Here's that in Offline Translator.".
 */
class OfflineTranslatorSkill(
    correspondingSkillInfo: SkillInfo,
    private val data: StandardRecognizerData<Translation>,
    private val isInstalled: (SkillContext) -> Boolean = { ctx ->
        OfflineTranslatorService.isInstalled(ctx.android)
    },
    private val client: (SkillContext) -> OfflineTranslatorClient = { ctx ->
        OfflineTranslatorService(ctx.android)
    },
    private val open: (SkillContext, String) -> Boolean = { ctx, text ->
        OfflineTranslatorService.open(ctx.android, text)
    },
) : Skill<OfflineTranslationRequest?>(correspondingSkillInfo, data.specificity) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, OfflineTranslationRequest?> {
        val (score, match) = data.score(ctx, input)
        val request = requestOf(ctx, input, match)
            ?.takeIf { isInstalled(ctx) }
        return Pair(if (request == null) AlwaysWorstScore else score, request)
    }

    /** The request in [match], or null if it isn't one for Offline Translator. */
    private fun requestOf(ctx: SkillContext, input: String, match: Translation): OfflineTranslationRequest? {
        if (ctx.sentencesLanguage != GarFallbackImpl.ENGLISH ||
            !GarOnlineInputs.takes(GarOnlineSkill.TRANSLATION, ctx, input, match)
        ) {
            return null
        }
        val translate = when (match) { is Translation.Translate -> match }
        val text = translate.query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val languages = CldrLanguages[ctx.locale.language] ?: return null
        val source = translate.source?.let { languages.getLocaleByLanguageName(it) ?: return null }
        val target = translate.target?.let { languages.getLocaleByLanguageName(it) ?: return null }
        // a language Offline Translator doesn't have goes to the online skill
        val from = source?.let { OfflineTranslatorLanguages.codeOf(it.locale) ?: return null }
        val to = target?.let { OfflineTranslatorLanguages.codeOf(it.locale) ?: return null }
        return OfflineTranslationRequest(text, from, to, source, target)
    }

    override suspend fun generateOutput(
        ctx: SkillContext,
        inputData: OfflineTranslationRequest?,
    ): SkillOutput {
        val request = inputData
            ?: throw IllegalStateException("an offline translation match without text ran")
        // the user speaks English: "translate good morning to spanish" is from English, but
        // "translate bonjour to english" has to be detected
        val from = request.from ?: if (request.to == ENGLISH) "" else ENGLISH
        return when (val result = client(ctx).translate(request.text, from, request.to.orEmpty())) {
            is OfflineTranslation.Translated -> if (result.text.isBlank()) {
                opened(ctx, request, null)
            } else {
                OfflineTranslatorOutput.Translated(request, result.text)
            }
            is OfflineTranslation.Failed -> opened(ctx, request, result.message)
            OfflineTranslation.Unreachable -> opened(ctx, request, null)
        }
    }

    private fun opened(ctx: SkillContext, request: OfflineTranslationRequest, reason: String?) =
        if (open(ctx, request.text)) {
            OfflineTranslatorOutput.Opened(request, reason)
        } else {
            OfflineTranslatorOutput.Failed(reason)
        }

    private companion object {
        const val ENGLISH = "en"
    }
}

/** Offline Translator's language codes (its catalog, index_v7.json), from CLDR's. */
object OfflineTranslatorLanguages {
    /** The languages Offline Translator has models for (2026-10-28). */
    val CODES = setOf(
        "af", "ar", "az", "be", "bg", "bn", "bs", "ca", "cs", "da", "de", "el", "en", "es", "et",
        "eu", "fa", "fi", "fr", "gl", "gu", "he", "hi", "hr", "hu", "id", "is", "it", "ja", "ka",
        "kn", "ko", "lt", "lv", "ml", "mr", "ms", "nb", "nl", "nn", "no", "pl", "pt", "ro", "ru",
        "sk", "sl", "sq", "sr", "sv", "sw", "ta", "te", "th", "tl", "tr", "ug", "uk", "ur", "vi",
        "zh", "zh_hant",
    )

    /** CLDR codes Offline Translator calls otherwise. */
    private val RENAMED = mapOf("iw" to "he", "fil" to "tl", "in" to "id", "zh_hans" to "zh")

    /** Offline Translator's code for the CLDR [locale] ("es_mx" -> "es"), null if it lacks it. */
    fun codeOf(locale: String): String? {
        val code = locale.lowercase()
        RENAMED[code]?.let { return it }
        if (code in CODES) {
            return code
        }
        val language = code.substringBefore('_')
        return (RENAMED[language] ?: language).takeIf { it in CODES }
    }
}
