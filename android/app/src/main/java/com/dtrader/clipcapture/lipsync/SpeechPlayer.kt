package com.dtrader.clipcapture.lipsync

import android.content.Context
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-to-speech rendered to WAV first, so the mouth can follow the actual
 * loudness of the voice while it plays.
 */
class SpeechPlayer(context: Context, private val onStatus: (String, Boolean) -> Unit) {
    class Line(val text: String, val wav: File, val envelope: FloatArray)

    private val dir = File(context.cacheDir, "tts").apply { mkdirs() }
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private var tts: TextToSpeech? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var envelope: FloatArray? = null
    @Volatile var speaking = false
        private set
    @Volatile var line: String? = null
        private set
    var ready = false
        private set

    init {
        var t: TextToSpeech? = null
        t = TextToSpeech(context.applicationContext) { status ->
            val e = t ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) { onStatus("เปิดระบบเสียงพูด (TTS) ของเครื่องไม่ได้", false); return@TextToSpeech }
            val r = e.setLanguage(Locale("th", "TH"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                onStatus("เครื่องไม่มีเสียงภาษาไทย — ติดตั้ง/เลือก \"Google Speech Services\" และดาวน์โหลดภาษาไทยในการตั้งค่า Text-to-speech", false)
                return@TextToSpeech
            }
            e.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) { pending.remove(id)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { pending.remove(id)?.completeExceptionally(IllegalStateException("TTS สร้างเสียงไม่สำเร็จ")) }
                override fun onError(id: String, errorCode: Int) { pending.remove(id)?.completeExceptionally(IllegalStateException("TTS สร้างเสียงไม่สำเร็จ (code $errorCode)")) }
            })
            ready = true
            onStatus("พร้อมพูด (${e.defaultEngine})", true)
        }
        tts = t
    }

    /** Renders each line to WAV and measures its loudness over time. */
    suspend fun prepare(lines: List<String>): List<Line> {
        val t = tts ?: error("TTS ยังไม่พร้อม")
        check(ready) { "TTS ยังไม่พร้อม" }
        return lines.mapIndexed { k, text ->
            val id = "synth-${System.nanoTime()}-$k"
            val wav = File(dir, "line_$k.wav")
            val done = CompletableDeferred<Unit>().also { pending[id] = it }
            check(t.synthesizeToFile(text, null, wav, id) == TextToSpeech.SUCCESS) { "TTS รับข้อความไม่ได้" }
            done.await()
            val pcm = withContext(Dispatchers.IO) { Pcm.parseWav(wav.readBytes()) }
            Line(text, wav, Envelope.of(pcm))
        }
    }

    /** Plays the lines in order with [gapMs] of silence between them. Cancel the caller to stop. */
    suspend fun play(lines: List<Line>, gapMs: Long) {
        try {
            for (l in lines) {
                val mp = MediaPlayer().apply { setDataSource(l.wav.path); prepare() }
                envelope = l.envelope
                line = l.text
                player = mp
                speaking = true
                mp.start()
                while (mp.isPlaying) delay(20)
                speaking = false
                player = null
                mp.release()
                line = null
                delay(gapMs)
            }
        } finally {
            stopPlayback()
        }
    }

    /** Mouth openness 0–1 right now, from the playing line's envelope. */
    fun level(): Float {
        val mp = player ?: return 0f
        val env = envelope ?: return 0f
        val pos = runCatching { mp.currentPosition }.getOrDefault(0)
        return env.getOrElse(pos / 40) { 0f }
    }

    private fun stopPlayback() {
        speaking = false
        line = null
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    fun release() {
        stopPlayback()
        tts?.stop(); tts?.shutdown(); tts = null
    }
}
