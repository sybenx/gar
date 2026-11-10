# :gar:assist

GAR's `VoiceInteractionService` (VIS). With it, GAR can be the device's digital assistant with
the privileges that come with the active VIS. Without it, the system only starts Dicio's
`ACTION_ASSIST` activity. Background and sources: `~/src/gar-notes/recon/zz-critic-gaps.md`, first gap.

## Make GAR the digital assistant

On the phone: Settings > Apps > Default apps > Digital assistant app > Default digital assistant
app > GAR.

Android 9 has no assistant role: Settings > Apps & notifications > Default apps > Assist & voice
input > Assist app > GAR. The `cmd role` commands below need Android 10 or newer.

Apps can't ask for this role (it is not requestable), so the home screen's "Make GAR your
assistant" card (`GarAssistantCard`, `gar/assist/` in `:app`) opens Assist & voice input instead,
falling back to the default apps list or Settings. GAR never changes the role itself. Over adb,
for development:

```sh
adb shell cmd role add-role-holder android.app.role.ASSISTANT cc.aaro.gar
```

Debug builds have a branch suffix (`cc.aaro.gar.<branch>`); `adb shell pm list packages cc.aaro.gar`
shows the installed id. To check that the system took the VIS and not only the activity:

```sh
adb shell settings get secure voice_interaction_service
# cc.aaro.gar/cc.aaro.gar.assist.GarVoiceInteractionService
adb shell dumpsys voiceinteraction
```

An empty value means the metadata was rejected and assist does nothing at all. On low-RAM devices
the picker skips VIS apps, and GAR falls back to the `ACTION_ASSIST` activity.

To undo: pick another app in the same setting, or
`adb shell cmd role remove-role-holder android.app.role.ASSISTANT cc.aaro.gar`.

## What it does

- `GarVoiceInteractionService`: kept bound by the system while GAR is the assistant, which is where
  the privileges come from: background activity starts, `startVoiceActivity`, shortcut host,
  while-in-use mic and audio control. It does no work itself. It turns off assist data and
  screenshots (`setDisabledShowContext`), since GAR never reads the screen.
- `GarVoiceInteractionSession` shows the app's assistant sheet in its own window: the
  `GarSessionUi` named by `GarAssist.SHEET_UI_CLASS` (`org.stypox.dicio.gar.sheet.GarSheetUi`,
  loaded once by name so this module stays free of Compose and Hilt; `@Keep` in the app). The
  session gives it a `GarSessionHost` (its context, its Dialog window, the source, whether the
  phone was locked at show, `dismiss()`, `openApp(intent)`), forwards `onShow`/`onHide`, and sends
  back to it when the sheet's own back callback didn't take it. Any hide tears the sheet down and
  finishes the session. With "Open the full app for the assistant" on (or if the sheet can't be
  loaded) it never shows a window (`setUiEnabled(false)`): it starts `org.stypox.dicio.MainActivity`
  with `ACTION_ASSIST` and finishes, like Home Assistant's companion app. It uses a plain
  `startActivity` instead of `startAssistantActivity`: the latter would put MainActivity in a
  separate "assistant" task, so a second MainActivity could run next to the launcher's one.
- `GarVoiceInteractionService.onReady()` lets the sheet warm up on a lowest-priority thread
  (`GarSessionUi.prewarm`: cached settings and the locale only, no skills, STT, TTS or model).
- `res/xml/gar_voice_interaction_service.xml`: `recognitionService` is Dicio's `SttService`,
  `settingsActivity` is MainActivity, `supportsAssist`; `supportsLaunchVoiceAssistFromKeyguard` is
  false (see below).

## API for the app (`GarAssist`)

- `isActiveAssistant(context)`: GAR's VIS is the active one. The privileges depend on this, not on
  the role alone.
- `isServiceReady`: the VIS is bound in this process, so sessions can be shown.
- `showAssistSession(source)`: same as the assist gesture; `source` (`SOURCE_ASSIST`,
  `SOURCE_HOTWORD`, `SOURCE_TILE`) goes to the sheet as `EXTRA_SOURCE`. The wake word uses it
  while the screen is on (`GarSheet.showForHotword` in the app).
- `unlockIntent(context, targets)`: the app's `GarUnlockActivity`, which shows over the lock
  screen, asks to unlock and then starts `targets`. `GarSessionHost.openApp` uses it while locked.
- `startVoiceActivity(intent) { result -> }`: runs a voice activity from a session without UI and
  reports `Completed(prompt)`, `Aborted(prompt)` or `Failed(reason)` on the main thread. For Step 3,
  turning off Do Not Disturb through Settings:

  ```kotlin
  GarAssist.startVoiceActivity(
      Intent(Settings.ACTION_VOICE_CONTROL_DO_NOT_DISTURB_MODE)
          .putExtra(Settings.EXTRA_DO_NOT_DISTURB_MODE_ENABLED, false)
  ) { result -> /* speak result */ }
  ```

  The same path reaches Settings' airplane mode and battery saver voice activities, and DeskClock's
  alarm and timer actions (those need `com.android.alarm.permission.SET_ALARM`, not declared yet).
  The session cancels confirmation and pick-option requests, since it has nothing to show them with.

## Not verified on a device yet

- The assistant sheet (gar/DESIGN.md, "Assistant entry points"): its back paths, the keyboard in
  the session window, showing over the lock screen, and the unlock trampoline were decided from
  the framework's sources (Android 15/16) and still need the emulator check.

- The picker lists GAR and the assist gesture opens MainActivity through the session.
- A shown session without UI does not block touches.
- `startVoiceActivity` with `ZenModeVoiceActivity` turns DND off on GrapheneOS 17.
- Behaviour on the lock screen: MainActivity only shows above the keyguard through its
  `showOnLockScreen` attribute (upstream). GAR doesn't offer the lock screen's voice assist
  affordance (`supportsLaunchVoiceAssistFromKeyguard="false"`), because every Dicio skill would be
  reachable from it. When the assistant is invoked on a locked phone anyway (assist gesture, wake
  word), the model's calls of tools without `on_lock_screen: true` in `tools.yml` (calls, apps,
  navigation, notifications, wake word) answer "Unlock your phone first" instead. Dicio's own
  sentence matches are not restricted (upstream behaviour). Unverified on a device.
