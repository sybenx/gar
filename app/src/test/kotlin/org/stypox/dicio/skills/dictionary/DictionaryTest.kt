package org.stypox.dicio.skills.dictionary

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.dicio.skill.skill.AlwaysWorstScore
import org.dicio.skill.skill.FloatScore
import org.stypox.dicio.gar.FakeSkillContext
import org.stypox.dicio.gar.garRankingWinner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** WordNet's license lines start with two spaces, so they sort before every word. */
private const val HEADER = "  1 This software and database is being provided to you  \n" +
    "  2 under the Creative Commons Attribution 4.0 License  \n"

/**
 * A tiny WordNet in its own format: [synsets] per part of speech as (words, pointers, gloss), each
 * word indexed; offsets are the data lines' byte offsets, as in the real files.
 */
private fun writeWordNet(
    dir: File,
    synsets: Map<PartOfSpeech, List<Triple<List<String>, String, String>>>,
    exceptions: Map<PartOfSpeech, List<String>> = mapOf(),
) {
    dir.mkdirs()
    for (pos in PartOfSpeech.entries) {
        val data = StringBuilder(HEADER)
        val index = sortedMapOf<String, MutableList<Long>>()
        val offsets = mutableListOf<Long>()
        // two passes: pointers name the offsets of later synsets ("&" to a similar adjective)
        for (pass in 0..1) {
            data.setLength(0)
            data.append(HEADER)
            for ((i, synset) in synsets[pos].orEmpty().withIndex()) {
                val offset = data.toString().toByteArray().size.toLong()
                if (pass == 0) offsets.add(offset)
                val (words, pointers, gloss) = synset
                val wordPart = words.joinToString(" ") { "$it 0" }
                val resolved = Regex("@(\\d+)").replace(pointers) {
                    "%08d".format(offsets.getOrElse(it.groupValues[1].toInt()) { 0L })
                }
                val line = "%08d 00 %s %02x %s %s | %s  \n".format(
                    offset, pos.fileName.first(), words.size, wordPart, resolved, gloss,
                )
                data.append(line)
                if (pass == 1) {
                    for (word in words) {
                        index.getOrPut(word.lowercase()) { mutableListOf() }.add(offsets[i])
                    }
                }
            }
        }
        File(dir, "data.${pos.fileName}").writeText(data.toString())
        val indexText = StringBuilder(HEADER)
        for ((lemma, synsetOffsets) in index) {
            indexText.append(
                "$lemma ${pos.fileName.first()} ${synsetOffsets.size} 0 ${synsetOffsets.size} 0 " +
                    synsetOffsets.joinToString(" ") { "%08d".format(it) } + "  \n"
            )
        }
        File(dir, "index.${pos.fileName}").writeText(indexText.toString())
        File(dir, "${pos.fileName}.exc").writeText(
            exceptions[pos].orEmpty().sorted().joinToString("") { "$it\n" }
        )
    }
}

private fun tempDir(): File = Files.createTempDirectory("gar-wordnet").toFile()

private fun sampleWordNet(dir: File) = writeWordNet(
    dir,
    mapOf(
        PartOfSpeech.NOUN to listOf(
            Triple(listOf("serendipity"), "000", "good luck in making unexpected and fortunate discoveries"),
            Triple(listOf("goose"), "000", "web-footed long-necked typically gregarious migratory aquatic birds"),
            Triple(listOf("ice_cream", "icecream"), "000", "frozen dessert containing cream and sugar"),
            Triple(listOf("run", "tally"), "000", "a score in baseball; \"the Yankees scored 3 runs\""),
        ),
        PartOfSpeech.VERB to listOf(
            Triple(listOf("run"), "000", "move fast by using one's feet; \"Don't run\"; \"run home\""),
            Triple(listOf("run", "operate"), "000", "direct or control"),
        ),
        PartOfSpeech.ADJECTIVE to listOf(
            Triple(listOf("happy"), "001 & @1 s 0000", "enjoying or showing joy; \"a happy smile\""),
            Triple(listOf("blissful"), "000", "completely happy"),
            Triple(listOf("felicitous", "happy"), "000", "well expressed and to the point"),
        ),
    ),
    exceptions = mapOf(PartOfSpeech.NOUN to listOf("geese goose"), PartOfSpeech.VERB to listOf("ran run")),
)

