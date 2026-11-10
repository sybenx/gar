package cc.aaro.gar.router.resolve

import java.text.Normalizer
import java.util.Locale

/**
 * Fuzzy matching of a spoken name against candidate names (contacts, app labels). Tolerates STT
 * splits and joins ("spot if i" ~ "Spotify", "you tube" ~ "YouTube"), case and accents.
 * Android data sources live in `:app`; this class is pure so it can be unit tested.
 *
 * Both sides are normalized (lowercase, accents stripped, punctuation removed) and compared with
 * and without filler words like "the", "my" and "app". A candidate's score is the best of:
 * - the similarity of both names with spaces removed (1 - edit distance / longer length), which
 *   handles splits and joins;
 * - a word match: each spoken word against its best candidate word, averaged, slightly lowered
 *   when the candidate has more words than were said ("sarah" vs "Sarah Connor").
 */
object NameMatcher {
    /** Below this score a candidate is not a match at all. */
    const val MIN_SCORE = 0.72

    /** Candidates within this distance of the best score make the result ambiguous. */
    const val TIE_MARGIN = 0.04

    /** A word this long that starts another word shares it, see [sharesWord]. */
    const val MIN_PREFIX = 3

    /** Words this long with the same first letter share it from [LOOSE_SCORE], see [sharesWord]. */
    const val LOOSE_LENGTH = 4

    /** How close two spellings of one name must be, see [sharesWord]: "steven" for "Stephen". */
    const val LOOSE_SCORE = 0.6

    /**
     * @return [Resolution.Resolved] when one candidate is clearly best,
     * [Resolution.Ambiguous] with up to [maxOptions] close candidates (best first), or
     * [Resolution.Unresolvable] when nothing is close enough
     * @throws IllegalArgumentException if [maxOptions] is below 2, too few for a pick question
     */
    fun <T> match(
        spoken: String,
        candidates: List<T>,
        name: (T) -> String,
        maxOptions: Int = 5,
    ): Resolution<T> {
        require(maxOptions >= 2) { "maxOptions must be at least 2, not $maxOptions" }
        val spokenWords = normalizedWords(spoken)
        // "the" alone would match "The Weather Channel"
        if (spokenWords.all { it in FILLERS }) {
            return Resolution.Unresolvable("no name")
        }
        val scored = candidates
            .map { Pair(it, score(spokenWords, normalizedWords(name(it)))) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second } // stable: equal scores keep the candidates' order
        if (scored.isEmpty()) {
            return Resolution.Unresolvable("no name like '$spoken'")
        }
        val best = scored[0].second
        val close = scored.takeWhile { it.second >= best - TIE_MARGIN }
        return if (close.size == 1) {
            Resolution.Resolved(close[0].first)
        } else {
            Resolution.Ambiguous(
                close.take(maxOptions).map { it.first },
                AmbiguityReason.SEVERAL_MATCHES,
            )
        }
    }

    /** Similarity of two names in 0..1 (1 = same after normalization). */
    fun similarity(a: String, b: String): Double = score(normalizedWords(a), normalizedWords(b))

    /**
     * Whether a word of [spoken] is a word of [name] as a whole: the same or close to it ("jon"
     * for "John"), the start of it or the other way round, from [MIN_PREFIX] letters ("sam" for
     * "Samantha", "johnny" for "John", "sara" for "Saralee"), or another spelling of it: both
     * [LOOSE_LENGTH] letters or more, the same first letter and close from [LOOSE_SCORE] ("gabby"
     * for "Gabi", "steven" for "Stephen", "shawn" for "Sean"), since Vosk spells a name the same
     * way every time. Filler words and single letters (the "s" of "mom's") don't count. The
     * whole-word test for matches by letters, such as Dicio's contact distance, which also finds
     * a name inside another word: "erin" is not in "Katerina Book Club".
     */
    fun sharesWord(spoken: String, name: String): Boolean {
        val nameWords = normalizedWords(name).filter { it !in FILLERS }
        return normalizedWords(spoken).filter { it !in FILLERS && it.length > 1 }.any { word ->
            nameWords.any {
                ratio(word, it) >= MIN_SCORE || startsWithin(word, it) || spelledLike(word, it)
            }
        }
    }

    /** Whether [a] and [b] are spellings of one name, see [sharesWord]. */
    private fun spelledLike(a: String, b: String): Boolean =
        a.length >= LOOSE_LENGTH && b.length >= LOOSE_LENGTH && a[0] == b[0] &&
            ratio(a, b) >= LOOSE_SCORE

    /** Whether one of [a] and [b] starts the other, the shorter one with [MIN_PREFIX] letters. */
    private fun startsWithin(a: String, b: String): Boolean {
        val (shorter, longer) = if (a.length <= b.length) Pair(a, b) else Pair(b, a)
        return shorter.length >= MIN_PREFIX && longer.startsWith(shorter)
    }

    /** Scored with and without filler words: "the camera app" ~ "Camera", "what's app" ~ "WhatsApp". */
    private fun score(spoken: List<String>, candidate: List<String>): Double =
        maxOf(
            wordsScore(spoken, candidate),
            wordsScore(withoutFillers(spoken), withoutFillers(candidate)),
        )

    private fun wordsScore(spoken: List<String>, candidate: List<String>): Double {
        if (spoken.isEmpty() || candidate.isEmpty()) return 0.0
        val joined = ratio(spoken.joinToString(""), candidate.joinToString(""))
        if (joined == 1.0) return 1.0

        var total = 0.0
        val used = mutableSetOf<Int>()
        for (word in spoken) {
            var bestIndex = -1
            var bestRatio = 0.0
            candidate.forEachIndexed { i, c ->
                val r = ratio(word, c)
                if (i !in used && r > bestRatio) {
                    bestRatio = r
                    bestIndex = i
                }
            }
            if (bestIndex >= 0) used += bestIndex
            total += bestRatio
        }
        val wordScore = total / spoken.size *
            (0.9 + 0.1 * used.size.toDouble() / candidate.size) // unsaid candidate words cost a bit
        return maxOf(joined, wordScore)
    }

    private fun ratio(a: String, b: String): Double {
        if (a == b) return 1.0
        val longer = maxOf(a.length, b.length)
        return if (longer == 0) 0.0 else 1.0 - levenshtein(a, b).toDouble() / longer
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val tmp = previous
            previous = current
            current = tmp
        }
        return previous[b.length]
    }

    private fun normalizedWords(name: String): List<String> {
        val stripped = Normalizer.normalize(name.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .replace(NON_WORD, " ")
        return stripped.split(' ').filter { it.isNotEmpty() }
    }

    private fun withoutFillers(words: List<String>) = words.filter { it !in FILLERS }.ifEmpty { words }

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val FILLERS = setOf("the", "my", "a", "an", "app", "application")
}
