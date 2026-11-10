# Building and installing GAR

How to build GAR from source on a Mac or Linux machine and install it on an arm64 Android phone
over adb. The author uses a Pixel 9a on GrapheneOS 17 (Android 17). Run every command from the
repository root. Steps marked *(untested)* are read from GAR's or Android's source and haven't
been tried yet. For how GAR works, see [DESIGN.md](DESIGN.md).

## 1. What you need

- **A Mac or Linux machine** with git, network access, and about 5 GB of free disk on top of
  Gradle's dependency cache in `~/.gradle`. Measured on the author's Mac: NDK r29 3.1 GB,
  `checkouts/` 0.5 GB (mostly CLDR data), the llama.cpp submodule 0.2 GB, the whisper.cpp
  submodule 0.05 GB, build outputs 0.8 GB.
- **Memory**: the Gradle daemon gets 2 GB of heap (`gradle.properties`), next to the Kotlin daemon
  and the C++ compile of llama.cpp. On a 16 GB machine, don't run an emulator or a llama-server
  during a build.
- **For the eval and the optional host test** (sections 7 and 3): a C/C++ compiler (macOS:
  `xcode-select --install`, which Homebrew usually installs already; Linux: your distribution's
  build-essential or g++), python3 and curl. The Android build doesn't need them.
