package org.stypox.dicio.skills.song

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.dicio.skill.skill.AlwaysBestScore
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.skills.timer.TimerInfo
import java.net.URLDecoder

private val OBTAINIUM = LinkHandler("dev.imranr.obtainium", "Obtainium")
private val FDROID = LinkHandler("org.fdroid.fdroid", "F-Droid")
private val DROIDIFY = LinkHandler("com.looker.droidify", "Droid-ify")
private val PLAY = LinkHandler("com.android.vending", "Google Play Store")
private val AURORA = LinkHandler("com.aurora.store", "Aurora Store")

/** A phone with the [apps] that open each link scheme. */
private class FakeInstallLauncher(
    val apps: Map<String, List<LinkHandler>> = mapOf(),
    var foreground: Boolean = true,
    /** The sources that open. */
    val working: Set<AudileSource> = AudileSource.entries.toSet(),
) : AudileInstallLauncher {
    val tried = mutableListOf<AudileInstallTarget>()

    override fun canStartActivities() = foreground
    override fun handlers(uri: String): List<LinkHandler> =
        apps[uri.substringBefore(':')].orEmpty()

    override fun open(target: AudileInstallTarget): Boolean {
        tried.add(target)
        return target.source in working
    }
}

/** The question's side effects: the no it remembers and the pages it opens. */
private class FakeInstallHost(
    var locked: Boolean = false,
    val result: AudileInstallResult = AudileInstallResult.Opened(
        AudileInstallTarget(AudileSource.OBTAINIUM, AudileInstall.OBTAINIUM_URI, OBTAINIUM)
    ),
) : AudileInstallHost {
    var declined = false
    var installs = 0

    override fun isDeviceLocked() = locked
    override suspend fun decline() {
        declined = true
    }

    override fun install(): AudileInstallResult {
        installs++
        return result
    }
}

private fun ask(host: AudileInstallHost, nowPlaying: NowPlaying = NowPlaying.NothingPlaying) =
    AudileInstallAskOutput(SongInfo, nowPlaying, host, rankers = null)

private fun answerSkill(ask: AudileInstallAskOutput): AudileInstallAnswerSkill =
    ask.nextSkills("Install Audile", "No thanks").single()
        .shouldBeInstanceOf<AudileInstallAnswerSkill>()

