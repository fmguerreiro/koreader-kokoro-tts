import io
import os
import threading

import numpy as np
import soundfile as sf
import torch
from flask import Flask, jsonify, request, send_file
from kokoro import KModel, KPipeline

MODEL_REPOSITORY = "hexgrad/Kokoro-82M"
SAMPLE_RATE = 24_000
VOICES = {
    "af_heart": "a",
    "ff_siwis": "f",
    "jf_alpha": "j",
}

app = Flask(__name__)
device = os.environ.get("KOKORO_DEVICE") or ("cuda" if torch.cuda.is_available() else "cpu")
model = KModel(repo_id=MODEL_REPOSITORY).to(device).eval()
pipelines = {
    language: KPipeline(lang_code=language, repo_id=MODEL_REPOSITORY, model=model)
    for language in sorted(set(VOICES.values()))
}
for voice, language in VOICES.items():
    pipelines[language].load_voice(voice)

synthesis_lock = threading.Lock()


@app.get("/voices")
def voices():
    return jsonify(sorted(VOICES))


@app.post("/synthesize")
def synthesize():
    payload = request.get_json(silent=True) or {}
    text = payload.get("text", "").strip()
    voice = payload.get("voice", "")

    if not text:
        return jsonify(error="text is required"), 400
    if voice not in VOICES:
        return jsonify(error=f"unsupported voice: {voice}"), 400

    with synthesis_lock, torch.inference_mode():
        audio = [
            result.audio.numpy()
            for result in pipelines[VOICES[voice]](text, voice=voice)
            if result.audio is not None
        ]

    if not audio:
        return jsonify(error="synthesis produced no audio"), 500

    wav = io.BytesIO()
    sf.write(wav, np.concatenate(audio), SAMPLE_RATE, format="WAV", subtype="PCM_16")
    wav.seek(0)
    return send_file(wav, mimetype="audio/wav", download_name="speech.wav")


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, threaded=True)
