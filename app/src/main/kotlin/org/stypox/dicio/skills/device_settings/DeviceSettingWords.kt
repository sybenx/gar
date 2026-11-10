package org.stypox.dicio.skills.device_settings

import cc.aaro.gar.router.Utterances
import cc.aaro.gar.router.resolve.ResolvedNumber
import cc.aaro.gar.router.resolve.TypedSlotRewrites
import org.stypox.dicio.sentences.Sentences.DeviceSettings

/**
 * Which inputs the device settings sentences take, and what they say (English). Dicio's matcher
 * takes a sentence with a word left out or swapped, so a match only counts when its words name
 * the setting: "turn on the lights" must not open the hotspot. The generic sentences (on, off,
 * status) leave the setting to [settingIn].
 */
object DeviceSettingWords {
    /**
     * What people call each setting, the more specific names first: "wifi hotspot" is the
     * hotspot, "auto brightness" isn't the brightness.
     */
    private val NAMES: List<Pair<String, DeviceSetting>> = listOf(
        "auto brightness" to DeviceSetting.AUTO_BRIGHTNESS,
        "automatic brightness" to DeviceSetting.AUTO_BRIGHTNESS,
        "adaptive brightness" to DeviceSetting.AUTO_BRIGHTNESS,
        "screen timeout" to DeviceSetting.SCREEN_TIMEOUT,
        "screen time out" to DeviceSetting.SCREEN_TIMEOUT,
        "screen turns off" to DeviceSetting.SCREEN_TIMEOUT,
        "screen turn off" to DeviceSetting.SCREEN_TIMEOUT,
        "screen goes off" to DeviceSetting.SCREEN_TIMEOUT,
        "timeout" to DeviceSetting.SCREEN_TIMEOUT,
        "time out" to DeviceSetting.SCREEN_TIMEOUT,
        "auto rotate" to DeviceSetting.AUTO_ROTATE,
        "autorotate" to DeviceSetting.AUTO_ROTATE,
        "auto rotation" to DeviceSetting.AUTO_ROTATE,
        "screen rotation" to DeviceSetting.AUTO_ROTATE,
        "rotation" to DeviceSetting.AUTO_ROTATE,
        "orientation" to DeviceSetting.AUTO_ROTATE,
        "hotspot" to DeviceSetting.HOTSPOT,
        "hot spot" to DeviceSetting.HOTSPOT,
        "tethering" to DeviceSetting.HOTSPOT,
        "airplane mode" to DeviceSetting.AIRPLANE_MODE,
        "aeroplane mode" to DeviceSetting.AIRPLANE_MODE,
        "flight mode" to DeviceSetting.AIRPLANE_MODE,
        "plane mode" to DeviceSetting.AIRPLANE_MODE,
        "airplane" to DeviceSetting.AIRPLANE_MODE,
        "aeroplane" to DeviceSetting.AIRPLANE_MODE,
        "battery saver" to DeviceSetting.BATTERY_SAVER,
        "battery saving" to DeviceSetting.BATTERY_SAVER,
        "power saver" to DeviceSetting.BATTERY_SAVER,
        "power saving" to DeviceSetting.BATTERY_SAVER,
        "low power mode" to DeviceSetting.BATTERY_SAVER,
        "mobile data" to DeviceSetting.MOBILE_DATA,
        "cellular data" to DeviceSetting.MOBILE_DATA,
        "cell data" to DeviceSetting.MOBILE_DATA,
        "data" to DeviceSetting.MOBILE_DATA,
        "dark mode" to DeviceSetting.DARK_MODE,
        "dark theme" to DeviceSetting.DARK_MODE,
        "night mode" to DeviceSetting.DARK_MODE,
        "light mode" to DeviceSetting.DARK_MODE,
        "light theme" to DeviceSetting.DARK_MODE,
        "nfc" to DeviceSetting.NFC,
        "n f c" to DeviceSetting.NFC,
        "location" to DeviceSetting.LOCATION,
        "gps" to DeviceSetting.LOCATION,
        "brightness" to DeviceSetting.BRIGHTNESS,
        "bright" to DeviceSetting.BRIGHTNESS,
        "silent" to DeviceSetting.RINGER,
        "ringer" to DeviceSetting.RINGER,
        "ring mode" to DeviceSetting.RINGER,
        "vibrate" to DeviceSetting.VIBRATE,
        "vibration" to DeviceSetting.VIBRATE,
        "mute" to DeviceSetting.RINGER,
        "muted" to DeviceSetting.RINGER,
        "silenced" to DeviceSetting.RINGER,
        "wifi" to DeviceSetting.WIFI,
        "wi fi" to DeviceSetting.WIFI,
        "wireless" to DeviceSetting.WIFI,
        "internet" to DeviceSetting.WIFI,
        "bluetooth" to DeviceSetting.BLUETOOTH,
        "blue tooth" to DeviceSetting.BLUETOOTH,
    )

