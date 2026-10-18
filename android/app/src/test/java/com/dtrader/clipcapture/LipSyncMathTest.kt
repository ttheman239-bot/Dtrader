package com.dtrader.clipcapture

import com.dtrader.clipcapture.lipsync.Envelope
import com.dtrader.clipcapture.lipsync.MouthPicker
import com.dtrader.clipcapture.lipsync.MouthTrack
import com.dtrader.clipcapture.lipsync.Pcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class LipSyncMathTest {

    private fun wav(samples: ShortArray, rate: Int = 16000): ByteArray {
        val b = ByteBuffer.allocate(44 + samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + samples.size * 2); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(rate); b.putInt(rate * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(samples.size * 2)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    @Test fun parsesPcm16Wav() {
        val pcm = Pcm.parseWav(wav(ShortArray(16000) { (it % 100).toShort() }))
        assertEquals(16000, pcm.sampleRate)
        assertEquals(1, pcm.channels)
        assertEquals(1000L, pcm.durationMs)
        assertEquals(42.toShort(), pcm.samples[42])
    }

    @Test fun envelopeIsClosedInSilenceAndOpenWhenLoud() {
        // 0.5 s silence, 0.5 s tone, 0.5 s silence.
        val s = ShortArray(24000) { i -> if (i in 8000 until 16000) (12000 * sin(i * 0.2)).toInt().toShort() else 0 }
        val env = Envelope.of(Pcm.parseWav(wav(s)))
        assertEquals(38, env.size)
        assertEquals(0f, env[5], 0.001f)
        assertTrue("open ${env[20]}", env[20] > 0.8f)
        assertTrue("released ${env[37]}", env[37] < 0.05f)
    }

    @Test fun trackInterpolatesAndGivesUpOnGaps() {
        val t = MouthTrack(
            longArrayOf(0, 80, 160, 1000),
            arrayOf(FloatArray(6) { 0f }, FloatArray(6) { 1f }, null, FloatArray(6) { 5f }),
        )
        assertArrayEquals(FloatArray(6) { 0.5f }, t.at(40)!!, 0.001f)
        assertArrayEquals(FloatArray(6) { 1f }, t.at(100)!!, 0.001f) // within reach of frame 80
        assertNull(t.at(600))
    }

    @Test fun pickerFollowsLevel() {
        val p = MouthPicker(floatArrayOf(0.0f, 0.1f, 0.3f, 0.5f))
        assertEquals(0, p.pick(0f, 0))
        assertEquals(3, p.pick(1f, 0))
        assertEquals(2, p.pick(0.6f, 0))
    }
}
