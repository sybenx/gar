package org.stypox.dicio.gar

import cc.aaro.gar.router.RouterOutput
import cc.aaro.gar.router.tools.ToolCatalog
import cc.aaro.gar.router.tools.ToolSpec
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.InteractionPlan
import org.dicio.skill.skill.SkillOutput
import org.dicio.skill.skill.Specificity
import org.stypox.dicio.MockSkill
import org.stypox.dicio.eval.SkillRanker
import org.stypox.dicio.eval.SkillWithResult
import org.stypox.dicio.gar.call.GarFollowUpOutput
import org.stypox.dicio.gar.online.GarOnlineSkills
import org.stypox.dicio.gar.settings.GarSettings
import org.stypox.dicio.gar.settings.GarSettingsModule
import org.stypox.dicio.gar.tools.GarToolsProvider
import org.stypox.dicio.gar.tools.OfferedTool
import org.stypox.dicio.sentences.Sentences
import org.stypox.dicio.settings.datastore.UserSettingsSerializer
import org.stypox.dicio.skills.battery.BatteryInfo
import org.stypox.dicio.skills.conversation.ConversationInfo
import org.stypox.dicio.skills.sms.FakeOwnContact
import org.stypox.dicio.skills.sms.OwnCard
import org.stypox.dicio.skills.sms.OwnContact
import org.stypox.dicio.skills.sms.OwnContactAskOutput
import org.stypox.dicio.skills.sms.OwnContactRememberedOutput
import org.stypox.dicio.skills.sms.SendResult
import org.stypox.dicio.skills.sms.SmsAskOutput
import org.stypox.dicio.skills.sms.SmsCardOutput
import org.stypox.dicio.skills.sms.SmsChooseOutput
import org.stypox.dicio.skills.sms.SmsChooserSkill
import org.stypox.dicio.skills.sms.SmsContact
import org.stypox.dicio.skills.sms.SmsFlow
import org.stypox.dicio.skills.sms.SmsInfo
import org.stypox.dicio.skills.sms.SmsQuestion
import org.stypox.dicio.skills.sms.SmsRecipient
import org.stypox.dicio.skills.sms.SmsRecipients
import org.stypox.dicio.skills.sms.SmsSendOutput
import org.stypox.dicio.skills.sms.SmsSender
import org.stypox.dicio.skills.sms.SmsSkill
import org.stypox.dicio.skills.sms.SmsState
import org.stypox.dicio.skills.telephone.TelephoneCorrection
import org.stypox.dicio.skills.telephone.TelephoneCorrectionSkill
import org.stypox.dicio.skills.telephone.TelephoneInfo
import org.stypox.dicio.skills.telephone.TelephoneOutput
import org.stypox.dicio.skills.telephone.TelephoneSkill
import org.stypox.dicio.util.StringUtils
import java.io.File
import java.util.Locale

private val catalogTools = ToolCatalog.load(
    File("src/main/gar/tools.yml").readText(),
    File("src/main/sentences/skill_definitions.yml").readText(),
).tools

/** A model that calls no tool, and records what it got. */
private class SilentRouter : GarRouterSource {
    val routed = mutableListOf<String>()

    override fun isModelAvailable(settings: GarSettings) = true

    override suspend fun route(settings: GarSettings, utterance: String, tools: List<ToolSpec>) =
        RouterOutput.NoMatch.also { routed.add(utterance) }
}

/** Texts that never leave the test. Each one goes through at once. */
private class RecordingSender : SmsSender {
    val sent = mutableListOf<Pair<String, String>>()

    override fun canSendDirectly(): Boolean = true

    override fun send(number: String, message: String, onResult: (SendResult) -> Unit) {
        sent.add(Pair(number, message))
        onResult(SendResult.SENT)
    }

    override fun openSmsApp(number: String, message: String): Boolean = true
}

/** Stand-ins for the contacts a phone offered for "send a text to myself", none the owner. */
private val STRANGERS = listOf(
    SmsContact("Gabi (Plumber, Handyman, Locksmith)", listOf("555 0130")),
    SmsContact("Myrtle Seller 27", listOf("555 0131")),
    SmsContact("Katerina Book Club", listOf("555 0132")),
    SmsContact("Joe's Work", listOf("555 0133")),
    SmsContact("Melody Feed Store", listOf("555 0134")),
)
private val AARON = SmsContact("Aaron", listOf("+14355550167"))
private val MOM = SmsContact("Mom", listOf("+1 555 0100"))
private val PHONE_CONTACTS = STRANGERS + listOf(AARON, MOM)

/** The real English strings of `strings_gar_sms.xml`, formatted like Android does. */
private fun smsString(name: String, vararg args: Any): String {
    val template = Regex("<string name=\"$name\">(.*?)</string>")
        .find(File("src/main/res/values/strings_gar_sms.xml").readText())!!.groupValues[1]
        .replace("\\'", "'")
    return String.format(Locale.ROOT, template, *args)
}

