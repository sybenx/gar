package org.stypox.dicio.skills.camera

import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.Resolution
import cc.aaro.gar.router.resolve.SlotResolvers
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
import org.stypox.dicio.io.graphical.Body
import org.stypox.dicio.io.graphical.Headline
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Camera
import org.stypox.dicio.util.getString

object CameraInfo : SkillInfo("camera") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_camera)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_camera)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.PhotoCamera)

    // no permissions: the camera app takes the picture

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Camera[ctx.sentencesLanguage] ?: return null
        return CameraSkill(CameraInfo, data)
    }
}

enum class CameraMode { PHOTO, SELFIE, VIDEO }

/** A request for the camera: the mode, and a countdown in seconds if one was said. */
data class CameraRequest(val mode: CameraMode, val timerSeconds: Int?)

/** What happened when GAR opened the camera. */
sealed interface CameraResult {
    /**
     * @param frontHonoured whether the camera app is known to read GAR's front camera extras:
     * null when no front camera was asked for, false for an app that ignores them
     * @param timerHonoured the same for the countdown
     */
    data class Opened(
        val request: CameraRequest,
        val frontHonoured: Boolean?,
        val timerHonoured: Boolean?,
    ) : CameraResult

    data object NoCameraApp : CameraResult

    /** A video on a locked phone: Android has no lock screen video intent. */
    data object Locked : CameraResult
}

/** An intent to start, as data: its action and extras (Boolean or Int values). */
data class CameraLaunch(val action: String, val extras: Map<String, Any>)

/**
 * What the camera skill does on the device, so that its decisions can be tested.
 */
interface CameraControl {
    fun isLocked(): Boolean

    /** The package that would open [action] without asking, or null (a chooser, or none). */
    fun handlerOf(action: String): String?

    /** @return false when no app took it */
    fun start(launch: CameraLaunch): Boolean
}

class AndroidCameraControl(private val context: Context) : CameraControl {
    override fun isLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

    override fun handlerOf(action: String): String? = context.packageManager
        .resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName?.takeIf { it != "android" }

    override fun start(launch: CameraLaunch): Boolean {
        val intent = Intent(launch.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        for ((key, value) in launch.extras) {
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
            }
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w("AndroidCameraControl", "No camera app for ${launch.action}", e)
            false
        }
    }
}

/**
 * GAR (gar:screen). "Take a picture", "take a selfie in 5 seconds", "record a video": the camera
 * app in that mode, through the intents Android defines for it ([CameraIntents]). The user taps
 * the shutter: no public intent shoots by itself. English only.
 */
class CameraSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Camera>,
    private val controlFactory: (SkillContext) -> CameraControl = { AndroidCameraControl(it.android) },
) : StandardRecognizerSkill<Camera>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Camera> {
        val (score, sentence) = super.score(ctx, input)
        return Pair(if (CameraWords.asksForCamera(input)) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Camera): SkillOutput {
        val (mode, delay) = when (inputData) {
            is Camera.Photo -> Pair(CameraMode.PHOTO, inputData.delay)
            is Camera.Selfie -> Pair(CameraMode.SELFIE, inputData.delay)
            Camera.Video -> Pair(CameraMode.VIDEO, null)
        }
        val request = CameraRequest(mode, delay?.let { timerSeconds(ctx, it) })
        return when (val result = CameraIntents.open(controlFactory(ctx), request)) {
            CameraResult.Locked -> GarLockedOutput()
            else -> CameraOutput(result)
        }
    }

    companion object {
        /** "5 seconds", "ten seconds" in seconds, at most [CameraIntents.MAX_TIMER_SECONDS]. */
        fun timerSeconds(ctx: SkillContext, said: String): Int? {
            val resolution = SlotResolvers(ctx.parserFormatter, ctx.sentencesLanguage).duration(said)
            val seconds = (resolution as? Resolution.Resolved)?.value?.seconds ?: return null
            return seconds.toInt().coerceIn(1, CameraIntents.MAX_TIMER_SECONDS)
        }
    }
}

/**
 * The camera intents (checked 2026-11-02):
 * - [MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA] and [MediaStore.INTENT_ACTION_VIDEO_CAMERA]
 *   open the camera app in photo or video mode; on a locked phone photos use
 *   [MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE], which opens over the lock screen
 *   (GrapheneOS Camera's `SecureMainActivity`, `showWhenLocked`). There is no secure video intent,
 *   so a video needs the phone unlocked.
 * - The front camera has no public extra. GAR sets the ones camera apps are known to read:
 *   `android.intent.extras.CAMERA_FACING` = 1, `android.intent.extras.LENS_FACING_FRONT` = 1,
 *   `android.intent.extra.USE_FRONT_CAMERA` and Google Assistant's
 *   `com.google.assistant.extra.USE_FRONT_CAMERA` (which Google Camera reads for "take a selfie";
 *   unconfirmed on the Pixel 9a's version). GrapheneOS Camera reads none of them (its source has
 *   no intent extras for the lens), so GAR says to flip the camera.
 * - The countdown: only Google Assistant's `com.google.assistant.extra.TIMER_DURATION_SECONDS`,
 *   for Google Camera; other apps get no timer, which GAR says.
 */
