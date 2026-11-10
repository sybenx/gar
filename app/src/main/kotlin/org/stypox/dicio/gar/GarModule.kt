package org.stypox.dicio.gar

import androidx.datastore.core.DataStore
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.stypox.dicio.di.SttInputDeviceWrapper
import org.stypox.dicio.eval.SkillEvaluator
import org.stypox.dicio.gar.model.GarModelManager
import org.stypox.dicio.gar.online.GarOnlineSettings
import org.stypox.dicio.gar.online.GarOnlineSettingsImpl
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.tools.GarToolsProvider
import org.stypox.dicio.gar.tools.GarToolsProviderImpl

@Module
@InstallIn(SingletonComponent::class)
abstract class GarModule {
    @Binds
    abstract fun bindGarFallback(impl: GarFallbackImpl): GarFallback

    @Binds
    abstract fun bindGarRouterSource(impl: GarModelManager): GarRouterSource

    @Binds
    abstract fun bindGarToolsProvider(impl: GarToolsProviderImpl): GarToolsProvider

    @Binds
    abstract fun bindGarOnlineSettings(impl: GarOnlineSettingsImpl): GarOnlineSettings
}

/** For code that Hilt doesn't inject, e.g. the confirmation card's buttons. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface GarEntryPoint {
    fun skillEvaluator(): SkillEvaluator

    /** For the online skills' gates, which Dicio's SkillHandler builds. */
    fun onlineSettings(): GarOnlineSettings

    /** For the owner's contact of "text myself" (`OwnContact`), which skills look up. */
    fun garSettings(): DataStore<GarSettings>

    /** For "keep the screen on" over the lock screen while the user talks to GAR. */
    fun sttInputDevice(): SttInputDeviceWrapper
}
