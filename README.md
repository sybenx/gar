<!-- GAR: fork notice -->
# Garble

Garble (formerly GAR) is an offline voice assistant for Android in the style of Google
Assistant, a modified fork of [Dicio](https://github.com/DicioTeam/dicio-android). Commands that
Dicio's sentences don't match go to a small language model running on the phone, speech is
recognized on the phone with Whisper, and Garble can be the phone's digital assistant. How it
works: [gar/DESIGN.md](gar/DESIGN.md). Building from source: [gar/BUILD.md](gar/BUILD.md).

Garble is not affiliated with or supported by the Dicio project: please don't report Garble
problems to Dicio.

## Install

Garble needs an arm64 phone on Android 9 or newer. Install it with
[Obtainium](https://github.com/ImranR98/Obtainium): Add app, paste
`https://github.com/sybenx/gar`, then Install. Obtainium picks up new releases from there. The
APK is also attached to every [release](https://github.com/sybenx/gar/releases). The application
id is `cc.aaro.gar`, so Garble installs next to Dicio.

## First run

- **Digital assistant**: Settings > Apps > Default apps > Digital assistant app > Garble. Apps can't
  ask for this role, so Garble doesn't prompt for it.
- **Speech**: the microphone button first asks for the microphone, then offers to download the
  Whisper speech model (83 MB). Typed commands work without it.
- **Language model**: nothing downloads by itself. Settings > Language model > Download fetches
  Qwen3 0.6B (397 MB). Without it only the built-in sentences understand commands.
- After the downloads Garble works without the network. If a permission such as notification access
  stays grayed out, open App info > menu > Allow restricted settings.

## License

GPL-3.0, like Dicio, whose authors wrote most of this app. Garble statically links
[llama.cpp](https://github.com/ggml-org/llama.cpp) and
[whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT License); their notices ship in the
app under Settings > Language model > Open source licenses. Downloaded models come with their own
licenses. The rest of this file is Dicio's README.

# Dicio assistant

Dicio is a *free and open source* **voice assistant** running on Android. It supports many different **skills** and input/output methods, and it provides both **speech** and **graphical** feedback to a question. It interprets user input and (when possible) generates user output entirely **on-device**, providing privacy by design. It has **multilanguage** support, and is currently available in these languages: Czech, Dutch, English, French, German, Greek, Italian, Polish, Russian, Slovenian, Spanish, Swedish, Turkish and Ukrainian. Open to contributions :-D

<p align="center">
    <img width="80" alt="Dicio logo" src="./app/src/main/res/mipmap-xxxhdpi/ic_launcher.png">
    &emsp;
    <a href="https://f-droid.org/packages/org.stypox.dicio">
        <img width="206" alt="Get it on F-Droid" src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png">
    </a>
    <a href="https://github.com/Stypox/dicio-android/releases">
        <img width="206" alt="Get it on GitHub" src="./meta/get-it-on-github.png">
    </a>
    <a href="https://github.com/DicioTeam/dicio-android/issues/464">
        <img width="206" alt="Get it on Play Store" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png">
    </a>
</p>

## Screenshots

[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/0.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/0.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/1.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/2.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/3.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/4.png)
[<img src="./fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width=200>](./fastlane/metadata/android/en-US/images/phoneScreenshots/4.png)

## Skills

Currently Dicio answers questions about:
- **search**: looks up information on **DuckDuckGo** (and in the future more engines) - _Search for Dicio_
- **weather**: collects weather information from **OpenWeatherMap** - _What's the weather like?_
- **lyrics**: shows **Genius** lyrics for songs - _What's the song that goes we will we will rock you?_
- **open**: opens an app on your device - _Open NewPipe_
- **calculator**: evaluates basic calculations - _What is four thousand and two times three minus a million divided by three hundred?_
- **telephone**: view and call contacts - _Call Tom_
- **timer**: set, query and cancel timers - _Set a timer for five minutes_
- **current time**: query current time - _What time is it?_
- **navigation**: opens the navigation app at the requested position - _Take me to New York, fifteenth avenue_
- **jokes**: tells you a joke - _Tell me a joke_
- **media**: play, pause, previous, next song
- **translation**: translate from/to any language with **Lingva** - _How do I say Football in German?_
- **wake word control**: turn on/off the wakeword - _Stop listening_
- **notifications**: reads all notifications currently in the status bar - _What are my notifications?_
- **flashlight**: turn on/off the phone flashlight - _Turn on the flashlight_

## Speech to text

Dicio uses [Vosk](https://github.com/alphacep/vosk-api/) as its speech to text (`STT`) engine. In order to be able to run on every phone small models are employed, weighing `~50MB`. The download from [here](https://alphacephei.com/vosk/models) starts automatically whenever needed, so the app language can be changed seamlessly.

## Wake Word

Dicio uses [OpenWakeWord](https://github.com/dscripka/openWakeWord) for wake word support, and by defaults it listens for the _Hey Dicio_ keyword. If you would like to use a different keyword, you can download other `.tflite` models from [Open Wake Word](https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1) or from [this collection](https://github.com/fwartner/home-assistant-wakewords-collection). Then head to `Settings > Input and output methods > Import custom wake word` and select the `.tflite` model you downloaded. Alternatively, you can train a wake word model for a keyword of your choice by following this [Jupiter Notebook](https://github.com/dscripka/openWakeWord/blob/main/notebooks/automatic_model_training.ipynb) with this [configuration](meta/openwakeword_training_config.yml).

## Contributing

Dicio's code is **not only here**! The repository with the *compiler for sentences* language files is at [`dicio-sentences-compiler`](https://github.com/Stypox/dicio-sentences-compiler), the *number parser and formatter* is at [`dicio-numbers`](https://github.com/Stypox/dicio-numbers) and the code for evaluating matching algorithms is at [`dicio-evaluation`](https://github.com/Stypox/dicio-evaluation).

When contributing keep in mind that other people may have **needs** and **views different** than yours, so please *respect* them. For any question feel free to contact the project team at [@Stypox](https://github.com/Stypox).

### Matrix room for communication

The **#dicio** channel on *Matrix* is available to get in touch with the developers: [#dicio:matrix.org](https://matrix.to/#/#dicio:matrix.org). Some convenient Matrix clients, available both for phone and desktop, are listed at that link.

### Translating

If you want to translate Dicio to a new language, follow the **steps** listed in the documentation: https://dicio.stypox.org/translating.html

### Adding skills

If you want to add a new skill, or improve an existing one, check out the guide in the documentation: https://dicio.stypox.org/adding_skill.html
