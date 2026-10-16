package com.dtrader.clipcapture.capture

import com.dtrader.clipcapture.config.CaptureConfig

/**
 * Finds usable clips inside one continuous recording (§4.4), live, from the
 * analysed frames. A clip runs from the moment the user *leaves* a neutral
 * pose they had held for `neutral_hold_start_ms`, to the moment they are back
 * in neutral for `neutral_hold_end_ms`, provided that:
 *  - its length is within clip_min_sec … clip_max_sec,
 *  - the pose actually changed in between (score dipped `segment_min_motion`
 *    below the neutral threshold), and
 *  - the live QC (face always found, mouth not open, no hand over mouth) passes.
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
        data class Rejected(val startMs: Long, val endMs: Long, val reasons: List<String>) : Event()
    }

    private val threshold get() = cfg.poseMatchStart

    private var runStart = -1L
    private var runQualified = false
    private var endChecked = false
    private var lastNeutralT = -1L

    /** Start of the clip being collected, or null while waiting for a neutral hold. */
    var anchorMs: Long? = null
        private set
    private var minScore = 100f
    private var stats = LiveStats()

    fun feed(f: Frame): Event? {
        val neutral = f.score != null && f.score >= threshold
        var event: Event? = null

        if (anchorMs != null) {
            collect(f)
            if (f.tMs - anchorMs!! > cfg.clipMaxSec * 1000) {
                // Took too long to come back to neutral; wait for the next neutral hold.
                anchorMs = null
            }
        }

        if (neutral) {
            if (runStart < 0) runStart = f.tMs
            val held = f.tMs - runStart
            val a = anchorMs
            if (a != null && !endChecked && held >= cfg.neutralHoldEndMs) {
                endChecked = true
                event = evaluate(a, f.tMs)
                anchorMs = null
            }
            if (held >= cfg.neutralHoldStartMs) runQualified = true
            lastNeutralT = f.tMs
        } else if (runStart >= 0) {
            // Leaving neutral: a long-enough neutral hold becomes the next clip start.
            if (runQualified) {
                anchorMs = lastNeutralT
                minScore = 100f
                stats = LiveStats()
            }
            runStart = -1
            runQualified = false
            endChecked = false
        }
        return event
    }

    private fun collect(f: Frame) {
        val s = stats
        s.frames++
        if (!f.hasFace) { s.noFaceFrames++; return }
        s.jawMax = maxOf(s.jawMax, f.jawOpen)
        s.jawSum += f.jawOpen
        if (f.handOverMouth) {
            val sec = (f.tMs - (anchorMs ?: f.tMs)) / 1000f
            if (s.handOverMouthFrames == 0) s.handOverMouthFirstSec = sec
            s.handOverMouthLastSec = sec
            s.handOverMouthFrames++
        }
        if (f.faceSizeOut) s.faceSizeOutFrames++
        if (f.faceOutOfFrame) s.faceOutOfFrameFrames++
        f.score?.let {
            minScore = minOf(minScore, it)
            s.scoreMin = minOf(s.scoreMin, it)
            s.scoreSum += it
            s.scoredFrames++
        }
    }

    private fun evaluate(start: Long, end: Long): Event {
        val durationSec = (end - start) / 1000f
        val reasons = mutableListOf<String>()
        if (durationSec < cfg.clipMinSec) reasons += "สั้นเกินไป (%.1f วิ < %.0f)".format(durationSec, cfg.clipMinSec)
        if (minScore > threshold - cfg.segmentMinMotion) reasons += "ท่าไม่ต่างจากท่ากลางพอ"
        if (reasons.isNotEmpty()) return Event.Rejected(start, end, reasons)
        val qc = LiveQc.evaluate(stats, durationSec, emptyList(), cfg)
        return if (qc.status == QcStatus.RETAKE) Event.Rejected(start, end, qc.reasons)
        else Event.Found(start, end, qc, stats)
    }
}
