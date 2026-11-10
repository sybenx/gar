package org.stypox.dicio.gar.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import com.google.protobuf.InvalidProtocolBufferException
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Singleton

object GarSettingsSerializer : Serializer<GarSettings> {
    // proto3 booleans default to false, so the "on" defaults are set here
    override val defaultValue: GarSettings = GarSettings.getDefaultInstance()
        .toBuilder()
        .setLlmEnabled(true)
        .setModel(GarModel.GAR_MODEL_QWEN3_0_6B)
        .setConfirmLlmActions(true)
        .build()

    override suspend fun readFrom(input: InputStream): GarSettings {
        try {
            return GarSettings.parseFrom(input)
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    override suspend fun writeTo(t: GarSettings, output: OutputStream) {
        t.writeTo(output)
    }
}

@Module
@InstallIn(SingletonComponent::class)
class GarSettingsModule {
    @Provides
    @Singleton
    fun provideGarSettings(@ApplicationContext context: Context): DataStore<GarSettings> {
        return DataStoreFactory.create(
            serializer = GarSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler {
                GarSettingsSerializer.defaultValue
            },
            produceFile = {
                context.dataStoreFile("gar_settings.pb")
            },
        )
    }

    companion object {
        /** An in-memory data store, for previews and tests. */
        fun newDataStoreForPreviews(
            initial: GarSettings = GarSettingsSerializer.defaultValue,
        ): DataStore<GarSettings> {
            return object : DataStore<GarSettings> {
                private val _data = MutableStateFlow(initial)
                override val data: Flow<GarSettings> = _data

                override suspend fun updateData(
                    transform: suspend (t: GarSettings) -> GarSettings
                ): GarSettings {
                    val newData = transform(_data.value)
                    _data.value = newData
                    return newData
                }
            }
        }
    }
}
