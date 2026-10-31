package app.embeddy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.embeddy.conversion.CropRect

/** A rectangle in container pixels: where fitted content is drawn. */
data class FittedRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Where content of [contentW]x[contentH] lands inside a [containerW]x[containerH]
 * box when scaled to fit and centered (ContentScale.Fit / PlayerView FIT).
 * The crop overlay maps touches through this so the rect matches the video,
 * not the letterbox bars. Unknown content size fills the container.
 */
fun fitContentRect(containerW: Float, containerH: Float, contentW: Int, contentH: Int): FittedRect {
    if (contentW <= 0 || contentH <= 0 || containerW <= 0f || containerH <= 0f) {
        return FittedRect(0f, 0f, containerW, containerH)
    }
    val scale = minOf(containerW / contentW, containerH / contentH)
    val w = contentW * scale
    val h = contentH * scale
    return FittedRect((containerW - w) / 2, (containerH - h) / 2, w, h)
}

/** Which part of the crop rect a drag grabbed. */
enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, MOVE }

/**
 * Apply a drag of ([dx], [dy]) — in fractions of the content — to [crop].
 * Corners resize (the opposite corner stays put); MOVE translates the window
 * without resizing it, stopping at the frame edges.
 */
fun dragCrop(crop: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect = when (handle) {
    CropHandle.TOP_LEFT -> crop.copy(left = crop.left + dx, top = crop.top + dy)
    CropHandle.TOP_RIGHT -> crop.copy(right = crop.right + dx, top = crop.top + dy)
    CropHandle.BOTTOM_LEFT -> crop.copy(left = crop.left + dx, bottom = crop.bottom + dy)
    CropHandle.BOTTOM_RIGHT -> crop.copy(right = crop.right + dx, bottom = crop.bottom + dy)
    CropHandle.MOVE -> {
        val w = crop.right - crop.left
        val h = crop.bottom - crop.top
        val l = (crop.left + dx).coerceIn(0f, 1f - w)
        val t = (crop.top + dy).coerceIn(0f, 1f - h)
        CropRect(l, t, l + w, t + h)
    }
}.normalized()

/**
 * Free-form crop window drawn over a fitted video/image preview.
 *
 * Corner handles have a 48dp touch radius; dragging inside the window moves
 * it. The rect is reported as fractions of the content via [onCropChange]
 * while dragging and committed with [onCropCommit] on release.
 */
@Composable
fun CropOverlay(
    crop: CropRect,
    contentWidth: Int,
    contentHeight: Int,
    accessibilityLabel: String,
    onCropChange: (CropRect) -> Unit,
    onCropCommit: (CropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleRadiusPx = with(LocalDensity.current) { 24.dp.toPx() }
    val latestCrop by rememberUpdatedState(crop)
    var activeHandle by remember { mutableStateOf<CropHandle?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val containerW = constraints.maxWidth.toFloat()
        val containerH = constraints.maxHeight.toFloat()
        val fitted = fitContentRect(containerW, containerH, contentWidth, contentHeight)

        fun cropPx(c: CropRect) = Rect(
            fitted.left + c.left * fitted.width,
            fitted.top + c.top * fitted.height,
            fitted.left + c.right * fitted.width,
            fitted.top + c.bottom * fitted.height,
        )

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = accessibilityLabel }
                .pointerInput(contentWidth, contentHeight, containerW, containerH) {
                    detectDragGestures(
                        onDragStart = { start ->
                            val r = cropPx(latestCrop)
                            val corners = mapOf(
                                CropHandle.TOP_LEFT to r.topLeft,
                                CropHandle.TOP_RIGHT to r.topRight,
                                CropHandle.BOTTOM_LEFT to r.bottomLeft,
                                CropHandle.BOTTOM_RIGHT to r.bottomRight,
                            )
                            activeHandle = corners.entries
                                .filter { (it.value - start).getDistance() <= handleRadiusPx * 2 }
                                .minByOrNull { (it.value - start).getDistance() }?.key
                                ?: CropHandle.MOVE.takeIf { r.contains(start) }
                        },
                        onDrag = { change, amount ->
                            val handle = activeHandle ?: return@detectDragGestures
                            change.consume()
                            onCropChange(
                                dragCrop(latestCrop, handle, amount.x / fitted.width, amount.y / fitted.height),
                            )
                        },
                        onDragEnd = {
                            if (activeHandle != null) onCropCommit(latestCrop)
                            activeHandle = null
                        },
                        onDragCancel = {
                            if (activeHandle != null) onCropCommit(latestCrop)
                            activeHandle = null
                        },
                    )
                },
        ) {
            val r = cropPx(crop)
            val shade = Color.Black.copy(alpha = 0.55f)
            // Dim everything outside the crop window (inside the content only).
            drawRect(shade, Offset(fitted.left, fitted.top), Size(fitted.width, r.top - fitted.top))
            drawRect(shade, Offset(fitted.left, r.bottom), Size(fitted.width, fitted.top + fitted.height - r.bottom))
            drawRect(shade, Offset(fitted.left, r.top), Size(r.left - fitted.left, r.height))
            drawRect(shade, Offset(r.right, r.top), Size(fitted.left + fitted.width - r.right, r.height))
            drawRect(Color.White, r.topLeft, r.size, style = Stroke(width = 2.dp.toPx()))
            // Rule-of-thirds guides
            val guide = Color.White.copy(alpha = 0.35f)
            for (k in 1..2) {
                val x = r.left + r.width * k / 3
                val y = r.top + r.height * k / 3
                drawLine(guide, Offset(x, r.top), Offset(x, r.bottom), 1.dp.toPx())
                drawLine(guide, Offset(r.left, y), Offset(r.right, y), 1.dp.toPx())
            }
            listOf(r.topLeft, r.topRight, r.bottomLeft, r.bottomRight).forEach { corner ->
                drawCircle(Color.White, radius = 8.dp.toPx(), center = corner)
            }
        }
    }
}