    /**
     * What the generic on and off sentences switch: not the ringer, which has sentences of its
     * own ("turn on silent mode" is the ringer off), nor Wi-Fi and Bluetooth, which the
     * connectivity sentences turn on and off.
     */
    private val SWITCHES = setOf(
        DeviceSetting.AIRPLANE_MODE, DeviceSetting.MOBILE_DATA, DeviceSetting.HOTSPOT,
        DeviceSetting.NFC, DeviceSetting.LOCATION, DeviceSetting.BATTERY_SAVER,
        DeviceSetting.DARK_MODE, DeviceSetting.AUTO_BRIGHTNESS, DeviceSetting.AUTO_ROTATE,
    )

    /**
     * Words that name another thing next to a setting: "turn on location sharing", "turn on wifi
     * calling", "is the data on my plan", "what's the brightness of the sun".
     */
    private val OTHER_THINGS = setOf(
        "sharing", "share", "history", "calling", "call", "calls", "charging", "debugging",
        "password", "plan", "roaming", "limit", "warning", "app", "apps", "sun", "moon", "star",
        "stars", "bulb", "lamp", "lights", "light", "tv", "television", "car", "computer",
        "laptop", "watch", "tablet", "reminder", "alarm", "timer", "flashlight", "torch", "filter",
        "sensor", "notification", "notifications", "music", "song", "video", "media",
        "microphone", "mic",
        // the keyboard's vibration isn't the ringer's vibrate mode ("turn on keyboard vibration")
        "keyboard", "keyboards", "haptic", "haptics", "typing", "feedback",
    )

    /** "light mode", "light theme": dark mode the other way round. */
    private val LIGHT_THEME = listOf("light mode", "light theme")

    private fun words(input: String) = Utterances.words(Utterances.normalize(input))

    private fun contains(words: List<String>, phrase: String): Boolean {
        val phraseWords = phrase.split(' ')
        return words.windowed(phraseWords.size).any { it == phraseWords }
    }

    /**
     * Other settings whose names contain a setting's: data saver isn't mobile data, nor the night
     * light dark mode. Their pages open with "open data saver settings" ([SettingsPage]).
     */
    private val NOT_SETTINGS = listOf(
        "data saver", "data usage", "screen saver", "screen time", "night light", "eye comfort",
    )

    /** The setting [input] names, or null; the more specific of two names wins. */
    fun settingIn(input: String): DeviceSetting? {
        val words = words(input)
        if (NOT_SETTINGS.any { contains(words, it) }) {
            return null
        }
        return NAMES.firstOrNull { (name, _) -> contains(words, name) }?.second
    }

    /** Whether [input] asks for the light theme, which is dark mode switched the other way. */
    fun isLightTheme(input: String): Boolean = LIGHT_THEME.any { contains(words(input), it) }

    private fun namesOtherThing(words: List<String>, setting: DeviceSetting): Boolean {
        // "light" is part of the light theme's name
        val other = if (setting == DeviceSetting.DARK_MODE) OTHER_THINGS - "light" else OTHER_THINGS
        return words.any { it in other }
    }

    /** The brightness sentences need one of these, outside a level ("brightness to fifty"). */
    private val BRIGHTNESS_WORDS = setOf(
        "brightness", "bright", "brighter", "brighten", "dim", "dimmer", "darker", "dark",
    )

    /** The ringer sentences need one of these. */
    private val RINGER_WORDS = setOf(
        "silent", "silence", "mute", "unmute", "unsilence", "vibrate", "vibration", "ringer",
        "ring", "ringtone",
    )

    /**
     * The screen timeout sentences need one of these: "time" alone is no screen timeout ("set the
     * time or for forty seconds" is a timer STT misheard).
     */
    private val TIMEOUT_WORDS = listOf("screen", "display", "timeout", "time out")

