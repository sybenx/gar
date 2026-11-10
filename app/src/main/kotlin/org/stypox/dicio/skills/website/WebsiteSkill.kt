package org.stypox.dicio.skills.website

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.Score
import org.dicio.skill.skill.Skill
import org.dicio.skill.skill.SkillInfo
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.standard.StandardRecognizerData
import org.dicio.skill.standard.StandardRecognizerSkill
import org.stypox.dicio.R
import org.stypox.dicio.io.graphical.HeadlineSpeechSkillOutput
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.sentences.Sentences.Website
import org.stypox.dicio.util.getString

object WebsiteInfo : SkillInfo("website") {
    override fun name(context: Context) =
        context.getString(R.string.skill_name_website)

    override fun sentenceExample(context: Context) =
        context.getString(R.string.skill_sentence_example_website)

    @Composable
    override fun icon() =
        rememberVectorPainter(Icons.Default.Language)

    // no permissions: the browser loads the page

    override fun build(ctx: SkillContext): Skill<*>? {
        val data = Sentences.Website[ctx.sentencesLanguage] ?: return null
        return WebsiteSkill(WebsiteInfo, data)
    }
}

/**
 * GAR (gar:places). "Go to reddit dot com", "open wikipedia.org", "visit the site example dot co
 * dot uk": the address ([WebAddress]) in the default browser. Only a match whose capture is an
 * address counts, since the sentences take any words ("go to bed"). Dicio's open skill and the
 * model's `open_app` calls reach the same output through [outputFor].
 */
class WebsiteSkill(
    correspondingSkillInfo: SkillInfo,
    data: StandardRecognizerData<Website>,
) : StandardRecognizerSkill<Website>(correspondingSkillInfo, data) {

    override fun score(ctx: SkillContext, input: String): Pair<Score, Website> {
        val (score, sentence) = super.score(ctx, input)
        val site = when (sentence) {
            is Website.Open -> sentence.site
        }
        return Pair(if (site?.let(WebAddress::parse) != null) score else AlwaysWorstScore, sentence)
    }

    override suspend fun generateOutput(ctx: SkillContext, inputData: Website): SkillOutput {
        val site = when (inputData) {
            is Website.Open -> inputData.site
        }
        return outputFor(ctx, site ?: "") ?: WebsiteOutput(null, opened = false)
    }

    companion object {
        private const val TAG = "WebsiteSkill"

        /**
         * Opens the address in [said] in the default browser, or returns null when [said] is no
         * address (an app's name, for the open skill).
         */
        fun outputFor(
            ctx: SkillContext,
            said: String,
            open: (Context, WebAddress) -> Boolean = ::openInBrowser,
        ): SkillOutput? {
            val address = WebAddress.parse(said, lenient = true) ?: return null
            return WebsiteOutput(address, open(ctx.android, address))
        }

        fun openInBrowser(context: Context, address: WebAddress): Boolean = try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(address.url))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No browser for ${address.url}", e)
            false
        }
    }
}

/** "Opening reddit.com.", "I couldn't find a browser to open reddit.com.". */
class WebsiteOutput(
    val address: WebAddress?,
    val opened: Boolean,
) : HeadlineSpeechSkillOutput {
    override fun getSpeechOutput(ctx: SkillContext): String = when {
        address == null -> ctx.getString(R.string.skill_website_not_understood)
        opened -> ctx.getString(R.string.skill_website_opening, address.display)
        else -> ctx.getString(R.string.skill_website_no_browser, address.display)
    }
}
