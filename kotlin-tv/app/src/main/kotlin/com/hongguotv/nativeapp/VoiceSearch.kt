// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.sin

/**
 * Voice input through the TV's speech recognition service, driven in-process so the
 * recognizer sees this app's microphone grant. (The system RECOGNIZE_SPEECH screen on
 * Android 11 TVs calls its own service in-process and is rejected for RECORD_AUDIO.)
 * Nothing is recorded by this app; the recognizer returns text.
 */
internal object VoiceSearch {
    const val PERMISSION_REQUEST = 0x5653

    fun available(context: Context): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    fun intent(context: Context): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    /** Recognizers often append sentence punctuation; drop it before searching. */
    fun text(bundle: Bundle?): String? = clean(bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())

    private fun clean(value: String?): String? =
        value?.trim()
            ?.trimEnd('。', '.', '！', '!', '？', '?', '，', ',')
            ?.takeIf { it.isNotBlank() }

    fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听清，请再说一次"
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "连不上语音服务，电视需要能访问 Google"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没有麦克风权限"
        SpeechRecognizer.ERROR_AUDIO -> "麦克风不可用，请检查遥控器连接"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音服务正忙，请稍后再试"
        else -> "语音识别失败（$code）"
    }
}

/**
 * Full-screen listening sheet: a pulsing brand orb that follows the voice level, the live
 * transcript underneath, and a quiet hint. BACK always closes it; after an error the sheet
 * offers 重试 / 用手机输入 / 返回. Watchdogs turn a silent recognizer (e.g. Google's TV
 * service without network access to Google) into a visible error instead of endless animation.
 */