/** What GAR reads back before sending [card]'s text. */
private fun readBack(card: SmsCardOutput): String = smsString(
    "skill_sms_read_back",
    SmsCardOutput.spokenName(card.draft.recipient),
    SmsCardOutput.spokenMessage(card.draft.message),
)

/**
 * Aaron's phone as GAR's evaluator sees it: every English skill of a new install, GAR's match
 * check of the real catalog, a model that calls no tool, and the sms skill with [contacts] and
 * [own] in memory instead of the contacts provider.
 */
private class ChatPhone(
    contacts: List<SmsContact> = PHONE_CONTACTS,
    val own: OwnContact = FakeOwnContact.of(*PHONE_CONTACTS.toTypedArray()),
) {
    val sender = RecordingSender()

    /** The names the sms flow looked up among the contacts, in order. */
    val searched = mutableListOf<String>()

    val flow = SmsFlow(
        findRecipient = { _, spoken, split ->
            searched.add(spoken)
            SmsRecipients.find(
                spoken = spoken,
                splitMessage = split,
                // like Contact.getFilteredSortedContacts: contacts that share some letters
                search = { name ->
                    contacts.map { Pair(it, StringUtils.contactStringDistance(it.name, name)) }
                        .filter { it.second < 0 }
                        .sortedBy { it.second }
                },
                name = { it.first.name },
                distance = { it.second },
                numbers = { it.first.numbers },
            )
        },
        senderFor = { sender },
        isDeviceLocked = { false },
        ownContact = { own },
    )

    val ranker = SkillRanker(
        GarOnlineSkills.forRanking(
            allEnglishSkills().map {
                if (it.correspondingSkillInfo == SmsInfo) SmsSkill(SmsInfo, Sentences.Sms["en"]!!, flow) else it
            },
            UserSettingsSerializer.defaultValue.enabledSkillsMap,
        ),
        MockSkill(Specificity.LOW, 0.0f),
    )
    val router = SilentRouter()
    private val fallback = GarFallbackImpl(
        settings = GarSettingsModule.newDataStoreForPreviews(),
        toolsProvider = object : GarToolsProvider {
            override suspend fun offeredTools(ctx: SkillContext) = listOf<OfferedTool>()
            override fun allTools() = catalogTools
        },
        routerSource = router,
        logger = { _, _ -> },
    )

    /** The evaluator's match for [input], with the questions asked so far still pending. */
    suspend fun match(input: String): SkillWithResult<*> =
        garMatchUtterances(FakeSkillContext(), ranker, fallback, listOf(input)).second

    /** Says [input]: GAR's answer, whose follow-up questions wait for the next input. */
    suspend fun say(input: String): SkillOutput = show(match(input).generateOutput(FakeSkillContext()))

    /** [output] is on the screen: its follow-up skills listen, like SkillEvaluator does. */
    fun show(output: SkillOutput): SkillOutput {
        when (val plan = output.getInteractionPlan(FakeSkillContext())) {
            InteractionPlan.FinishInteraction -> ranker.removeAllBatches()
            is InteractionPlan.FinishSubInteraction -> ranker.removeTopBatch()
            is InteractionPlan.Continue -> {}
            is InteractionPlan.StartSubInteraction -> ranker.addBatchToTop(plan.nextSkills)
            is InteractionPlan.ReplaceSubInteraction -> {
                ranker.removeTopBatch()
                ranker.addBatchToTop(plan.nextSkills)
            }
        }
        return output
    }
}

/**
 * Aaron's chat with GAR on his Pixel (Vosk), replayed through the evaluator's matching step with
 * every English skill: what he said, in the context he said it in.
 */
