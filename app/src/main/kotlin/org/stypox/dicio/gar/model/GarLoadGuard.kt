package org.stypox.dicio.gar.model

import java.io.File
import java.io.IOException

/**
 * Remembers model loads that never finished because the process died: llama.cpp aborts the whole
 * process on some malformed files (GGML_ASSERT), and the system kills it when a model is too big
 * for the phone's memory. MainActivity, the wake word and the assistant service share the process,
 * and each of them loads the model, so without this such a file would crash the app on every
 * start. A marker file is written before each load and deleted when the load ends (also with an
 * error); a marker left from earlier loads of the same file counts as one more unfinished load.
 *
 * @param maxUnfinished after this many unfinished loads in a row the file is not loaded again
 * until [clear] (the user retries) or the file changes
 */
class GarLoadGuard(private val markerFile: File, private val maxUnfinished: Int = 2) {

    /**
     * Call right before loading the file identified by [id].
     * @throws ModelCrashedException if earlier loads of [id] didn't finish too often
     */
    fun begin(id: String) {
        val count = unfinished(id)
        if (count >= maxUnfinished) {
            throw ModelCrashedException(count)
        }
        try {
            markerFile.parentFile?.mkdirs()
            markerFile.writeText("${count + 1}\n$id")
        } catch (e: IOException) {
            // without the marker the guard can't help, but loading must not fail because of it
        }
    }

    /** Call when the load started by [begin] ended, successfully or with an error. */
    fun end() {
        markerFile.delete()
    }

    /** Forgets unfinished loads, so the next [begin] loads again. */
    fun clear() {
        markerFile.delete()
    }

    /** Whether [begin] would throw for [id]. */
    fun refuses(id: String): Boolean = unfinished(id) >= maxUnfinished

    /** How many loads of [id] in a row didn't finish. */
    fun unfinished(id: String): Int {
        val text = try {
            markerFile.takeIf { it.isFile }?.readText() ?: return 0
        } catch (e: IOException) {
            return 0
        }
        val count = text.substringBefore('\n').toIntOrNull() ?: return 0
        return if (text.substringAfter('\n') == id) count else 0
    }
}

class ModelCrashedException(unfinishedLoads: Int) :
    Exception("Loading this model stopped the app $unfinishedLoads times")
