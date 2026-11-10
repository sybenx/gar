package org.stypox.dicio.gar.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.dicio.numbers.ParserFormatter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.context.SpeechOutputDevice
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.dicio.skill.standard.util.MatchHelper
import org.stypox.dicio.R
import org.stypox.dicio.gar.voicelog.GarVoiceLog
import org.stypox.dicio.gar.voicelog.GarVoiceStart
import org.stypox.dicio.gar.voicelog.VoiceLogFiles
import org.stypox.dicio.gar.voicelog.VoiceSignals
import org.stypox.dicio.gar.voicelog.VoiceStarterIndex
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.Locale

/**
 * The chips on the empty home screen and on the sheet before the first turn (gar/DESIGN.md,
 * "Home and chips"): what the user asked most in the last 30 days, filled up with defaults.
 *
 * Only an allowlist of turns teaches a starter: answered, not suspect, 2 to 6 words, matched by
 * a skill's sentences (the voice log's `match.path` is "sentence", so no model route and no
 * follow-up), not a card's button, and answered by a read-only skill of [STARTER_SKILLS] (a
 * timer only with a duration). One chip per skill. A chip sends the raw words the user said or
 * typed, never the grouping key, unless a default says the same (then the default's clean
 * wording), and each is matched against its skill again before it is shown.
 */
object GarStarterChips {
    private const val TAG = "GarStarterChips"

    /** The skills a starter may come from: read-only or harmless, no names or queries. */
    val STARTER_SKILLS = setOf(
        "weather", "current_time", "world_time", "date", "battery", "calculator", "units", "timer",
    )
    private const val TIMER_SKILL = "timer"

    const val WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val MIN_COUNT = 2
    const val MIN_WORDS = 2
    const val MAX_WORDS = 6
    /** The starters are kept this long while the voice log writes no new starter turn. */
    const val CACHE_MILLIS = 6L * 60 * 60 * 1000

    /** What the starters read of a voice-log line. */
    data class Turn(
        val input: String,
        val wallMillis: Long,
        val start: String?,
        val outcome: String?,
        val skill: String?,
        /** The voice log's `match.path`: which path won ("sentence", "model", ...). */
        val path: String?,
        val suspect: Boolean,
        val followUp: Boolean,
    )

    private val DURATION = Regex("\\b(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}' ]+")
    private val SPACES = Regex("\\s+")

    /** Lowercase words without punctuation: the key turns are grouped and counted by. */
    fun normalize(input: String): String =
        NON_WORD.replace(input.lowercase(Locale.ROOT).replace('’', '\''), " ")
            .trim().replace(SPACES, " ")

    /** [normalize] without apostrophes, so that STT's "whats" meets "what's". */
    fun key(input: String): String = normalize(input).replace("'", "")

    /**
     * Whether [short] is [long] cut off: its words start [long]'s, the last one possibly cut
     * itself ("what time is" and "what time is i" of "what time is it", "whats my batter" of
     * "whats my battery"), but a different number isn't ("1 minute" of "10 minutes").
     */
    fun isCutOff(short: String, long: String): Boolean {
        val shortWords = short.split(' ').filter { it.isNotEmpty() }
        val longWords = long.split(' ').filter { it.isNotEmpty() }
        val last = shortWords.size - 1
        return short != long && last >= 0 && shortWords.size <= longWords.size &&
            shortWords.subList(0, last) == longWords.subList(0, last) &&
            longWords[last].startsWith(shortWords[last])
    }

    /** Whether [turn] may teach a starter (the allowlist above). */
    fun allowed(turn: Turn, now: Long): Boolean {
        val words = normalize(turn.input).split(' ').filter { it.isNotEmpty() }
        return turn.outcome == VoiceSignals.OUTCOME_ANSWERED &&
            !turn.suspect &&
            !turn.followUp &&
            turn.path == PATH_SENTENCE &&
            turn.start != GarVoiceStart.BUTTON.key &&
            words.size in MIN_WORDS..MAX_WORDS &&
            turn.skill in STARTER_SKILLS &&
            (turn.skill != TIMER_SKILL || DURATION.containsMatchIn(normalize(turn.input))) &&
            turn.wallMillis in (now - WINDOW_MILLIS)..now
    }

