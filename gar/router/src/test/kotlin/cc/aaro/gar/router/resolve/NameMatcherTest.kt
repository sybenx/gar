package cc.aaro.gar.router.resolve

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

private data class Contact(val id: Int, val name: String)

private fun match(spoken: String, vararg names: String, maxOptions: Int = 5) =
    NameMatcher.match(spoken, names.toList(), { it }, maxOptions)

class NameMatcherTest : StringSpec({
    "STT splits and joins of app names" {
        match("spot if i", "Settings", "Spotify", "Signal", "Spotlight") shouldBe Resolution.Resolved("Spotify")
        match("you tube", "YouTube Music", "YouTube", "Tube Map") shouldBe Resolution.Resolved("YouTube")
        match("what's app", "WhatsApp", "Maps") shouldBe Resolution.Resolved("WhatsApp")
        match("g mail", "Gmail", "Mail") shouldBe Resolution.Resolved("Gmail")
    }

    "case, accents, punctuation and filler words don't matter" {
        match("mom", "Dad", "Mom", "Tom") shouldBe Resolution.Resolved("Mom")
        match("jose garcia", "José García", "Joseph Gray") shouldBe Resolution.Resolved("José García")
        match("the camera app", "Calculator", "Camera") shouldBe Resolution.Resolved("Camera")
        match("my johnson", "Mike Jones", "Mike Johnson") shouldBe Resolution.Resolved("Mike Johnson")
        match("google maps", "Google", "Google Maps", "Maps") shouldBe Resolution.Resolved("Google Maps")
        match("o'brien", "Pat O'Brien", "Brian") shouldBe Resolution.Resolved("Pat O'Brien")
    }

    "an exact match beats a longer name with the same first word" {
        match("sarah", "Sarah Lee", "Sarah") shouldBe Resolution.Resolved("Sarah")
        match("john", "John Smith", "Jon Snow") shouldBe Resolution.Resolved("John Smith")
    }

    "ties and near ties are ambiguous, best first, in candidate order" {
        match("sarah", "Sarah Connor", "Mike", "Sarah Lee") shouldBe
            Resolution.Ambiguous(listOf("Sarah Connor", "Sarah Lee"), AmbiguityReason.SEVERAL_MATCHES)
        match("mom", "Mom", "Mom") shouldBe
            Resolution.Ambiguous(listOf("Mom", "Mom"), AmbiguityReason.SEVERAL_MATCHES)
        val many = match("alex", "Alex Bell", "Alex Cole", "Alex Dunn", "Alex Ford", "Alex Gray",
            "Alex Hart", maxOptions = 3)
        many shouldBe Resolution.Ambiguous(listOf("Alex Bell", "Alex Cole", "Alex Dunn"),
            AmbiguityReason.SEVERAL_MATCHES)
    }

    "nothing close enough" {
        match("urine", "Aaron", "Mom").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("pizza", "Spotify", "YouTube").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("", "Mom").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("mom").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("mom", "", "!!").shouldBeInstanceOf<Resolution.Unresolvable>()
    }

    "short names need all their letters" {
        // one letter of three is a third off, below MIN_SCORE: "mom" doesn't find a contact saved as "Mum"
        match("mom", "Mum", "Dad").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("jo", "Joe", "Jon").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("dan", "Dad").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("dad", "Dan", "Dad") shouldBe Resolution.Resolved("Dad")
    }

    "extra and reordered words" {
        match("call mom", "Mom").shouldBeInstanceOf<Resolution.Unresolvable>()
        match("mom's", "Mom", "Tom") shouldBe Resolution.Resolved("Mom")
        match("john smith", "Smith John") shouldBe Resolution.Resolved("Smith John")
        match("sarah connor", "Sarah", "Sara Connor") shouldBe Resolution.Resolved("Sara Connor")
    }

    "close app names" {
        match("messages", "Messages", "Messenger") shouldBe Resolution.Resolved("Messages")
        match("messenger", "Messages", "Messenger") shouldBe Resolution.Resolved("Messenger")
        match("whatsapp", "WhatsApp", "WhatsApp Business") shouldBe Resolution.Resolved("WhatsApp")
        match("tick tock", "TikTok") shouldBe Resolution.Resolved("TikTok")
    }

    "filler words are dropped from the candidates too, but a filler alone is no name" {
        // "App Store" without "app" is "store"
        match("store", "Play Store", "App Store") shouldBe Resolution.Resolved("App Store")
        match("camera", "Camera", "Camera App") shouldBe
            Resolution.Ambiguous(listOf("Camera", "Camera App"), AmbiguityReason.SEVERAL_MATCHES)
        match("the", "The Weather Channel", "Theo") shouldBe Resolution.Unresolvable("no name")
        match("my", "My Fitness Pal") shouldBe Resolution.Unresolvable("no name")
        match("the app", "The Weather Channel", "App Store") shouldBe Resolution.Unresolvable("no name")
        match("!!", "Mom") shouldBe Resolution.Unresolvable("no name")
    }

    "a pick question needs two options at least" {
        for (maxOptions in listOf(1, 0)) {
            shouldThrow<IllegalArgumentException> { match("mom", "Mom", "Mom", maxOptions = maxOptions) }
        }
        match("mom", "Mom", "Mom", maxOptions = 2) shouldBe
            Resolution.Ambiguous(listOf("Mom", "Mom"), AmbiguityReason.SEVERAL_MATCHES)
    }

    "works on any candidate type" {
        val contacts = listOf(Contact(1, "Anna Schmidt"), Contact(2, "Hannah"), Contact(3, "Ana"))
        NameMatcher.match("hannah", contacts, { it.name }) shouldBe Resolution.Resolved(Contact(2, "Hannah"))
    }

    "a shared word is a whole word, never letters inside another word" {
        // seen on Aaron's phone: "no to erin" picked "Katerina Book Club"
        NameMatcher.sharesWord("erin", "Katerina Book Club") shouldBe false
        NameMatcher.sharesWord("no to erin", "Katerina Book Club") shouldBe false
        for (contact in listOf(
            "Gabi (Plumber, Handyman, Locksmith)", "Myrtle Seller 27", "Katerina Book Club",
            "Joe's Work", "Melody Feed Store",
        )) {
            NameMatcher.sharesWord("myself", contact) shouldBe false
        }
        NameMatcher.sharesWord("club", "Katerina Book Club") shouldBe true
        NameMatcher.sharesWord("joe", "Joe's Work") shouldBe true
        // the "s" of a possessive is no word of its own
        NameMatcher.sharesWord("mom's", "Joe's Work") shouldBe false
    }

    "a shared word may be misheard a little, split, or the start of a longer one" {
        NameMatcher.sharesWord("jon", "John") shouldBe true
        NameMatcher.sharesWord("sara lee", "Sara-Lee") shouldBe true
        NameMatcher.sharesWord("sara lee", "Saralee") shouldBe true
        NameMatcher.sharesWord("sam", "Samantha Jones") shouldBe true
        NameMatcher.sharesWord("johnny", "John") shouldBe true
        NameMatcher.sharesWord("connor", "Sarah Connor") shouldBe true
        // another spelling Vosk writes the same way every time, as Dicio's distance found it
        NameMatcher.sharesWord("gabby", "Gabi (Plumber, Handyman, Locksmith)") shouldBe true
        NameMatcher.sharesWord("steven", "Stephen") shouldBe true
        NameMatcher.sharesWord("sean", "Shawn") shouldBe true
        NameMatcher.sharesWord("katherine", "Kathryn") shouldBe true
        NameMatcher.sharesWord("jamie", "Jaime Lopez") shouldBe true
        // but not with another first letter, or too far apart
        NameMatcher.sharesWord("erin", "Aaron") shouldBe false
        NameMatcher.sharesWord("myself", "Melody Feed Store") shouldBe false
        // too short to start a word, and filler words don't count
        NameMatcher.sharesWord("to", "Tom") shouldBe false
        NameMatcher.sharesWord("the", "The Weather Channel") shouldBe false
        NameMatcher.sharesWord("pizza", "Mom") shouldBe false
        NameMatcher.sharesWord("", "Mom") shouldBe false
    }

    "similarity" {
        NameMatcher.similarity("you tube", "YouTube") shouldBe 1.0
        NameMatcher.similarity("spot if i", "Spotify") shouldBeGreaterThan 0.8
        NameMatcher.similarity("mom", "dad") shouldBeLessThan 0.5
    }
})
