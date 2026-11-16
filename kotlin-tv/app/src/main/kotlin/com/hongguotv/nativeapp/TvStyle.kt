// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// TV 界面的公共颜色及背景样式；调整公共外观时优先查看此处。

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Shared native colors and shapes. Focus motion lives in [TvMotion]. */
object TvStyle {
    data class Palette(
        val id: String, val label: String, val background: Int, val surface: Int,
        val raised: Int, val accent: Int, val text: Int, val muted: Int, val outline: Int,
    )
    private fun palette(id: String, label: String, vararg colors: String) =
        colors.map { Color.parseColor(it) }.let {
            Palette(id, label, it[0], it[1], it[2], it[3], it[4], it[5], it[6])
        }
    val themes = listOf(
        palette("dark", "影院黑", "#0A0A0E", "#16161D", "#24242E", "#FF6B35", "#F5F5F7", "#9A9AA8", "#2E2E3A"),
        palette("light", "纯净白", "#F3F5F8", "#FFFFFF", "#E1E7F0", "#B83226", "#172033", "#526078", "#BCC6D5"),
        palette("sand", "暖米色", "#F4EFE5", "#FFFBF2", "#E8DDC9", "#984020", "#332A20", "#6D604E", "#C7B89D"),
        palette("forest", "森林绿", "#101E1A", "#192D26", "#2A4539", "#88D5A7", "#EFF8F1", "#AAC1B2", "#4B6858"),
        palette("ocean", "深海蓝", "#101B2D", "#192B43", "#2A405E", "#87C7FF", "#F0F6FF", "#ADBDD3", "#49617F"),
    )
    var current: Palette = themes.first()
        private set
    fun load(context: Context) {
        val id = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", "dark")
        current = themes.firstOrNull { it.id == id } ?: themes.first()
    }
    fun select(context: Context, palette: Palette) {
        current = palette
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", palette.id).apply()
    }
    val background get() = current.background
    val surface get() = current.surface
    val raised get() = current.raised
    val accent get() = current.accent
    val text get() = current.text
    val muted get() = current.muted
    val outline get() = current.outline
    const val POSTER_ASPECT = .7f

    /** 红果 brand gradient (orange to mint) taken from the launcher icon. */
    val BRAND_START = Color.parseColor("#FF6B35")
    val BRAND_END = Color.parseColor("#3EE8C8")

    fun brandGradient(context: Context, radius: Int = 999) =
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(BRAND_START, Color.parseColor("#FF8A4C"))).apply {
            cornerRadius = radius * context.resources.displayMetrics.density
        }

    fun brandBar(context: Context) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BRAND_START, BRAND_END)).apply {
            cornerRadius = 2 * context.resources.displayMetrics.density
        }

    /** Black gradient used to keep text readable on top of artwork. */
    fun scrim(orientation: GradientDrawable.Orientation, alpha: Int = 0xE6, base: Int = background) =
        GradientDrawable(orientation, intArrayOf(
            Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)),
            Color.argb(alpha * 2 / 3, Color.red(base), Color.green(base), Color.blue(base)),
            Color.TRANSPARENT,
        ))

    /** Focus outline drawn above artwork; transparent when not focused. */
    fun focusRing(context: Context, focused: Boolean, radius: Int = 12) =
        GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius * context.resources.displayMetrics.density
            setStroke((3 * context.resources.displayMetrics.density).toInt(), if (focused) Color.WHITE else Color.TRANSPARENT)
        }

    fun shape(context: Context, color: Int, border: Int = Color.TRANSPARENT, radius: Int = 12) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius * context.resources.displayMetrics.density
            setStroke((2 * context.resources.displayMetrics.density).toInt(), border)
        }

    fun card(context: Context, focused: Boolean, selected: Boolean = false) =
        GradientDrawable().apply {
            setColor(if (focused) raised else if (selected) raised else surface)
            cornerRadius = 12 * context.resources.displayMetrics.density
            setStroke(maxOf(1, (2 * context.resources.displayMetrics.density).toInt()),
                if (focused) text else Color.TRANSPARENT)
        }
}
