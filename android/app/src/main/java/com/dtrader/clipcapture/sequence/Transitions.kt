package com.dtrader.clipcapture.sequence

import com.dtrader.clipcapture.config.CaptureConfig
import com.dtrader.clipcapture.vision.FrameFeatures
import com.dtrader.clipcapture.vision.PoseMatcher

/**
 * Cost of cutting from the last frame of clip i to the first frame of clip j
 * (§4.6): `1 − PoseMatcher score / 100`, so 0 = identical pose, 1 = nothing alike.
 * NaN means it couldn't be measured (no face in one of the frames).
 */
class TransitionMatrix(val costs: Array<FloatArray>, private val cfg: CaptureConfig) {
    val size get() = costs.size

    fun cost(from: Int, to: Int): Float = costs[from][to]

    fun allowed(from: Int, to: Int): Boolean = cost(from, to).let { !it.isNaN() && it <= cfg.transitionCostReject }

    /**
     * Crossfade length for a cut: the reject threshold split in thirds maps to
     * crossfade_frames low / mid / max.
     */
    fun crossfadeFrames(from: Int, to: Int): Int {
        val c = cost(from, to)
        val r = cfg.transitionCostReject
        return when {
            c.isNaN() || c > r * 2 / 3 -> cfg.crossfadeMax
            c > r / 3 -> cfg.crossfadeMid
            else -> cfg.crossfadeLow
        }
    }

    /** Clips with no allowed cut to any other clip. */
    fun deadEnds(): List<Int> = (0 until size).filter { i -> (0 until size).none { j -> j != i && allowed(i, j) } }

    /** Clips no other clip can cut to. */
    fun unreachable(): List<Int> = (0 until size).filter { j -> (0 until size).none { i -> i != j && allowed(i, j) } }

    companion object {
        fun compute(ends: List<FrameFeatures?>, starts: List<FrameFeatures?>, cfg: CaptureConfig): TransitionMatrix {
            require(ends.size == starts.size)
            val n = ends.size
            val costs = Array(n) { i ->
                val matcher = ends[i]?.takeIf { it.hasFace }?.let { runCatching { PoseMatcher(it, cfg) }.getOrNull() }
                FloatArray(n) { j ->
                    val score = starts[j]?.let { matcher?.match(it)?.score }
                    if (score == null) Float.NaN else (1f - score / 100f).coerceIn(0f, 1f)
                }
            }
            return TransitionMatrix(costs, cfg)
        }
    }
}