- **JDK 21**. `:app`, `:gar:llm` and `:gar:assist` compile to Java 21 bytecode (`java = "21"`
  in `gradle/libs.versions.toml`), so JDK 17 can't build them. The two included plugin builds ask
  Gradle for a JDK 21 toolchain and have no toolchain download set up, so a JDK 21 must be
  installed. Newer JDKs are untested.

  ```bash
  brew install --cask temurin@21                      # macOS; on Linux use your distribution's OpenJDK 21
  ```

  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)    # macOS; on Linux the JDK 21 directory
  ```

- **The Android SDK** (command-line tools or Android Studio) with these packages:

  | Package | Why |
  |---|---|
  | `platforms;android-36` | `compileSdk` 36 |
  | `build-tools;35.0.0` | AGP 8.13.0's default, the build doesn't set `buildToolsVersion` |
  | `ndk;29.0.14206865` | NDK r29 (`garNdk`): builds llama.cpp in `:gar:llm`, strips it in `:app` |
  | `cmake;3.31.6` | `garCmake`: `:gar:llm`'s native build (Ninja is included) |
  | `platform-tools` | `adb` |

  ```bash
  brew install --cask android-commandlinetools android-platform-tools   # macOS, or get the tools from developer.android.com
  ```

  ```bash
  export ANDROID_HOME=$(brew --prefix)/share/android-commandlinetools   # where the Homebrew cask puts the SDK
  ```

  ```bash
  sdkmanager "platforms;android-36" "build-tools;35.0.0" "ndk;29.0.14206865" "cmake;3.31.6" "platform-tools"
  ```

  ```bash
  sdkmanager --licenses
  ```

  Instead of `ANDROID_HOME`, `sdk.dir=<path>` in `local.properties` works too (the file is
  gitignored). AGP can install missing SDK packages by itself once the licenses are accepted
  *(untested here: every package was already installed)*; installing them up front is more
  predictable. No `cmake` or `ninja` on `PATH` is needed.

What the build downloads by itself on the first run: Gradle 9.1.0 (the wrapper), the Maven
dependencies, Dicio's two git libraries (`dicio-numbers` and `dicio-sentences-compiler` at the
commits in `gradle/libs.versions.toml`, cloned into `checkouts/`) and one commit of
`unicode-org/cldr`. What it doesn't: the JDK, the SDK command-line tools and their licenses, and
the llama.cpp and whisper.cpp submodules.

## 2. Get the code

GAR lives on the branch `gar` of https://github.com/sybenx/gar.

```bash
git clone --branch gar https://github.com/sybenx/gar gar && cd gar
```

llama.cpp is a git submodule at `gar/llm/llama.cpp`, pinned to `620d2225` (release v0.7.0).
`--depth 1` fetches only that commit (and the tip of llama.cpp's default branch, which the
submodule clone starts from) from GitHub, about 45 MB:

```bash
git submodule update --init --depth 1 gar/llm/llama.cpp
```

Without `--depth 1`, `.gitmodules`' `shallow = true` only makes the first clone shallow: git then
fetches the pinned commit with its whole history, about 400 MB (12 minutes on the author's
connection instead of 40 seconds). If the shallow fetch fails, fetch the full history on purpose:

```bash
git submodule update --init --no-recommend-shallow gar/llm/llama.cpp
```

whisper.cpp (speech recognition) is a second submodule, at `gar/stt/whisper.cpp`, pinned to
`21ba6d26` (master of 2026-10-27, after v1.9.5). The same way, about 40 MB:

```bash
git submodule update --init --depth 1 gar/stt/whisper.cpp
```

`git submodule status` should then print both commits without a leading `-`. Build in a git clone
and on a branch, not a detached HEAD: the build reads the branch name with JGit and makes the
debug build's application id from it (section 3). A source tree without `.git` likely fails to
configure *(untested)*.

## 3. Build

### Debug APK

```bash
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk` (about 94 MB, mostly unminified
code). In a fresh clone it took 3.5 minutes on the author's M1, with Gradle's dependency cache
already filled. What is in it:

- **arm64-v8a only.** llama.cpp is built for arm64 only, so x86_64 emulators and devices can't
  install it. The phone needs Android 9 (API 28) or newer and a CPU with ARMv8.2-A dot product and
  fp16 instructions, the baseline libgar_llama is compiled for.
- **libgar_llama.so**: GAR's JNI layer with llama.cpp and ggml linked in statically, optimized
  (Release) in every variant, CPU only, without llama.cpp's HTTP/TLS code. AGP strips it to
  about 4.4 MB.
- **libgar_whisper.so**: the same for whisper.cpp (speech recognition), with its own static copy
  of ggml and the same CPU baseline; both libraries export only their JNI functions, so the two
  ggml copies never meet. About 1.7 MB before stripping. No model is in the APK (section 5).
- **Application id** `cc.aaro.gar.<branch>`, version `1.0.0-debug`, label `GAR`: on the branch
  `gar` the id is `cc.aaro.gar.gar`. A release build is `cc.aaro.gar`, version `1.0.0`, also
  labelled `GAR`, and Dicio is `org.stypox.dicio`, so all three install side by side, each with
  its own data and settings (and its own downloaded model). GAR's versions start at 1.0.0
  (code 10000, above Dicio's 19; see Release APK). Dicio named debug builds after the git branch, which made `GAR-gar`; GAR
  keeps the branch out of the label and the version (`app/build.gradle.kts`, GAR comment).
- **The tool catalog** (`app/src/main/gar/tools.yml`) and Dicio's `skill_definitions.yml`, copied
  into the APK's assets under `gar/`; the router builds its tool schemas from them at runtime.

To check the application id and the ABI with the SDK's `aapt2`:

```bash
"$ANDROID_HOME/build-tools/35.0.0/aapt2" dump badging app/build/outputs/apk/debug/app-debug.apk | grep -E "^(package|native-code)"
```

It should print `package: name='cc.aaro.gar.gar' versionCode='10000' versionName='1.0.0-debug' ...`
(with your branch's name in the id) and `native-code: 'arm64-v8a'`.

With a phone connected (section 4), `./gradlew :app:installDebug` builds and installs in one step.

### Unit tests

```bash
./gradlew :gar:router:test
```

```bash
./gradlew :app:testDebugUnitTest
```

The first is the pure JVM router (prompt formats, grammars, call filters, resolvers) and also
compiles the eval runner; it takes about a minute. The second is the app's unit tests, including
`GarCatalogTest`, `GarCallFilterTest` and `GarRankingTest` in
`app/src/test/kotlin/org/stypox/dicio/gar/`; it takes several minutes. The tests are Kotest on the
JUnit Platform. Kotest's `--tests` filter fails in this project with `initializationError`, so
run whole tasks. Results go to `<module>/build/test-results/`, HTML reports to
`<module>/build/reports/tests/`.

`./gradlew :gar:stt:testDebugUnitTest` runs the speech module's JVM tests (audio context,
end-pointing, WAV reading) in seconds.

Dicio's CI style, `./gradlew testDebugUnitTest`, runs the Android modules' tests (`:app`,
`:skill`, `:gar:llm`, `:gar:stt`) but not `:gar:router`, whose task is `test`.

Optional: run llama.cpp itself on the computer through the same JNI code. The script builds
libgar_llama for the host (it needs `JAVA_HOME` and finds the SDK's CMake through `ANDROID_HOME`)
and prints the library's path; without the two variables `LlamaEngineHostTest` is skipped.

```bash
gar/llm/host-test/build-host-jni.sh
```

```bash
GAR_HOST_JNI_LIB=<printed path> GAR_TEST_MODEL=~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf ./gradlew :gar:llm:testDebugUnitTest
```

`GAR_TEST_MODEL_FG=~/.cache/gar/models/functiongemma-270m-it-q8_0.gguf` adds the FunctionGemma checks.

Whisper works the same way: `WhisperEngineHostTest` transcribes the WAVs in
`gar/stt/src/test/resources/wav/` (made with macOS `say`) and whisper.cpp's JFK sample, checks
cancelling, and runs Silero VAD on speech and silence. It needs the speech model and the VAD
file (82 MB and 0.9 MB, the files the app downloads in section 5):

```bash
curl -L --create-dirs -o ~/.cache/gar/stt/ggml-base.en-acft-q8_0.bin https://dl.voiceinput.futo.org/VoiceInput/base_en_acft_q8_0.bin
```

```bash
curl -L --create-dirs -o ~/.cache/gar/stt/ggml-silero-v6.2.0.bin https://huggingface.co/ggml-org/whisper-vad/resolve/9ffd54a1e1ee413ddf265af9913beaf518d1639b/ggml-silero-v6.2.0.bin
```

```bash
shasum -a 256 ~/.cache/gar/stt/*.bin   # Linux: sha256sum
```

Expect `e9b4b7b81b8a28769e8aa9962aa39bb9f21b622cf6a63982e93f065ed5caf1c8` for the model and
`2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987` for the VAD. Then build the
host library (it also converts the JFK sample to WAV next to it, with macOS `afconvert`) and run
the module's tests with the three paths:

```bash
gar/stt/host-test/build-host-jni.sh
```

```bash
GAR_STT_HOST_JNI_LIB=<printed path> GAR_STT_TEST_MODEL=~/.cache/gar/stt/ggml-base.en-acft-q8_0.bin GAR_STT_TEST_VAD=~/.cache/gar/stt/ggml-silero-v6.2.0.bin ./gradlew :gar:stt:testDebugUnitTest
```

The host build takes about 20 seconds on the author's Mac and the tests about 15 seconds.

### Release APK

Releases are built by `.github/workflows/release.yml`: pushing a tag `v1.2.3` builds
`:app:assembleRelease` with the version from the tag, signs it with the release key (repository
secrets `GAR_KEYSTORE_B64` and `GAR_KEYSTORE_PASSWORD`) and attaches `gar-1.2.3.apk` to a GitHub
release, which Obtainium installs from. The version code follows the name (`1.2.3` is 10203), so
every new tag must be a higher `x.y.z` with minor and patch below 100.

Locally, without a keystore the release build stays unsigned
(`app/build/outputs/apk/release/app-release-unsigned.apk`). To sign it, point Gradle at a key
with the environment variables `GAR_KEYSTORE_FILE` and `GAR_KEYSTORE_PASSWORD` (alias `gar`;
`GAR_KEY_ALIAS` and `GAR_KEY_PASSWORD` if yours differ), or the Gradle properties
`garKeystoreFile`, `garKeystorePassword`, `garKeyAlias` and `garKeyPassword` in
`~/.gradle/gradle.properties`, never in the repository. `-PgarVersion=1.2.3` (or `GAR_VERSION`)
sets the version, 1.0.0 by default.

```bash
./gradlew :app:assembleRelease
```

Installing an update needs the same key again. To check the signer:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
```

### Baseline profile and the benchmark build *(untested)*

Debug builds ignore baseline profiles, so the profile only helps release-like builds. The
`benchmark` build type is release code signed with the debug key, profileable from adb (Perfetto,
simpleperf) and not debuggable, with its own app id `cc.aaro.gar.benchmark` so it installs next
to the debug build (gar/DESIGN.md, "Frame rate"). Whether the phone switches to it is an open
question; until then it is for measuring.

Generate the profile on the emulator only (gartest, already running; the generator makes the
release build GAR's assistant there to profile the sheet). It installs `nonMinifiedRelease`
(`cc.aaro.gar`, debug key) and writes `app/src/release/generated/baselineProfiles/`, which the
release and benchmark builds read:

```bash
ANDROID_SERIAL=emulator-5580 ./gradlew :app:generateReleaseBaselineProfile
```

```bash
./gradlew :app:assembleBenchmark
```

The output should be `app/build/outputs/apk/benchmark/app-benchmark.apk`. `profileinstaller`
installs the profile at the first start, or right away with
`adb shell cmd package compile -f -m speed-profile cc.aaro.gar.benchmark`.

## 4. Install with adb

Turn on Developer options on the phone (Settings > About phone, tap Build number seven times).

**Over USB**: Settings > System > Developer options > USB debugging on, connect the cable, allow
the computer on the phone, then check that it is listed:

```bash
adb devices
```

**Over Wi-Fi** (Android 11+, phone and computer on the same network): Developer options >
Wireless debugging on > Pair device with pairing code. Pair with the address, port and six-digit
code that dialog shows:

```bash
adb pair <phone ip>:<pairing port>
```

Then connect to the address and port shown on the Wireless debugging screen itself, which is a
different port from the pairing one:

```bash
adb connect <phone ip>:<connection port>
```

The connection port changes whenever wireless debugging restarts (turned off and on, a reboot,
another network); connect again with the new port. The pairing is kept. `adb mdns services` lists
the phone's current ports.

Install, or update in place and keep the app's data:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

adb doesn't mark the app as installed from a file, so Android's restricted settings shouldn't
gray out anything *(untested)*. An APK copied to the phone and opened from a file manager is a
sideload: notification access (Android 13+) and the SMS permission (Android 15+) stay grayed out
until App info > ⋮ > Allow restricted settings, and the installer denies full-screen intents,
which the wake word uses to turn the screen on *(untested)*.

## 5. First run on the phone

### Models

- **Speech**: the home screen's microphone button first asks for the microphone. In English,
  Settings > Input method left at its default means Whisper, so on a new install the button reads
  "Download speech model (83 MB)" and a tap downloads FUTO's ACFT base.en (82 MB) from
  dl.voiceinput.futo.org plus the Silero VAD file (0.9 MB) from huggingface.co, both checked for
  size and sha256. A home screen card offers the same download once. Settings > Language model >
  Speech recognition picks base.en or tiny.en (44 MB; a switch downloads the new one before it
  deletes the old), shows progress, and has Cancel and Delete. A phone that already has Vosk's
  model keeps Vosk until a Whisper model is downloaded, then switches by itself. Choosing Vosk or
  Whisper under Settings > Input method sticks; other languages always get Vosk (about 50 MB from
  alphacephei.com). There is no way to import either. Typed commands work without them.
- **Language model**: on by default, but nothing downloads by itself. Download in Settings >
  Language model fetches Qwen3 0.6B (397 MB from Hugging Face, at a pinned revision, checked for
  size and sha256). Cancel keeps the partial file and the next download resumes. FunctionGemma 270M
  (292 MB) is the other choice; its Gemma Terms of Use must be accepted in the same screen first.
  Without a model only the built-in sentences understand commands, and the model handles English
  only.
- **Import instead**: Settings > Language model > Import a model opens the file picker. Pick a
  `.gguf` file and say whether it is "Qwen or another Hermes-style model" or FunctionGemma. GAR
  copies it into its own storage and rejects files that aren't GGUF, that are bigger than a
  quarter of the phone's RAM, or that don't fit in the free storage. A model that crashes the app
  while loading twice in a row is blocked until you tap the error. To import the default model,
  download it on the computer, check it, and copy it to the phone:

  ```bash
  curl -L --create-dirs -o ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/50968a4468ef4233ed78cd7c3de230dd1d61a56b/Qwen3-0.6B-Q4_K_M.gguf
  ```

  ```bash
  shasum -a 256 ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf   # Linux: sha256sum. Expect ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a
  ```

  ```bash
  adb push ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf /sdcard/Download/
  ```

**Network**: GAR's core only uses the network to download these models (the language model and
the VAD file from huggingface.co, the Whisper model from dl.voiceinput.futo.org, Vosk from
alphacephei.com, and wake word models from github.com if you turn a wake word on; it is off by
default). Speech is recognized on the phone; audio never leaves it. Dicio's online skills (weather, search, lyrics, translation,
jokes) start disabled, and the song skill's AudD/ACRCloud service stays off until you add your
own token. On GrapheneOS you can revoke GAR's Network permission (App info > Permissions >
Network) once the models are downloaded. GrapheneOS makes a revoked Network look like no
connection, so a download then fails like an offline one *(untested)*.

### Make GAR the digital assistant

Settings > Apps > Default apps > Digital assistant app > Default digital assistant app > GAR
(Android 9: Settings > Apps & notifications > Default apps > Assist & voice input > Assist app).
Apps can't ask for this role, so GAR has no prompt for it. Over adb (Android 10+), with the id
from `adb shell pm list packages cc.aaro.gar`:

```bash
adb shell cmd role add-role-holder android.app.role.ASSISTANT cc.aaro.gar.gar
```

```bash
adb shell settings get secure voice_interaction_service   # expect <id>/cc.aaro.gar.assist.GarVoiceInteractionService
```

As the assistant, the assist gesture opens GAR, and GAR's voice interaction service lets it start
activities from the background (commands after the wake word), turn off Do Not Disturb that
GAR didn't turn on (through Settings' own voice activity), and start Audile's background
recognition. None of this has been tried on a phone yet. To undo it, pick another app in the
same setting, or over adb with the same id:

```bash
adb shell cmd role remove-role-holder android.app.role.ASSISTANT cc.aaro.gar.gar
```

Details: [assist/README.md](assist/README.md) (it uses the release id `cc.aaro.gar`).

### Permissions and special access

GAR asks for a skill's runtime permissions before that skill's first run. The special accesses
are switches in Android's settings, which GAR's messages and settings rows open for you.

| Feature | What to allow |
|---|---|
| Speech input, "what song is this" with AudD/ACRCloud | Microphone |
| Calls (Dicio's telephone skill) | Contacts and Phone |
| Texts | Contacts and SMS, asked together so a spoken "yes" can send. Without SMS, GAR opens the default SMS app with the text filled in. |
| Calendar | Calendar. GAR writes to an existing calendar it may edit (Settings > Skills > Calendar > Calendar for new events, Automatic by default), for example a CalDAV account synced by DAVx5, the author's setup. It doesn't create a calendar of its own. With none, it hands the event to another calendar app, or says there's no calendar it can add events to. |
| Alarms | Nothing to allow; GAR uses the clock app (GrapheneOS: Clock). |
| Turning Bluetooth on | Nearby devices (Android 12+) |
| Do Not Disturb | Do Not Disturb access, whose page GAR opens the first time. Turning off a Do Not Disturb that GAR didn't turn on also needs GAR as the digital assistant; otherwise GAR opens the Do Not Disturb settings. |
| Reading notifications (Dicio), naming what plays on this phone, saying Audile's answers | Notification access. Settings > Skills > Song recognition > "Name what's playing on this phone" opens it. |
| Calendar reminders, the stopwatch's notification, the song skill's offline queue | Notifications. Android only asks for them when you turn on a wake word: allow them in App info, or with the song skill's "Allow notifications" button. |

## 6. Optional: Audile for "what song is this"

Without a recognition service, GAR names what plays on the phone itself and hands other music to
[Audile](https://github.com/AudileTeam/Audile) (`com.mrsep.musicrecognizer`, GPL-3.0, free,
uses Shazam by default). This path has only been tried on an emulator with a stand-in app.

1. Install Audile from F-Droid or its GitHub releases (they are signed with different keys, so
   stay with one) and open it once: the shortcut GAR uses for background recognition only exists
   after Audile has run. Until then GAR opens Audile's screen instead.
2. In GAR, leave Settings > Skills > Song recognition > Recognition service at Off. Audile is
   used only when no AudD or ACRCloud service is set up.
3. Make GAR the digital assistant (section 5). Then Audile recognizes in the background; otherwise
   GAR opens Audile's screen, which starts listening.
4. Give GAR notification access, so it can read Audile's result and say "That's ... by ...". It
   waits up to 2 minutes.
5. Turn on Audile's "Song results found in the foreground" notifications (off by default):
   Settings > Skills > Song recognition > Say Audile's answers opens that page. GAR needs them
   for songs found on Audile's own screen.

## 7. The eval

[gar/eval/README.md](eval/README.md) describes the router eval (`gar/eval/cases.yml`, the blind
`gar/eval/holdout.yml`, `gar/eval/run-eval.sh`). It needs llama.cpp's `llama-server` at the
submodule's commit. The SDK's CMake and Ninja build it in about a minute and a half on an M1 with
4 jobs, CPU only, outside the repository, without the web UI (which would otherwise be
downloaded at build time; CMake then warns "UI: no assets available", which is expected):

```bash
C=$ANDROID_HOME/cmake/3.31.6/bin; "$C/cmake" -S gar/llm/llama.cpp -B ~/.cache/gar/llama.cpp -G Ninja -DCMAKE_MAKE_PROGRAM="$C/ninja" -DCMAKE_BUILD_TYPE=Release -DGGML_METAL=OFF -DGGML_BLAS=OFF -DLLAMA_OPENSSL=OFF -DLLAMA_USE_PREBUILT_UI=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF && "$C/cmake" --build ~/.cache/gar/llama.cpp --target llama-server -j 4
```

If `~/.cache/gar/llama.cpp` was configured before with another generator, delete it first: CMake
won't switch generators in the same build folder.

`~/.cache/gar/llama.cpp/bin/llama-server --version` should name commit `620d222`. The eval also
runs Gradle, so it needs the `JAVA_HOME` of section 1:

```bash
LLAMA_SERVER=~/.cache/gar/llama.cpp/bin/llama-server gar/eval/run-eval.sh ~/.cache/gar/models/Qwen3-0.6B-Q4_K_M.gguf hermes
```

That runs the 361 cases of `cases.yml`, about 6 minutes on an M1. For a quick check of the setup,
put a few cases in a file of the same format inside the repository and pass it as
`CASES=<path relative to the repository root>`: 3 cases took 17 seconds, llama-server's start
included.

This build is tuned for the computer's CPU, not the phone's baseline, so its timings don't carry
over to the phone. Run one llama-server at a time: each takes memory for its model and KV cache.

## 8. Troubleshooting

- **`llama.cpp not found in .../gar/llm/src/main/cpp/../../../llama.cpp`** from CMake: the
  submodule isn't there. Run section 2's `git submodule update --init --depth 1
  gar/llm/llama.cpp`, not the command the message prints, which fetches the whole history.
- **`whisper.cpp not found in .../gar/stt/src/main/cpp/../../../whisper.cpp`**: the same for the
  speech submodule: `git submodule update --init --depth 1 gar/stt/whisper.cpp`.
- **A toolchain error asking for language version 21**, or javac's "invalid source release: 21":
  Gradle runs on another JDK and finds no JDK 21, or compiles with the older one. Set `JAVA_HOME`
  to a JDK 21 and stop the old daemon with `./gradlew --stop`.
- **"SDK location not found"**: set `ANDROID_HOME` or `sdk.dir` in `local.properties`.
- **`Warning: can't read local.properties`** at the start of every build: harmless without the
  file, when `ANDROID_HOME` is set.
- **"CMake '3.31.6' was not found"**, a missing NDK, or "licences have not been accepted": install
  the packages and accept the licenses with the `sdkmanager` commands of section 1.
- **The first build stops while cloning**: it needs GitHub for `dicio-numbers`,
  `dicio-sentences-compiler` and CLDR. `settings.gradle.kts` also fetches the first two again on
  every configuration, so builds without network may fail *(untested)*. With local clones,
  `useLocalDicioLibraries=dicio-numbers:../dicio-numbers,dicio-sentences-compiler:../dicio-sentences-compiler`
  in `local.properties` uses those instead.
- **`--tests` fails with `initializationError`**: Kotest's filter doesn't work here; run the whole
  test task.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: the installed app is signed with another key (a debug
  build from another computer uses that computer's `~/.android/debug.keystore`). Uninstall it
  first, which deletes its data and the downloaded model: `adb uninstall cc.aaro.gar.gar`.
- **`INSTALL_FAILED_NO_MATCHING_ABIS`**: the device or emulator isn't arm64.
- **The model and settings are gone after a rebuild**: a build on another branch is another app
  (`cc.aaro.gar.<branch>`). `adb shell pm list packages cc.aaro.gar` lists the installed ones.
- **The assist gesture does nothing**: if `adb shell settings get secure voice_interaction_service`
  is empty, Android rejected GAR's assistant service. Low-RAM devices skip assistant services;
  GAR then only opens through its assist activity.
- **`adb connect` is refused**: wireless debugging restarted and the port changed. Read the new
  one on the Wireless debugging screen or from `adb mdns services`.
- **The notification access switch is grayed out**: GAR was installed from an APK file. App info >
  ⋮ > Allow restricted settings, then try again.
- **The launcher's "Speech to text popup" shortcut doesn't open in a debug build**:
  `app/src/main/res/xml/shortcuts.xml` targets the release id `cc.aaro.gar` (Dicio's debug builds
  have the same limit).
