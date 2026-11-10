package org.stypox.dicio.settings.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

object UserSettingsSerializer : Serializer<UserSettings> {
    // GAR: ids of the skills that make network requests (declared before defaultValue, which
    // uses it during object initialization): Dicio's and GAR's own (gar/online/GarOnlineSkill)
    private val GAR_ONLINE_SKILL_IDS = org.stypox.dicio.gar.online.GarOnlineSkill.ids

    override val defaultValue: UserSettings = UserSettings.getDefaultInstance()
        .toBuilder()
        .setAutoFinishSttPopup(true)
        // GAR defaults: no wake word, and skills that use the network start disabled
        .setWakeDevice(WakeDevice.WAKE_DEVICE_NOTHING)
        .putAllEnabledSkills(GAR_ONLINE_SKILL_IDS.associateWith { false })
        .build()

    override suspend fun readFrom(input: InputStream): UserSettings {
        try {
            // GAR: an online skill added after the install starts off too (a skill missing from
            // the map is on, see SkillHandler)
            return withGarOnlineSkillsOff(UserSettings.parseFrom(input))
        } catch (exception: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto", exception)
        }
    }

    /** GAR: [settings] with each online skill it doesn't switch on or off switched off. */
    fun withGarOnlineSkillsOff(settings: UserSettings): UserSettings {
        val missing = GAR_ONLINE_SKILL_IDS.filter { it !in settings.enabledSkillsMap }
        return if (missing.isEmpty()) {
            settings
        } else {
            settings.toBuilder().putAllEnabledSkills(missing.associateWith { false }).build()
        }
    }

    override suspend fun writeTo(t: UserSettings, output: OutputStream) {
        t.writeTo(output)
    }
}
