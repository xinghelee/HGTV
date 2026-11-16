// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent

/**
 * Voice input through the TV's own speech recognizer (on Google/Android TV usually the
 * system voice search UI). Nothing is recorded by this app; the recognizer returns text.
 */
internal object VoiceSearch {
    const val REQUEST = 0x5653

    fun intent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出想看的剧名")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    fun available(activity: Activity): Boolean =
        runCatching { activity.packageManager.queryIntentActivities(intent(), 0).isNotEmpty() }
            .getOrDefault(false)

    /** Recognizers often append sentence punctuation; drop it before searching. */
    fun text(data: Intent?): String? =
        data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            ?.trimEnd('。', '.', '！', '!', '？', '?', '，', ',')
            ?.takeIf { it.isNotBlank() }
}
