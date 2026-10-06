<p align="center"><img src="icon.webp" alt="KOReader Piper text-to-speech icon" width="160"></p>

# KOReader Piper text-to-speech

Read the visible page in KOReader through a self-hosted [Piper](https://github.com/OHF-Voice/piper1-gpl) server.

## How it works

This repository contains three components:

1. `koreader/ttsbridge.koplugin` extracts the current page text and opens a `koreader-tts://speak` URI. It opens `koreader-tts://stop` when playback should stop.
2. `android` handles those URIs, splits long text, requests WAV audio from Piper, and plays the results in order.
3. `server` runs Piper's Flask HTTP server. It keeps Piper's `/voices` and `/synthesize` endpoints unchanged.

The Android app maps English, French, and Japanese book language tags to `en_US-lessac-medium`, `fr_FR-siwis-medium`, and `ja_JP-hi_fi_captain-medium`.

## Prerequisites

- KOReader on Android
- Python 3.9 or newer on the Piper server
- JDK 17 and Android SDK platform 35 for the companion app
- Android Debug Bridge (`adb`) for command-line installation
- Network access from the Android device to the Piper server

## Start the Piper server

```sh
cd server
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
python -m piper.download_voices --data-dir models \
  en_US-lessac-medium \
  fr_FR-siwis-medium \
  ja_JP-hi_fi_captain-medium
./start.sh
```

The server listens on `0.0.0.0:5000`. `GET /voices` lists installed voices. `POST /synthesize` accepts Piper's JSON request format and returns WAV audio.

## Configure, build, and install the Android app

`PIPER_ENDPOINT` is required at build time and becomes the server URL in the APK. It has no default. Set it to a URL reachable from the Android device:

```sh
cd android
PIPER_ENDPOINT=http://your-server.example:5000 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Rebuild and reinstall the APK to change the endpoint. Both HTTP and HTTPS endpoints work; HTTP is enabled for local deployments.

## Install the KOReader plugin

Copy `koreader/ttsbridge.koplugin` into the `plugins` directory in KOReader's data directory, then restart KOReader. Keep the directory name unchanged so KOReader recognizes it as a plugin.

## Use it

1. Open a book in KOReader.
2. Open **More tools**, then **Piper text-to-speech**.
3. Select **Speak current page**.
4. Select **Stop speaking** to stop synthesis and playback.

The plugin uses the book language metadata when present and KOReader's text language fallback otherwise. Unsupported languages stop without playback and appear in Android logs.

## License

Repository source is licensed under the [MIT License](LICENSE). Piper is a separate GPL-3.0-or-later dependency.
