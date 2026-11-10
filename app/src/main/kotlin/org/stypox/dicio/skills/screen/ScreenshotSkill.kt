package org.stypox.dicio.skills.screen

import android.app.Activity
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import cc.aaro.gar.router.Utterances
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.gar.sheet.GarSheet
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Screenshot
import org.stypox.dicio.util.getString

object ScreenshotInfo : SkillInfo("screenshot") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_screenshot)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_screenshot)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Screenshot)

    // no permissions: GAR's screenshot service is an accessibility service the user turns on in
    // Accessibility settings, which the skill offers to open (Dicio's permission check can't)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Screenshot[ctx.sentencesLanguage] ?: return null
        return ScreenshotSkill(ScreenshotInfo, data)
    }
}

/** What the screenshot skill does on the device, so that its decisions can be tested. */
interface ScreenshotControl {
    fun isLocked(): Boolean

    /** Takes a screenshot after [delayMillis], or returns false when the service isn't on. */
    fun take(delayMillis: Long): Boolean

    /** Whether the service is running, so that [take] would work. */
    fun isOn(): Boolean

    fun openAccessibilitySettings(): Boolean
}

class AndroidScreenshotControl(private val context: Context) : ScreenshotControl {
    override fun isLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    override fun take(delayMillis: Long): Boolean {
        val service = GarScreenshotService.instance ?: return false
        service.takeScreenshot(delayMillis)
        return true
    }

    override fun isOn(): Boolean =
        GarScreenshotService.instance != null && GarScreenshotService.isEnabled(context)

    override fun openAccessibilitySettings(): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w("ScreenshotControl", "No accessibility settings", e)
        false
    }
}

/**
 * GAR (gar:screen). "Take a screenshot": GAR's own window goes away first (its task moves to the
 * back, [ScreenshotOutput]), then GAR's optional accessibility service ([GarScreenshotService])
 * takes the screenshot of what was under it. While the service is off, GAR offers to open
 * Accessibility settings and says what the service does. English only.
 */
class ScreenshotSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Screenshot>,
    private val controlFactory: (SkillContext) -> ScreenshotControl = { AndroidScreenshotControl(it.android) },
    private val yesNo: (SkillContext) -> org.dicio.skill.standard.StandardRecognizerData<Sentences.UtilYesNo>? =
        { Sentences.UtilYesNo[it.sentencesLanguage] },
) : StandardRecognizerSkill<Screenshot>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Screenshot> {
        val (score, sentence) = super.score(ctx, input)
        return Pair(if (ScreenWords.asksForScreenshot(input)) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Screenshot): SkillOutput {
        val control = controlFactory(ctx)
        return when {
            // a screenshot of the lock screen and GAR's own answer: nothing anyone asks for
            control.isLocked() -> GarLockedOutput()
            control.isOn() -> ScreenshotOutput(control)
            else -> offer(ctx, control)
        }
    }

    private fun offer(ctx: SkillContext, control: ScreenshotControl) = GarOfferOutput(
        info = correspondingSkillInfo,
        speech = { it.getString(R.string.skill_screenshot_needs_service) },
        details = { c ->
            listOf(
                c.getString(R.string.skill_screenshot_service_what),
                c.getString(R.string.skill_screenshot_service_how),
            )
        },
        yesLabel = R.string.skill_screenshot_open_settings,
        noLabel = R.string.skill_screenshot_not_now,
        yesNoData = yesNo(ctx),
    ) {
        when {
            control.isLocked() -> GarLockedOutput()
            control.openAccessibilitySettings() -> ScreenMessageOutput(R.string.skill_screenshot_turn_on)
            else -> ScreenMessageOutput(R.string.skill_screenshot_no_settings)
        }
    }
}

/** Whether an input asks for a screenshot (English). */
object ScreenWords {
    /** It names a screenshot, or a picture of the screen, and doesn't record a video of it. */
    fun asksForScreenshot(input: String): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        val names = words.any { it in SCREENSHOT_WORDS } ||
            ("screen" in words && words.any { it in CAPTURE_WORDS })
        return names && words.none { it in OTHER_THINGS }
    }

    private val SCREENSHOT_WORDS = setOf("screenshot", "screenshots", "screencap", "screengrab")
    private val CAPTURE_WORDS = setOf(
        "capture", "picture", "photo", "pic", "shot", "grab", "snapshot", "snap",
    )
    private val OTHER_THINGS = setOf(
        "record", "recording", "video", "share", "send", "delete", "show", "open", "last",
        "latest", "edit", "settings", "timeout", "brightness", "lock",
    )
}

/**
 * Moves GAR's window out of the way, then has the service take the screenshot. Says nothing:
 * speech, or a toast or snackbar when that is the speech output, would be in the picture.
 */
class ScreenshotOutput(private val control: ScreenshotControl) : SkillOutput {
    /** Set once the screenshot was asked for, so that showing the card again doesn't repeat it. */
    private var started = false

    override fun getSpeechOutput(ctx: SkillContext): String = ""

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        val context = LocalContext.current
        LaunchedEffect(this) {
            if (!started) {
                started = true
                context.findActivity()?.moveTaskToBack(true)
                GarSheet.dismissIfShown() // the assistant sheet too, so the app below is shot
                control.take(DISMISS_DELAY_MILLIS)
            }
        }
        Headline(text = ctx.getString(R.string.skill_screenshot_taken))
    }

    companion object {
        /** Enough for the task's closing animation. */
        const val DISMISS_DELAY_MILLIS = 700L

        private fun Context.findActivity(): Activity? {
            var context: Context? = this
            while (context is ContextWrapper) {
                if (context is Activity) {
                    return context
                }
                context = context.baseContext
            }
            return null
        }
    }
}

/** A short line of the screen skills: "Turn on GAR screenshots there, then ask again." */
class ScreenMessageOutput(val message: Int) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = ctx.getString(message)
}