    /** One skill's best request: [count] turns asked it (with its cut-off prefixes). */
    private class Candidate(
        val key: String,
        val chip: GarChip,
        val skill: String,
        val count: Int,
        val newest: Long,
    )

    /**
     * The learned starters, best first, at most one per skill: allowed turns grouped by [key],
     * a cut-off request ([isCutOff]) counted with the longer one it starts; per skill the
     * group asked most (then the longest, then the newest), if at least [MIN_COUNT] times; the
     * skills by that count, then by the newest. Each chip sends the newest raw input of its
     * group, or a default's wording when its key is the default's or starts it ([defaults]
     * carry their skill ids). A skill's best that is still a cut-off of another skill's or of a
     * default (STT's "whats my batter", answered by the calculator) is dropped. The skill comes
     * along for the re-check and for [fill].
     */
    fun learned(
        turns: List<Turn>,
        now: Long,
        defaults: List<Pair<GarChip, String>> = listOf(),
    ): List<Pair<GarChip, String>> {
        val candidates = turns.filter { allowed(it, now) }
            .groupBy { it.skill!! }
            .mapNotNull { (skill, skillTurns) -> best(skill, skillTurns, defaults) }
        val others = candidates.map { Pair(it.key, it.skill) } +
            defaults.map { (chip, skill) -> Pair(key(chip.input), skill) }
        return candidates
            .filter { candidate ->
                others.none { (otherKey, skill) ->
                    skill != candidate.skill && isCutOff(candidate.key, otherKey)
                }
            }
            .sortedWith(
                compareByDescending<Candidate> { it.count }.thenByDescending { it.newest }
            )
            .map { Pair(it.chip, it.skill) }
    }

    private fun best(
        skill: String,
        turns: List<Turn>,
        defaults: List<Pair<GarChip, String>>,
    ): Candidate? {
        val groups = turns.groupBy { key(it.input) }
        val (groupKey, group, count) = groups
            .filterKeys { full -> groups.keys.none { isCutOff(full, it) } }
            .map { (full, own) ->
                val prefixes = groups.filterKeys { isCutOff(it, full) }.values.sumOf { it.size }
                Triple(full, own, own.size + prefixes)
            }
            .maxWithOrNull(
                compareBy<Triple<String, List<Turn>, Int>> { it.third }
                    .thenBy { it.first.split(' ').size }
                    .thenBy { triple -> triple.second.maxOf { it.wallMillis } }
            )
            ?: return null
        if (count < MIN_COUNT) {
            return null
        }
        val newest = group.maxBy { it.wallMillis }
        val default = defaults.firstOrNull { (chip, defaultSkill) ->
            val defaultKey = key(chip.input)
            defaultSkill == skill && (groupKey == defaultKey || isCutOff(groupKey, defaultKey))
        }
        val chip = default?.first ?: newest.input.trim().let { raw ->
            GarChip(label = raw.replaceFirstChar { it.titlecase(Locale.getDefault()) }, input = raw)
        }
        return Candidate(groupKey, chip, skill, count, newest.wallMillis)
    }

    /**
     * [learned] up to [GarChips.MAX_CHIPS], filled with [defaults] (with their skill ids) of the
     * skills no learned chip is from, and that say something else.
     */
    fun fill(
        learned: List<Pair<GarChip, String>>,
        defaults: List<Pair<GarChip, String>>,
    ): List<GarChip> {
        val taken = learned.take(GarChips.MAX_CHIPS)
        val chips = taken.map { it.first }.toMutableList()
        for ((default, skill) in defaults) {
            if (chips.size >= GarChips.MAX_CHIPS) {
                break
            }
            if (taken.none { it.second == skill } &&
                chips.none { key(it.input) == key(default.input) }
            ) {
                chips.add(default)
            }
        }
        return chips
    }

    /** Pure: the starters for [turns] at [now] (wall clock), without the re-check. */
    fun rank(
        turns: List<Turn>,
        now: Long,
        defaults: List<Pair<GarChip, String>> = listOf(),
    ): List<GarChip> = fill(learned(turns, now, defaults), defaults)

