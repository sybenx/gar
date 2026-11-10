package org.stypox.dicio.gar.stt

import cc.aaro.gar.router.SpokenForm
import org.stypox.dicio.io.input.InputEvent
import java.util.Locale

/**
 * What [org.stypox.dicio.io.input.external_popup.ExternalPopupInputDevice] sends for another
 * app's recognizer results: those write text ("Set a timer for 5 minutes.") like Whisper does, so
 * in English each alternative goes through [SpokenForm], which GAR matches, and the first kept one's
 * written text rides along (design 4.7). Alternatives that leave no words are dropped; with none
 * left this is None. Other languages get the results as they are, as before. Either way the
 * input was spoken, so the answer is said (gar/DESIGN.md, "Speaking vs showing").
 */
fun garExternalFinal(results: List<String>, confidences: List<Float>, locale: Locale): InputEvent {
    if (locale.language != "en") {
        return InputEvent.Final(results.zip(confidences), spoken = true)
    }
    val kept = results.map(SpokenForm::of).zip(confidences)
        .filter { (text, _) -> text.spoken.isNotEmpty() }
    if (kept.isEmpty()) {
        return InputEvent.None
    }
    return InputEvent.Final(
        kept.map { (text, confidence) -> text.spoken to confidence },
        kept.first().first.written,
        spoken = true,
    )
}
