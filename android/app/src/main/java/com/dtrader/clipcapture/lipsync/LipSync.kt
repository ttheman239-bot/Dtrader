package com.dtrader.clipcapture.lipsync

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View

/**
 * Puts the person's own mouth (from [MouthLibrary]) on the playing clip, opened
 * as far as the speech is loud right now, placed by the clip's [MouthTrack].
 */
class LipSync(
    private val library: MouthLibrary,
    /** Track per clip index; clips without one keep their own mouth. */
    private val tracks: Map<Int, MouthTrack>,
    private val speech: SpeechPlayer,
) {
    private val picker = MouthPicker(FloatArray(library.samples.size) { library.samples[it].jaw })
    private var alpha = 0f

    /** Call once per display frame before [frame]. */
    fun update() {
        val target = if (speech.speaking) 1f else 0f
        alpha += (target - alpha) * 0.3f
        if (alpha < 0.01f) alpha = 0f
    }

    /** What to draw over [clip] at [positionMs], or null to show the clip as is. */
    fun frame(clip: Int, positionMs: Long, nowMs: Long): MouthOverlayView.Draw? {
        if (alpha <= 0f) return null
        val at = tracks[clip]?.at(positionMs) ?: return null
        val sample = library.samples[picker.pick(speech.level(), (nowMs / 120).toInt())]
        return MouthOverlayView.Draw(sample, at, alpha)
    }
}

/** Draws one mouth sample mapped onto three normalised anchor points of the video. */
class MouthOverlayView(context: Context) : View(context) {
    class Draw(val sample: MouthSample, val anchors: FloatArray, val alpha: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val dst = FloatArray(6)
    var draw: Draw? = null
        set(v) {
            if (field == null && v == null) return
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val d = draw ?: return
        for (k in 0 until 6) dst[k] = d.anchors[k] * (if (k % 2 == 0) width else height)
        // Three point pairs → affine map (scale, rotation, shear for head pitch).
        if (!m.setPolyToPoly(d.sample.anchors, 0, dst, 0, 3)) return
        paint.alpha = (d.alpha * 255).toInt().coerceIn(0, 255)
        canvas.drawBitmap(d.sample.bitmap, m, paint)
    }
}
