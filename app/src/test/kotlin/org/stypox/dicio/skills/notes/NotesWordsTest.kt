package org.stypox.dicio.skills.notes

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** gar:notes: how lists, notes and facts read their requests, and what the store keeps. */
class NotesWordsTest : StringSpec({
    "list requests, whole" {
        val expected = listOf(
            "add milk to my shopping list" to ListRequest.Add(listOf("milk"), "shopping "),
            "put eggs on the list" to ListRequest.Add(listOf("eggs"), ""),
            "add milk, eggs and bread to my grocery list" to
                ListRequest.Add(listOf("milk", "eggs", "bread"), "grocery "),
            "add mac and cheese and apples to the list" to
                ListRequest.Add(listOf("mac and cheese", "apples"), ""),
            "add to my shopping list milk and eggs" to ListRequest.Add(listOf("milk", "eggs"), "shopping "),
            "ok please add some milk to my list" to ListRequest.Add(listOf("milk"), ""),
            "remove eggs from the list" to ListRequest.Remove("eggs", ""),
            "take eggs off my shopping list" to ListRequest.Remove("eggs", "shopping "),
            "what's on my shopping list" to ListRequest.Read("shopping "),
            "what is on the list" to ListRequest.Read(""),
            "read me my to do list" to ListRequest.Read("to do "),
            "what do i need to buy" to ListRequest.Read(NotesBook.DEFAULT_LIST),
            "is milk on my shopping list" to ListRequest.Has("milk", "shopping "),
            "clear my shopping list" to ListRequest.Clear("shopping ", delete = false),
            "remove everything from the list" to ListRequest.Clear("", delete = false),
            "delete my packing list" to ListRequest.Clear("packing ", delete = true),
            "what lists do i have" to ListRequest.Lists,
            "make a new packing list" to ListRequest.Create("packing"),
        )
        for ((input, request) in expected) {
            withClue(input) { ListWords.request(input) shouldBe request }
        }
    }

    "not list requests" {
        for (input in listOf(
            "add a dentist appointment to my calendar", "add it to the list", "what's on my calendar",
            "remind me to buy milk", "what is on the playlist", "play my workout playlist",
        )) {
            withClue(input) { ListWords.request(input) shouldBe null }
        }
    }

    "note requests" {
        val expected = listOf(
            "take a note: call the dentist" to NoteRequest.Take("call the dentist"),
            "take a note call the dentist" to NoteRequest.Take("call the dentist"),
            "note to self buy stamps" to NoteRequest.Take("buy stamps"),
            "write down call mom tomorrow" to NoteRequest.Take("call mom tomorrow"),
            "make a note that the garage code is 1234" to NoteRequest.Take("the garage code is 1234"),
            "take a note" to NoteRequest.Take(null),
            "read my notes" to NoteRequest.Read(lastOnly = false),
            "what are my notes" to NoteRequest.Read(lastOnly = false),
            "read my last note" to NoteRequest.Read(lastOnly = true),
            "delete my last note" to NoteRequest.DeleteLast,
            "delete all my notes" to NoteRequest.DeleteAll,
        )
        for ((input, request) in expected) {
            withClue(input) { NoteWords.request(input) shouldBe request }
        }
        NoteWords.request("what's the note on the piano called") shouldBe null
    }

    "fact requests" {
        val expected = listOf(
            "remember that my locker is 42" to FactRequest.Remember("my locker is 42"),
            "remember I parked on level 3" to FactRequest.Remember("i parked on level 3"),
            "where did I park" to FactRequest.Recall("where did i park", always = true),
            "what's my locker number" to FactRequest.Recall("whats my locker number", always = false),
            "forget where I parked" to FactRequest.Forget("where i parked", always = true),
            "forget everything I told you" to FactRequest.ForgetAll,
            "what do you remember" to FactRequest.ListAll,
            "remember my mom likes tulips" to FactRequest.Remember("my mom likes tulips"),
            "remember my keys are in the drawer" to FactRequest.Remember("my keys are in the drawer"),
            "do you remember my locker number" to FactRequest.Recall("my locker number", always = true),
            // anyone's question: the facts' only when a fact answers it
            "do you know the time" to FactRequest.Recall("the time", always = false),
            // what to keep about it isn't said: GAR asks
            "remember my parking spot" to FactRequest.AskWhat("my parking spot"),
            "remember this parking space" to FactRequest.AskWhat("this parking space"),
            "remember my locker number" to FactRequest.AskWhat("my locker number"),
            "remember where i parked" to FactRequest.AskWhat("where i parked"),
        )
        for ((input, request) in expected) {
            withClue(input) { FactWords.request(input) shouldBe request }
        }
        // the conversation's and the reminders' phrasings, and nothing to keep
        for (input in listOf("forget it", "forget about it", "remember to buy eggs", "remember")) {
            withClue(input) { FactWords.request(input) shouldBe null }
        }
    }

    "lists keep items once, by any spelling, and remember the last list" {
        var data = GarNotesData()
        val (afterAdd, added) = NotesBook.add(data, "shopping", listOf("milk", "eggs"))
        added shouldBe ListAdd("shopping", listOf("milk", "eggs"), listOf())
        val (afterAgain, again) = NotesBook.add(afterAdd, "shopping", listOf("Egg", "bread"))
        again shouldBe ListAdd("shopping", listOf("bread"), listOf("Egg"))
        data = afterAgain
        NotesBook.listName(data, "") shouldBe "shopping"
        NotesBook.listName(data, "grocery ") shouldBe "shopping"
        NotesBook.listName(GarNotesData(), "the ") shouldBe NotesBook.DEFAULT_LIST
        NotesBook.findItem(data, "shopping", "egg") shouldBe "eggs"
        NotesBook.findItem(data, "shopping", "cheese") shouldBe null
        NotesBook.list(NotesBook.remove(data, "shopping", "eggs"), "shopping")?.items shouldBe listOf("milk", "bread")
        NotesBook.list(NotesBook.clear(data, "shopping"), "shopping")?.items?.shouldBeEmpty()
        NotesBook.delete(data, "shopping").lists.shouldBeEmpty()
    }

    "a fact replaces what was remembered about the same thing, and is found by its words" {
        var data = GarNotesData()
        data = NotesBook.remember(data, "my locker is 42", 1)
        data = NotesBook.remember(data, "i parked on level 3", 2)
        data = NotesBook.remember(data, "i left my keys in the kitchen", 3)
        data = NotesBook.remember(data, "i parked on level 5", 4)
        data = NotesBook.remember(data, "my locker is 43", 5)
        data.facts.map { it.text } shouldBe listOf("i left my keys in the kitchen", "i parked on level 5", "my locker is 43")

        NotesBook.recall(data, "where did i park").map { it.text } shouldBe listOf("i parked on level 5")
        NotesBook.recall(data, "whats my locker number").map { it.text } shouldBe listOf("my locker is 43")
        NotesBook.recall(data, "where are my keys").map { it.text } shouldBe listOf("i left my keys in the kitchen")
        NotesBook.recall(data, "whats the weather like in paris", strict = true).shouldBeEmpty()
        NotesBook.recall(data, "what time is it", strict = true).shouldBeEmpty()
        // one or two words must all match: "how do i turn on wifi" is no question about the password
        val wifi = NotesBook.remember(GarNotesData(), "my wifi password is hunter2", 1)
        NotesBook.recall(wifi, "how do i turn on wifi", strict = true).shouldBeEmpty()
        NotesBook.recall(wifi, "whats my wifi password", strict = true).map { it.text } shouldBe
            listOf("my wifi password is hunter2")

        // the answer to "What should I remember about ...?"
        FactWords.completed("my parking spot", "level 3") shouldBe "my parking spot is level 3"
        FactWords.completed("my parking spot", "it's on level 3") shouldBe "my parking spot is on level 3"
        FactWords.completed("where i parked", "on level 3") shouldBe "i parked on level 3"
        FactWords.completed("where i parked", "I parked on level 3") shouldBe "i parked on level 3"

        FactWords.secondPerson("i parked on level 5") shouldBe "You parked on level 5."
        FactWords.secondPerson("my locker is 43") shouldBe "Your locker is 43."
        FactWords.secondPerson("i am allergic to cats") shouldBe "You are allergic to cats."
    }

    "the store keeps what it saved, and starts empty when the file is broken" {
        val store = MemoryNotesStore()
        val notes = GarNotes(store)
        notes.update { NotesBook.addNote(it, "Call the dentist", 7) }
        GarNotes(store).current().notes shouldBe listOf(GarNote("Call the dentist", 7))
        GarNotes(MemoryNotesStore("{not json")).current() shouldBe GarNotesData()
    }
})

/** A [GarNotesStore] in memory. */
class MemoryNotesStore(var text: String? = null) : GarNotesStore {
    var saves = 0

    override fun load(): String? = text

    override fun save(encoded: String) {
        text = encoded
        saves++
    }
}
