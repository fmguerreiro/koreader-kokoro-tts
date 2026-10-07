package io.github.fmguerreiro.koreaderkokoro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.util.Locale

class KoreaderTtsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        if (intent.action == Intent.ACTION_VIEW && uri?.scheme == SCHEME) {
            when (uri.host) {
                "speak" -> speak(uri.getQueryParameter("text_base64"), uri.getQueryParameter("language"))
                "stop" -> startService(
                    Intent(this, KoreaderTtsService::class.java)
                        .setAction(KoreaderTtsService.ACTION_STOP)
                )
            }
        }
        finish()
    }

    private fun speak(encodedText: String?, languageTag: String?) {
        val text = encodedText
            ?.let { runCatching { Base64.decode(it, Base64.URL_SAFE).toString(StandardCharsets.UTF_8) }.getOrNull() }
            ?.trim()
        if (text.isNullOrEmpty()) {
            Log.w(TAG, "Ignored empty speech URI")
            return
        }
        startForegroundService(
            Intent(this, KoreaderTtsService::class.java)
                .setAction(KoreaderTtsService.ACTION_SPEAK)
                .putExtra(KoreaderTtsService.EXTRA_TEXT, text)
                .putExtra(KoreaderTtsService.EXTRA_LANGUAGE, languageTag ?: Locale.US.toLanguageTag())
        )
    }

    private companion object {
        const val SCHEME = "koreader-tts"
        const val TAG = "KOReaderTtsBridge"
    }
}
