package org.stypox.dicio.gar.online

import android.content.Context
import androidx.datastore.core.DataStore
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.dicio.skill.context.SkillContext
import org.stypox.dicio.eval.SkillHandler
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.GarEntryPoint
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.settings.datastore.UserSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the online skills' switches live: whether a skill is on is Dicio's own setting (the switch
 * in Settings > Skills), whether the user already chose about it is GAR's
 * ([GarSettings.getOnlineSkillsAskedList]).
 */
interface GarOnlineSettings {
    /** Dicio's current ranker, which it builds again when the switches change; null in tests. */
    val skillRankers: StateFlow<SkillRanker>?

    suspend fun isEnabled(id: String): Boolean

    /** Whether the user already chose about the online skill [id], so GAR doesn't ask again. */
    suspend fun wasAsked(id: String): Boolean

    /**
     * Turns the skills [ids] on or off and remembers that the user chose about them. Returns once
     * Dicio ranks with the new skills, so that a follow-up question of a skill run right after
     * goes to the ranker that stays.
     */
    suspend fun setEnabled(ids: List<String>, enabled: Boolean)

    /** Remembers that the user chose about [ids]; ids of skills that aren't online are ignored. */
    suspend fun markAsked(ids: List<String>)

    companion object {
        /** The app's settings, for skills that Hilt doesn't build. */
        fun forContext(ctx: SkillContext): GarOnlineSettings =
            forContext(ctx.android)

        fun forContext(context: Context): GarOnlineSettings =
            EntryPointAccessors.fromApplication(context.applicationContext, GarEntryPoint::class.java)
                .onlineSettings()

        /**
         * Called by Dicio's skill settings (a GAR line) when the user switches the skill [id] on or
         * off: that is a choice too, so GAR won't ask about it.
         */
        suspend fun chosenInSkillSettings(context: Context, id: String) {
            if (GarOnlineSkill.of(id) != null) { // other skills don't need GAR's settings
                skillSwitched(forContext(context), id)
            }
        }

        /** What [chosenInSkillSettings] does with GAR's [settings]: only online skills count. */
        suspend fun skillSwitched(settings: GarOnlineSettings, id: String) {
            if (GarOnlineSkill.of(id) != null) {
                settings.markAsked(listOf(id))
            }
        }
    }
}

/**
 * @param skillRankers Dicio's current ranker, which is built again when the enabled skills change;
 * null in tests
 */
@Singleton
class GarOnlineSettingsImpl(
    private val userSettings: DataStore<UserSettings>,
    private val garSettings: DataStore<GarSettings>,
    override val skillRankers: StateFlow<SkillRanker>? = null,
) : GarOnlineSettings {

    @Inject
    constructor(
        userSettings: DataStore<UserSettings>,
        garSettings: DataStore<GarSettings>,
        skillHandler: SkillHandler,
    ) : this(userSettings, garSettings, skillHandler.skillRanker)

    override suspend fun isEnabled(id: String): Boolean =
        userSettings.data.first().enabledSkillsMap.getOrDefault(id, true)

    override suspend fun wasAsked(id: String): Boolean =
        id in garSettings.data.first().onlineSkillsAskedList

    override suspend fun setEnabled(ids: List<String>, enabled: Boolean) {
        val ranker = skillRankers?.value
        val before = userSettings.data.first().enabledSkillsMap
        val after = userSettings.updateData { settings ->
            settings.toBuilder()
                .putAllEnabledSkills(ids.associateWith { enabled })
                .build()
        }.enabledSkillsMap
        markAsked(ids)
        if (skillRankers != null && after != before) {
            // SkillHandler builds the new ranker on another thread; without it the follow-up
            // batch of the skill run next would go to the old ranker, which is then dropped
            withTimeoutOrNull(RANKER_TIMEOUT_MILLIS) { skillRankers.first { it !== ranker } }
        }
    }

    override suspend fun markAsked(ids: List<String>) {
        val online = ids.filter { GarOnlineSkill.of(it) != null }
        if (online.isEmpty()) {
            return
        }
        garSettings.updateData { settings ->
            val asked = settings.onlineSkillsAskedList
            val new = online.distinct().filter { it !in asked }
            if (new.isEmpty()) settings else settings.toBuilder().addAllOnlineSkillsAsked(new).build()
        }
    }

    companion object {
        const val RANKER_TIMEOUT_MILLIS = 3000L
    }
}
