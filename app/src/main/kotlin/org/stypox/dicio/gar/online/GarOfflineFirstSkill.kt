package org.stypox.dicio.gar.online

import org.dicio.skill.context.SkillContext
import org.dicio.skill.skill.SkillOutput

/**
 * An online skill that can sometimes answer without the internet, through an app already on the
 * phone (gar:weather: Breezy Weather's shared forecast). Its gate ([GarOnlineGateSkill]) asks for
 * that answer first and only asks to turn the skill on when there is none, since nothing would be
 * sent anywhere (gar/DESIGN.md, "Online skills").
 */
interface GarOfflineFirstSkill {
    /** The answer to [inputData] (from the skill's own score) without the network, or null. */
    suspend fun offlineOutput(ctx: SkillContext, inputData: Any?): SkillOutput?
}
