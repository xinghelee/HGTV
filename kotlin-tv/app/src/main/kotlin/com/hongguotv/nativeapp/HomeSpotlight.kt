// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.Series

/** Real covers are not clean backdrops: keep source lettering and app text in separate columns. */
internal class HomeSpotlight(
    context: Context,
    series: Series,
    status: String,
    resume: Boolean,
    cover: (ImageView, String) -> Unit,
    open: (Boolean) -> Unit,
) : LinearLayout(context) {
    val play: TextView
    val detail: TextView
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Receives the cover bitmap when a hero action gains focus, for the ambient backdrop. */
    var backdrop: ((android.graphics.Bitmap) -> Unit)? = null
    private val image: ImageView

    private fun feedBackdrop(owner: View, attempt: Int = 0) {
        if (backdrop == null) return
        val bitmap = (image.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        if (bitmap != null) backdrop?.invoke(bitmap)
        else if (attempt < 12) postDelayed({ if (owner.hasFocus()) feedBackdrop(owner, attempt + 1) }, 250)
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(14), dp(20), dp(14), dp(8))
        val info = LinearLayout(context).apply { orientation = VERTICAL; clipChildren = false; clipToPadding = false }
        fun label(value: String, size: Float, color: Int, lines: Int = 1) = TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        info.addView(label(if (resume) "接着上次的故事" else "今日精选", 14f, TvStyle.accent).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = .08f
        })
        info.addView(label(series.title, 30f, TvStyle.text, 2).apply {
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setLineSpacing(0f, 1.1f)
            setShadowLayer(dp(8).toFloat(), 0f, dp(2).toFloat(), 0x99000000.toInt())
        }, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        info.addView(label(status.ifBlank { series.tags }, 15f, TvStyle.muted), LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val actions = LinearLayout(context).apply { clipChildren = false; clipToPadding = false }
        fun action(value: String, primary: Boolean, proceed: () -> Unit) = label(value, 16f, TvStyle.text).apply {
            id = View.generateViewId()
            isFocusable = true
            isClickable = true
            minHeight = dp(46)
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(28), dp(10), dp(28), dp(10))
            fun paint(focus: Boolean) {
                background = when {
                    focus -> TvStyle.shape(context, Color.WHITE, radius = 999)
                    primary -> TvStyle.brandGradient(context)
                    else -> TvStyle.shape(context, 0x29FFFFFF, radius = 999)
                }
                setTextColor(if (focus) TvStyle.background else Color.WHITE)
            }
            paint(false)
            setOnFocusChangeListener { _, focus ->
                paint(focus)
                TvMotion.focus(this, focus, 1.06f)
                if (focus) feedBackdrop(this)
            }
            setOnClickListener { proceed() }
        }
        play = action(if (resume) "▶  继续观看" else "▶  开始观看", true) { open(true) }
        detail = action("查看详情", false) { open(false) }
        play.tag = "home:spotlight:play"
        detail.tag = "home:spotlight:detail"
        actions.addView(play)
        actions.addView(detail, LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        play.nextFocusRightId = detail.id
        detail.nextFocusLeftId = play.id
        info.addView(actions, LayoutParams(-1, -2).apply { topMargin = dp(22) })
        addView(info, LayoutParams(0, -2, 1f))
        image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, 14 * resources.displayMetrics.density)
            }
            clipToOutline = true
            elevation = dp(16).toFloat()
        }
        addView(image, LayoutParams(dp(150), dp(214)).apply { leftMargin = dp(32); rightMargin = dp(24) })
        cover(image, series.cover)
    }
}