internal class VoiceSearchOverlay(
    context: Context,
    private val onResult: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onPhoneInput: (() -> Unit)? = null,
) : FrameLayout(context) {
    private enum class State { PREPARING, LISTENING, PROCESSING, ERROR, DONE }

    private var state = State.PREPARING
    private var recognizer: SpeechRecognizer? = null
    private var heardSpeech = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val watchdog = Runnable { timeout() }
    private val actions = LinearLayout(context).apply { gravity = Gravity.CENTER; visibility = View.GONE }
    private val actionLabels = mutableListOf<TextView>()
    private var selected = 0
    private val orb = VoiceOrb(context)
    private val status = label(26f, TvStyle.text)
    private val transcript = label(44f, TvStyle.text, bold = true).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
    }
    private val hint = label(17f, TvStyle.muted)

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        background = android.graphics.drawable.ColorDrawable(
            Color.argb(0xFA, Color.red(TvStyle.background), Color.green(TvStyle.background), Color.blue(TvStyle.background)))
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(orb, LinearLayout.LayoutParams(dp(260), dp(260)))
        column.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        column.addView(transcript, LinearLayout.LayoutParams(dp(900), -2).apply { topMargin = dp(18) })
        val choices = listOfNotNull("重试", onPhoneInput?.let { "用手机输入" }, "返回")
        choices.forEach { name ->
            val chip = label(18f, TvStyle.text).apply { text = name; setPadding(dp(28), dp(11), dp(28), dp(11)) }
            actions.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8); rightMargin = dp(8) })
            actionLabels += chip
        }
        column.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(28) })
        addView(column, LayoutParams(-2, -2, Gravity.CENTER))
        addView(hint, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(48) })
    }

    fun start() {
        recognizer?.destroy()
        heardSpeech = false
        transcript.setTextColor(TvStyle.muted)
        transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        transcript.text = "例如：“凡人百世书”"
        show(State.PREPARING, "正在打开麦克风…")
        val created = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
        if (created == null) {
            show(State.ERROR, "这台电视没有可用的语音识别服务")
            return
        }
        recognizer = created
        created.setRecognitionListener(listener)
        runCatching { created.startListening(VoiceSearch.intent(context)) }
            .onFailure { Log.w(TAG, "startListening failed", it); show(State.ERROR, "语音识别启动失败") }
    }

    /** Re-armed on every state change; fires only if the recognizer goes quiet. */
    private fun arm(ms: Long) {
        handler.removeCallbacks(watchdog)
        if (ms > 0) handler.postDelayed(watchdog, ms)
    }

    private fun timeout() {
        val message = when (state) {
            State.PREPARING -> "语音服务没有响应，电视需要能访问 Google"
            State.LISTENING -> if (heardSpeech) "识别超时，请再说一次" else "没有听到声音，请按住遥控器语音键或靠近麦克风"
            State.PROCESSING -> "识别超时，电视需要能访问 Google"
            else -> return
        }
        Log.w(TAG, "watchdog timeout in $state")
        release()
        if (transcript.currentTextColor == TvStyle.muted) transcript.text = ""
        show(State.ERROR, message)
    }

    /** Stops listening and removes the sheet without searching. */
    fun cancel() {
        if (state == State.DONE) return
        state = State.DONE
        release()
        onClose()
    }

    fun handleKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val up = event.action == KeyEvent.ACTION_UP && !event.isCanceled
        when (event.keyCode) {
            // Close on press: while the mic is open some TVs mark the release as canceled.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (down) cancel()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (down && state == State.ERROR) {
                    val step = if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                    select((selected + step).coerceIn(0, actionLabels.lastIndex))
                }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST ->
                if (up) when (state) {
                    State.ERROR -> activate(if (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) 0 else selected)
                    State.LISTENING -> runCatching { recognizer?.stopListening() }
                    else -> Unit
                }
            else -> Unit
        }
        return true
    }

    private fun activate(index: Int) {
        when (actionLabels.getOrNull(index)?.text?.toString()) {
            "重试" -> start()
            "用手机输入" -> { cancel(); onPhoneInput?.invoke() }
            else -> cancel()
        }
    }

    private fun select(index: Int) {
        selected = index
        actionLabels.forEachIndexed { i, chip ->
            val on = i == index
            chip.background = TvStyle.shape(context, if (on) Color.WHITE else TvVisual.QUIET, radius = 999)
            chip.setTextColor(if (on) TvStyle.background else TvStyle.text)
            chip.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    private fun release() {
        handler.removeCallbacks(watchdog)
        orb.level = 0f
        orb.active = false
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun show(next: State, message: String) {
        state = next
        status.text = message
        orb.active = next == State.LISTENING || next == State.PROCESSING
        orb.error = next == State.ERROR
        if (next != State.LISTENING) orb.level = 0f
        actions.visibility = if (next == State.ERROR) View.VISIBLE else View.GONE
        // An empty transcript would still reserve two large lines and push the buttons into the hint.
        transcript.visibility = if (next == State.ERROR && (transcript.text.isNullOrBlank() || transcript.currentTextColor == TvStyle.muted)) View.GONE else View.VISIBLE
        if (next == State.ERROR) select(0)
        hint.text = when (next) {
            State.ERROR -> "← → 选择  ·  确定执行  ·  返回键关闭"
            State.LISTENING -> "说完后稍等片刻，或按确定键结束  ·  按返回键取消"
            else -> "按返回键取消"
        }
        arm(when (next) {
            State.PREPARING -> 6000L
            State.LISTENING -> if (heardSpeech) 15000L else 10000L
            State.PROCESSING -> 10000L
            else -> 0L
        })
    }

    private fun showText(value: String) {
        transcript.setTextColor(TvStyle.text)
        transcript.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
        transcript.text = value
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { if (state != State.DONE) show(State.LISTENING, "请说出想看的剧名") }
        override fun onBeginningOfSpeech() { if (state != State.DONE) { heardSpeech = true; show(State.LISTENING, "正在聆听…") } }
        override fun onRmsChanged(rmsdB: Float) { if (state == State.LISTENING) orb.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() { if (state != State.DONE) show(State.PROCESSING, "正在识别…") }
        override fun onPartialResults(partialResults: Bundle?) {
            if (state == State.DONE) return
            VoiceSearch.text(partialResults)?.let(::showText)
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
        override fun onError(error: Int) {
            if (state == State.DONE) return
            Log.w(TAG, "recognizer error $error")
            release()
            if (transcript.currentTextColor == TvStyle.muted) transcript.text = ""
            show(State.ERROR, VoiceSearch.errorMessage(error))
        }
        override fun onResults(results: Bundle?) {
            if (state == State.DONE) return
            val spoken = VoiceSearch.text(results)
            if (spoken == null) {
                release()
                show(State.ERROR, VoiceSearch.errorMessage(SpeechRecognizer.ERROR_NO_MATCH))
                return
            }
            showText(spoken)
            show(State.PROCESSING, "正在搜索")
            state = State.DONE
            release()
            // Leave the recognized words on screen for a beat so the hand-off reads clearly.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ onClose(); onResult(spoken) }, 350)
        }
    }

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private companion object { const val TAG = "HongguoTV.Voice" }
}

/** Brand-gradient microphone orb with soft rings that breathe and swell with the voice level. */
private class VoiceOrb(context: Context) : View(context) {
    var active = false
        set(value) { field = value; invalidate() }
    var error = false
        set(value) { field = value; invalidate() }
    var level = 0f
    private var shown = 0f
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = 52 * density
        val t = SystemClock.uptimeMillis() / 1000f
        shown += (level - shown) * 0.3f
        if (active) {
            for (i in 0 until 3) {
                // Each ring drifts outward and fades; voice level pushes them further.
                val phase = ((t * 0.55f + i / 3f) % 1f)
                val radius = base + (18 + phase * 52 + shown * 40) * density
                ring.strokeWidth = (2.5f - phase * 1.5f) * density
                ring.color = TvStyle.BRAND_START
                ring.alpha = ((1f - phase) * (110 + shown * 120)).toInt().coerceIn(0, 255)
                canvas.drawCircle(cx, cy, radius, ring)
            }
            fill.shader = RadialGradient(cx, cy, base + 60 * density,
                intArrayOf(Color.argb((60 + shown * 90).toInt(), 255, 107, 53), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, base + 60 * density, fill)
        }
        val breathe = if (active) 1f + 0.04f * sin(t * 3.2f) + shown * 0.12f else 1f
        val r = base * breathe
        fill.shader = if (error) null else android.graphics.LinearGradient(cx - r, cy - r, cx + r, cy + r,
            TvStyle.BRAND_START, Color.parseColor("#FF8A4C"), Shader.TileMode.CLAMP)
        fill.color = if (error) TvStyle.raised else Color.WHITE
        canvas.drawCircle(cx, cy, r, fill)
        drawMic(canvas, cx, cy, base * 0.5f)
        if (active) postInvalidateOnAnimation()
    }

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        glyph.strokeWidth = max(2f, s * 0.13f)
        glyph.color = if (error) TvStyle.muted else Color.WHITE
        rect.set(cx - s * 0.3f, cy - s * 0.82f, cx + s * 0.3f, cy + s * 0.18f)
        canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, glyph)
        rect.set(cx - s * 0.56f, cy - s * 0.42f, cx + s * 0.56f, cy + s * 0.44f)
        canvas.drawArc(rect, 20f, 140f, false, glyph)
        canvas.drawLine(cx, cy + s * 0.44f, cx, cy + s * 0.78f, glyph)
        canvas.drawLine(cx - s * 0.28f, cy + s * 0.78f, cx + s * 0.28f, cy + s * 0.78f, glyph)
    }
}
