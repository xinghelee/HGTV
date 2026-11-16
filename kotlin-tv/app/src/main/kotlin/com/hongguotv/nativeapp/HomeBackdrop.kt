// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.view.View

/**
 * Ambient backdrop behind the catalog: the focused poster, cropped to the screen's shape and
 * blurred by down-sampling (works on every API level, no RenderScript or RenderEffect), with
 * dark scrims so text stays readable. Changes crossfade and are debounced while the D-pad moves.
 */
internal class HomeBackdrop(private val target: View) {
    private var current: Drawable? = null
    private var source: Bitmap? = null
    private var pending: Runnable? = null

    fun show(bitmap: Bitmap?) {
        if (bitmap == null || bitmap === source || bitmap.isRecycled) return
        pending?.let(target::removeCallbacks)
        pending = Runnable {
            pending = null
            if (bitmap.isRecycled) return@Runnable
            source = bitmap
            val next = compose(target.resources, blur(bitmap))
            val previous = current ?: ColorDrawable(TvStyle.background)
            target.background = TransitionDrawable(arrayOf(previous, next)).apply {
                isCrossFadeEnabled = true
                startTransition(320)
            }
            current = next
        }.also { target.postDelayed(it, 140) }
    }

    fun clear() {
        pending?.let(target::removeCallbacks)
        pending = null
        current = null
        source = null
        target.setBackgroundColor(TvStyle.background)
    }

    private fun blur(bitmap: Bitmap): Bitmap {
        // Posters are portrait; take a 16:9 band from the upper-middle where faces usually sit.
        val width = bitmap.width
        val band = (width * 9 / 16).coerceAtMost(bitmap.height)
        val top = ((bitmap.height - band) * 0.3f).toInt().coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(bitmap, 0, top, width, band)
        // Two bilinear down-steps give a smooth result once stretched back to full screen.
        val mid = Bitmap.createScaledBitmap(cropped, 96, 54, true)
        val small = Bitmap.createScaledBitmap(mid, 32, 18, true)
        if (cropped !== bitmap) cropped.recycle()
        mid.recycle()
        return small
    }

    private fun compose(resources: Resources, blurred: Bitmap): Drawable {
        val base = TvStyle.background
        fun tone(alpha: Int) = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
        return LayerDrawable(arrayOf(
            BitmapDrawable(resources, blurred).apply { isFilterBitmap = true },
            ColorDrawable(tone(0x8C)),
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(tone(0xF2), tone(0x99), tone(0x33))),
            GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(tone(0xF2), tone(0x80), Color.TRANSPARENT)),
        ))
    }
}
