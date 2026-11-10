package org.stypox.dicio.skills.notes

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.stypox.dicio.R
import org.stypox.dicio.sentences.Sentences

/** Whether the phone is locked (with a PIN, pattern, ...): lists, notes and facts are private. */
internal fun isPhoneLocked(ctx: SkillContext): Boolean =
    ctx.android.getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true

/** "Add milk to my shopping list", "what's on my list": lists kept by GAR. */
object ListsInfo : SkillInfo("lists") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_lists)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_lists)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.AutoMirrored.Filled.ListAlt)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Lists[ctx.sentencesLanguage] ?: return null
        val yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage] ?: return null
        return ListsSkill(ListsInfo, data, yesNoData)
    }

    // GAR's screen for what it keeps: every list, note and fact, to share or delete
    override val renderSettings: @Composable () -> Unit get() = @Composable { NotesScreen() }
}

/** "Take a note: call the dentist", "read my notes". */
object NotesInfo : SkillInfo("notes") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_notes)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_notes)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.AutoMirrored.Filled.StickyNote2)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Notes[ctx.sentencesLanguage] ?: return null
        val yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage] ?: return null
        return NotesSkill(NotesInfo, data, yesNoData)
    }

    override val renderSettings: @Composable () -> Unit get() = @Composable { NotesScreen() }
}

/** "Remember that my locker is 42", "where did I park". */
object FactsInfo : SkillInfo("facts") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_facts)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_facts)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Lightbulb)

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Facts[ctx.sentencesLanguage] ?: return null
        val yesNoData = Sentences.UtilYesNo[ctx.sentencesLanguage] ?: return null
        return FactsSkill(FactsInfo, data, yesNoData)
    }

    override val renderSettings: @Composable () -> Unit get() = @Composable { NotesScreen() }
}