class SongAudileInstallTest : StringSpec({
    "Audile's links: Obtainium's add link to its GitHub repository, F-Droid and its release" {
        AudileInstall.OBTAINIUM_URI shouldBe
            "obtainium://add?url=https%3A%2F%2Fgithub.com%2FAudileTeam%2FAudile"
        URLDecoder.decode(AudileInstall.OBTAINIUM_URI.substringAfter("url="), "UTF-8") shouldBe
            AudileInstall.REPO_URL
        AudileInstall.STORE_URI shouldBe "market://details?id=com.mrsep.musicrecognizer"
        AudileInstall.RELEASES_URL shouldBe "https://github.com/AudileTeam/Audile/releases/latest"
    }

    "Obtainium first, then an F-Droid app, then GitHub in the browser" {
        val all = FakeInstallLauncher(
            mapOf("obtainium" to listOf(OBTAINIUM), "market" to listOf(PLAY, DROIDIFY, FDROID))
        )
        AudileInstall.targets(all::handlers) shouldContainExactly listOf(
            AudileInstallTarget(AudileSource.OBTAINIUM, AudileInstall.OBTAINIUM_URI, OBTAINIUM),
            // the first F-Droid app in the system's order, never Google Play
            AudileInstallTarget(AudileSource.FDROID, AudileInstall.STORE_URI, DROIDIFY),
            AudileInstallTarget(AudileSource.GITHUB, AudileInstall.RELEASES_URL, null),
        )

        val fdroidOnly = FakeInstallLauncher(mapOf("market" to listOf(FDROID)))
        AudileInstall.targets(fdroidOnly::handlers).map { it.source } shouldContainExactly
            listOf(AudileSource.FDROID, AudileSource.GITHUB)
    }

    "Google Play and Aurora Store don't have Audile" {
        val stores = FakeInstallLauncher(mapOf("market" to listOf(PLAY, AURORA)))
        AudileInstall.targets(stores::handlers) shouldContainExactly listOf(
            AudileInstallTarget(AudileSource.GITHUB, AudileInstall.RELEASES_URL, null),
        )
    }

    "the first page that opens is the one" {
        val launcher = FakeInstallLauncher(
            mapOf("obtainium" to listOf(OBTAINIUM), "market" to listOf(FDROID)),
        )
        AudileInstall.open(launcher).shouldBeInstanceOf<AudileInstallResult.Opened>()
            .target.source shouldBe AudileSource.OBTAINIUM
        launcher.tried.map { it.source } shouldContainExactly listOf(AudileSource.OBTAINIUM)

        // Obtainium refused the link: F-Droid, then the browser
        val refused = FakeInstallLauncher(
            mapOf("obtainium" to listOf(OBTAINIUM), "market" to listOf(FDROID)),
            working = setOf(AudileSource.GITHUB),
        )
        AudileInstall.open(refused).shouldBeInstanceOf<AudileInstallResult.Opened>()
            .target.source shouldBe AudileSource.GITHUB
        refused.tried.map { it.source } shouldContainExactly
            listOf(AudileSource.OBTAINIUM, AudileSource.FDROID, AudileSource.GITHUB)

        // not even a browser
        AudileInstall.open(FakeInstallLauncher(working = setOf())) shouldBe
            AudileInstallResult.Failed
    }

    "from the background without the assistant role nothing is opened" {
        val launcher = FakeInstallLauncher(
            mapOf("obtainium" to listOf(OBTAINIUM)), foreground = false,
        )
        AudileInstall.open(launcher) shouldBe AudileInstallResult.BlockedInBackground
        launcher.tried.shouldBeEmpty()
    }

    "GAR asks until the user says no, not on a locked phone, and only in English" {
        AudileInstallPrompt.asks(declined = false, locked = false, english = true) shouldBe true
        AudileInstallPrompt.asks(declined = true, locked = false, english = true) shouldBe false
        AudileInstallPrompt.asks(declined = false, locked = true, english = true) shouldBe false
        AudileInstallPrompt.asks(declined = false, locked = false, english = false) shouldBe false
    }

    "the question goes to the song skill and remembers nothing yet" {
        val host = FakeInstallHost()
        val ask = ask(host)
        answerSkill(ask).correspondingSkillInfo shouldBe SongInfo
        ask.askedRanker.shouldBeNull()
        ask.answered shouldBe false
        host.declined shouldBe false
        host.installs shouldBe 0
    }

    "yes: the install page opens, and nothing is remembered" {
        for (answer in listOf(
            "yes", "sure", "install it", "Install Audile", "yes please", "go ahead", "get it",
            "download it", "ok", "sounds good", "do it", "please do", "I want it", "why not",
            "of course", "no wait, install it",
        )) {
            withClue(answer) {
                val host = FakeInstallHost()
                val ask = ask(host)
                val skill = answerSkill(ask)
                val (score, data) = skill.score(FakeSkillContext(), answer)
                score shouldBe AlwaysBestScore
                data shouldBe AudileInstallAnswer.YES

                skill.generateOutput(FakeSkillContext(), data)
                    .shouldBeInstanceOf<SongTextOutput>().text shouldBe
                    SongText(R.string.skill_song_install_obtainium)
                host.installs shouldBe 1
                host.declined shouldBe false
                ask.answered shouldBe true
            }
        }
    }

    "no: the choice is remembered and nothing opens" {
        for (answer in listOf(
            "no", "No thanks", "nope", "nah", "never mind", "cancel", "don't", "I'm good",
            "no I don't want it", "stop", "don't install it now",
        )) {
            withClue(answer) {
                val host = FakeInstallHost()
                val skill = answerSkill(ask(host))
                val (score, data) = skill.score(FakeSkillContext(), answer)
                score shouldBe AlwaysBestScore
                data shouldBe AudileInstallAnswer.NO

                skill.generateOutput(FakeSkillContext(), data)
                    .shouldBeInstanceOf<SongTextOutput>().text shouldBe
                    SongText(R.string.skill_song_install_declined)
                host.declined shouldBe true
                host.installs shouldBe 0
            }
        }
    }

    "later: nothing is remembered, nothing opens, and GAR asks again next time" {
        for (answer in listOf(
            "not now", "maybe later", "later", "not yet", "not right now", "no, maybe later",
            "no not now", "not now thanks", "install it later",
        )) {
            withClue(answer) {
                val host = FakeInstallHost()
                val ask = ask(host)
                val skill = answerSkill(ask)
                val (score, data) = skill.score(FakeSkillContext(), answer)
                score shouldBe AlwaysBestScore
                data shouldBe AudileInstallAnswer.LATER

                skill.generateOutput(FakeSkillContext(), data)
                    .shouldBeInstanceOf<SongTextOutput>().text shouldBe
                    SongText(R.string.skill_song_install_later)
                host.declined shouldBe false
                host.installs shouldBe 0
                ask.answered shouldBe true
            }
        }
        // on a locked phone too
        val host = FakeInstallHost(locked = true)
        answerSkill(ask(host)).generateOutput(FakeSkillContext(), AudileInstallAnswer.LATER)
            .shouldBeInstanceOf<SongTextOutput>().text shouldBe
            SongText(R.string.skill_song_install_later)
        host.declined shouldBe false
        host.installs shouldBe 0
    }

    "any other reply answers nothing, and GAR asks again next time" {
        val host = FakeInstallHost()
        val ask = ask(host)
        val skill = answerSkill(ask)
        for (reply in listOf(
            "set a timer for ten minutes", "what song is this", "what is audile", "", "hmm what",
            // unclear: often a polite no, unsure, cut off or a misheard "no"
            "it's fine", "that's okay", "all good", "not sure", "no wait", "do", "go", "please",
            "I don't get it", "I do not get it",
        )) {
            withClue(reply) { skill.score(FakeSkillContext(), reply).first shouldBe AlwaysWorstScore }
        }

        // a new command goes to its own skill, and drops the question
        val timer = TimerInfo.build(FakeSkillContext())!!
        val ranker = SkillRanker(listOf(timer), MockSkill(Specificity.LOW, 0.0f))
        ranker.addBatchToTop(ask.nextSkills("Install Audile", "No thanks"))
        val input = "set a timer for ten minutes"
        ranker.getBest(FakeSkillContext().forInput(input), input)?.skill shouldBeSameInstanceAs timer
        ranker.hasAnyBatches() shouldBe false

        host.declined shouldBe false
        host.installs shouldBe 0
    }

    "locked before the answer: a yes opens nothing, a no is remembered" {
        val host = FakeInstallHost()
        val ask = ask(host)
        host.locked = true
        answerSkill(ask).generateOutput(FakeSkillContext(), AudileInstallAnswer.YES)
            .shouldBeInstanceOf<GarLockedOutput>()
        host.installs shouldBe 0
        host.declined shouldBe false

        answerSkill(ask).generateOutput(FakeSkillContext(), AudileInstallAnswer.NO)
            .shouldBeInstanceOf<SongTextOutput>().text shouldBe
            SongText(R.string.skill_song_install_declined)
        host.declined shouldBe true
    }

    "a yes that opens nothing says so" {
        val host = FakeInstallHost(result = AudileInstallResult.BlockedInBackground)
        answerSkill(ask(host)).generateOutput(FakeSkillContext(), AudileInstallAnswer.YES)
            .shouldBeInstanceOf<SongTextOutput>().text shouldBe
            SongText(R.string.skill_song_install_background)
    }

    "every install result has its text" {
        val obtainium =
            AudileInstallTarget(AudileSource.OBTAINIUM, AudileInstall.OBTAINIUM_URI, OBTAINIUM)
        val droidify = AudileInstallTarget(AudileSource.FDROID, AudileInstall.STORE_URI, DROIDIFY)
        val github = AudileInstallTarget(AudileSource.GITHUB, AudileInstall.RELEASES_URL, null)
        mapOf(
            AudileInstallResult.Opened(obtainium) to SongText(R.string.skill_song_install_obtainium),
            // the F-Droid app by its name
            AudileInstallResult.Opened(droidify) to
                SongText(R.string.skill_song_install_store, listOf("Droid-ify")),
            AudileInstallResult.Opened(github) to SongText(R.string.skill_song_install_github),
            AudileInstallResult.BlockedInBackground to SongText(R.string.skill_song_install_background),
            AudileInstallResult.Failed to SongText(R.string.skill_song_install_failed),
        ).forEach { (result, text) -> SongMessages.install(result) shouldBe text }
    }

    "the question's card says why the phone's player can't answer" {
        SongMessages.installHint(NowPlaying.NoAccess) shouldBe R.string.skill_song_install_hint_listener
        SongMessages.installHint(NowPlaying.Untitled) shouldBe R.string.skill_song_install_hint_untitled
        SongMessages.installHint(NowPlaying.NothingPlaying).shouldBeNull()
        ask(FakeInstallHost(), NowPlaying.NoAccess).nowPlaying shouldBe NowPlaying.NoAccess
    }
})
