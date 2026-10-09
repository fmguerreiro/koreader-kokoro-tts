package io.github.fmguerreiro.koreaderkokoro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import java.nio.charset.StandardCharsets
import java.util.Locale

class KoreaderTtsActivity : Activity() {
    private var pendingSpeech: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data
        if (intent.action != Intent.ACTION_VIEW || uri?.scheme != SCHEME) {
            finish()
            return
        }

        when (uri.host) {
            "speak" -> requestSpeech(uri)
            "pause", "resume", "stop" -> {
                val action = when (uri.host) {
                    "pause" -> KoreaderTtsService.ACTION_PAUSE
                    "resume" -> KoreaderTtsService.ACTION_RESUME
                    else -> KoreaderTtsService.ACTION_STOP
                }
                startService(
                    Intent(this, KoreaderTtsService::class.java)
                        .setAction(action)
                )
                finish()
            }

            else -> finish()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) return

        val speech = pendingSpeech
        pendingSpeech = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && speech != null) {
            startForegroundService(speech)
        } else {
            Toast.makeText(
                this,
                "Notification permission is required for speech",
                Toast.LENGTH_LONG
            ).show()
        }
        finish()
    }

    private fun requestSpeech(uri: Uri) {
        val speech = speechIntent(uri) ?: run {
            finish()
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSpeech = speech
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }

        startForegroundService(speech)
        finish()
    }

    private fun speechIntent(uri: Uri): Intent? {
        val text = uri.getQueryParameter("text_base64")
            ?.let {
                runCatching {
                    Base64.decode(it, Base64.URL_SAFE).toString(StandardCharsets.UTF_8)
                }.getOrNull()
            }
            ?.trim()
        val requestId = uri.getQueryParameter("request_id")?.takeIf { it.isNotBlank() }
        val callbackPort = uri.getQueryParameter("callback_port")
            ?.toIntOrNull()
            ?.takeIf { it in 1..65535 }
        if (text.isNullOrEmpty() || requestId == null || callbackPort == null) {
            Log.w(TAG, "Ignored invalid speech URI")
            return null
        }

        return Intent(this, KoreaderTtsService::class.java)
            .setAction(KoreaderTtsService.ACTION_SPEAK)
            .putExtra(KoreaderTtsService.EXTRA_TEXT, text)
            .putExtra(
                KoreaderTtsService.EXTRA_LANGUAGE,
                uri.getQueryParameter("language") ?: Locale.US.toLanguageTag()
            )
            .putExtra(KoreaderTtsService.EXTRA_REQUEST_ID, requestId)
            .putExtra(KoreaderTtsService.EXTRA_CALLBACK_PORT, callbackPort)
    }

    private companion object {
        const val SCHEME = "koreader-tts"
        const val TAG = "KOReaderTtsBridge"
        const val NOTIFICATION_PERMISSION_REQUEST = 1
    }
}