class GarChatReplayTest : StringSpec({
    "send a text to myself goes to his own contact, asked once" {
        val phone = ChatPhone()
        // it used to offer five contacts that only share letters with "myself"
        phone.match("send a text to myself").skill.correspondingSkillInfo shouldBe SmsInfo
        val ask = phone.say("send a text to myself").shouldBeInstanceOf<OwnContactAskOutput>()
        ask.options.shouldBeEmpty()
        ask.notFound.shouldBeNull()
        smsString("skill_own_contact_ask") shouldBe "Which contact is you?"
        phone.searched.shouldBeEmpty()

        val remembered = phone.say("it's aaron").shouldBeInstanceOf<OwnContactRememberedOutput>()
        remembered.name shouldBe "Aaron"
        smsString("skill_own_contact_remembered", remembered.name) shouldBe
            "OK, you're Aaron. You can change that in Settings, You and places."
        remembered.then.shouldBeInstanceOf<GarFollowUpOutput>().output.shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.MESSAGE
            it.recipient shouldBe SmsRecipient("Aaron", "+14355550167")
        }
        phone.sender.sent.shouldBeEmpty()

        // the next time, myself is Aaron without asking
        phone.say("cancel")
        phone.say("send a text to myself").shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.MESSAGE
            it.recipient shouldBe SmsRecipient("Aaron", "+14355550167")
        }
        phone.router.routed.shouldBeEmpty()
    }

    "send a text to myself with his card in Contacts asks for the message right away" {
        val card = OwnCard("Alex Herbert", listOf("435-555-0167", "+14355550167"), listOf())
        val phone = ChatPhone(own = FakeOwnContact(profile = card))
        phone.say("send a text to myself").shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.MESSAGE
            it.recipient shouldBe SmsRecipient("Alex Herbert", "+14355550167")
        }
        phone.searched.shouldBeEmpty()
    }

    "no to erin after which one looks the new name up, not Katerina Book Club" {
        // the list his phone showed for "send a text to myself"
        val phone = ChatPhone()
        phone.show(SmsChooseOutput(phone.flow, STRANGERS, null, nested = false))
        phone.match("no to erin").skill.shouldBeInstanceOf<SmsChooserSkill>()
        val ask = phone.say("no to erin").shouldBeInstanceOf<SmsAskOutput>()
        phone.searched shouldContainExactly listOf("erin")
        ask.question shouldBe SmsQuestion.RECIPIENT
        ask.notFound shouldBe "erin"
        smsString("skill_sms_unknown_contact", ask.notFound!!) shouldBe
            "I couldn't find erin in your contacts. Who should I text?"

        // then the name again, as he meant it
        phone.say("aaron").shouldBeInstanceOf<SmsAskOutput>().let {
            it.question shouldBe SmsQuestion.MESSAGE
            it.recipient shouldBe SmsRecipient("Aaron", "+14355550167")
        }
        phone.sender.sent.shouldBeEmpty()

        // the same for a call: the telephone skill's own list
        val calls = ChatPhone()
        val telephone = TelephoneSkill(TelephoneInfo, Sentences.Telephone["en"]!!, Sentences.UtilYesNo["en"]!!)
        calls.show(TelephoneOutput(STRANGERS.map { Pair(it.name, it.numbers) }, telephone.yesNoData, telephone))
        val match = calls.match("no to erin")
        match.skill.shouldBeInstanceOf<TelephoneCorrectionSkill>()
        match.inputData shouldBe TelephoneCorrection.LookUp("erin")
    }

    "no, I'm good after which one stops, as the conversation skill does" {
        val telephone = TelephoneSkill(TelephoneInfo, Sentences.Telephone["en"]!!, Sentences.UtilYesNo["en"]!!)
        for (input in listOf("no, I'm good", "no, forget it", "not now")) {
            withClue(input) {
                val phone = ChatPhone()
                phone.show(SmsChooseOutput(phone.flow, STRANGERS, null, nested = false))
                phone.match(input).skill.correspondingSkillInfo shouldBe ConversationInfo
                val calls = ChatPhone()
                calls.show(TelephoneOutput(STRANGERS.map { Pair(it.name, it.numbers) }, telephone.yesNoData, telephone))
                calls.match(input).skill.correspondingSkillInfo shouldBe ConversationInfo
            }
        }
    }

    "a dictated message is kept as said and read back before sending" {
        val phone = ChatPhone()
        phone.say("send a text to aaron").shouldBeInstanceOf<SmsAskOutput>().question shouldBe SmsQuestion.MESSAGE
        // no command, no correction: the message as said
        val card = phone.say("no no no send a text aaron").shouldBeInstanceOf<SmsCardOutput>()
        card.draft.recipient shouldBe SmsRecipient("Aaron", "+14355550167")
        card.draft.message shouldBe "No no no send a text aaron"
        readBack(card) shouldBe "Your message to Aaron says: No no no send a text aaron. Ready to send it?"
        card.draft.state shouldBe SmsState.PENDING
        phone.sender.sent.shouldBeEmpty()

        // sent only after a yes
        phone.say("yes").shouldBeInstanceOf<SmsSendOutput>().state shouldBe SmsState.SENT
        phone.sender.sent shouldContainExactly listOf(Pair("+14355550167", "No no no send a text aaron"))
        phone.router.routed.shouldBeEmpty()
    }

    "where's my battery gets the battery level" {
        for (input in listOf("where's my battery", "where is my battery", "what's my battery")) {
            withClue(input) {
                val phone = ChatPhone()
                val match = phone.match(input)
                match.skill.correspondingSkillInfo shouldBe BatteryInfo
                match.inputData.shouldBeInstanceOf<Sentences.Battery.Level>()
                phone.router.routed.shouldBeEmpty()
            }
        }
    }
})
