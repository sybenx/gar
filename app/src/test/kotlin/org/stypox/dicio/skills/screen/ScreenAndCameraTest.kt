package org.stypox.dicio.skills.screen

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.call.GarLockedOutput
import org.stypox.dicio.gar.garRankingWinner
import org.stypox.dicio.gar.offer.GarOfferOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.skills.camera.CameraControl
import org.stypox.dicio.skills.camera.CameraInfo
import org.stypox.dicio.skills.camera.CameraIntents
import org.stypox.dicio.skills.camera.CameraLaunch
import org.stypox.dicio.skills.camera.CameraMode
import org.stypox.dicio.skills.camera.CameraRequest
import org.stypox.dicio.skills.camera.CameraResult
import org.stypox.dicio.skills.camera.CameraWords

private class FakeCamera(
    val locked: Boolean = false,
    val handler: String? = CameraIntents.GRAPHENEOS_CAMERA,
    val starts: Boolean = true,
) : CameraControl {
    val launched = mutableListOf<CameraLaunch>()
    override fun isLocked() = locked
    override fun handlerOf(action: String) = handler
    override fun start(launch: CameraLaunch): Boolean {
        launched += launch
        return starts
    }
}

private class FakeShade(val count: Int?, val clears: Boolean = true) : NotificationShade {
    var cleared = 0
    override fun isLocked() = false
    override fun clearableCount() = count
    override fun clearAll(): Boolean {
        cleared++
        return clears
    }
}

private class FakeScreenshots(val locked: Boolean = false, val on: Boolean = true) : ScreenshotControl {
    val taken = mutableListOf<Long>()
    override fun isLocked() = locked
    override fun take(delayMillis: Long): Boolean {
        taken += delayMillis
        return on
    }
    override fun isOn() = on
    override fun openAccessibilitySettings() = true
}

