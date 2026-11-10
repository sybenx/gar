package cc.aaro.gar.router

import cc.aaro.gar.router.format.enum
import cc.aaro.gar.router.format.flashlight
import cc.aaro.gar.router.format.testTool
import cc.aaro.gar.router.format.text
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private val mediaPlay = testTool("media_play", text("query", required = true), text("app"))
private val mediaControl = testTool("media_control", enum("action", "play", "pause", "next", "previous"))

private fun playCall(utterance: String, query: String = utterance) =
    RouterOutput.Call(mediaPlay, mapOf("query" to query), listOf(), Utterances.normalize(utterance))

/** gar:media, see [MediaControlRewrite]. */
class CallRewriteTest : StringSpec({
    "a media_play call for a transport control becomes media_control" {
        // the eval's media_control commands that the model gave media_play (2026-10-27)
        for ((utterance, action) in listOf(
            "pause the music" to "pause",
            "paws the music" to "pause",
            "stop the music" to "pause",
            "hold the music for a second" to "pause",
            "hold on stop the music for a sec" to "pause",
            "pause this" to "pause",
            "skip this song" to "next",
            "skip ahead" to "next",
            "skip to the next song" to "next",
            "next one please" to "next",
            "go back to the previous track" to "previous",
            "go back a song" to "previous",
            "go back to the last track" to "previous",
            "play the last song again" to "previous",
            "resume playback" to "play",
            "resume playing my podcast" to "play",
            "keep playing" to "play",
            "continue the podcast" to "play",
            "unpause the video" to "play",
            "can you pause the music" to "pause",
        )) {
            withClue(utterance) {
                MediaControlRewrite.rewrite(playCall(utterance), listOf(mediaPlay, mediaControl)) shouldBe
                    RouterOutput.Call(mediaControl, mapOf("action" to action), listOf(), utterance)
            }
        }
    }

    "a request that names what to play stays media_play" {
        for (utterance in listOf(
            "play taylor swift on spotify", "play stop and stare", "play hold on by justin bieber",
            "play next to me by imagine dragons", "play back in black", "put on my workout playlist",
            "play the last episode of the daily", "play something relaxing on you tube",
            "listen to the joe rogan podcast",
        )) {
            withClue(utterance) {
                val call = playCall(utterance)
                MediaControlRewrite.rewrite(call, listOf(mediaPlay, mediaControl)) shouldBe call
            }
        }
    }

    "nothing changes without media_control or for other tools" {
        val call = playCall("pause the music")
        MediaControlRewrite.rewrite(call, listOf(mediaPlay)) shouldBe call
        val torch = RouterOutput.Call(flashlight, mapOf("state" to "on"), listOf(), "stop the music")
        MediaControlRewrite.rewrite(torch, listOf(flashlight, mediaControl)) shouldBe torch
    }
})
