<div align="center">
  <a href="https://github.com/fmguerreiro/koreader-kokoro-tts">
    <img src="icon.webp" alt="KOReader Kokoro text-to-speech" width="96" height="96" />
  </a>
  <h1>KOReader Kokoro text-to-speech</h1>
  <p><em>Read KOReader's visible page through a self-hosted Kokoro-82M server.</em></p>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-4c1.svg" alt="MIT License" /></a>
</div>

## How it works

This repository contains three components:

1. `koreader/ttsbridge.koplugin` extracts the current page text and opens a `koreader-tts://speak` URI. It opens `koreader-tts://stop` when playback should stop.
2. `android` handles those URIs, splits long text, requests WAV audio from Kokoro, and plays the results in order.
3. `server` runs Kokoro-82M behind the same small `/voices` and `/synthesize` HTTP interface.

The Android app maps English, French, and Japanese book language tags to Kokoro's `af_heart`, `ff_siwis`, and `jf_alpha` voices. Kokoro produces substantially more natural speech than the former Piper backend, while remaining local and self-hosted.

## Prerequisites

- KOReader on Android
- Python 3.10 or newer on the Kokoro server
- JDK 17 and Android SDK platform 35 for the companion app
- Android Debug Bridge (`adb`) for command-line installation
- Network access from the Android device to the Kokoro server

An NVIDIA GPU is optional. The server uses CUDA when PyTorch detects it and otherwise runs on the CPU. For GPU use, install the PyTorch build matching your CUDA runtime before installing `requirements.txt`.

## Start the Kokoro server

```sh
cd server
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r requirements.txt
./start.sh
```

The first start downloads `hexgrad/Kokoro-82M` and the configured voice files from Hugging Face. The server listens on `0.0.0.0:5000`. `GET /voices` lists the three voices. `POST /synthesize` accepts JSON containing `text` and `voice`, then returns 24 kHz PCM WAV audio.

Set `KOKORO_DEVICE=cpu` or `KOKORO_DEVICE=cuda` to override automatic device selection.

## Configure, build, and install the Android app

`KOKORO_ENDPOINT` is required at build time and becomes the server URL in the APK. Set it to a URL reachable from the Android device:

```sh
cd android
KOKORO_ENDPOINT=http://your-server.example:5000 ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Rebuild and reinstall the APK to change the endpoint. Both HTTP and HTTPS endpoints work; HTTP is enabled for local deployments.

## Install the KOReader plugin

Copy `koreader/ttsbridge.koplugin` into the `plugins` directory in KOReader's data directory, then restart KOReader. Keep the directory name unchanged so KOReader recognizes it as a plugin.

## Use it

1. Open a book in KOReader.
2. Open **More tools**, then **Kokoro text-to-speech**.
3. Select **Speak current page**.
4. Select **Stop speaking** to stop synthesis and playback.

The plugin uses the book language metadata when present and KOReader's text language fallback otherwise. Unsupported languages stop without playback and appear in Android logs.

## License

Repository source is licensed under the [MIT License](LICENSE). Kokoro-82M is a separate Apache-2.0 dependency.
