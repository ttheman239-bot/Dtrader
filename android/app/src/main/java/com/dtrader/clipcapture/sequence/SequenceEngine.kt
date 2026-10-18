package com.dtrader.clipcapture.sequence

import android.content.Context
import android.net.Uri
import android.view.TextureView
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dtrader.clipcapture.lipsync.LipSync
import com.dtrader.clipcapture.lipsync.MouthOverlayView
import java.io.File

/**
 * Endless playback of a session's clips (§4.6) with two ExoPlayers on stacked
 * TextureViews: while one plays, the next clip chosen by [SequencePlanner] is
 * prepared on the other, and at the end of the clip the top view fades in
 * over the step's crossfade length. Each player has a mouth overlay driven by
 * [lipSync] while speech plays. Call [tick] once per display frame.
 */
class SequenceEngine(
    context: Context,
    private val graph: ClipGraph,
    private val planner: SequencePlanner,
    /** Tag to prefer for the next clip, read at every cut ("active" while speaking). */
    private val preferTag: () -> String?,
    private val onCut: (Cut) -> Unit,
    /** Current lip-sync, or null to show the clips' own mouths. */
    private val lipSync: () -> LipSync? = { null },
) {
    data class Cut(val from: Int?, val to: Int, val step: SequencePlanner.Step?)

    val view = FrameLayout(context)
    /** One slot per player: the video plus its mouth overlay, faded together. */
    private val views = Array(2) { FrameLayout(context) }
    private val overlays = Array(2) { MouthOverlayView(context) }
    private val slotClip = IntArray(2) { -1 }
    private val players = Array(2) { ExoPlayer.Builder(context).build().apply { volume = 0f; repeatMode = Player.REPEAT_MODE_OFF } }
    private val fps = graph.config.fps

    private var front = 0
    private var current = -1
    private var pending: SequencePlanner.Step? = null
    private var fadeStartedAt = -1L
    private var paused = false

    init {
        val match = { FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
        views.forEachIndexed { k, slot ->
            val tv = TextureView(context)
            slot.addView(tv, match())
            slot.addView(overlays[k], match())
            view.addView(slot, match())
            players[k].setVideoTextureView(tv)
        }
    }

    fun start() {
        val first = planner.first(preferTag())
        load(front, first)
        players[front].playWhenReady = true
        views[front].alpha = 1f
        views[1 - front].alpha = 0f
        current = first
        onCut(Cut(null, first, null))
    }

    fun setPaused(p: Boolean) {
        paused = p
        players.forEach { it.playWhenReady = false }
        if (!p) {
            players[front].playWhenReady = true
            if (fadeStartedAt >= 0) players[1 - front].playWhenReady = true
        }
    }

    fun tick(nowMs: Long) {
        if (current < 0 || paused) return
        updateMouths(nowMs)
        val a = players[front]
        val back = 1 - front
        val b = players[back]
        val dur = a.duration
        if (dur == C.TIME_UNSET || dur <= 0) return

        if (pending == null) {
            pending = planner.next(current, preferTag()).also { load(back, it.clip) }
        }
        val step = pending!!
        val fadeMs = step.crossfadeFrames * 1000L / fps
        if (fadeStartedAt < 0 && (a.currentPosition >= dur - fadeMs || a.playbackState == Player.STATE_ENDED)) {
            if (b.playbackState != Player.STATE_READY) return // wait for the next clip rather than cut to black
            views[back].bringToFront()
            views[back].alpha = 0f
            b.playWhenReady = true
            fadeStartedAt = nowMs
        }
        if (fadeStartedAt >= 0) {
            val t = if (fadeMs <= 0) 1f else ((nowMs - fadeStartedAt).toFloat() / fadeMs).coerceIn(0f, 1f)
            views[back].alpha = t
            if (t >= 1f) {
                a.playWhenReady = false
                val from = current
                front = back
                current = step.clip
                pending = null
                fadeStartedAt = -1
                onCut(Cut(from, current, step))
            }
        }
    }

    private fun updateMouths(nowMs: Long) {
        val lip = lipSync()
        lip?.update()
        for (k in 0..1) {
            val visible = k == front || fadeStartedAt >= 0
            overlays[k].draw = if (lip != null && visible && slotClip[k] >= 0) lip.frame(slotClip[k], players[k].currentPosition, nowMs) else null
        }
    }

    private fun load(slot: Int, clip: Int) {
        slotClip[slot] = clip
        val p = players[slot]
        p.playWhenReady = false
        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(graph.clips[clip].clipPath!!))))
        p.prepare()
        p.seekTo(0)
    }

    fun release() {
        players.forEach { it.release() }
    }
}
