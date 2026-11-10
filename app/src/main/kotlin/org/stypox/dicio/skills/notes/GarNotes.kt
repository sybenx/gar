package org.stypox.dicio.skills.notes

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** A list the user keeps by voice: "shopping", "to do", "packing". Items as said, oldest first. */
@Serializable
data class GarList(val name: String, val items: List<String> = listOf())

/** A note taken by voice, [created] in epoch millis. */
@Serializable
data class GarNote(val text: String, val created: Long)

/**
 * A fact to remember, as the user said it after "remember (that)": "my locker is 42", "i parked
 * on level 3". [saved] in epoch millis.
 */
@Serializable
data class GarFact(val text: String, val saved: Long)

/** Everything GAR keeps for the lists, notes and facts skills; [lastList] is the list used last. */
@Serializable
data class GarNotesData(
    val lists: List<GarList> = listOf(),
    val notes: List<GarNote> = listOf(),
    val facts: List<GarFact> = listOf(),
    val lastList: String? = null,
)

/** Where [GarNotes] keeps its data: a file in the app's private storage, a string in tests. */
interface GarNotesStore {
    fun load(): String?
    fun save(encoded: String)
}

/**
 * gar:notes. GAR's own small store for lists, notes and remembered facts (gar/PARITY.md, batch 6):
 * a JSON file in the app's private files, never sent anywhere and not readable by other apps. It
 * is read once per process and kept in memory, so that the facts skill can check its questions
 * while Dicio ranks the input ([peek], like `GarStartedTimers`).
 */
class GarNotes(private val store: GarNotesStore) {
    private var data: GarNotesData = decode(store.load())

    /** What is kept now. Never touches the disk. */
    @Synchronized
    fun current(): GarNotesData = data

    /** Applies [change] and saves the result; returns it. */
    @Synchronized
    fun update(change: (GarNotesData) -> GarNotesData): GarNotesData {
        val changed = change(data)
        if (changed != data) {
            data = changed
            store.save(JSON.encodeToString(GarNotesData.serializer(), changed))
        }
        return changed
    }

    companion object {
        private const val TAG = "GarNotes"
        private const val FILE_NAME = "gar_notes.json"
        private val JSON = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: GarNotes? = null

        /** The app's notes, loaded on first use. */
        fun get(context: Context): GarNotes {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: GarNotes(fileStore(File(context.applicationContext.filesDir, FILE_NAME)))
                    .also { instance = it }
            }
        }

        /** The notes if this process loaded them, without loading them (for `score`). */
        fun peek(): GarNotes? = instance

        /** The saved data, or nothing if there is none or it can't be read. */
        fun decode(text: String?): GarNotesData {
            if (text.isNullOrEmpty()) {
                return GarNotesData()
            }
            return try {
                JSON.decodeFromString(GarNotesData.serializer(), text)
            } catch (e: IllegalArgumentException) {
                // also kotlinx.serialization's SerializationException
                GarNotesData()
            }
        }

        /** An [AtomicFile], so that a crash while saving never leaves half a file. */
        private fun fileStore(file: File): GarNotesStore {
            val atomic = AtomicFile(file)
            return object : GarNotesStore {
                override fun load(): String? = try {
                    atomic.readFully().toString(Charsets.UTF_8)
                } catch (e: FileNotFoundException) {
                    null
                } catch (e: IOException) {
                    Log.w(TAG, "Could not read the notes", e)
                    null
                }

                override fun save(encoded: String) {
                    val stream = try {
                        atomic.startWrite()
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not save the notes", e)
                        return
                    }
                    try {
                        stream.write(encoded.toByteArray(Charsets.UTF_8))
                        atomic.finishWrite(stream)
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not save the notes", e)
                        atomic.failWrite(stream)
                    }
                }
            }
        }
    }
}
