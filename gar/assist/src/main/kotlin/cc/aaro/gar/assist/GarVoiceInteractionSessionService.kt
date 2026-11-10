package cc.aaro.gar.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Creates the sessions of [GarVoiceInteractionService], see [GarVoiceInteractionSession]. */
class GarVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return GarVoiceInteractionSession(this)
    }
}