    /** One voice-log line, or null when it isn't a turn with input (or can't be read). */
    fun parse(line: String): Turn? = try {
        val json = Json.parseToJsonElement(line).jsonObject
        val input = json.string("input")
        val time = json.string("t")
        if (input == null || time == null) {
            null
        } else {
            val answer = json["answer"] as? JsonObject
            val match = json["match"] as? JsonObject
            Turn(
                input = input,
                wallMillis = OffsetDateTime.parse(time).toInstant().toEpochMilli(),
                start = json.string("start"),
                outcome = answer?.string("outcome"),
                skill = answer?.string("skill"),
                path = match?.string("path"),
                suspect = (json["suspect"] as? JsonArray)?.isNotEmpty() == true,
                followUp = json["follow_up"] is JsonObject,
            )
        }
    } catch (e: Exception) {
        null // a line cut by a rotation or a crash, or from another version
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The starter index's line for the voice log's [line] ([VoiceStarterIndex]): only the fields
     * [parse] reads, of a turn [allowed] when it was asked; null for any other line.
     */
    fun indexLine(line: String): String? {
        val turn = parse(line)?.takeIf { allowed(it, it.wallMillis) } ?: return null
        return buildJsonObject {
            put("input", turn.input)
            put("t", Instant.ofEpochMilli(turn.wallMillis).atOffset(ZoneOffset.UTC).toString())
            turn.start?.let { put("start", it) }
            putJsonObject("answer") {
                turn.outcome?.let { put("outcome", it) }
                turn.skill?.let { put("skill", it) }
            }
            putJsonObject("match") { turn.path?.let { put("path", it) } }
        }.toString()
    }

    /** The default starters, in the app's language, with the skill each is for. */
    fun defaults(context: Context): List<Pair<GarChip, String>> = listOf(
        R.string.gar_starter_weather to "weather",
        R.string.gar_starter_timer to TIMER_SKILL,
        R.string.gar_starter_time to "current_time",
        R.string.gar_starter_battery to "battery",
    ).map { (text, skill) -> Pair(GarChip(context.getString(text)), skill) }

    /** The starters at [time] (elapsed), for the voice log's state [stamp] ([indexStamp]). */
    private class Cached(val time: Long, val stamp: String, val chips: List<GarChip>)

    @Volatile
    private var cache: Cached? = null

    /** The cached starters if they are recent, else null. */
    fun cached(): List<GarChip>? =
        cache?.takeIf { SystemClock.elapsedRealtime() - it.time < CACHE_MILLIS }?.chips

    /**
     * The starters' own thread, at the lowest priority: they are read when GAR opens, which is
     * when the user starts talking, and the command must get the CPU first.
     */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "GarStarters").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }.asCoroutineDispatcher()
    }

    /**
     * The starters (none over the lock screen, none when the chips' language isn't the
     * sentences' one), cached for [CACHE_MILLIS] or until the voice log indexes a new starter
     * turn. Reads the starter index (never the whole log) and scores sentences: call it on
     * [dispatcher].
     */
    suspend fun get(context: Context): List<GarChip> {
        val appContext = context.applicationContext
        if (appContext.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
            return listOf()
        }
        val entryPoint = EntryPointAccessors.fromApplication(appContext, GarUiEntryPoint::class.java)
        val skillContext = entryPoint.skillContext()
        if (!GarChips.languageMatches(skillContext)) {
            return listOf()
        }
        val logOn = try {
            GarVoiceLog.settingOn(entryPoint.garSettings().data.first())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            false
        }
        val stamp = indexStamp(appContext, logOn)
        cache?.takeIf { SystemClock.elapsedRealtime() - it.time < CACHE_MILLIS && it.stamp == stamp }
            ?.let { return it.chips }

        val defaults = defaults(appContext)
        val chips = try {
            val turns = if (logOn) readTurns(appContext) else listOf()
            val candidates = learned(turns, System.currentTimeMillis(), defaults)
            val skills = if (candidates.isEmpty()) {
                listOf()
            } else {
                withTimeoutOrNull(SKILLS_TIMEOUT_MILLIS) {
                    entryPoint.skillHandler().enabledSkillsInfo.filterNotNull().first()
                } ?: listOf()
            }
            val stillMatching = candidates
                .filter { (chip, skillId) -> stillMatches(skillContext, skills, skillId, chip.input) }
            fill(stillMatching, defaults)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read the starters from the voice log", t)
            defaults.map { it.first }
        }
        cache = Cached(SystemClock.elapsedRealtime(), stamp, chips)
        return chips
    }

    private fun indexFile(context: Context): File =
        File(File(context.filesDir, VoiceLogFiles.DIR_NAME), VoiceStarterIndex.NAME)

    /** What the starters depend on: the setting and the index's size and time. */
    private fun indexStamp(context: Context, logOn: Boolean): String =
        if (logOn) {
            indexFile(context).let { "${it.length()}:${it.lastModified()}" }
        } else {
            "off"
        }

    /**
     * The turns of the starter index, oldest first; none before the voice log built it (then
     * the defaults show until it did, see `GarVoiceLog.install`).
     */
    private fun readTurns(context: Context): List<Turn> =
        VoiceStarterIndex.read(File(context.filesDir, VoiceLogFiles.DIR_NAME))
            ?.mapNotNull(::parse)
            ?: listOf()

    /**
     * Whether the skill [skillId] (among the enabled [skills]) still matches [input] with its
     * sentences, by the score the ranker would need in its last round. Uses its own match
     * helper, so it never touches the evaluator's.
     */
    private fun stillMatches(
        base: SkillContext,
        skills: List<SkillInfo>,
        skillId: String,
        input: String,
    ): Boolean = try {
        val info = skills.firstOrNull { it.id == skillId }
        val ctx = ScoringContext(base, MatchHelper(base.parserFormatter, input))
        val skill = info?.build(ctx)
        if (skill == null) {
            false
        } else {
            val score = skill.score(ctx, input).first.scoreIn01Range()
            score > when (skill.specificity) {
                Specificity.HIGH -> HIGH_THRESHOLD
                Specificity.MEDIUM -> MEDIUM_THRESHOLD
                Specificity.LOW -> LOW_THRESHOLD
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Could not score a starter for $skillId", t)
        false
    }

    /** The evaluator's context with a match helper of its own, for [stillMatches]. */
    private class ScoringContext(
        private val base: SkillContext,
        override val standardMatchHelper: MatchHelper,
    ) : SkillContext {
        override val android: Context get() = base.android
        override val locale: Locale get() = base.locale
        override val sentencesLanguage: String get() = base.sentencesLanguage
        override val parserFormatter: ParserFormatter? get() = base.parserFormatter
        override val speechOutputDevice: SpeechOutputDevice get() = base.speechOutputDevice
        override val previousOutput: SkillOutput? get() = null
    }

    /** The voice log's name for a match by a skill's sentences (`VoiceTurn.routedBy`). */
    const val PATH_SENTENCE = "sentence"

    // SkillRanker's last-round thresholds (private there): the least a default match needs
    private const val HIGH_THRESHOLD = 0.70f
    private const val MEDIUM_THRESHOLD = 0.80f
    private const val LOW_THRESHOLD = 0.90f
    private const val SKILLS_TIMEOUT_MILLIS = 5_000L
}

/**
 * The starter chips: the cached ones (or the defaults) at once, then the ones read from the
 * voice log; none over the lock screen. Previews get the defaults.
 */
@Composable
fun rememberGarStarterChips(): State<List<GarChip>> {
    val context = LocalContext.current
    if (LocalInspectionMode.current) {
        return remember { mutableStateOf(GarStarterChips.defaults(context).map { it.first }) }
    }
    val locked = remember(context) {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true
    }
    val initial = remember(context) {
        if (locked) {
            listOf()
        } else {
            GarStarterChips.cached() ?: GarStarterChips.defaults(context).map { it.first }
        }
    }
    return produceState(initialValue = initial, context) {
        value = withContext(GarStarterChips.dispatcher) { GarStarterChips.get(context) }
    }
}
