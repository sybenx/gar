package org.stypox.dicio.skills.song

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStore
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

// no need to use Hilt injection here, let DataStore take care of handling the singleton itself

/**
 * The song skill's settings: the recognition service and its credentials. The tokens are stored
 * as plain protobuf in the app's private storage, like Dicio's other settings (the app has
 * allowBackup=false).
 */
internal val Context.songSettingsDataStore by dataStore(
    fileName = "skill_settings_song.pb",
    serializer = SkillSettingsSongSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { SkillSettingsSongSerializer.defaultValue },
)

object SkillSettingsSongSerializer : Serializer<SkillSettingsSong> {
    /** No service: nothing is recorded or sent until the user picks one and adds a token. */
    override val defaultValue: SkillSettingsSong = SkillSettingsSong.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SkillSettingsSong {
        try {
            return SkillSettingsSong.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: SkillSettingsSong, output: OutputStream) {
        t.writeTo(output)
    }
}
