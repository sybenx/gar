package cc.aaro.gar.baselineprofile

import android.os.Build
import android.view.KeyEvent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The paths GAR's baseline profile covers (gar/DESIGN.md, "Frame rate"): a cold start to the
 * conversation, a typed command and its answer, scrolling; then the assistant sheet shown and
 * hidden through the voice interaction service. Emulator only (gartest): the sheet part makes GAR
 * the emulator's assistant first and gives the role back to the previous holder at the end.
 *
 *     ./gradlew :app:generateReleaseBaselineProfile
 */
@RunWith(AndroidJUnit4::class)
class GarBaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    /** The cold start to the conversation, also the startup profile (dex layout). */
    @Test
    fun startup() {
        rule.collect(packageName = targetPackage(), includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            device.wait(Until.hasObject(By.res(INTERACTION_LIST)), TIMEOUT_MILLIS)
        }
    }

    /**
     * A typed command and its answer, scrolling, then the sheet shown and hidden. The emulator's
     * assistant before the run (Google or the debug build) gets the role back afterwards.
     */
    @Test
    fun conversationAndSheet() {
        val packageName = targetPackage()
        val shell = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // read once: from the second pass on the holder would be the release build itself
        val previousAssistant = if (isEmulator()) {
            shell.executeShellCommand("cmd role get-role-holders --user 0 $ASSISTANT_ROLE")
                .lines().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        } else {
            null
        }
        try {
            rule.collect(packageName = packageName) {
                pressHome()
                startActivityAndWait()
                device.wait(Until.hasObject(By.res(INTERACTION_LIST)), TIMEOUT_MILLIS)
                device.findObject(By.clazz(EDIT_TEXT))?.let { field ->
                    field.click()
                    field.text = "what time is it"
                    device.pressEnter()
                    device.wait(Until.hasObject(By.textContains(":")), TIMEOUT_MILLIS)
                }
                device.findObject(By.res(INTERACTION_LIST))?.let { list ->
                    repeat(SCROLLS) {
                        list.scroll(Direction.UP, 0.8f)
                        list.scroll(Direction.DOWN, 0.8f)
                    }
                }
                device.waitForIdle()

                // the assistant sheet, shown and hidden by the voice interaction service
                if (isEmulator()) {
                    device.executeShellCommand(
                        "cmd role add-role-holder --user 0 $ASSISTANT_ROLE $packageName"
                    )
                    pressHome()
                    // the shell may not call `cmd voiceinteraction show` on API 35, the assist
                    // key may
                    device.pressKeyCode(KeyEvent.KEYCODE_ASSIST)
                    device.wait(Until.hasObject(By.pkg(packageName)), TIMEOUT_MILLIS)
                    device.waitForIdle()
                    device.pressBack()
                    device.waitForIdle()
                }
            }
        } finally {
            if (isEmulator() && previousAssistant != packageName) {
                // the role is exclusive: adding the previous holder replaces the release build
                shell.executeShellCommand(
                    if (previousAssistant != null) {
                        "cmd role add-role-holder --user 0 $ASSISTANT_ROLE $previousAssistant"
                    } else {
                        "cmd role remove-role-holder --user 0 $ASSISTANT_ROLE $packageName"
                    }
                )
            }
        }
    }

    /** The app the plugin installed (release has no id suffix). */
    private fun targetPackage(): String =
        InstrumentationRegistry.getArguments().getString("targetAppId") ?: DEFAULT_PACKAGE

    /** gartest; the profile is never generated on a phone. */
    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.PRODUCT.startsWith("sdk_") || Build.FINGERPRINT.contains("generic")

    private companion object {
        const val DEFAULT_PACKAGE = "cc.aaro.gar"
        const val INTERACTION_LIST = "interaction_list"
        const val EDIT_TEXT = "android.widget.EditText"
        const val TIMEOUT_MILLIS = 10_000L
        const val SCROLLS = 3
        const val ASSISTANT_ROLE = "android.app.role.ASSISTANT"
    }
}
