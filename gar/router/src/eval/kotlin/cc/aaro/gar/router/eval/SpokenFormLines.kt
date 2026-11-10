package cc.aaro.gar.router.eval

import cc.aaro.gar.router.SpokenForm
import java.io.File
import kotlin.system.exitProcess

/**
 * Puts each line of `--in` through [SpokenForm] and writes the spoken forms to `--out`, one line
 * for each line (an empty one where nothing is left). gar/eval/stt/make-whisper-cases.sh runs it on
 * Whisper's transcripts through `./gradlew :gar:router:spokenForm`.
 */
fun main(args: Array<String>) {
    val options = args.toList().chunked(2).associate { it[0].removePrefix("--") to it.getOrNull(1) }
    val input = options["in"]
    val output = options["out"]
    if (input == null || output == null) {
        System.err.println("usage: --in <lines> --out <spoken lines>")
        exitProcess(2)
    }
    val lines = File(input).readLines()
    File(output).writeText(lines.joinToString("") { SpokenForm.of(it).spoken + "\n" })
}
