package org.stypox.dicio.skills.song

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.call.GarYesNo
import org.stypox.dicio.gar.call.normalizeAnswer
import java.net.URLEncoder

/** An app that opens a link, as plain data (Android's ResolveInfo isn't available in JVM tests). */
data class LinkHandler(val packageName: String, val label: String)

/** Where Audile can be installed from. */
enum class AudileSource {
    /** Obtainium, which installs Audile from its GitHub releases and keeps it updated. */
    OBTAINIUM,

    /** An F-Droid client: F-Droid builds and signs Audile itself. */
    FDROID,

    /** Audile's latest release on GitHub, in the browser. */
    GITHUB,
}

/**
 * A page that installs Audile.
 * @param uri what is opened with ACTION_VIEW
 * @param handler the app that opens it, null for whichever app opens web links
 */
data class AudileInstallTarget(
    val source: AudileSource,
    val uri: String,
    val handler: LinkHandler?,
)

/** How opening Audile's install page went, which picks the answer. */
sealed interface AudileInstallResult {
    data class Opened(val target: AudileInstallTarget) : AudileInstallResult

    /** GAR isn't visible and isn't the assistant, so Android would block the start. */
    data object BlockedInBackground : AudileInstallResult

    /** No page opened, not even in a browser. */
    data object Failed : AudileInstallResult
}

/** What opening an install page needs from the device, faked in tests. */
interface AudileInstallLauncher {
    /** Whether GAR's own UI is visible or GAR is the active assistant (background starts). */
    fun canStartActivities(): Boolean

    /** The apps that open [uri] with ACTION_VIEW, in the system's order. */
    fun handlers(uri: String): List<LinkHandler>

    /** @return false if [target] didn't open */
    fun open(target: AudileInstallTarget): Boolean
}

/**
 * Where GAR sends the user to install Audile (gar/DESIGN.md, "Step 4: song"). Audile is published
 * on GitHub (AudileTeam/Audile, which aleksey-saenko/MusicRecognizer redirects to) and in
 * F-Droid's main repository; it isn't on Google Play or IzzyOnDroid (checked 2026-10-27).
 */
object AudileInstall {
    const val REPO_URL = "https://github.com/AudileTeam/Audile"

    /** The latest release's page, with the APK to download. */
    const val RELEASES_URL = "$REPO_URL/releases/latest"

    /**
     * Obtainium's "add app" link (wiki.obtainium.imranr.dev/deep_links): Obtainium's Add App
     * screen opens with Audile's repository filled in, or Audile's page when Obtainium already
     * tracks it (an earlier yes whose install didn't finish).
     */
    val OBTAINIUM_URI = "obtainium://add?url=" + URLEncoder.encode(REPO_URL, "UTF-8")

    /** The link that app stores open on an app's page. */
    const val STORE_URI = "market://details?id=" + AudileIntents.PACKAGE

    /**
     * F-Droid and its clients, which show F-Droid's repository. Other apps that open store links
     * (Google Play, Aurora Store) don't have Audile.
     */
    val FDROID_CLIENTS = setOf(
        "org.fdroid.fdroid", // F-Droid
        "org.fdroid.basic", // F-Droid Basic
        "com.looker.droidify", // Droid-ify
        "com.machiav3lli.fdroid", // Neo Store
    )

    /**
     * The pages to try, best first: Obtainium (the user's way to install apps) if it is
     * installed, then an F-Droid client, then the GitHub release in the browser.
     * @param handlers the apps that open a link, see [AudileInstallLauncher.handlers]
     */
    fun targets(handlers: (String) -> List<LinkHandler>): List<AudileInstallTarget> =
        listOfNotNull(
            handlers(OBTAINIUM_URI).firstOrNull()?.let {
                AudileInstallTarget(AudileSource.OBTAINIUM, OBTAINIUM_URI, it)
            },
            handlers(STORE_URI).firstOrNull { it.packageName in FDROID_CLIENTS }?.let {
                AudileInstallTarget(AudileSource.FDROID, STORE_URI, it)
            },
            AudileInstallTarget(AudileSource.GITHUB, RELEASES_URL, null),
        )

    /** Opens the first of [targets] that opens. */
    fun open(launcher: AudileInstallLauncher): AudileInstallResult {
        if (!launcher.canStartActivities()) {
            return AudileInstallResult.BlockedInBackground // a start would be blocked silently
        }
        for (target in targets(launcher::handlers)) {
            if (launcher.open(target)) {
                return AudileInstallResult.Opened(target)
            }
        }
        return AudileInstallResult.Failed
    }
}

class AndroidAudileInstallLauncher(private val context: Context) : AudileInstallLauncher {
    override fun canStartActivities(): Boolean =
        AndroidAudileLauncher(context).canStartActivities()

