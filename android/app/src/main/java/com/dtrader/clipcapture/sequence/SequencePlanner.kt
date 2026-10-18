package com.dtrader.clipcapture.sequence

import com.dtrader.clipcapture.config.CaptureConfig
import kotlin.random.Random

/**
 * Picks the next clip for endless playback (§4.6): only cuts the matrix allows,
 * none of the last no_repeat_last_n clips, and clips with [preferTag] first
 * ("active" while speaking, "calm" while silent). Cheaper cuts are more likely.
 */
class SequencePlanner(
    private val matrix: TransitionMatrix,
    private val tags: List<String>,
    private val cfg: CaptureConfig,
    private val random: Random = Random.Default,
) {
    data class Step(
        val clip: Int,
        val cost: Float,
        val crossfadeFrames: Int,
        /** True when no allowed cut existed and the cheapest one was used anyway. */
        val forced: Boolean,
    )

    private val history = ArrayDeque<Int>()

    fun first(preferTag: String?): Int {
        val n = matrix.size
        require(n > 0) { "no clips" }
        val pool = (0 until n).filter { preferTag == null || tags[it] == preferTag }.ifEmpty { (0 until n).toList() }
        return pool[random.nextInt(pool.size)].also { remember(it) }
    }

    fun next(current: Int, preferTag: String?): Step {
        val n = matrix.size
        if (n == 1) return Step(0, matrix.cost(0, 0), matrix.crossfadeFrames(0, 0), !matrix.allowed(0, 0)).also { remember(0) }

        val allowed = (0 until n).filter { it != current && matrix.allowed(current, it) }
        if (allowed.isEmpty()) {
            // Dead end: take the cheapest measurable cut and say so.
            val j = (0 until n).filter { it != current }
                .minByOrNull { matrix.cost(current, it).let { c -> if (c.isNaN()) Float.MAX_VALUE else c } }!!
            return step(current, j, forced = true)
        }
        val recent = history.takeLast(cfg.noRepeatLastN).toSet()
        val fresh = allowed.filter { it !in recent }
        // If every allowed clip was played recently, use the one played longest ago.
        val pool = fresh.ifEmpty { listOf(allowed.minBy { history.lastIndexOf(it) }) }
        val tagged = pool.filter { preferTag != null && tags[it] == preferTag }.ifEmpty { pool }
        return step(current, weighted(current, tagged), forced = false)
    }

    private fun weighted(from: Int, pool: List<Int>): Int {
        val w = pool.map { 1.0 / (0.02 + matrix.cost(from, it)) }
        var r = random.nextDouble() * w.sum()
        for (k in pool.indices) {
            r -= w[k]
            if (r <= 0) return pool[k]
        }
        return pool.last()
    }

    private fun step(from: Int, to: Int, forced: Boolean): Step {
        remember(to)
        return Step(to, matrix.cost(from, to), matrix.crossfadeFrames(from, to), forced)
    }

    private fun remember(i: Int) {
        history.addLast(i)
        while (history.size > 64) history.removeFirst()
    }
}
