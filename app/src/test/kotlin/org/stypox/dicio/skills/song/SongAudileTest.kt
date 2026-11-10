package org.stypox.dicio.skills.song

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

private class FakeAudileLauncher(
    var foreground: Boolean = true,
    var locked: Boolean = false,
    var shortcutHost: Boolean = false,
    /** The starts that work. */
    val working: Set<Class<out AudileStart>> = setOf(
        AudileStart.BackgroundShortcut::class.java,
        AudileStart.Recognize::class.java,
        AudileStart.Launch::class.java,
    ),
) : AudileLauncher {
    val tried = mutableListOf<AudileStart>()

    override fun canStartActivities() = foreground
    override fun isLocked() = locked
    override fun isShortcutHost() = shortcutHost
    override fun start(start: AudileStart): Boolean {
        tried.add(start)
        return start.javaClass in working
    }
}

class SongAudileTest : StringSpec({
    "the release build is preferred, the debug build works too" {
        AudileIntents.installed { true } shouldBe "com.mrsep.musicrecognizer"
        AudileIntents.installed { it == "com.mrsep.musicrecognizer.debug" } shouldBe
            "com.mrsep.musicrecognizer.debug"
        AudileIntents.installed { it == "org.example.other" } shouldBe null
    }

    "the RECOGNIZE intent goes to Audile's MainActivity, in either build" {
        AudileIntents.recognizeIntent("com.mrsep.musicrecognizer") shouldBe ComponentIntent(
            action = "com.mrsep.musicrecognizer.intent.action.RECOGNIZE",
            packageName = "com.mrsep.musicrecognizer",
            className = "com.mrsep.musicrecognizer.presentation.MainActivity",
        )
        AudileIntents.recognizeIntent("com.mrsep.musicrecognizer.debug").className shouldBe
            "com.mrsep.musicrecognizer.presentation.MainActivity"
        AudileIntents.BACKGROUND_SHORTCUT_ID shouldBe "RecognizeInBackgroundShortcutId"
    }

    "the background shortcut comes first, only for a shortcut host" {
        val p = AudileIntents.PACKAGE
        AudileIntents.attempts(p, shortcutHost = true) shouldBe listOf(
            AudileStart.BackgroundShortcut(p), AudileStart.Recognize(p), AudileStart.Launch(p),
        )
        AudileIntents.attempts(p, shortcutHost = false) shouldBe listOf(
            AudileStart.Recognize(p), AudileStart.Launch(p),
        )
    }

    "GAR on screen opens Audile listening" {
        val launcher = FakeAudileLauncher()
        AudileHandOff.run(AudileIntents.PACKAGE, launcher) shouldBe AudileResult.OPENED
        launcher.tried shouldBe listOf(AudileStart.Recognize(AudileIntents.PACKAGE))
    }

    "as the assistant, Audile listens in the background" {
        val launcher = FakeAudileLauncher(foreground = false, shortcutHost = true)
        AudileHandOff.run(AudileIntents.PACKAGE, launcher) shouldBe
            AudileResult.LISTENING_IN_BACKGROUND
        // also on the lock screen: Audile's background activity shows over it
        FakeAudileLauncher(locked = true, shortcutHost = true).let {
            AudileHandOff.run(AudileIntents.PACKAGE, it) shouldBe AudileResult.LISTENING_IN_BACKGROUND
        }
    }

    "a missing shortcut or a refused intent falls back to the next start" {
        val noShortcut = FakeAudileLauncher(
            shortcutHost = true,
            working = setOf(AudileStart.Recognize::class.java, AudileStart.Launch::class.java),
        )
        AudileHandOff.run(AudileIntents.DEBUG_PACKAGE, noShortcut) shouldBe AudileResult.OPENED
        noShortcut.tried shouldBe listOf(
            AudileStart.BackgroundShortcut(AudileIntents.DEBUG_PACKAGE),
            AudileStart.Recognize(AudileIntents.DEBUG_PACKAGE),
        )

        val launchOnly = FakeAudileLauncher(working = setOf(AudileStart.Launch::class.java))
        AudileHandOff.run(AudileIntents.PACKAGE, launchOnly) shouldBe AudileResult.OPENED_APP

        val nothing = FakeAudileLauncher(working = setOf())
        AudileHandOff.run(AudileIntents.PACKAGE, nothing) shouldBe AudileResult.FAILED
        nothing.tried.size shouldBe 2
    }

    "from the background without the assistant role nothing is started" {
        val launcher = FakeAudileLauncher(foreground = false)
        AudileHandOff.run(AudileIntents.PACKAGE, launcher) shouldBe
            AudileResult.BLOCKED_IN_BACKGROUND
        launcher.tried.shouldBeEmpty()
    }

    "on the lock screen Audile opens behind it" {
        AudileHandOff.run(AudileIntents.PACKAGE, FakeAudileLauncher(locked = true)) shouldBe
            AudileResult.OPENED_LOCKED
    }
})