object CameraIntents {
    const val GOOGLE_CAMERA = "com.google.android.GoogleCamera"
    const val GRAPHENEOS_CAMERA = "app.grapheneos.camera"
    const val MAX_TIMER_SECONDS = 30

    const val EXTRA_CAMERA_FACING = "android.intent.extras.CAMERA_FACING"
    const val EXTRA_LENS_FACING_FRONT = "android.intent.extras.LENS_FACING_FRONT"
    const val EXTRA_USE_FRONT_CAMERA = "android.intent.extra.USE_FRONT_CAMERA"
    const val EXTRA_ASSISTANT_FRONT_CAMERA = "com.google.assistant.extra.USE_FRONT_CAMERA"
    const val EXTRA_ASSISTANT_TIMER = "com.google.assistant.extra.TIMER_DURATION_SECONDS"

    /** The apps that read the front camera and timer extras. */
    private val READS_EXTRAS = setOf(GOOGLE_CAMERA)

    /** The action for [mode]: null for a video on a locked phone. */
    fun action(mode: CameraMode, locked: Boolean): String? = when {
        mode == CameraMode.VIDEO -> if (locked) null else MediaStore.INTENT_ACTION_VIDEO_CAMERA
        locked -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        else -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
    }

    /** [action] with the front camera and timer extras that [request] asks for. */
    fun launch(action: String, request: CameraRequest): CameraLaunch {
        val extras = mutableMapOf<String, Any>()
        if (request.mode == CameraMode.SELFIE) {
            extras[EXTRA_CAMERA_FACING] = 1
            extras[EXTRA_LENS_FACING_FRONT] = 1
            extras[EXTRA_USE_FRONT_CAMERA] = true
            extras[EXTRA_ASSISTANT_FRONT_CAMERA] = true
        }
        request.timerSeconds?.let { extras[EXTRA_ASSISTANT_TIMER] = it }
        return CameraLaunch(action, extras)
    }

    fun open(control: CameraControl, request: CameraRequest): CameraResult {
        val action = action(request.mode, control.isLocked()) ?: return CameraResult.Locked
        val launch = launch(action, request)
        val reads = control.handlerOf(action) in READS_EXTRAS
        if (!control.start(launch)) {
            return CameraResult.NoCameraApp
        }
        return CameraResult.Opened(
            request,
            frontHonoured = if (request.mode == CameraMode.SELFIE) reads else null,
            timerHonoured = if (request.timerSeconds != null) reads else null,
        )
    }
}

/** Whether an input asks for the camera (English). */
object CameraWords {
    /**
     * It names a photo, a selfie, a video or the camera, and not the screen ("take a picture of
     * my screen", "record my screen") or a call ("start a video call").
     */
    fun asksForCamera(input: String): Boolean {
        val words = Utterances.words(Utterances.normalize(input))
        return words.any { it in CAMERA_WORDS } && words.none { it in OTHER_THINGS }
    }

    private val CAMERA_WORDS = setOf(
        "picture", "pictures", "photo", "photos", "photograph", "pic", "pics", "snapshot",
        "selfie", "selfies", "video", "camera",
    )
    private val OTHER_THINGS = setOf(
        "screen", "screenshot", "screens", "call", "chat", "meeting", "settings", "gallery",
        "album", "last", "latest", "recent", "send", "share", "show", "delete", "edit", "play",
        "watch",
    )
}

/**
 * "Opening the camera.", "Opening the front camera with a 5 second timer.", "Opening the camera.
 * It doesn't take GAR's front camera choice, so flip it.", with what the camera app does with
 * GAR's request on the card.
 */
class CameraOutput(val result: CameraResult) : SkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when (result) {
        is CameraResult.Opened -> listOfNotNull(
            opening(ctx, result),
            if (result.frontHonoured == false) ctx.getString(R.string.skill_camera_flip) else null,
            if (result.timerHonoured == false) ctx.getString(R.string.skill_camera_no_timer) else null,
        ).joinToString(" ")
        CameraResult.NoCameraApp -> ctx.getString(R.string.skill_camera_no_app)
        CameraResult.Locked -> ctx.getString(R.string.gar_unlock_first)
    }

    private fun opening(ctx: SkillContext, result: CameraResult.Opened): String {
        val request = result.request
        val front = request.mode == CameraMode.SELFIE && result.frontHonoured == true
        val timer = request.timerSeconds?.takeIf { result.timerHonoured == true }
        return when {
            request.mode == CameraMode.VIDEO -> ctx.getString(R.string.skill_camera_opening_video)
            timer != null && front -> ctx.getString(R.string.skill_camera_opening_front_timer, timer)
            timer != null -> ctx.getString(R.string.skill_camera_opening_timer, timer)
            front -> ctx.getString(R.string.skill_camera_opening_front)
            else -> ctx.getString(R.string.skill_camera_opening)
        }
    }

    @Composable
    override fun GraphicalOutput(ctx: SkillContext) {
        Column {
            Headline(text = getSpeechOutput(ctx))
            if (result is CameraResult.Opened) {
                Spacer(modifier = Modifier.height(8.dp))
                Body(text = ctx.getString(R.string.skill_camera_tap_shutter))
                if (result.frontHonoured == true || result.timerHonoured == true) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Body(text = ctx.getString(R.string.skill_camera_asked))
                }
            }
        }
    }
}
