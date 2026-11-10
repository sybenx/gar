package org.stypox.dicio.gar.stt

import cc.aaro.gar.stt.Transcriber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException

/**
 * What the Whisper input device needs of the speech model files and the loaded model. Implemented
 * by `GarSttModelManager`, which owns downloading, verifying, switching and deleting the files
 * (the model chosen in GAR's settings plus the Silero VAD file) and keeps the loaded model in a
 * `GarEngineHolder`; tests use a fake.
 *
 * Lifecycle, as seen by the device: [acquire] when capture starts (the model loads while the user
 * talks), [use] to transcribe (waits for that load), [release] once Final, None or Error is sent.
 * The manager unloads the model some time after the last release.
 */
interface SttModels {
    /**
     * Whether the selected model and the VAD file are both on disk and verified. The device shows
     * NotDownloaded while false; the input device wrapper and the resolver collect it to switch
     * between Vosk and Whisper when the files appear or go.
     */
    val installed: StateFlow<Boolean>

    /** The download started by [download] (or from GAR's settings). */
    val transfer: StateFlow<SttTransfer>

    /**
     * Changes when the selected model's file changes (a switch, a new download) or a failed load
     * is forgotten ([retryLoad], also from GAR's settings). The device drops a load error it saw
     * in an earlier generation, since it was about a file or a load that is gone.
     */
    val loadGeneration: StateFlow<Int>

    /**
     * The Silero VAD file; it exists while [installed] is true. The device loads it itself (it
     * takes milliseconds), so capture never waits for Whisper's load.
     */
    val vadFile: File

    /**
     * Downloads what is missing of the selected model and the VAD file, in the background;
     * progress and failure go to [transfer]. Does nothing while a download runs.
     */
    fun download()

    /** Keeps the model loaded until [release], starting to load it now if needed. */
    fun acquire()

    /** Undoes one [acquire]. */
    fun release()

    /** Starts loading the model in the background, if it is installed and not loaded. */
    fun preload()

    /**
     * Forgets a failed load (also one that stopped the app, see `GarLoadGuard`), so the next
     * [acquire], [preload] or [use] tries to load again.
     */
    suspend fun retryLoad()

    /**
     * Runs [block] with the loaded model, loading it first or waiting for the load in progress.
     * Throws [SttLoadException] when the model can't be loaded (files missing, a damaged file, an
     * earlier failed load not yet retried) and [kotlinx.coroutines.CancellationException] when
     * cancelled. Whatever [block] throws passes through unchanged.
     */
    suspend fun <R> use(block: suspend (Transcriber) -> R): R

    /**
     * The contact names for Whisper's prompt, most important first ([GarSttPromptNames]): empty
     * while "Help recognise contact names" is off or GAR can't read the contacts. [acquire]
     * refreshes them when they are old.
     */
    val promptNames: StateFlow<List<String>> get() = NO_PROMPT_NAMES

    /**
     * Unloads the model now unless someone holds it, e.g. when the input device wrapper is told
     * to release resources (MainActivity.onDestroy, the wake word service's timeout).
     */
    fun unloadIfIdle() {}
}

/** A speech model download, as [SttModels.transfer] reports it. */
sealed interface SttTransfer {
    data object Idle : SttTransfer

    /** [totalBytes] covers the model and the VAD file together. */
    data class Running(val currentBytes: Long, val totalBytes: Long) : SttTransfer

    data class Failed(val throwable: Throwable) : SttTransfer
}

/** The speech model could not be loaded; the device shows ErrorLoading and retries on a tap. */
class SttLoadException(message: String, cause: Throwable? = null) : IOException(message, cause)

private val NO_PROMPT_NAMES: StateFlow<List<String>> = MutableStateFlow(emptyList<String>()).asStateFlow()
