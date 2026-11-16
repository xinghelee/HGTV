// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class VoiceSearchTest {
    private fun texts(view: View): List<TextView> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else if (view is TextView) listOf(view) else emptyList()

    private fun result(vararg spoken: String) =
        Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(*spoken))

    @Test fun recognizedTextDropsSentencePunctuation() {
        assertEquals("全球死考", VoiceSearch.text(result(" 全球死考。")))
        assertEquals("凡人百世书", VoiceSearch.text(result("凡人百世书？")))
        assertNull(VoiceSearch.text(result("。")))
        assertNull(VoiceSearch.text(null))
    }

    @Test fun voiceButtonOnlyAppearsWhenARecognizerIsOffered() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val upper = TextView(activity).apply { id = View.generateViewId() }
        var started = 0
        val searched = mutableListOf<String>()
        val plain = TvSearchPanel(activity, "", emptyList(), upper, { emptyList() }, {})
        val voice = TvSearchPanel(activity, "", emptyList(), upper, { emptyList() }, { searched += it }, { started++ })
        try {
            assertFalse(texts(plain).any { it.text.contains("语音") })
            val button = texts(voice).single { it.text.contains("语音") }
            assertTrue(button.isFocusable)
            button.performClick()
            assertEquals(1, started)
            voice.voiceResult("全球死考")
            assertEquals("全球死考", voice.input.text.toString())
            assertEquals(listOf("全球死考"), searched)
        } finally {
            plain.suggestions.close()
            voice.suggestions.close()
            controller.pause().stop().destroy()
        }
    }
}
