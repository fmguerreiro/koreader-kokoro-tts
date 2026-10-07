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
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val TAG = "KOReaderTtsBridge"
private const val MAX_CHUNK_LENGTH = 300
private const val CHANNEL_ID = "koreader-tts-playback"
private const val NOTIFICATION_ID = 1

class KoreaderTtsService : Service() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var synthesis: Future<*>? = null
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private val audioFiles = ArrayDeque<File>()
    private var synthesisComplete = true
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
                if (text.isNullOrEmpty()) {
                    Log.w(TAG, "Ignored empty speech request")
                    stopSelf(startId)
                } else {
                    startForeground(NOTIFICATION_ID, playbackNotification())
                    speak(text, intent.getStringExtra(EXTRA_LANGUAGE) ?: Locale.US.toLanguageTag())
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
    private fun speak(text: String, languageTag: String) {
        val voice = voiceFor(languageTag)
        if (voice == null) {
            Log.e(TAG, "Unsupported Kokoro language: $languageTag")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        generation++
        stopLocked()
        synthesisComplete = false
        val requestGeneration = generation
        synthesis = executor.submit {
            for (chunk in text.chunkForSpeech()) {
                if (!isCurrent(requestGeneration)) return@submit
                val audioFile = synthesize(
                    BuildConfig.KOKORO_ENDPOINT,
                    voice,
                    chunk,
                    requestGeneration
                ) ?: break
                synchronized(this) {
                    if (requestGeneration != generation) {
                        audioFile.delete()
                        return@submit
                    }
                    audioFiles.addLast(audioFile)
                }
                playNext(requestGeneration)
            }
            synchronized(this) {
                if (requestGeneration == generation) {
                    synthesis = null
                    synthesisComplete = true
                }
            }
            playNext(requestGeneration)
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

    private fun playNext(requestGeneration: Long) {
        val next = synchronized(this) {
            if (requestGeneration != generation || player != null) return
            if (audioFiles.isEmpty()) {
                if (synthesisComplete) finishSpeech(requestGeneration)
                return
            }

            val audioFile = audioFiles.removeFirst()
            val nextPlayer = MediaPlayer()
            player = nextPlayer
            playingFile = audioFile
            nextPlayer to audioFile
        }
        val (nextPlayer, audioFile) = next
        nextPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        nextPlayer.setOnCompletionListener {
            releasePlayer(nextPlayer, audioFile)
            playNext(requestGeneration)
        }
        nextPlayer.setOnErrorListener { _, what, extra ->
            Log.e(TAG, "Kokoro playback failed: $what/$extra")
            releasePlayer(nextPlayer, audioFile)
            playNext(requestGeneration)
            true
        }
        try {
            nextPlayer.setDataSource(audioFile.absolutePath)
            nextPlayer.prepare()
            nextPlayer.start()
        } catch (error: Exception) {
            releasePlayer(nextPlayer, audioFile)
            Log.e(TAG, "Kokoro playback setup failed", error)
            playNext(requestGeneration)
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

    @Synchronized
    private fun finishSpeech(requestGeneration: Long) {
        if (requestGeneration != generation || !synthesisComplete || player != null || audioFiles.isNotEmpty()) return
        Log.i(TAG, "Completed Kokoro speech")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
        synthesisComplete = true
        player?.release()
        player = null
        playingFile?.delete()
        playingFile = null
        audioFiles.forEach(File::delete)
        audioFiles.clear()
    }

    private fun playbackNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Reading current page")
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
        const val ACTION_SPEAK = "io.github.fmguerreiro.koreaderkokoro.SPEAK"
        const val ACTION_STOP = "io.github.fmguerreiro.koreaderkokoro.STOP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANGUAGE = "language"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 120_000
    }
}

private fun String.chunkForSpeech(): List<String> {
    if (length <= MAX_CHUNK_LENGTH) return listOf(this)

    val chunks = mutableListOf<String>()
    var start = 0
    while (start < length) {
        var end = minOf(start + MAX_CHUNK_LENGTH, length)
        if (end < length) {
            while (end > start && !this[end - 1].isWhitespace()) {
                end--
            }
            if (end == start) end = minOf(start + MAX_CHUNK_LENGTH, length)
        }
        if (end < length && this[end - 1].isHighSurrogate() && this[end].isLowSurrogate()) end--
        chunks += substring(start, end)
        start = end
        while (start < length && this[start].isWhitespace()) start++
    }
    return chunks
}
