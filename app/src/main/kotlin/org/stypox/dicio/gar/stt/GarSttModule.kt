package org.stypox.dicio.gar.stt

import androidx.datastore.core.DataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.gar.settings.GarSettings

@Module
@InstallIn(SingletonComponent::class)
abstract class GarSttModule {
    @Binds
    abstract fun bindSttModels(impl: GarSttModelManager): SttModels
}

/**
 * For Dicio's composables that get no view model of GAR's: the home button and the STT popup read
 * the level and the speech model's size through this, so their callers stay unchanged, and
 * Settings shows the resolved device for UNSET.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GarSttEntryPoint {
    fun sttInputDevice(): SttInputDeviceWrapper

    fun garSettings(): DataStore<GarSettings>

    fun sttDeviceResolver(): GarSttDeviceResolver
}