class DictionaryTest : StringSpec({
    "the request forms and their words" {
        for ((input, request) in listOf(
            "define serendipity" to DictionaryRequest("serendipity", false),
            "define the word ubiquitous" to DictionaryRequest("ubiquitous", false),
            "what does ubiquitous mean" to DictionaryRequest("ubiquitous", false),
            "what's the meaning of the word ephemeral" to DictionaryRequest("ephemeral", false),
            "meaning of petrichor" to DictionaryRequest("petrichor", false),
            "what is the definition of ice cream" to DictionaryRequest("ice cream", false),
            "can you define serendipity please" to DictionaryRequest("serendipity", false),
            "synonym for happy" to DictionaryRequest("happy", true),
            "what's a synonym for big" to DictionaryRequest("big", true),
            "what are some synonyms of fast" to DictionaryRequest("fast", true),
            "give me another word for tired" to DictionaryRequest("tired", true),
        )) {
            withClue(input) { DictionaryRequests.parse(input) shouldBe request }
        }
    }

    "other requests are no dictionary request" {
        for (input in listOf(
            "how do you spell necessary", "what does that mean", "define it", "what is serendipity",
            "the meaning of life", "what's the meaning of this", "look up serendipity",
            "define a b c d", "what does 42 mean", "who is taylor swift", "set a timer",
        )) {
            withClue(input) { DictionaryRequests.parse(input).shouldBeNull() }
        }
    }

    "ranked with every skill: definitions go to the dictionary, spelling stays" {
        for (input in listOf("define serendipity", "what does ubiquitous mean", "synonym for happy")) {
            withClue(input) { garRankingWinner(input).first shouldBe DictionaryInfo.id }
        }
        garRankingWinner("how do you spell necessary").first shouldBe "spelling"
    }

    "WordNet lookups: base forms, exceptions, several parts of speech" {
        val dir = tempDir()
        sampleWordNet(dir)
        val wordNet = WordNet(dir)
        wordNet.lookup("serendipity").single().senses.single().definition shouldBe
            "good luck in making unexpected and fortunate discoveries"
        wordNet.lookup("geese").single().lemma shouldBe "goose"
        wordNet.lookup("Ice Cream").single().senses.single().words shouldContainExactly
            listOf("ice cream", "icecream")
        // the verb has more meanings
        wordNet.lookup("run").map { it.partOfSpeech } shouldContainExactly
            listOf(PartOfSpeech.VERB, PartOfSpeech.NOUN)
        wordNet.lookup("ran").single().lemma shouldBe "run"
        wordNet.lookup("runs").map { it.lemma } shouldContainExactly listOf("run", "run")
        val run = wordNet.lookup("run").first().senses.first()
        run.definition shouldBe "move fast by using one's feet"
        run.examples shouldContainExactly listOf("Don't run", "run home")
        wordNet.lookup("frobnicate").shouldBeEmpty()
        WordNet(File(dir, "missing")).lookup("run").shouldBeEmpty()
    }

    "synonyms: the meanings' words, then similar adjectives" {
        val dir = tempDir()
        sampleWordNet(dir)
        val happy = WordNet(dir).lookup("happy")
        happy.single().senses.first().similar shouldContainExactly listOf("blissful")
        DictionarySkill.synonymsOf("happy", happy) shouldContainExactly listOf("blissful", "felicitous")
    }

    "the skill answers from the downloaded dictionary, and downloads it first" {
        val dir = tempDir()
        val source = tempDir()
        sampleWordNet(source)
        val zip = File(tempDir(), "oewn.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            for (name in DictionaryPack.FILES) {
                out.putNextEntry(ZipEntry("oewn2025/$name"))
                out.write(File(source, name).readBytes())
                out.closeEntry()
            }
            out.putNextEntry(ZipEntry("oewn2025/index.sense"))
            out.closeEntry()
        }
        var downloads = 0
        val pack = DictionaryPack(File(dir, "gar/dictionary/x")) { url, target, size, sha ->
            downloads++
            url shouldBe DictionaryPack.URL
            size shouldBe DictionaryPack.SIZE
            sha shouldBe DictionaryPack.SHA256
            zip.copyTo(target, overwrite = true)
        }
        val skill = DictionarySkill(DictionaryInfo) { pack }
        val ctx = FakeSkillContext()

        skill.score(ctx, "define serendipity").first shouldBe FloatScore(1.0f)
        skill.score(ctx, "how do you spell necessary").first shouldBe AlwaysWorstScore

        skill.generateOutput(ctx, DictionaryRequests.parse("define serendipity"))
            .shouldBeInstanceOf<DictionaryOutput.Definitions>().entries.single().lemma shouldBe "serendipity"
        pack.isInstalled shouldBe true
        File(pack.dir, "index.sense").exists() shouldBe false
        skill.generateOutput(ctx, DictionaryRequests.parse("synonym for happy")) shouldBe
            DictionaryOutput.Synonyms("happy", listOf("blissful", "felicitous"))
        skill.generateOutput(ctx, DictionaryRequests.parse("define frobnicate")) shouldBe
            DictionaryOutput.NotFound("frobnicate")
        downloads shouldBe 1
    }

    "a failed download says so, and tries again next time" {
        val pack = DictionaryPack(File(tempDir(), "x")) { _, _, _, _ -> throw IOException("offline") }
        DictionarySkill(DictionaryInfo) { pack }
            .generateOutput(FakeSkillContext(), DictionaryRequest("serendipity", false)) shouldBe
            DictionaryOutput.DownloadFailed
        pack.isInstalled shouldBe false
    }

    "a list is said with and" {
        DictionaryOutput.spokenList(listOf("a"), "and") shouldBe "a"
        DictionaryOutput.spokenList(listOf("a", "b", "c"), "and") shouldBe "a, b and c"
    }
})