    override fun handlers(uri: String): List<LinkHandler> {
        val packageManager = context.packageManager
        return packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)), PackageManager.MATCH_DEFAULT_ONLY,
        ).map {
            LinkHandler(
                it.activityInfo.packageName,
                it.activityInfo.applicationInfo.loadLabel(packageManager).toString(),
            )
        }
    }

    override fun open(target: AudileInstallTarget): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        target.handler?.let { intent.setPackage(it.packageName) }
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing opens ${target.uri}", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed to open ${target.uri}", e)
        false
    }

    companion object {
        private const val TAG = "AudileInstall"
    }
}

/** Opens Audile's install page from a button on GAR's screen; a toast says if nothing opened. */
fun openAudileInstall(context: Context) {
    if (AudileInstall.open(AndroidAudileInstallLauncher(context)) is AudileInstallResult.Failed) {
        Toast.makeText(context, R.string.skill_song_install_failed, Toast.LENGTH_LONG).show()
    }
}

/**
 * Whether GAR asks to install Audile when nothing else can recognize a song, see
 * [AudileInstallAskOutput]. Otherwise it only says that it needs Audile and where to get it
 * ([AudileMissingOutput]).
 */
object AudileInstallPrompt {
    /**
     * @param declined the user already said no (`audile_install_declined`)
     * @param locked the phone is locked: the answer is the owner's lasting choice, and an install
     * page would open behind the lock screen, so GAR asks once the phone is unlocked
     * @param english the question's answers are English only ([AudileInstallAnswers])
     */
    fun asks(declined: Boolean, locked: Boolean, english: Boolean): Boolean =
        !declined && !locked && english
}

/** What GAR's question about Audile does on the device and in the settings, faked in tests. */
interface AudileInstallHost {
    /** Whether the phone is locked with a PIN, pattern, ... */
    fun isDeviceLocked(): Boolean

    /** Remembers the user's no (`audile_install_declined`), so GAR doesn't ask again. */
    suspend fun decline()

    /** Opens Audile's install page, see [AudileInstall.open]. */
    fun install(): AudileInstallResult
}

class AndroidAudileInstallHost(private val context: Context) : AudileInstallHost {
    override fun isDeviceLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    override suspend fun decline() {
        try {
            context.songSettingsDataStore.updateData {
                it.toBuilder().setAudileInstallDeclined(true).build()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't save the no to installing Audile", e)
        }
    }

    override fun install(): AudileInstallResult =
        AudileInstall.open(AndroidAudileInstallLauncher(context))

    companion object {
        private const val TAG = "AudileInstallHost"
    }
}

/** An answer to "Want to install it?" ([AudileInstallAskOutput]). */
enum class AudileInstallAnswer {
    /** Opens Audile's install page. */
    YES,

    /** A clear no, which is remembered (`audile_install_declined`). */
    NO,

    /** "not now", "maybe later": nothing is remembered, so GAR asks again next time. */
    LATER,
}

/**
 * The answer to an [AudileInstallAskOutput]: [AudileInstallAnswer.YES] (yes, or the [yesLabel]
 * button) opens Audile's install page and remembers nothing, so GAR asks again if Audile still
 * isn't installed next time; [AudileInstallAnswer.NO] (no, or the [noLabel] button) remembers the
 * no; [AudileInstallAnswer.LATER] remembers nothing and says GAR will ask next time. Matches
 * nothing else, so that a new command goes to its own skill and leaves the question unanswered.
 * A yes on a phone that was locked meanwhile opens nothing, like the online skills' question.
 */
class AudileInstallAnswerSkill(
    correspondingSkillInfo: SkillInfo,
    private val host: AudileInstallHost,
    private val yesLabel: String,
    private val noLabel: String,
    private val onAnswered: () -> Unit = {},
) : Skill<AudileInstallAnswer?>(correspondingSkillInfo, Specificity.HIGH) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, AudileInstallAnswer?> {
        val answer = when (normalizeAnswer(input)) {
            normalizeAnswer(yesLabel) -> AudileInstallAnswer.YES
            normalizeAnswer(noLabel) -> AudileInstallAnswer.NO
            else -> AudileInstallAnswers.classify(input)
        }
        return Pair(if (answer == null) AlwaysWorstScore else AlwaysBestScore, answer)
    }

    override suspend fun generateOutput(
        ctx: SkillContext,
        inputData: AudileInstallAnswer?,
    ): SkillOutput {
        onAnswered()
        // AlwaysWorstScore is never chosen, so null can't happen
        return when {
            inputData == AudileInstallAnswer.LATER ->
                SongTextOutput(SongText(R.string.skill_song_install_later))
            inputData != AudileInstallAnswer.YES -> {
                host.decline()
                SongTextOutput(SongText(R.string.skill_song_install_declined))
            }
            // nothing is remembered: GAR asks again once the phone is unlocked
            host.isDeviceLocked() -> GarLockedOutput()
            else -> SongTextOutput(SongMessages.install(host.install()))
        }
    }
}

