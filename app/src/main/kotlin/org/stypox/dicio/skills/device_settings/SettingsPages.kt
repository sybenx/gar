package org.stypox.dicio.skills.device_settings

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import cc.aaro.gar.router.Utterances
import org.stypox.dicio.R
import org.stypox.dicio.skills.connectivity.ActivityIntent
import org.stypox.dicio.skills.connectivity.ConnectivityLogic

private fun page(action: String) = ActivityIntent(action)

private val WIRELESS = page(Settings.ACTION_WIRELESS_SETTINGS)
private val DISPLAY = page(Settings.ACTION_DISPLAY_SETTINGS)
private val SOUND = page(Settings.ACTION_SOUND_SETTINGS)

/**
 * A page of Android's Settings that "open <page> settings" opens. Each tries its [intents] in
 * order, since some pages only exist on newer Android versions or only in AOSP's Settings
 * ("android.settings.TETHER_SETTINGS" and the others written out are in AOSP's Settings
 * manifest, but not in the SDK).
 *
 * @param names what people call the page (normalized, without "settings")
 * @param alone whether a name is enough without "settings": "open developer options"
 * @param label how GAR names the page: "Opening %s."
 */
enum class SettingsPage(
    val names: List<String>,
    val alone: Boolean,
    @StringRes val label: Int,
    val intents: List<ActivityIntent>,
) {
    DISPLAY_PAGE(
        listOf("display", "screen", "display and brightness", "brightness", "screen brightness"),
        false, R.string.gar_settings_page_display, listOf(DISPLAY),
    ),
    SOUND_PAGE(
        listOf(
            "sound", "sounds", "sound and vibration", "volume", "ringtone", "ringtones",
            "vibration", "ringer",
        ),
        false, R.string.gar_settings_page_sound, listOf(SOUND),
    ),
    BATTERY(
        listOf("battery", "power", "battery usage"),
        false, R.string.gar_settings_page_battery,
        listOf(page(Intent.ACTION_POWER_USAGE_SUMMARY), page(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
    ),
    BATTERY_SAVER(
        listOf(
            "battery saver", "power saver", "power saving", "power saving mode", "battery saving",
            "low power mode", "battery saver mode",
        ),
        true, R.string.gar_settings_page_battery_saver,
        listOf(page(Settings.ACTION_BATTERY_SAVER_SETTINGS)),
    ),
    WIFI(
        listOf("wifi", "wi fi", "wireless", "wifi networks", "wi fi networks"),
        false, R.string.gar_settings_page_wifi,
        listOf(ConnectivityLogic.INTERNET_PANEL, ConnectivityLogic.WIFI_SETTINGS),
    ),
    BLUETOOTH(
        listOf("bluetooth", "blue tooth", "bluetooth devices", "connected devices", "paired devices"),
        false, R.string.gar_settings_page_bluetooth, listOf(ConnectivityLogic.BLUETOOTH_SETTINGS),
    ),
    NETWORK(
        listOf("network", "networks", "internet", "network and internet", "connections"),
        false, R.string.gar_settings_page_network, listOf(WIRELESS),
    ),
    MOBILE_NETWORK(
        listOf(
            "mobile network", "mobile networks", "mobile data", "cellular", "cellular data",
            "cell data", "sim", "sims", "sim card", "sim cards", "carrier",
        ),
        false, R.string.gar_settings_page_mobile_network,
        listOf(
            page(Settings.ACTION_DATA_ROAMING_SETTINGS),
            page(Settings.ACTION_NETWORK_OPERATOR_SETTINGS),
            WIRELESS,
        ),
    ),
    DATA_USAGE(
        listOf("data usage", "data saver"),
        true, R.string.gar_settings_page_data_usage,
        listOf(page(Settings.ACTION_DATA_USAGE_SETTINGS), WIRELESS),
    ),
    AIRPLANE_MODE(
        listOf("airplane mode", "aeroplane mode", "flight mode", "plane mode", "airplane"),
        true, R.string.gar_settings_page_airplane_mode,
        listOf(page(Settings.ACTION_AIRPLANE_MODE_SETTINGS), WIRELESS),
    ),
    HOTSPOT(
        listOf(
            "hotspot", "hot spot", "wifi hotspot", "wi fi hotspot", "mobile hotspot",
            "personal hotspot", "portable hotspot", "tethering", "hotspot and tethering",
        ),
        true, R.string.gar_settings_page_hotspot,
        listOf(
            // the Wi-Fi hotspot page, then hotspot and tethering (no public API: AOSP's actions)
            page("com.android.settings.WIFI_TETHER_SETTINGS"),
            page("android.settings.TETHER_SETTINGS"),
            WIRELESS,
        ),
    ),
    // not alone: "show my location" asks where the phone is, not for its settings
    LOCATION(
        listOf("location", "location services", "gps"),
        false, R.string.gar_settings_page_location,
        listOf(page(Settings.ACTION_LOCATION_SOURCE_SETTINGS)),
    ),
    NFC(
        listOf("nfc", "n f c", "contactless", "contactless payments", "tap to pay", "nfc and payments"),
        true, R.string.gar_settings_page_nfc,
        listOf(page(Settings.ACTION_NFC_SETTINGS), WIRELESS),
    ),
    SECURITY(
        listOf("security", "lock screen", "screen lock", "fingerprint", "face unlock", "security and privacy"),
        false, R.string.gar_settings_page_security, listOf(page(Settings.ACTION_SECURITY_SETTINGS)),
    ),
    PRIVACY(
        listOf("privacy", "permissions", "app permissions", "permission manager"),
        false, R.string.gar_settings_page_privacy, listOf(page(Settings.ACTION_PRIVACY_SETTINGS)),
    ),
    NOTIFICATIONS(
        listOf("notification", "notifications", "app notifications"),
        false, R.string.gar_settings_page_notifications,
        listOf(
            page("android.settings.NOTIFICATION_SETTINGS"),
            page(Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS),
            page(Settings.ACTION_APPLICATION_SETTINGS),
        ),
    ),
    APPS(
        listOf("apps", "app", "applications", "application", "all apps", "installed apps"),
        false, R.string.gar_settings_page_apps,
        listOf(
            page(Settings.ACTION_APPLICATION_SETTINGS),
            page(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
        ),
    ),
    DEFAULT_APPS(
        listOf("default apps", "default app", "default applications"),
        true, R.string.gar_settings_page_default_apps,
        listOf(page(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)),
    ),
    STORAGE(
        listOf("storage", "phone storage"),
        false, R.string.gar_settings_page_storage,
        listOf(page(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)),
    ),
    ACCESSIBILITY(
        listOf("accessibility"),
        true, R.string.gar_settings_page_accessibility,
        listOf(page(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
    ),
    DATE_TIME(
        listOf("date", "time", "date and time", "time and date", "time zone", "timezone"),
        false, R.string.gar_settings_page_date_time, listOf(page(Settings.ACTION_DATE_SETTINGS)),
    ),
    LANGUAGE(
        listOf("language", "languages", "language and input", "languages and input", "region"),
        false, R.string.gar_settings_page_language, listOf(page(Settings.ACTION_LOCALE_SETTINGS)),
    ),
    KEYBOARD(
        listOf("keyboard", "keyboards", "on screen keyboard", "input", "input method"),
        false, R.string.gar_settings_page_keyboard,
        listOf(page(Settings.ACTION_INPUT_METHOD_SETTINGS)),
    ),
    ABOUT_PHONE(
        listOf(
            "about phone", "about", "about this phone", "about my phone", "about device",
            "phone info", "device info",
        ),
        true, R.string.gar_settings_page_about_phone,
        listOf(page(Settings.ACTION_DEVICE_INFO_SETTINGS)),
    ),
    DEVELOPER_OPTIONS(
        listOf("developer options", "developer", "developer settings", "dev options"),
        true, R.string.gar_settings_page_developer_options,
        listOf(page(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)),
    ),
    ACCOUNTS(
        listOf("account", "accounts", "sync", "passwords and accounts"),
        false, R.string.gar_settings_page_accounts, listOf(page(Settings.ACTION_SYNC_SETTINGS)),
    ),
    DARK_THEME(
        listOf("dark mode", "dark theme", "night mode", "light mode", "light theme", "theme"),
        true, R.string.gar_settings_page_dark_theme,
        listOf(page("android.settings.DARK_THEME_SETTINGS"), DISPLAY),
    ),
    NIGHT_LIGHT(
        listOf("night light", "blue light filter", "eye comfort"),
        true, R.string.gar_settings_page_night_light,
        listOf(page(Settings.ACTION_NIGHT_DISPLAY_SETTINGS), DISPLAY),
    ),
    DO_NOT_DISTURB(
        listOf("do not disturb", "dnd", "d n d", "priority mode", "modes"),
        true, R.string.gar_settings_page_do_not_disturb,
        listOf(page(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS), SOUND),
    ),
    CAST(
        listOf("cast", "casting", "screen cast", "screen casting"),
        false, R.string.gar_settings_page_cast, listOf(page(Settings.ACTION_CAST_SETTINGS)),
    ),
    VPN(
        listOf("vpn", "v p n"),
        false, R.string.gar_settings_page_vpn, listOf(page(Settings.ACTION_VPN_SETTINGS)),
    ),
    ASSISTANT(
        listOf(
            "assistant", "digital assistant", "default assistant", "assistant app",
            "digital assistant app", "voice input",
        ),
        false, R.string.gar_settings_page_assistant,
        listOf(
            page(Settings.ACTION_VOICE_INPUT_SETTINGS),
            page(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        ),
    ),
    HOME_APP(
        listOf("home app", "home screen", "launcher", "default launcher"),
        false, R.string.gar_settings_page_home_app, listOf(page(Settings.ACTION_HOME_SETTINGS)),
    ),
    USERS(
        listOf("users", "user", "multiple users"),
        false, R.string.gar_settings_page_users, listOf(page("android.settings.USER_SETTINGS")),
    ),
    PRINTING(
        listOf("print", "printing", "printers"),
        false, R.string.gar_settings_page_printing, listOf(page(Settings.ACTION_PRINT_SETTINGS)),
    );

    /** How GAR's app resolver names the page: "battery settings", "developer options". */
    val appName: String get() = if (alone) names.first() else "${names.first()} settings"

    companion object {
        /** Words before a page's name that aren't part of it: "my phone's storage". */
        private val LEADING_FILLERS = setOf(
            "the", "my", "your", "phone", "phone's", "phones", "device", "device's", "system",
        )

        /** Words after a page's name: "the battery settings page". */
        private val TRAILING_WORDS = setOf(
            "settings", "setting", "page", "menu", "options", "preferences", "screen",
        )

        private val BY_NAME: Map<String, SettingsPage> =
            entries.flatMap { page -> page.names.map { it to page } }.toMap()

        /** The page called [said] ("battery", "my phone's storage", "about phone"), or null. */
        fun find(said: String): SettingsPage? {
            val words = Utterances.words(Utterances.normalize(said))
            // "phone" stays when it is the page's name: "about phone"
            val withoutLeading = words.dropWhile { it in LEADING_FILLERS }
            for (candidate in listOf(words, withoutLeading).distinct()) {
                // "screen settings": "settings" goes, "screen" is the name
                var name = candidate
                while (name.isNotEmpty()) {
                    BY_NAME[name.joinToString(" ")]?.let { return it }
                    if (name.last() !in TRAILING_WORDS) {
                        break
                    }
                    name = name.dropLast(1)
                }
            }
            return null
        }

        private val SETTINGS_WORDS = setOf("settings", "setting", "options", "preferences")

        /**
         * The app name of the page that [said], an app as the model's `open_app` call wrote it,
         * names: "the battery settings" -> "battery settings", "developer options", or null for
         * anything else. A page's name needs "settings" after it unless it works alone, so
         * "open storage" stays an app's name. See [SettingsPageCalls].
         */
        fun appNameFor(said: String): String? {
            val page = find(said) ?: return null
            val words = Utterances.words(Utterances.normalize(said))
            return page.appName.takeIf { page.alone || words.any { it in SETTINGS_WORDS } }
        }

        /** The page [appNameFor] named [appName]. */
        fun fromAppName(appName: String): SettingsPage? = entries.firstOrNull { it.appName == appName }

        /** The pages a setting opens when GAR can't switch it, or when asked to show it. */
        fun forSetting(setting: DeviceSetting): List<ActivityIntent> = when (setting) {
            DeviceSetting.WIFI -> WIFI.intents
            DeviceSetting.BLUETOOTH -> BLUETOOTH.intents
            DeviceSetting.AIRPLANE_MODE -> AIRPLANE_MODE.intents
            // the internet panel has the mobile data switch
            DeviceSetting.MOBILE_DATA -> listOf(ConnectivityLogic.INTERNET_PANEL) + MOBILE_NETWORK.intents
            DeviceSetting.HOTSPOT -> HOTSPOT.intents
            // the NFC panel has just the switch
            DeviceSetting.NFC -> listOf(page(Settings.Panel.ACTION_NFC)) + NFC.intents
            DeviceSetting.LOCATION -> LOCATION.intents
            DeviceSetting.BATTERY_SAVER -> BATTERY_SAVER.intents
            DeviceSetting.DARK_MODE -> DARK_THEME.intents
            DeviceSetting.BRIGHTNESS, DeviceSetting.AUTO_BRIGHTNESS, DeviceSetting.SCREEN_TIMEOUT ->
                DISPLAY_PAGE.intents
            DeviceSetting.AUTO_ROTATE -> listOf(page(Settings.ACTION_AUTO_ROTATE_SETTINGS), DISPLAY)
            DeviceSetting.RINGER, DeviceSetting.VIBRATE -> SOUND_PAGE.intents
        }

        /** The page GAR names for a setting it opens: "Opening display settings." */
        fun pageOf(setting: DeviceSetting): SettingsPage = when (setting) {
            DeviceSetting.WIFI -> WIFI
            DeviceSetting.BLUETOOTH -> BLUETOOTH
            DeviceSetting.AIRPLANE_MODE -> AIRPLANE_MODE
            DeviceSetting.MOBILE_DATA -> MOBILE_NETWORK
            DeviceSetting.HOTSPOT -> HOTSPOT
            DeviceSetting.NFC -> NFC
            DeviceSetting.LOCATION -> LOCATION
            DeviceSetting.BATTERY_SAVER -> BATTERY_SAVER
            DeviceSetting.DARK_MODE -> DARK_THEME
            DeviceSetting.BRIGHTNESS, DeviceSetting.AUTO_BRIGHTNESS, DeviceSetting.SCREEN_TIMEOUT,
            DeviceSetting.AUTO_ROTATE -> DISPLAY_PAGE
            DeviceSetting.RINGER, DeviceSetting.VIBRATE -> SOUND_PAGE
        }
    }
}
