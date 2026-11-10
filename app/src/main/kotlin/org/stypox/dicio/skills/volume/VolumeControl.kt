package org.stypox.dicio.skills.volume

import android.media.AudioManager
import cc.aaro.gar.router.resolve.ResolvedNumber
import kotlin.math.roundToInt

/** The parts of [AudioManager] the volume skill uses, so that its logic can be tested. */
interface VolumeControl {
    /** Whether the device has a fixed volume that apps can't change. */
    val isFixed: Boolean
    fun min(stream: Int): Int
    fun max(stream: Int): Int

    /** The volume index, 0 while the stream is muted (as [AudioManager.getStreamVolume]). */
    fun current(stream: Int): Int
    fun isMuted(stream: Int): Boolean

    /** Sets the volume index and shows the system's volume panel. */
    fun set(stream: Int, index: Int)

    /** Mutes or unmutes the stream and shows the system's volume panel. */
    fun mute(stream: Int, mute: Boolean)
}

class AndroidVolumeControl(private val audioManager: AudioManager) : VolumeControl {
    override val isFixed: Boolean get() = audioManager.isVolumeFixed
    override fun min(stream: Int) = audioManager.getStreamMinVolume(stream)
    override fun max(stream: Int) = audioManager.getStreamMaxVolume(stream)
    override fun current(stream: Int) = audioManager.getStreamVolume(stream)
    override fun isMuted(stream: Int) = audioManager.isStreamMute(stream)

    override fun set(stream: Int, index: Int) =
        audioManager.setStreamVolume(stream, index, AudioManager.FLAG_SHOW_UI)

    override fun mute(stream: Int, mute: Boolean) = audioManager.adjustStreamVolume(
        stream,
        if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
        AudioManager.FLAG_SHOW_UI,
    )
}

/** What happened to the volume, see [VolumeLogic.apply]. */
sealed interface VolumeResult {
    /** The stream's volume after the command, which may also be unchanged (a query). */
    data class Level(val stream: VolumeStream, val percent: Int, val muted: Boolean) : VolumeResult

    /** The device's volume is fixed. */
    data object Fixed : VolumeResult

    /**
     * Android refused the change: without Do Not Disturb access, apps can't change the ring and
     * notification volume in a way that takes the phone out of silent mode or into it.
     */
    data class NotAllowed(val stream: VolumeStream) : VolumeResult
}

object VolumeLogic {
    /** Plain levels up to this are out of ten ("volume to seven" = 70%), above it percentages. */
    private const val LEVEL_OUT_OF_TEN_MAX = 10.0

    /**
     * The index for [level] on a stream with the given range. See [VolumeCommand.level]; a plain
     * fraction ("half" = 0.5) is a share of the whole range.
     */
    fun levelIndex(level: ResolvedNumber, min: Int, max: Int): Int {
        val percent = when {
            level.percent || level.value > LEVEL_OUT_OF_TEN_MAX -> level.value
            level.value > 0.0 && level.value < 1.0 -> level.value * 100.0
            else -> level.value * 10.0
        }
        return (percent * max / 100.0).roundToInt().coerceIn(min, max)
    }

    /**
     * How many volume steps [steps] moves: a count (at least one, one if not said), or a
     * percentage of the whole range.
     */
    fun stepCount(steps: ResolvedNumber?, max: Int): Int {
        val count = when {
            steps == null -> 1.0
            steps.percent -> steps.value * max / 100.0
            else -> steps.value
        }
        return count.roundToInt().coerceIn(1, maxOf(1, max))
    }

    /** The volume as a percentage of the stream's maximum. */
    fun percentOf(index: Int, max: Int): Int =
        if (max <= 0) 0 else (index * 100.0 / max).roundToInt().coerceIn(0, 100)

    /**
     * Applies [command] and reports the stream's volume afterwards.
     *
     * @throws SecurityException never: Android's refusal becomes [VolumeResult.NotAllowed]
     */
    fun apply(control: VolumeControl, command: VolumeCommand): VolumeResult {
        if (control.isFixed) {
            return VolumeResult.Fixed
        }
        val stream = command.stream.streamType
        val min = control.min(stream)
        val max = control.max(stream)

        try {
            when {
                command.action == VolumeAction.MUTE -> control.mute(stream, true)
                command.action == VolumeAction.UNMUTE -> {
                    control.mute(stream, false)
                    // a stream turned all the way down isn't muted: make it audible
                    if (control.current(stream) == 0) {
                        control.set(stream, maxOf(min, 1).coerceAtMost(max))
                    }
                }
                command.action == VolumeAction.MAX -> setUnmuted(control, stream, max)
                command.level != null ->
                    setUnmuted(control, stream, levelIndex(command.level, min, max))
                command.action == VolumeAction.UP -> {
                    // a muted stream reads as 0: unmute first and go up from the level it had,
                    // as the volume keys do
                    if (control.isMuted(stream)) {
                        control.mute(stream, false)
                    }
                    val target = control.current(stream) + stepCount(command.steps, max)
                    control.set(stream, target.coerceIn(min, max))
                }
                // a muted stream stays muted and keeps the level it will be unmuted to
                command.action == VolumeAction.DOWN -> if (!control.isMuted(stream)) {
                    val target = control.current(stream) - stepCount(command.steps, max)
                    control.set(stream, target.coerceIn(min, max))
                }
                else -> {} // nothing to change: report the current volume
            }
        } catch (e: SecurityException) {
            return VolumeResult.NotAllowed(command.stream)
        }

        val index = control.current(stream)
        return VolumeResult.Level(
            stream = command.stream,
            percent = percentOf(index, max),
            muted = control.isMuted(stream) || index == 0,
        )
    }

    /** Sets [index], unmuting first when it is audible, as the volume keys do. */
    private fun setUnmuted(control: VolumeControl, stream: Int, index: Int) {
        if (index > 0 && control.isMuted(stream)) {
            control.mute(stream, false)
        }
        control.set(stream, index)
    }
}