/**
 * Short spoken answers to "Want to install it?", English only like GAR's other answers: yes to
 * install ("yes", "sure", "install it", "get it", "go ahead"), no ("no", "no thanks", "never
 * mind", "I'm good", "don't"), later ("not now", "maybe later", "not yet"), null for anything
 * else. Only answers made of [ANSWER_WORDS] count, so a new command ("what song is this", "set a
 * timer") is no answer. Unclear replies are no answer either ("it's fine", "all good", "not
 * sure", "no wait", "I don't get it", a bare "do" or "go"), so that only a clear no is
 * remembered. Built like `GarOnlineAnswers` on the shared yes/no core [GarYesNo].
 */
object AudileInstallAnswers {
    private val YES_WORDS = GarYesNo.YES_WORDS + setOf("course")

    /**
     * Yes words that aren't a yes after [POLITE_LEADS] ("that's okay", "it's fine", "all good",
     * often a polite no thanks) and are a no after "I'm" ("I'm good").
     */
    private val WEAK_YES_WORDS = setOf("ok", "okay", "alright", "right", "fine", "good")
    private val POLITE_LEADS = setOf("it's", "its", "that's", "thats", "all")
    private val IM = setOf("i'm", "im")

    /** Before "get": "I don't get it" is confused, not a no. */
    private val NOT_WORDS = setOf("don't", "dont", "not")

    /** An explicit go-ahead on their own: "install it", "get it", "download Audile". */
    private val INSTALL_VERBS = setOf("install", "get", "download", "open")

    /** "please do", "yes do", "I do", but not "do" alone, which may be a misheard "no". */
    private val DO_LEADS = setOf("please", "yes", "i")

    private val ANSWER_WORDS = YES_WORDS + GarYesNo.NO_WORDS + GarYesNo.NEGATIONS + INSTALL_VERBS +
        IM + setOf(
            "it", "it's", "its", "that", "that's", "thats", "this", "the", "all", "do", "go",
            "ahead", "for", "let", "let's", "lets", "why", "of", "now", "yet", "later", "i", "you",
            "can", "could", "would", "want", "like", "to", "mind", "maybe", "wait", "actually",
            "thanks", "thank", "please", "just", "so", "well", "oh", "uh", "um", "hmm", "and",
            "then", "sounds", "need", "me", "audile", "app",
        )

    fun classify(input: String): AudileInstallAnswer? {
        val said = normalizeAnswer(input).split(" ").filter { it.isNotEmpty() }
        if (said.isEmpty() || said.any { it !in ANSWER_WORDS }) {
            return null
        }
        // "no wait" may be cut off before what follows: "no wait, install it"
        if (said.last() == "wait" && said.getOrNull(said.size - 2) in GarYesNo.NO_WORDS) {
            return null
        }
        // "why not" and "sure, why not" are a yes, "not sure" is unsure: neither is a negation
        val words = said.joinToString(" ").replace("why not", "yes").replace("not sure", "maybe")
            .split(" ")
        if (words.indices.any { words[it] == "get" && words.getOrNull(it - 1) in NOT_WORDS }) {
            return null
        }
        // before the negations: "no, maybe later" and "not now" are a no for now only
        if (words.indices.any { isLater(words, it) }) {
            return AudileInstallAnswer.LATER
        }
        if (words.indices.any { GarYesNo.isNegation(words, it) }) {
            return AudileInstallAnswer.NO
        }
        return when (
            GarYesNo.answer(
                words,
                isYes = { words[it] in YES_WORDS && !isPolite(words, it) && !isNoThanks(words, it) },
                isGoAhead = { isGoAhead(words, it) },
                isNo = { isNoThanks(words, it) },
            )
        ) {
            true -> AudileInstallAnswer.YES
            false -> AudileInstallAnswer.NO
            null -> null
        }
    }

    /** "later", "not now", "not right now", "not yet". */
    private fun isLater(words: List<String>, index: Int): Boolean = when (words[index]) {
        "later" -> true
        "now", "yet" -> "not" in words.subList(maxOf(0, index - 2), index)
        else -> false
    }

    /** "that's okay", "it's fine", "all good", but not "all right", which is a yes. */
    private fun isPolite(words: List<String>, index: Int): Boolean {
        val before = words.getOrNull(index - 1)
        return words[index] in WEAK_YES_WORDS && before in POLITE_LEADS &&
            !(before == "all" && words[index] == "right")
    }

    /** "I'm good", "I'm fine": no thanks. */
    private fun isNoThanks(words: List<String>, index: Int): Boolean =
        words[index] in WEAK_YES_WORDS && words.getOrNull(index - 1) in IM

    /**
     * "install it", "get it", "I want it", "do it", "please do", "go ahead", "go for it", "let's
     * go", but not "go" or "do" alone, which may be a misheard "no".
     */
    private fun isGoAhead(words: List<String>, index: Int): Boolean {
        val word = words[index]
        return word in INSTALL_VERBS ||
            (word == "want" && words.getOrNull(index - 1) == "i") ||
            (word == "do" && (words.getOrNull(index + 1) == "it" ||
                (index == words.lastIndex && words.getOrNull(index - 1) in DO_LEADS))) ||
            (word == "go" && (words.getOrNull(index + 1) in setOf("ahead", "for") ||
                words.getOrNull(index - 1) in setOf("let's", "lets")))
    }
}