    /**
     * What a sentence match means, or null when the skill doesn't take it: its words don't name
     * the setting, they name another thing, or it is Wi-Fi or Bluetooth on, off or their
     * settings, which the connectivity sentences take. A command's sentence also matches a
     * question about it, with the question's words left out ([openingOf]): "did i turn on
     * airplane mode" asks for the setting's status, "should i turn on battery saver" switches
     * nothing.
     */
    fun inputOf(sentence: DeviceSettings, input: String): DeviceSettingsInput? {
        val words = words(input)
        val opening = openingOf(input)
        return when (sentence) {
            DeviceSettings.TurnOn, DeviceSettings.TurnOff -> {
                // Android's night light only switches on its page, and it isn't dark mode
                if (NIGHT_LIGHT.any { contains(words, it) }) {
                    return OpenSettingsPage(SettingsPage.NIGHT_LIGHT, switch = true)
                        .takeIf { opening == Opening.COMMAND }
                }
                val setting = settingIn(input)?.takeIf { it in SWITCHES } ?: return null
                if (namesOtherThing(words, setting)) {
                    return null
                }
                val on = (sentence == DeviceSettings.TurnOn) != isLightTheme(input)
                val action = if (on) DeviceAction.ON else DeviceAction.OFF
                asked(opening, SaidDeviceSetting(setting, action, null))
            }
            DeviceSettings.Status -> {
                // "ringer off" matched "is ... ringer off" with the question's word left out
                if (words.none { it in QUESTION_WORDS }) {
                    return null
                }
                // "are my earbuds connected": Bluetooth's status names the headphones it plays on
                val setting = settingIn(input) ?: return HeadphonesStatus.takeIf {
                    HEADPHONES.any { contains(words, it) } &&
                        !namesOtherThing(words, DeviceSetting.BLUETOOTH)
                }
                if (namesOtherThing(words, setting)) {
                    return null
                }
                SaidDeviceSetting(setting, DeviceAction.STATUS, null)
            }
            is DeviceSettings.OpenPage -> {
                val page = sentence.page?.let { SettingsPage.find(it) } ?: return null
                // "open wifi settings" is the connectivity sentences'; a name alone needs to be
                // one that works alone ("open developer options", not "open storage")
                val settingsWord = words.any { it in SETTINGS_WORDS }
                if (page == SettingsPage.WIFI || page == SettingsPage.BLUETOOTH ||
                    !(settingsWord || page.alone)
                ) {
                    return null
                }
                OpenSettingsPage(page)
            }
            DeviceSettings.BrightnessUp -> asked(opening, brightness(
                words, sentence, DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.UP),
            ))
            DeviceSettings.BrightnessDown -> asked(opening, brightness(
                words, sentence, DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.DOWN),
            ))
            DeviceSettings.BrightnessMax -> asked(opening, brightness(
                words, sentence,
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Max),
            ))
            DeviceSettings.BrightnessMin -> asked(opening, brightness(
                words, sentence,
                DeviceSettingCommand(DeviceSetting.BRIGHTNESS, DeviceAction.SET, DeviceLevel.Min),
            ))
            is DeviceSettings.BrightnessSet -> asked(opening, brightness(
                words, sentence,
                // "lower the brightness a bit" is down, not a level
                if (isAutomatic(sentence.level)) {
                    SaidDeviceSetting(DeviceSetting.AUTO_BRIGHTNESS, DeviceAction.ON, null)
                } else if (isVagueAmount(sentence.level)) {
                    DeviceSettingCommand(
                        DeviceSetting.BRIGHTNESS,
                        if (words.any { it in DOWN_WORDS }) DeviceAction.DOWN else DeviceAction.UP,
                    )
                } else {
                    SaidDeviceSetting(
                        DeviceSetting.BRIGHTNESS, DeviceAction.SET, sentence.level?.trim(),
                    )
                },
            ))
            DeviceSettings.RingerSilent -> asked(
                opening, ringer(words, DeviceSettingCommand(DeviceSetting.RINGER, DeviceAction.OFF)),
            )
            DeviceSettings.RingerVibrate -> asked(
                opening, ringer(words, DeviceSettingCommand(DeviceSetting.VIBRATE, DeviceAction.ON)),
            )
            DeviceSettings.RingerNormal -> asked(
                opening, ringer(words, DeviceSettingCommand(DeviceSetting.RINGER, DeviceAction.ON)),
            )
            is DeviceSettings.TimeoutSet -> {
                val duration = sentence.duration?.trim()
                val frame = withoutCapture(words, duration)
                if (TIMEOUT_WORDS.none { contains(frame, it) } ||
                    namesOtherThing(frame, DeviceSetting.SCREEN_TIMEOUT)
                ) {
                    return null
                }
                asked(
                    opening, SaidDeviceSetting(DeviceSetting.SCREEN_TIMEOUT, DeviceAction.SET, duration),
                )
            }
        }
    }

    /** How an input opens: as a command, or as a question ([openingOf]). */
    enum class Opening {
        /** "Turn on airplane mode", "can you turn on airplane mode", "please mute my phone". */
        COMMAND,

        /** A question about how a setting is: "did i turn on airplane mode", "is my phone muted". */
        STATE,

        /**
         * Another question, or a request for advice: "should i turn on battery saver", "how do i
         * turn on the hotspot", "can i turn off location", "what does airplane mode do".
         */
        QUESTION,
    }

    /**
     * How [input] opens, after fillers ("ok", "hey", "please"): "can you", "could you", "would
     * you" and "will you" are polite commands, did, is, are, was and were ask how a setting is,
     * and the other question words, "can i" or "should i" ask something else.
     */
    fun openingOf(input: String): Opening {
        val words = words(input).dropWhile { it in OPENING_FILLERS }
        val first = words.firstOrNull() ?: return Opening.COMMAND
        return when {
            first in POLITE_ASKS && words.getOrNull(1) in setOf("you", "u") -> Opening.COMMAND
            first in STATE_OPENERS -> Opening.STATE
            first in QUESTION_OPENERS || first in POLITE_ASKS -> Opening.QUESTION
            else -> Opening.COMMAND
        }
    }

    private val OPENING_FILLERS = setOf(
        "ok", "okay", "hey", "please", "so", "um", "uh", "oh", "and", "just", "now", "alright",
    )
    private val POLITE_ASKS = setOf("can", "could", "would", "will")
    private val STATE_OPENERS = setOf(
        "did", "didn't", "didnt", "is", "isn't", "isnt", "are", "aren't", "arent", "was", "were",
        "am",
    )
    private val QUESTION_OPENERS = setOf(
        "do", "does", "should", "shall", "may", "might", "how", "why", "what", "what's", "whats",
        "when", "where", "which", "who",
    )

    /**
     * [command] as [opening] asks for it: a command as it is, a question about how its setting
     * is as that setting's status, and null for any other question (it switches nothing).
     */
    private fun asked(opening: Opening, command: DeviceSettingsInput?): DeviceSettingsInput? {
        val setting = when (command) {
            is DeviceSettingCommand -> command.setting
            is SaidDeviceSetting -> command.setting
            else -> return command
        }
        return when (opening) {
            Opening.COMMAND -> command
            Opening.STATE -> SaidDeviceSetting(setting, DeviceAction.STATUS, null)
            Opening.QUESTION -> null
        }
    }

    /** Names of Android's night light, which only switches on its page. */
    private val NIGHT_LIGHT = listOf("night light", "blue light", "eye comfort")

    /** "Are my earbuds connected": the Bluetooth audio devices. */
    private val HEADPHONES = listOf(
        "headphones", "headphone", "headset", "headsets", "earbuds", "earbud", "ear buds",
        "earphones", "earphone", "ear phones", "buds", "airpods", "airpod",
    )

    /**
     * The setting [input] names for a check of the model's calls: [settingIn], or Bluetooth for
     * headphones ("pair my headphones").
     */
    fun namedSetting(input: String): DeviceSetting? {
        settingIn(input)?.let { return it }
        val words = words(input)
        return DeviceSetting.BLUETOOTH.takeIf { HEADPHONES.any { contains(words, it) } }
    }

    /** Whether [input] names another thing next to [setting] ("turn on location sharing"). */
    fun namesOtherThing(input: String, setting: DeviceSetting): Boolean =
        namesOtherThing(words(input), setting)

    /**
     * Whether [input] says a brightness word ("brighter", "dim"); [namesOtherThing] tells whether
     * it is another thing's ("how bright is the sun").
     */
    fun saysBrightness(input: String): Boolean = words(input).any { it in BRIGHTNESS_WORDS }

    /** Whether [input] asks to lower something ("lower", "dim", "darker"). */
    fun saysDown(input: String): Boolean = words(input).any { it in DOWN_WORDS }

    /** Whether [input] asks to raise something ("up", "brighter", "increase"). */
    fun saysUp(input: String): Boolean = words(input).any { it in UP_WORDS }

    /** Whether [input] asks to turn something off ("off", "disable"). */
    fun saysOff(input: String): Boolean = words(input).any { it in OFF_WORDS }

    private val UP_WORDS = setOf(
        "up", "raise", "increase", "brighter", "brighten", "boost", "more", "higher", "crank",
    )
    private val OFF_WORDS = setOf("off", "disable", "deactivate", "stop")

    private val SETTINGS_WORDS = setOf("settings", "setting", "options", "preferences")

    /** The status sentences ask: every one of them starts with one of these. */
    private val QUESTION_WORDS = setOf(
        "is", "are", "am", "do", "does", "what", "what's", "whats", "how", "when",
    )

    /** "a bit", "a little": one step up or down rather than a level to ask about. */
    private val VAGUE_AMOUNTS = setOf(
        "a bit", "a little", "a little bit", "a tad", "slightly", "some", "a notch", "a touch",
    )

    private val DOWN_WORDS = setOf("lower", "decrease", "reduce", "dim", "down", "darker", "dimmer")

    /** "make the brightness automatic": adaptive brightness. */
    private fun isAutomatic(level: String?): Boolean =
        level != null && words(level).any { it in setOf("auto", "automatic", "adaptive") }

    private fun isVagueAmount(level: String?): Boolean =
        level != null && words(level).filter { it != "please" }.joinToString(" ") in VAGUE_AMOUNTS

    private fun brightness(
        words: List<String>,
        sentence: DeviceSettings,
        input: DeviceSettingsInput,
    ): DeviceSettingsInput? {
        val frame = withoutCapture(words, (sentence as? DeviceSettings.BrightnessSet)?.level)
        return input.takeIf {
            frame.any { it in BRIGHTNESS_WORDS } &&
                !namesOtherThing(frame, DeviceSetting.BRIGHTNESS)
        }
    }

    private fun ringer(words: List<String>, input: DeviceSettingsInput): DeviceSettingsInput? =
        input.takeIf {
            words.any { it in RINGER_WORDS } && !namesOtherThing(words, DeviceSetting.RINGER)
        }

    /** [words] without the first occurrence of [capture]'s words. */
    private fun withoutCapture(words: List<String>, capture: String?): List<String> {
        val captureWords = Utterances.words(Utterances.normalize(capture ?: return words))
        if (captureWords.isEmpty()) {
            return words
        }
        val start = (0..words.size - captureWords.size).firstOrNull { start ->
            captureWords.indices.all { words[start + it] == captureWords[it] }
        } ?: return words
        return words.subList(0, start) + words.subList(start + captureWords.size, words.size)
    }

    private val MIN_WORDS = setOf(
        "min", "minimum", "the min", "the minimum", "lowest", "the lowest", "bottom", "the bottom",
        "zero",
    )

    /** Words around a level that aren't part of it: "brightness fifty percent", "to max". */
    private val LEVEL_FILLERS = setOf(
        "to", "at", "the", "my", "brightness", "screen", "display", "level", "volume", "timeout",
        "time", "out", "of", "for", "after", "on", "please",
    )

    /** Words after a level: "thirty percent please". */
    private val TRAILING_FILLERS = setOf("please", "now", "thanks", "thank", "you")

    /**
     * A level as said without the words around it ("to fifty percent please" -> "fifty
     * percent"), or null when nothing is left. "minutes" and the like stay: they are part of a
     * duration.
     */
    fun cleanLevel(raw: String): String? {
        val words = words(raw).dropLastWhile { it in TRAILING_FILLERS }
        val start = words.indexOfFirst { it !in LEVEL_FILLERS }
        if (start == -1) {
            return null
        }
        return words.subList(start, words.size).joinToString(" ")
    }

    /** "max", "half", "the lowest": a level the resolvers can't read, or null for any other. */
    fun levelWord(level: String): DeviceLevel? = when {
        TypedSlotRewrites.isMaxLevel(level) -> DeviceLevel.Max
        words(level).joinToString(" ") in MIN_WORDS -> DeviceLevel.Min
        TypedSlotRewrites.isHalfLevel(level) ->
            DeviceLevel.Amount(ResolvedNumber(50.0, percent = true, isInteger = true))
        else -> null
    }
}
