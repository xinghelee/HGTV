// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.view.View
import android.view.animation.DecelerateInterpolator

/** Focus lift for TV cards: scale and elevation only, cheap on low-end TV GPUs. */
internal object TvMotion {
    private val ease = DecelerateInterpolator(1.6f)

    fun focus(view: View, focused: Boolean, scale: Float = 1.08f, duration: Long = 160) {
        val target = if (focused) scale else 1f
        val lift = if (focused) 12 * view.resources.displayMetrics.density else 0f
        view.animate().cancel()
        if (!view.isAttachedToWindow || !view.isLaidOut) {
            view.scaleX = target; view.scaleY = target; view.translationZ = lift
            return
        }
        view.animate().scaleX(target).scaleY(target).translationZ(lift)
            .setDuration(duration).setInterpolator(ease).start()
    }
}