class ScreenAndCameraTest : StringSpec({
    "camera intents: still or video, the secure one on the lock screen" {
        CameraIntents.action(CameraMode.PHOTO, locked = false) shouldBe "android.media.action.STILL_IMAGE_CAMERA"
        CameraIntents.action(CameraMode.SELFIE, locked = true) shouldBe "android.media.action.STILL_IMAGE_CAMERA_SECURE"
        CameraIntents.action(CameraMode.VIDEO, locked = false) shouldBe "android.media.action.VIDEO_CAMERA"
        CameraIntents.action(CameraMode.VIDEO, locked = true) shouldBe null
        CameraIntents.open(FakeCamera(locked = true), CameraRequest(CameraMode.VIDEO, null)) shouldBe
            CameraResult.Locked
    }

    "a selfie asks for the front camera, and says when the app won't take it" {
        val graphene = FakeCamera()
        val result = CameraIntents.open(graphene, CameraRequest(CameraMode.SELFIE, 5))
            .shouldBeInstanceOf<CameraResult.Opened>()
        result.frontHonoured shouldBe false
        result.timerHonoured shouldBe false
        graphene.launched.single().extras shouldBe mapOf(
            CameraIntents.EXTRA_CAMERA_FACING to 1,
            CameraIntents.EXTRA_LENS_FACING_FRONT to 1,
            CameraIntents.EXTRA_USE_FRONT_CAMERA to true,
            CameraIntents.EXTRA_ASSISTANT_FRONT_CAMERA to true,
            CameraIntents.EXTRA_ASSISTANT_TIMER to 5,
        )

        val google = CameraIntents.open(FakeCamera(handler = CameraIntents.GOOGLE_CAMERA), CameraRequest(CameraMode.SELFIE, null))
            .shouldBeInstanceOf<CameraResult.Opened>()
        google.frontHonoured shouldBe true
        google.timerHonoured shouldBe null

        val photo = FakeCamera()
        CameraIntents.open(photo, CameraRequest(CameraMode.PHOTO, null))
            .shouldBeInstanceOf<CameraResult.Opened>().frontHonoured shouldBe null
        photo.launched.single().extras shouldBe mapOf()

        CameraIntents.open(FakeCamera(starts = false), CameraRequest(CameraMode.PHOTO, null)) shouldBe
            CameraResult.NoCameraApp
    }

    "the words of the camera, the screenshot and the notifications" {
        for (input in listOf("take a picture", "take a selfie", "record a video", "open the front camera")) {
            withClue(input) { CameraWords.asksForCamera(input) shouldBe true }
        }
        for (input in listOf("take a picture of my screen", "start a video call", "show my last photo", "take a shot")) {
            withClue(input) { CameraWords.asksForCamera(input) shouldBe false }
        }
        for (input in listOf("take a screenshot", "screenshot this", "capture the screen", "grab a picture of my screen")) {
            withClue(input) { ScreenWords.asksForScreenshot(input) shouldBe true }
        }
        for (input in listOf("record my screen", "show my last screenshot", "lock the screen", "share this screenshot")) {
            withClue(input) { ScreenWords.asksForScreenshot(input) shouldBe false }
        }
        for (input in listOf("clear my notifications", "dismiss all notifications", "get rid of my notifications")) {
            withClue(input) { ClearWords.clearsNotifications(input) shouldBe true }
        }
        for (input in listOf("read my notifications", "clear notification settings", "clear notifications from whatsapp")) {
            withClue(input) { ClearWords.clearsNotifications(input) shouldBe false }
        }
    }

    "clearing: only when there is something, and only through the listener" {
        val shade = FakeShade(count = 3)
        ClearNotificationsSkill.clear(shade) shouldBe ClearResult.CLEARED
        shade.cleared shouldBe 1
        val empty = FakeShade(count = 0)
        ClearNotificationsSkill.clear(empty) shouldBe ClearResult.NOTHING_TO_CLEAR
        empty.cleared shouldBe 0
        ClearNotificationsSkill.clear(FakeShade(count = null)) shouldBe ClearResult.NO_LISTENER
    }

    "a screenshot: the service when it is on, the offer to turn it on when not, never locked" {
        val data = Sentences.Screenshot["en"]!!
        val ctx = FakeSkillContext()
        ScreenshotSkill(ScreenshotInfo, data, { FakeScreenshots() }, { null })
            .generateOutput(ctx, Sentences.Screenshot.Take).shouldBeInstanceOf<ScreenshotOutput>()
        ScreenshotSkill(ScreenshotInfo, data, { FakeScreenshots(on = false) }, { null })
            .generateOutput(ctx, Sentences.Screenshot.Take).shouldBeInstanceOf<GarOfferOutput>()
        ScreenshotSkill(ScreenshotInfo, data, { FakeScreenshots(locked = true) }, { null })
            .generateOutput(ctx, Sentences.Screenshot.Take).shouldBeInstanceOf<GarLockedOutput>()
        // nothing is said: speech, or a toast, would be in the picture
        ScreenshotOutput(FakeScreenshots()).getSpeechOutput(ctx) shouldBe ""
    }

    "camera, screenshot and clearing rank to their skills" {
        for ((input, skill) in listOf(
            "take a picture" to CameraInfo.id,
            "take a photo in five seconds" to CameraInfo.id,
            "take a selfie" to CameraInfo.id,
            "record a video" to CameraInfo.id,
            "take a screenshot" to ScreenshotInfo.id,
            "take a picture of my screen" to ScreenshotInfo.id,
            "clear my notifications" to ClearNotificationsInfo.id,
            "dismiss all my notifications" to ClearNotificationsInfo.id,
            "read my notifications" to "notify",
        )) {
            withClue(input) { garRankingWinner(input).first shouldBe skill }
        }
        withClue("open the camera") { garRankingWinner("open the camera").first shouldBeIn listOf("open", CameraInfo.id) }
        withClue("start a video call") { garRankingWinner("start a video call").first shouldNotBe CameraInfo.id }
    }
})
