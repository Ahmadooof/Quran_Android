package com.readqurantoday.quran

import android.graphics.Path
import android.graphics.RectF

/** Sets this path to a cartouche [half] either side of [centre], [tall] high about [mid]: rounded corners, a point at each end. */
fun Path.cartouche(centre: Float, mid: Float, half: Float, tall: Float, box: RectF) {
    val top = mid - tall / 2f
    val bottom = mid + tall / 2f
    val round = tall * CARTOUCHE_ROUND
    val point = round * CARTOUCHE_POINT
    val left = centre - half
    val right = centre + half
    reset()
    moveTo(left + round, top)
    lineTo(right - round, top)
    box.set(right - round * 2f, top, right, top + round * 2f)
    arcTo(box, 270f, 90f)
    lineTo(right + point, mid)
    box.set(right - round * 2f, bottom - round * 2f, right, bottom)
    arcTo(box, 0f, 90f)
    lineTo(left + round, bottom)
    box.set(left, bottom - round * 2f, left + round * 2f, bottom)
    arcTo(box, 90f, 90f)
    lineTo(left - point, mid)
    box.set(left, top, left + round * 2f, top + round * 2f)
    arcTo(box, 180f, 90f)
    close()
}

// The corners' radius as a share of the height, and how far each end's point stands out past them
private const val CARTOUCHE_ROUND = 0.21f
private const val CARTOUCHE_POINT = 0.55f
