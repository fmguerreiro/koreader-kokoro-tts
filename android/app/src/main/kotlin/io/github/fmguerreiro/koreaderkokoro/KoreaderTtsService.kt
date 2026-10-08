package io.github.fmguerreiro.koreaderkokoro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val TAG = "KOReaderTtsBridge"
private const val CHANNEL_ID = "koreader-tts-playback"
private const val NOTIFICATION_ID = 1

class KoreaderTtsService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var synthesis: Future<*>? = null
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var generation = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "KOReader speech", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SPEAK -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim()
                val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
                val callbackPort = intent.getIntExtra(EXTRA_CALLBACK_PORT, 0)
                if (text.isNullOrEmpty() || requestId.isNullOrEmpty() || callbackPort !in 1..65535) {
                    Log.w(TAG, "Ignored invalid speech request")
                    stopSelf(startId)
                } else {
                    startForeground(NOTIFICATION_ID, playbackNotification())
                    speak(
                        text,
                        intent.getStringExtra(EXTRA_LANGUAGE) ?: Locale.US.toLanguageTag(),
                        requestId,
                        callbackPort
                    )
                }
            }

            ACTION_STOP -> stopSpeech()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        synchronized(this) {
            generation++
            stopLocked()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    @Synchronized
    private fun speak(text: String, languageTag: String, requestId: String, callbackPort: Int) {
        val voice = voiceFor(languageTag)
        generation++
        stopLocked()
        val requestGeneration = generation
        synthesis = executor.submit {
            if (voice == null) {
                Log.e(TAG, "Unsupported Kokoro language: $languageTag")
                sendCallback(callbackPort, requestId, "error")
                if (isCurrent(requestGeneration)) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@submit
            }
            val audioFile = synthesize(BuildConfig.KOKORO_ENDPOINT, voice, text, requestGeneration)
            if (audioFile == null) {
                if (isCurrent(requestGeneration)) {
                    sendCallback(callbackPort, requestId, "error")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@submit
            }
            if (!isCurrent(requestGeneration)) {
                audioFile.delete()
                return@submit
            }

            sendCallback(callbackPort, requestId, "ready")
            play(audioFile, requestGeneration, requestId, callbackPort)
            synchronized(this) {
                if (requestGeneration == generation) synthesis = null
            }
        }
    }

    private fun synthesize(
        endpoint: String,
        voice: String,
        text: String,
        requestGeneration: Long
    ): File? {
        val output = File.createTempFile("koreader-tts-", ".wav", cacheDir)
        val connection = try {
            (URL("${endpoint.trimEnd('/')}/synthesize").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.bufferedWriter(StandardCharsets.UTF_8).use {
                    it.write(JSONObject().put("text", text).put("voice", voice).toString())
                }
            }
        } catch (error: Exception) {
            output.delete()
            if (isCurrent(requestGeneration)) Log.e(TAG, "Kokoro request failed", error)
            return null
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Kokoro returned HTTP ${connection.responseCode}")
                output.delete()
                null
            } else {
                connection.inputStream.use { input ->
                    output.outputStream().use { outputStream -> input.copyTo(outputStream) }
                }
                if (isCurrent(requestGeneration)) output else {
                    output.delete()
                    null
                }
            }
        } catch (error: Exception) {
            output.delete()
            if (isCurrent(requestGeneration)) Log.e(TAG, "Kokoro response failed", error)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun play(
        audioFile: File,
        requestGeneration: Long,
        requestId: String,
        callbackPort: Int
    ) {
        val nextPlayer = synchronized(this) {
            if (requestGeneration != generation) {
                audioFile.delete()
                return
            }
            MediaPlayer().also {
                player = it
                playingFile = audioFile
            }
        }
        nextPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        nextPlayer.setOnCompletionListener {
            releasePlayer(nextPlayer, audioFile)
            completeSpeech(requestGeneration, requestId, callbackPort)
        }
        nextPlayer.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Kokoro playback failed: $what/$extra")
            releasePlayer(nextPlayer, audioFile)
            failPlayback(requestGeneration, requestId, callbackPort)
            true
        }
        try {
            nextPlayer.setDataSource(audioFile.absolutePath)
            nextPlayer.prepare()
            nextPlayer.start()
        } catch (error: Exception) {
            releasePlayer(nextPlayer, audioFile)
            Log.e(TAG, "Kokoro playback setup failed", error)
            failPlayback(requestGeneration, requestId, callbackPort)
        }
    }

    private fun completeSpeech(requestGeneration: Long, requestId: String, callbackPort: Int) {
        executor.submit {
            if (!isCurrent(requestGeneration)) return@submit
            sendCallback(callbackPort, requestId, "done")
            if (isCurrent(requestGeneration)) {
                Log.i(TAG, "Completed Kokoro speech")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun failPlayback(requestGeneration: Long, requestId: String, callbackPort: Int) {
        executor.submit {
            if (!isCurrent(requestGeneration)) return@submit
            sendCallback(callbackPort, requestId, "error")
            if (isCurrent(requestGeneration)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun sendCallback(callbackPort: Int, requestId: String, event: String) {
        val encodedRequestId = URLEncoder.encode(requestId, StandardCharsets.UTF_8.name())
        val connection = try {
            URL("http://127.0.0.1:$callbackPort/?request_id=$encodedRequestId&event=$event")
                .openConnection() as HttpURLConnection
        } catch (error: Exception) {
            Log.w(TAG, "Could not create KOReader callback", error)
            return
        }
        try {
            connection.connectTimeout = CALLBACK_TIMEOUT_MILLIS
            connection.readTimeout = CALLBACK_TIMEOUT_MILLIS
            connection.responseCode
        } catch (error: Exception) {
            Log.w(TAG, "Could not notify KOReader: $event", error)
        } finally {
            connection.disconnect()
        }
    }

    private fun releasePlayer(releasedPlayer: MediaPlayer, audioFile: File) {
        synchronized(this) {
            if (player === releasedPlayer) {
                player = null
                playingFile = null
            }
        }
        releasedPlayer.release()
        audioFile.delete()
    }

    @Synchronized
    private fun isCurrent(requestGeneration: Long): Boolean =
        requestGeneration == generation && !Thread.currentThread().isInterrupted

    private fun stopSpeech() {
        synchronized(this) {
            generation++
            stopLocked()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Stopped speech")
    }

    private fun stopLocked() {
        synthesis?.cancel(true)
        synthesis = null
        player?.release()
        player = null
        playingFile?.delete()
        playingFile = null
    }

    private fun playbackNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Narrating with Kokoro")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

    private fun voiceFor(languageTag: String): String? = when (
        Locale.forLanguageTag(languageTag).language.lowercase(Locale.ROOT)
    ) {
        "en" -> "af_heart"
        "fr" -> "ff_siwis"
        "ja" -> "jf_alpha"
        else -> null
    }

    companion object {
        const val ACTION_SPEAK = "dev.fmguerreiro.koreader.tts.SPEAK"
        const val ACTION_STOP = "dev.fmguerreiro.koreader.tts.STOP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_CALLBACK_PORT = "callback_port"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 120_000
        private const val CALLBACK_TIMEOUT_MILLIS = 2_000
    }
}
