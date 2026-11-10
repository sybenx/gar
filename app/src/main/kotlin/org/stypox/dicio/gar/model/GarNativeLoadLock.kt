package org.stypox.dicio.gar.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets only one native model load (llama.cpp or whisper.cpp) run at a time, held around each
 * [GarLoadGuard]'s begin and end. Both models load when MainActivity starts; if the process died
 * while both loads ran, both guards would count it, and a healthy model could end up refused as
 * one that stops the app. With one load at a time only the guilty model's marker is left.
 *
 * Speech loads go first: a language model load that hasn't started waits for the speech loads
 * that are waiting, since the user speaks before the language model runs.
 */
class GarNativeLoadLock {
    private val mutex = Mutex()
    private val speechWaiting = MutableStateFlow(0)

    /** Runs [block], a speech model load, once no other native load runs. */
    suspend fun <T> withSpeechLoad(block: suspend () -> T): T {
        speechWaiting.update { it + 1 }
        var counted = true
        try {
            return mutex.withLock {
                speechWaiting.update { it - 1 }
                counted = false
                block()
            }
        } finally {
            if (counted) {
                speechWaiting.update { it - 1 }
            }
        }
    }

    /** Runs [block], a language model load, once no speech load runs or waits. */
    suspend fun <T> withLanguageModelLoad(block: suspend () -> T): T {
        while (true) {
            speechWaiting.first { it == 0 }
            mutex.lock()
            if (speechWaiting.value == 0) {
                break
            }
            // a speech load came in meanwhile: let it go first
            mutex.unlock()
        }
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }

    companion object {
        /** The process-wide lock that GarModelManager and GarSttModelManager share. */
        val shared = GarNativeLoadLock()
    }
}
