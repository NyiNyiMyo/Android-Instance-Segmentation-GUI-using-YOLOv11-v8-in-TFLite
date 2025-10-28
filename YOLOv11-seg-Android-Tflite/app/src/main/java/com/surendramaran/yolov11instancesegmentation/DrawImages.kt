package com.surendramaran.yolov11instancesegmentation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.ContextCompat
import android.util.Log
import com.surendramaran.yolov11instancesegmentation.ImageUtils.scaleMask


class DrawImages(private val context: Context) {

    private val boxColors = listOf(
        R.color.overlay_orange,
        R.color.overlay_blue,
        R.color.overlay_green,
        R.color.overlay_red,
        R.color.overlay_pink,
        R.color.overlay_cyan,
        R.color.overlay_purple,
        R.color.overlay_gray,
        R.color.overlay_teal,
        R.color.overlay_yellow,
    )

   //fun invoke(results: List<SegmentationResult>) : Bitmap {
        //val width = results.first().mask[0].size
        //val height = results.first().mask.size
        //val combined = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

fun invoke(results: List<SegmentationResult>?, background: Bitmap?): Bitmap {
    // --- SAFETY CHECKS ---
    val baseBitmap = background ?: run {
        Log.e("Segmentation", "Background bitmap is null, returning fallback.")
        return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    val combined = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)

    try {
        results?.forEach { result ->
            val colorResId = boxColors[result.box.cls % 10]

            val mask = result.mask
            val maskH = mask.size
            val maskW = mask[0].size

            // --- SCALE MASK IF SIZE DOES NOT MATCH FRAME ---
            val scaledMask = if (maskH != baseBitmap.height || maskW != baseBitmap.width) {
                try {
                    mask.scaleMask(baseBitmap.width, baseBitmap.height)
                } catch (e: Exception) {
                    Log.e("Segmentation", "Scaling failed: ${e.message}")
                    mask
                }
            } else mask

            // Draw overlay using scaled mask
            applyTransparentOverlay(context, combined, result.copy(mask = scaledMask), colorResId)
        }
    } catch (e: Exception) {
        Log.e("Segmentation", "Error while overlaying: ${e.message}")
    }

    return combined
}



    private fun applyTransparentOverlay(
    context: Context,
    overlay: Bitmap,
    segmentationResult: SegmentationResult,
    overlayColorResId: Int
) {
    val mask = segmentationResult.mask
    val maskHeight = mask.size
    val maskWidth = mask[0].size

    val overlayWidth = overlay.width
    val overlayHeight = overlay.height

    // Safely scale mask to match the overlay dimensions
        val scaledMask = mask.scaleMask(overlayWidth, overlayHeight)

        val overlayColor = ContextCompat.getColor(context, overlayColorResId)
    val canvas = Canvas(overlay)
    val paint = Paint().apply {
        style = Paint.Style.FILL
    }

    try {
        for (y in 0 until overlayHeight) {
            for (x in 0 until overlayWidth) {
                val maskValue = scaledMask[y][x]
                if (maskValue > 0.5f) {
                    paint.color = applyTransparentOverlayColor(overlayColor)
                    canvas.drawPoint(x.toFloat(), y.toFloat(), paint)
                }
            }
        }
    } catch (e: Exception) {
        Log.e("Segmentation", "Error while overlaying: ${e.message}")
    }

    // Draw bounding box and label
    val boxPaint = Paint().apply {
        color = overlayColor
        strokeWidth = 2F
        style = Paint.Style.STROKE
    }

    val box = segmentationResult.box
    val left = (box.x1 * overlayWidth).toInt()
    val top = (box.y1 * overlayHeight).toInt()
    val right = (box.x2 * overlayWidth).toInt()
    val bottom = (box.y2 * overlayHeight).toInt()

    canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), boxPaint)

    val textBgPaint = Paint().apply {
        color = overlayColor
        style = Paint.Style.FILL
    }

    val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 16f
        style = Paint.Style.FILL
    }

    val bounds = android.graphics.Rect()
    textPaint.getTextBounds(box.clsName, 0, box.clsName.length, bounds)
    val padding = 2
    val textWidth = bounds.width()
    val textHeight = bounds.height()

    canvas.drawRect(
        left.toFloat(),
        top.toFloat() - textHeight - 2 * padding,
        left + textWidth + 2 * padding.toFloat(),
        top.toFloat(),
        textBgPaint
    )
    canvas.drawText(box.clsName, left + padding.toFloat(), top - padding.toFloat(), textPaint)
}

    private fun applyTransparentOverlayColor(color: Int): Int {
        val alpha = 150 // 0-100 100-255
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)

        return Color.argb(alpha, red, green, blue)
    }
}