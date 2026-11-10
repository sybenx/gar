package org.stypox.dicio.skills.connectivity

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Permission
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

/** Needed on API 31+ to show the system's "turn on Bluetooth?" dialog. */
val PERMISSION_BLUETOOTH_CONNECT = Permission.NormalPermission(
    name = R.string.perm_bluetooth_connect,
    id = Manifest.permission.BLUETOOTH_CONNECT,
)

object ConnectivityInfo : SkillInfo("connectivity") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_connectivity)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_connectivity)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.SettingsInputAntenna)

    // BLUETOOTH_CONNECT is not a runtime permission before API 31, so it can't be requested there
    override val neededPermissions: List<Permission>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(PERMISSION_BLUETOOTH_CONNECT)
        } else {
            listOf()
        }

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Connectivity[ctx.sentencesLanguage] ?: return null
        return ConnectivitySkill(ConnectivityInfo, data)
    }
}
