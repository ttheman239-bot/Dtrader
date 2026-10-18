package com.dtrader.clipcapture.lipsync

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/** PCM 16-bit WAV contents. */
class Pcm(val samples: ShortArray, val sampleRate: Int, val channels: Int) {
    val durationMs get() = samples.size * 1000L / (sampleRate.toLong() * channels)

    companion object {
        /** Parses a RIFF/WAVE file; only 16-bit PCM is supported (what Android TTS writes). */
        fun parseWav(bytes: ByteArray): Pcm {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "ไม่ใช่ไฟล์ WAV" }
            var pos = 12
            var rate = 0; var ch = 0; var bits = 0; var format = 0
            var data: ShortArray? = null
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4)
                var size = b.getInt(pos + 4)
                val body = pos + 8
                // Streaming writers may leave the size as 0 or -1; use what's there.
                if (size <= 0 || body + size > bytes.size) size = bytes.size - body
                when (id) {
                    "fmt " -> {
                        format = b.getShort(body).toInt()
                        ch = b.getShort(body + 2).toInt()
                        rate = b.getInt(body + 4)
                        bits = b.getShort(body + 14).toInt()
                    }
                    "data" -> {
                        val n = size / 2
                        data = ShortArray(n) { b.getShort(body + it * 2) }
                    }
                }
                pos = body + size + (size and 1)
            }
            require(format == 1 && bits == 16) { "เสียงไม่ใช่ PCM 16-bit (format $format, $bits bit) — ยังไม่รองรับ" }
            require(rate > 0 && ch > 0) { "WAV ไม่มีข้อมูล fmt" }
            return Pcm(data ?: ShortArray(0), rate, ch)
        }
    }
}

/**
 * How open the mouth should be over time, from the loudness of the speech:
 * RMS per [windowMs], normalised to the loud end of this utterance, gated
 * below [gate], with a fast attack and a slower release so it reads as talking.
 */
object Envelope {
    fun of(pcm: Pcm, windowMs: Int = 40, gate: Float = 0.12f): FloatArray {
        val perWin = (pcm.sampleRate * pcm.channels * windowMs / 1000).coerceAtLeast(1)
        val n = (pcm.samples.size + perWin - 1) / perWin
        val rms = FloatArray(n) { w ->
            var sum = 0.0
            val from = w * perWin
            val to = minOf(pcm.samples.size, from + perWin)
            for (i in from until to) { val v = pcm.samples[i] / 32768.0; sum += v * v }
            if (to > from) sqrt(sum / (to - from)).toFloat() else 0f
        }
        if (n == 0) return rms
        val ref = rms.sorted()[((n - 1) * 0.95).toInt()].takeIf { it > 1e-4f } ?: return FloatArray(n)
        val out = FloatArray(n)
        var level = 0f
        for (i in 0 until n) {
            val x = (rms[i] / ref).coerceIn(0f, 1f)
            val target = if (x < gate) 0f else (x - gate) / (1f - gate)
            level += (target - level) * (if (target > level) 0.75f else 0.35f)
            out[i] = level
        }
        return out
    }
}

/** Per-frame placement anchors of a clip (see [FaceTracker.ANCHORS]), normalised 0–1. */
class MouthTrack(val timesMs: LongArray, val anchors: Array<FloatArray?>) {
    /** Anchors at [tMs], linearly interpolated; null if no face within [maxGapMs]. */
    fun at(tMs: Long, maxGapMs: Long = 200): FloatArray? {
        if (timesMs.isEmpty()) return null
        var hi = timesMs.binarySearch(tMs).let { if (it >= 0) it else -it - 1 }
        if (hi < timesMs.size && timesMs[hi] == tMs) anchors[hi]?.let { return it }
        // Nearest known frames on each side.
        var lo = hi - 1
        while (lo >= 0 && anchors[lo] == null) lo--
        while (hi < timesMs.size && anchors[hi] == null) hi++
        val a = anchors.getOrNull(lo)
        val b = anchors.getOrNull(hi)
        return when {
            a != null && b != null && timesMs[hi] - timesMs[lo] <= maxGapMs * 2 -> {
                val f = ((tMs - timesMs[lo]).toFloat() / (timesMs[hi] - timesMs[lo])).coerceIn(0f, 1f)
                FloatArray(a.size) { a[it] + (b[it] - a[it]) * f }
            }
            a != null && abs(tMs - timesMs[lo]) <= maxGapMs -> a
            b != null && abs(timesMs[hi] - tMs) <= maxGapMs -> b
            else -> null
        }
    }

    companion object {
        /** Three-tap moving average over known frames, to take out landmark jitter. */
        fun smooth(a: Array<FloatArray?>): Array<FloatArray?> = Array(a.size) { i ->
            val c = a[i] ?: return@Array null
            val nb = listOfNotNull(a.getOrNull(i - 1), c, a.getOrNull(i + 1))
            FloatArray(c.size) { k -> nb.map { it[k] }.average().toFloat() }
        }
    }
}

/** Chooses a mouth sample for an openness level; [jaws] are the samples' jawOpen values. */
class MouthPicker(private val jaws: FloatArray) {
    private val order = jaws.indices.sortedBy { jaws[it] }
    private val maxJaw = jaws.maxOrNull() ?: 0f

    /**
     * Sample for [level] in 0–1 (scaled to the widest mouth in the library). Among
     * the samples closest in openness, [variant] rotates so the shape keeps changing.
     */
    fun pick(level: Float, variant: Int): Int {
        require(jaws.isNotEmpty())
        val target = level.coerceIn(0f, 1f) * maxJaw
        val near = order.sortedBy { abs(jaws[it] - target) }.take(3)
        val tol = maxJaw * 0.08f
        val close = near.filter { abs(jaws[it] - jaws[near[0]]) <= tol }
        return close[Math.floorMod(variant, close.size)]
    }
}
