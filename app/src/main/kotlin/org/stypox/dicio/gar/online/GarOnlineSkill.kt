package org.stypox.dicio.gar.online

import androidx.annotation.StringRes
import cc.aaro.gar.router.KeywordFilter
import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.standard.util.nfkdNormalizeWord
import org.stypox.dicio.R
import org.stypox.dicio.cldr.CldrLanguages
import org.stypox.dicio.gar.GarMatchCheck
import org.stypox.dicio.sentences.Sentences

/**
 * Dicio's skills that make network requests. GAR turns them off on a new install
 * (`UserSettingsSerializer`, compared in a test) and asks once before one of them runs (gar/DESIGN.md,
 * "Online skills").
 *
 * @param id the skill's [org.dicio.skill.skill.SkillInfo.id]
 * @param question the spoken question: what the skill contacts, and whether to turn it on
 * @param detail what the card adds: what is sent where
 * @param label the skill in a sentence ("the weather skill")
 */
enum class GarOnlineSkill(
    val id: String,
    @StringRes val question: Int,
    @StringRes val detail: Int,
    @StringRes val label: Int,
) {
    WEATHER(
        "weather", R.string.gar_online_ask_weather, R.string.gar_online_detail_weather,
        R.string.gar_online_label_weather,
    ),
    SEARCH(
        "search", R.string.gar_online_ask_search, R.string.gar_online_detail_search,
        R.string.gar_online_label_search,
    ),
    LYRICS(
        "lyrics", R.string.gar_online_ask_lyrics, R.string.gar_online_detail_lyrics,
        R.string.gar_online_label_lyrics,
    ),
    TRANSLATION(
        "translation", R.string.gar_online_ask_translation,
        R.string.gar_online_detail_translation, R.string.gar_online_label_translation,
    ),
    JOKE(
        "Joke", R.string.gar_online_ask_joke, R.string.gar_online_detail_joke,
        R.string.gar_online_label_joke,
    ),

    // gar:knowledge: GAR's own skills (gar/PARITY.md, batch 5), which read their inputs in Kotlin
    KNOWLEDGE(
        "knowledge", R.string.gar_online_ask_knowledge, R.string.gar_online_detail_knowledge,
        R.string.gar_online_label_knowledge,
    ),
    DICTIONARY(
        "dictionary", R.string.gar_online_ask_dictionary, R.string.gar_online_detail_dictionary,
        R.string.gar_online_label_dictionary,
    ),
    CURRENCY(
        "currency", R.string.gar_online_ask_currency, R.string.gar_online_detail_currency,
        R.string.gar_online_label_currency,
    );

    companion object {
        val ids: List<String> = entries.map { it.id }

        fun of(id: String): GarOnlineSkill? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which English inputs an online skill takes (gar/DESIGN.md, "Online skills"). Dicio's matcher
 * scores the online skills' sentences above its thresholds for many commands of other skills: the
 * lyrics sentence "lyrics|text .song." with "lyrics" left out took "text dad" and "text mom i'm on
 * my way" from the sms skill (and would have sent the text to Genius), "find .what. online" took
 * "find my phone", and the translation sentences "could you switch the torch on for me" with
 * "translate" left out. So an online skill, on or behind GAR's question, only takes an input that
 * names what the skill is about outside the sentence's captures; any other input goes where it
 * went with the skill off.
 */
object GarOnlineInputs {
    /** Every weather sentence names one of these. */
    private val WEATHER_WORDS = listOf(
        "weather", "outside", "cold", "cool", "warm", "hot", "sunny", "rainy", "raining",
    )

    /** "look .what. up" is "look up" once the capture is left out. */
    private val SEARCH_WORDS = listOf(
        "search", "research", "seek", "lookup", "look up", "online", "internet", "web",
    )

    private val LYRICS_WORDS = listOf("lyric")

    /** "what's the song that goes ...", "sing the song ...": a song with its words. */
    private val SONG_WORDS = listOf("song")
    private val SONG_LINE_WORDS = listOf("goes", "going", "says", "tells", "sings", "sing")

    private val TRANSLATE_WORDS = listOf("translate", "translation", "translator")

    private val JOKE_WORDS = listOf(
        "joke", "pun", "gag", "prank", "laugh", "fun", "funny", "entertain", "cheer",
    )

    /**
     * Whether [online]'s match of [input] with [inputData] (from its own sentences) is about what
     * the skill does. English only: [ctx] gives the language names for the translation skill.
     */
    fun takes(online: GarOnlineSkill, ctx: SkillContext, input: String, inputData: Any?): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        val frame = GarMatchCheck.withoutCaptures(words, capturesOf(inputData)).joinToString(" ")
        return when (online) {
            // gar:weather: GAR's own reading of a weather request, as the skill's score has it
            GarOnlineSkill.WEATHER -> inputData is org.stypox.dicio.skills.weather.WeatherRequest ||
                KeywordFilter.containsAny(frame, WEATHER_WORDS)
            GarOnlineSkill.SEARCH -> KeywordFilter.containsAny(frame, SEARCH_WORDS)
            GarOnlineSkill.LYRICS -> {
                val song = (inputData as? Sentences.Lyrics.Query)?.song
                !song.isNullOrBlank() && (KeywordFilter.containsAny(frame, LYRICS_WORDS) ||
                    (KeywordFilter.containsAny(frame, SONG_WORDS) &&
                        KeywordFilter.containsAny(frame, SONG_LINE_WORDS)))
            }
            // "how do you say hello in french", "what is hello in spanish": a language is enough
            GarOnlineSkill.TRANSLATION -> KeywordFilter.containsAny(frame, TRANSLATE_WORDS) ||
                (inputData as? Sentences.Translation.Translate)?.let {
                    namesLanguage(ctx, it.target) || namesLanguage(ctx, it.source)
                } == true
            GarOnlineSkill.JOKE -> KeywordFilter.containsAny(frame, JOKE_WORDS)
            // gar:knowledge: these skills' own scores take only whole requests of their forms
            GarOnlineSkill.KNOWLEDGE, GarOnlineSkill.DICTIONARY, GarOnlineSkill.CURRENCY -> true
        }
    }

    private fun capturesOf(inputData: Any?): List<String?> = when (inputData) {
        is Sentences.Weather.Current -> listOf(inputData.where)
        is Sentences.Search.Query -> listOf(inputData.what)
        is Sentences.Lyrics.Query -> listOf(inputData.song)
        is Sentences.Translation.Translate ->
            listOf(inputData.query, inputData.source, inputData.target)
        else -> listOf()
    }

    /**
     * Whether [name] is exactly a language's name ("spanish"). Not the translation skill's own
     * lookup, which also accepts "grandma's house" (in "get me directions to grandma's house").
     */
    private fun namesLanguage(ctx: SkillContext, name: String?): Boolean {
        val normalized = nfkdNormalizeWord(name?.trim() ?: return false)
        return normalized.isNotEmpty() && CldrLanguages[ctx.locale.language].orEmpty().any {
            it.translationNormalized.equals(normalized, ignoreCase = true)
        }
    }
}
