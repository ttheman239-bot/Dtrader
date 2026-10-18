package com.dtrader.clipcapture.capture

import com.dtrader.clipcapture.config.CaptureConfig
import kotlin.math.abs

/**
 * Finds usable clips inside one continuous recording, live (§4.4).
 *
 * Every time the user holds the neutral pose, that moment becomes a possible
 * cut point. Passing through neutral briefly in the middle of a move does not
 * end anything: when a neutral hold is reached, the segmenter looks back over
 * all earlier cut points and picks a start that makes a clip of
 * clip_min_sec…clip_max_sec (closest to the middle of that range) in which the
 * pose really changed and the live QC passes. The next clip can start where
 * this one ended. Too-short gaps are simply not used — no error is raised.
 *
 * Times are milliseconds from the start of the recording.
 */
class Segmenter(private val cfg: CaptureConfig) {

    data class Frame(
        val tMs: Long,
        val score: Float?,
        val jawOpen: Float,
        val handOverMouth: Boolean,
        val hasFace: Boolean,
        val faceSizeOut: Boolean = false,
        val faceOutOfFrame: Boolean = false,
    )

    sealed class Event {
        data class Found(val startMs: Long, val endMs: Long, val qc: QcSummary, val stats: LiveStats) : Event()
        /** A gap was long enough but no start gave a usable clip; [reasons] says why. */
        data class Rejected(val startMs: Long, val endMs: Long, val reasons: List<String>) : Event()
    }

    private val threshold get() = cfg.poseMatchStart
    private val minMs get() = (cfg.clipMinSec * 1000).toLong()
    private val maxMs get() = (cfg.clipMaxSec * 1000).toLong()

    private val frames = ArrayDeque<Frame>()
    /** Moments the user left a held neutral pose: possible clip starts. */
    private val starts = ArrayList<Long>()

    private var runStart = -1L
    private var runLast = -1L
    private var runUsedAsEnd = false
    private var runEndedClip = false

    /**
     * Start of the longest clip that could still be completed right now, or null.
     * The UI shows `t - collectingSinceMs` as "collected so far".
     */
    fun collectingSince(tMs: Long): Long? = starts.firstOrNull { tMs - it <= maxMs }

    val inNeutral get() = runStart >= 0

    fun feed(f: Frame): Event? {
        frames.addLast(f)
        // Keep only what a clip ending now could still use.
        while (frames.isNotEmpty() && frames.first().tMs < f.tMs - maxMs - 2_000) frames.removeFirst()
        starts.removeAll { f.tMs - it > maxMs }

        val neutral = f.score != null && f.score >= threshold
        var event: Event? = null
        if (neutral) {
            if (runStart < 0) { runStart = f.tMs; runUsedAsEnd = false; runEndedClip = false }
            runLast = f.tMs
            if (!runUsedAsEnd && f.tMs - runStart >= cfg.neutralHoldEndMs) {
                runUsedAsEnd = true
                event = tryClipEndingAt(f.tMs)
                runEndedClip = event is Event.Found
            }
        } else if (runStart >= 0) {
            // Leaving neutral: a long-enough hold, or the hold that just ended a clip, is a new start.
            if (runLast - runStart >= cfg.neutralHoldStartMs || runEndedClip) starts += runLast
            runStart = -1
        }
        return event
    }

    private fun tryClipEndingAt(end: Long): Event? {
        val target = (minMs + maxMs) / 2
        val candidates = starts.filter { end - it in minMs..maxMs }.sortedBy { abs((end - it) - target) }
        if (candidates.isEmpty()) return null // too short so far: keep going, no message
        var firstFailure: Pair<Long, List<String>>? = null
        for (s in candidates) {
            val inside = frames.filter { it.tMs in s..end }
            val reasons = mutableListOf<String>()
            val minScore = inside.mapNotNull { it.score }.minOrNull() ?: 100f
            if (minScore > threshold - cfg.segmentMinMotion) reasons += "ท่าไม่ต่างจากท่ากลางพอ — ขยับให้ชัดขึ้น"
            val stats = statsOf(inside, s)
            val qc = LiveQc.evaluate(stats, (end - s) / 1000f, emptyList(), cfg)
            if (qc.status == QcStatus.RETAKE) reasons += qc.reasons
            if (reasons.isEmpty()) {
                // The clip is used: earlier starts can't be reused for a later clip.
                starts.clear()
                return Event.Found(s, end, qc, stats)
            }
            if (firstFailure == null) firstFailure = s to reasons
        }
        return firstFailure?.let { (s, r) -> Event.Rejected(s, end, r) }
    }

    private fun statsOf(inside: List<Frame>, start: Long): LiveStats {
        val s = LiveStats()
        for (f in inside) {
            s.frames++
            if (!f.hasFace) { s.noFaceFrames++; continue }
            s.jawMax = maxOf(s.jawMax, f.jawOpen)
            s.jawSum += f.jawOpen
            if (f.handOverMouth) {
                val sec = (f.tMs - start) / 1000f
                if (s.handOverMouthFrames == 0) s.handOverMouthFirstSec = sec
                s.handOverMouthLastSec = sec
                s.handOverMouthFrames++
            }
            if (f.faceSizeOut) s.faceSizeOutFrames++
            if (f.faceOutOfFrame) s.faceOutOfFrameFrames++
            f.score?.let { s.scoreMin = minOf(s.scoreMin, it); s.scoreSum += it; s.scoredFrames++ }
        }
        return s
    }
}
