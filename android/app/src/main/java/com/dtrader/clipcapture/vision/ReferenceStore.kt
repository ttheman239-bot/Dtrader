package com.dtrader.clipcapture.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File

/** Files for one session: reference frame, its landmarks, and raw clips. */
class SessionFiles(context: Context, sessionId: Long) {
    val dir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions/$sessionId").apply { mkdirs() }
    val referenceImage = File(dir, "reference.png")
    val referenceData = File(dir, "reference.json")
    val rawDir: File = File(dir, "raw").apply { mkdirs() }

    val clipDir: File = File(dir, "clips").apply { mkdirs() }

    /** A continuous recording ("take"). */
    fun take(timestamp: Long) = File(rawDir, "take_$timestamp.mp4")

    fun clip(number: Int) = File(clipDir, "clip_%03d.mp4".format(number))

    fun saveReference(bitmap: Bitmap, features: FrameFeatures) {
        referenceImage.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        referenceData.writeText(features.toJson().toString())
    }

    fun loadReferenceFeatures(): FrameFeatures? =
        referenceData.takeIf { it.exists() }?.let { FrameFeatures.fromJson(JSONObject(it.readText())) }

    fun loadReferenceBitmap(): Bitmap? =
        referenceImage.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
}
