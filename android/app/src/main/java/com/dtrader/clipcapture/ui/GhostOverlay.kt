package com.dtrader.clipcapture.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.dtrader.clipcapture.vision.FaceIdx
import com.dtrader.clipcapture.vision.FrameFeatures
import com.dtrader.clipcapture.vision.PoseIdx

/** MediaPipe Face Mesh silhouette (face oval) indices, in order. */
private val FACE_OVAL = intArrayOf(
    10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
    152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
)
private const val CHIN = 152
private const val LEFT_ELBOW = 13
private const val RIGHT_ELBOW = 14

/**
 * Draws the neutral pose as a thick coloured outline (face, shoulders, arms) and
 * the live pose as a thin white one, over a FIT_CENTER preview. The user moves
 * so the white lines sit on the coloured ghost; the colour turns green when the
 * pose matches.
 */
@Composable
fun GhostOverlay(
    reference: FrameFeatures?,
    live: FrameFeatures?,
    color: Color,
    mirror: Boolean,
    minVisibility: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val ref = reference ?: return@Canvas
        val w = ref.width.toFloat()
        val h = ref.height.toFloat()
        if (w <= 0f || h <= 0f) return@Canvas
        // Same fit as PreviewView FIT_CENTER (analysis and preview are both 16:9).
        val s = minOf(size.width / w, size.height / h)
        val ox = (size.width - w * s) / 2
        val oy = (size.height - h * s) / 2
        val map = { x: Float, y: Float -> Offset(ox + (if (mirror) 1f - x else x) * w * s, oy + y * h * s) }

        drawPose(ref, map, color.copy(alpha = 0.9f), 6f * density, minVisibility)
        live?.let { drawPose(it, map, Color.White.copy(alpha = 0.85f), 2.2f * density, minVisibility) }
    }
}

private fun DrawScope.drawPose(f: FrameFeatures, map: (Float, Float) -> Offset, color: Color, width: Float, minVis: Float) {
    val stroke = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
    f.face?.let { face ->
        val path = Path()
        FACE_OVAL.forEachIndexed { i, idx ->
            val p = map(face[idx * 2], face[idx * 2 + 1])
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        drawPath(path, color, style = stroke)
        listOf(FaceIdx.LEFT_EYE_OUTER, FaceIdx.RIGHT_EYE_OUTER, 1).forEach { idx ->
            drawCircle(color, radius = width * 1.1f, center = map(face[idx * 2], face[idx * 2 + 1]))
        }
    }
    val pose = f.pose ?: return
    fun pt(i: Int): Offset? = if (pose[i * 3 + 2] >= minVis) map(pose[i * 3], pose[i * 3 + 1]) else null
    val ls = pt(PoseIdx.LEFT_SHOULDER)
    val rs = pt(PoseIdx.RIGHT_SHOULDER)
    if (ls != null && rs != null) {
        drawLine(color, ls, rs, width, StrokeCap.Round)
        f.face?.let { face ->
            val mid = Offset((ls.x + rs.x) / 2, (ls.y + rs.y) / 2)
            drawLine(color, mid, map(face[CHIN * 2], face[CHIN * 2 + 1]), width * 0.7f, StrokeCap.Round)
        }
    }
    listOf(
        Triple(PoseIdx.LEFT_SHOULDER, LEFT_ELBOW, PoseIdx.LEFT_WRIST),
        Triple(PoseIdx.RIGHT_SHOULDER, RIGHT_ELBOW, PoseIdx.RIGHT_WRIST),
    ).forEach { (a, b, c) ->
        val pa = pt(a); val pb = pt(b); val pc = pt(c)
        if (pa != null && pb != null) drawLine(color, pa, pb, width, StrokeCap.Round)
        if (pb != null && pc != null) drawLine(color, pb, pc, width, StrokeCap.Round)
        if (pc != null) drawCircle(color, radius = width * 1.4f, center = pc)
    }
}
