package org.stypox.dicio.gar.stt

/**
 * Who takes a turn over when the system keeps the mic silenced (see gar/DESIGN.md, "Assistant
 * sheet"): the sheet sets [handler] while it is shown. The audio policy lets only the top app's
 * recognizer through while another app records, and the sheet's session window doesn't make GAR
 * the top app; an activity of GAR's does.
 */
object GarMicSilenced {
    /** Returns true when it took the turn over; called on the capture thread. */
    @Volatile
    var handler: (() -> Boolean)? = null

    fun handOff(): Boolean = handler?.invoke() == true
}
