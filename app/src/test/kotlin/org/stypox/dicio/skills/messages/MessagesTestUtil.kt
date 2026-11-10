package org.stypox.dicio.skills.messages

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.stypox.dicio.R
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.FakeSkillInfo
import org.stypox.dicio.gar.RecordingSkill
import java.io.File
import java.util.Locale

private fun sourceFile(path: String): String =
    sequenceOf(File(path), File("app", path)).first { it.isFile }.readText()

/**
 * The strings of `strings_gar_messages.xml`, formatted like Android does, by resource id: the
 * outputs' speech without an Android context.
 */
val englishSay: Say by lazy {
    val templates = Regex("<string name=\"([a-z0-9_]+)\">(.*?)</string>")
        .findAll(sourceFile("src/main/res/values/strings_gar_messages.xml"))
        .associate { match ->
            val text = match.groupValues[2].replace("\\'", "'")
            Pair(match.groupValues[1], if (text.length >= 2 && text.startsWith('"') && text.endsWith('"')) {
                text.substring(1, text.length - 1)
            } else {
                text
            })
        }
    val names = R.string::class.java.fields.associate { Pair(it.getInt(null), it.name) }
    val say: Say = { id, args ->
        val template = templates[names[id]] ?: throw AssertionError("no string with id $id")
        String.format(Locale.ROOT, template, *args.toTypedArray())
    }
    say
}

/** The messaging apps' notifications in memory, replying as [result] says. */
class FakeSource(
    var conversations: List<Conversation>? = listOf(),
    var result: ReplyResult = ReplyResult.SENT,
    var opens: Boolean = true,
) : MessageSource {
    val replies = mutableListOf<Pair<String, String>>()
    val opened = mutableListOf<String>()

    override fun conversations(): List<Conversation>? = conversations

    override fun reply(conversation: Conversation, text: String): ReplyResult {
        replies.add(Pair(conversation.key, text))
        return result
    }

    override fun openApp(packageName: String): Boolean {
        opened.add(packageName)
        return opens
    }
}

fun chat(
    name: String,
    vararg texts: String,
    app: String = "Signal",
    time: Long = 1_000,
    canReply: Boolean = true,
    key: String = "$app|$name",
) = Conversation(
    key = key,
    packageName = "org.example.${app.lowercase().replace(" ", "")}",
    app = app,
    name = name,
    isGroup = false,
    messages = texts.mapIndexed { i, text -> ChatMessage(null, text, time - texts.size + i + 1) },
    time = time,
    canReply = canReply,
)

fun group(
    name: String,
    vararg messages: Pair<String, String>,
    app: String = "Signal",
    time: Long = 1_000,
    canReply: Boolean = true,
) = Conversation(
    key = "$app|$name",
    packageName = "org.example.${app.lowercase()}",
    app = app,
    name = name,
    isGroup = true,
    messages = messages.mapIndexed { i, (sender, text) -> ChatMessage(sender, text, time - messages.size + i + 1) },
    time = time,
    canReply = canReply,
)

/** A flow on [source], with its own memory, unlocked unless [locked]. */
fun messagesFlow(source: FakeSource, locked: Boolean = false, memory: ReadMemory = ReadMemory()) =
    MessagesFlow(sourceFor = { source }, isDeviceLocked = { locked }, memory = memory)

/** Changes the stack of batches like SkillEvaluator does after an output. */
fun SkillRanker.apply(plan: InteractionPlan) {
    when (plan) {
        InteractionPlan.FinishInteraction -> removeAllBatches()
        is InteractionPlan.FinishSubInteraction -> removeTopBatch()
        is InteractionPlan.Continue -> {}
        is InteractionPlan.StartSubInteraction -> addBatchToTop(plan.nextSkills)
        is InteractionPlan.ReplaceSubInteraction -> {
            removeTopBatch()
            addBatchToTop(plan.nextSkills)
        }
    }
}

/** A stack of batches with [output]'s follow-up skills on top and nothing below. */
fun rankerAfter(output: SkillOutput): SkillRanker =
    SkillRanker(listOf(), RecordingSkill(FakeSkillInfo("fallback")))
        .also { it.apply(output.getInteractionPlan(FakeSkillContext())) }

/** Evaluates [input] against the stack like SkillEvaluator, and applies the output's plan. */
suspend fun SkillRanker.evaluate(input: String, ctx: SkillContext = FakeSkillContext().forInput(input)): SkillOutput? {
    val output = getBest(ctx, input)?.generateOutput(ctx) ?: return null
    apply(output.getInteractionPlan(ctx))
    return output
}
