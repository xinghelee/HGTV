// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// 两栏遥控搜索：左侧输入，右侧联想、历史与结果。

import android.content.Context
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.hongguotv.core.Zhuyin

/** Remote-first search workspace; the existing discovery filters remain a separate screen. */
internal class TvSearchPanel(
    context: Context,
    initial: String,
    history: List<String>,
    private val upper: View,
    fetch: (String) -> List<String>,
    private val search: (String) -> Unit,
    private val voice: (() -> Unit)? = null,
) : LinearLayout(context) {
    val input =
        EditText(context).apply {
            id = View.generateViewId()
            hint = "剧名 / 拼音首字母 / 注音"
            textSize = 20f
            setTextColor(TvStyle.text)
            setHintTextColor(TvStyle.muted)
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(80))
            showSoftInputOnFocus = false
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setText(initial)
            setSelection(text.length)
            background = TvStyle.shape(context, TvVisual.QUIET, radius = 14)
            setPadding(dp(16), 0, dp(12), 0)
        }
    data class EditState(val draft: String, val words: List<String>, val word: Int, val recent: Int, val key: Int)
    private var lastKey = 0
    private var editingFocus: View? = null
    private val editor = LinearLayout(context)
    private val resultStage = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private lateinit var backButton: TextView
    var showingResults = false
        private set
    fun capture() = EditState(input.text.toString(), suggestions.values(), suggestions.selectedIndex(), recents.indexOfFirst { it.hasFocus() }, lastKey)
    fun restore(state: EditState) {
        input.setText(state.draft)
        suggestions.cancel()
        suggestions.restore(state.words)
        lastKey = state.key.coerceIn(0, keys.lastIndex)
        editingFocus = if (state.word >= 0) { suggestions.viewAt(state.word) }
            else recents.getOrNull(state.recent) ?: keys[lastKey]
        updateNavigation()
    }
    fun showResults() {
        if (!showingResults) editingFocus = findFocus() ?: editingFocus
        showingResults = true
        editor.visibility = GONE
        resultStage.visibility = VISIBLE
        backButton.requestFocus()
    }
    fun back(): Boolean {
        if (!showingResults) return false
        showingResults = false
        resultStage.visibility = GONE
        editor.visibility = VISIBLE
        if (editingFocus?.requestFocus() != true) entry().requestFocus()
        updateNavigation()
        return true
    }
    fun resultTitle(query: String) { resultHeading.text = "搜索 · $query" }
    private val resultHeading = label("搜索结果", 24f).apply { setTextColor(TvStyle.text); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    fun resultBackEntry(): View = backButton
    val results = LinearLayout(context).apply { orientation = VERTICAL }
    private val abcKeys = mutableListOf<TextView>()
    private val zhuyinKeys = mutableListOf<TextView>()
    private val preferences = context.getSharedPreferences("search_keyboard", Context.MODE_PRIVATE)
    private var zhuyinMode = preferences.getBoolean("zhuyin", false)
    private val keys: List<TextView> get() = if (zhuyinMode) zhuyinKeys else abcKeys
    private val zhuyin: Zhuyin by lazy { zhuyinFor(context) }
    private lateinit var abcGrid: LinearLayout
    private lateinit var zhuyinGrid: LinearLayout
    private lateinit var modeButton: TextView
    // Simplified-character candidates for the syllable being composed: 2 rows of 6, the last slot pages.
    private val candidateSlots = mutableListOf<TextView>()
    private lateinit var candidateGrid: LinearLayout
    private var candidatePage = 0
    private val actions = mutableListOf<TextView>()
    private val recents = mutableListOf<TextView>()
    var resultEntry: () -> View? = { null }
    val suggestions: SearchSuggestionsView
    val searchButton: TextView

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 18f) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(TvStyle.muted)
            setPadding(0, dp(6), 0, dp(8))
        }

    private fun action(value: String, click: (View) -> Unit) =
        TextView(context).apply {
            id = View.generateViewId()
            text = value
            textSize = 18f
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            minHeight = dp(36)
            setTextColor(TvStyle.text)
            setPadding(dp(4), dp(6), dp(4), dp(6))
            background = TvStyle.shape(context, TvVisual.QUIET)
            setOnFocusChangeListener { _, focused ->
                background = TvStyle.shape(context, if (focused) android.graphics.Color.WHITE else TvVisual.QUIET)
                setTextColor(if (focused) TvStyle.background else TvStyle.text)
                TvMotion.focus(this, focused, 1.08f)
            }
            setOnClickListener(click)
        }

    private fun hideKeyboard() {
        input.showSoftInputOnFocus = false
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** Fills the box with recognized speech and searches it straight away. */
    fun voiceResult(value: String) {
        input.setText(value)
        input.setSelection(input.text.length)
        submit(value)
    }

    // 提交前清理空白和隐藏输入法；非空关键词通过回调交给主页面搜索。
    private fun submit(value: String = input.text.toString()) {
        val finished = zhuyin.finish(value, suggestions.values())
        if (finished != value && value == input.text.toString()) {
            input.setText(finished)
            input.setSelection(input.text.length)
        }
        finished
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let {
                hideKeyboard()
                search(it)
            }
    }

    // 替换输入框当前选区；没有选区时，在光标位置插入字符。
    private fun insert(value: String) {
        hideKeyboard()
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        input.text.replace(start, end, value)
    }

    // 优先删除选区，否则按 Unicode 码点删除，避免截断代理对字符。
    private fun delete() {
        val start = input.selectionStart.coerceAtLeast(0)
        val end = input.selectionEnd.coerceAtLeast(start)
        if (end > start) input.text.delete(start, end)
        else if (start > 0)
            input.text.delete(Character.offsetByCodePoints(input.text, start, -1), start)
    }

    // 组装左侧键盘、中间联想历史和右侧结果容器，并建立遥控器焦点关系。
    init {
        orientation = VERTICAL
        addView(editor, LayoutParams(-1, -1))
        addView(resultStage, LayoutParams(-1, -1))
        backButton = action("← 返回选词") { back() }
        val resultHeader = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        resultHeader.addView(backButton, LayoutParams(-2, dp(46)).apply { rightMargin = dp(20) })
        resultHeader.addView(resultHeading, LayoutParams(0, -2, 1f))
        resultStage.addView(resultHeader)
        resultStage.addView(results, LayoutParams(-1, 0, 1f))
        val compact = resources.configuration.screenWidthDp < 800
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        val leftScroll =
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(left)
            }
        editor.addView(
            leftScroll,
            LayoutParams(dp(if (compact) 206 else 248), -1).apply { rightMargin = dp(36) },
        )
        left.addView(label("搜索", 30f).apply { setTextColor(TvStyle.text); typeface = android.graphics.Typeface.DEFAULT_BOLD })

        left.addView(input, LayoutParams(-1, dp(48)))
        candidateGrid = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(8), 0, 0); visibility = GONE }
        repeat(2) {
            val line = LinearLayout(context)
            repeat(6) {
                val slot = action("") { pickCandidate(candidateSlots.indexOf(it)) }
                candidateSlots += slot
                line.addView(slot, LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5); bottomMargin = dp(5) })
            }
            candidateGrid.addView(line)
        }
        left.addView(candidateGrid)
        abcGrid = keyGrid("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".map { it.toString() }, abcKeys) { insert(it.lowercase()) }
        zhuyinGrid = keyGrid(Zhuyin.KEYS.map { it.toString() }, zhuyinKeys) { insert(it) }
        left.addView(abcGrid)
        left.addView(zhuyinGrid)
        searchButton = action("搜索") { submit() }
        modeButton = action(if (zhuyinMode) "ABC" else "注音") { setZhuyinMode(!zhuyinMode) }
        actions +=
            listOf(
                action("删除") { delete() },
                action("清空") { input.setText("") },
                searchButton,
                modeButton,
                action("输入法") {
                    input.showSoftInputOnFocus = true
                    input.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                },
            ) + listOfNotNull(voice?.let { start -> action("🎤 语音") { hideKeyboard(); start() } })
        actions.chunked(3).forEach { row ->
            val line = LinearLayout(context)
            row.forEach {
                line.addView(
                    it,
                    LayoutParams(0, dp(40), 1f).apply {
                        rightMargin = dp(5)
                        bottomMargin = dp(5)
                    },
                )
            }
            left.addView(line)
        }
        left.addView(label("→ 选词   ·   确认输入", 15f))
        val resultColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
            background = TvStyle.shape(context, TvStyle.background, radius = 16)
        }
        editor.addView(resultColumn, LayoutParams(0, -1, 1f))
        val words = LinearLayout(context).apply { orientation = VERTICAL }
        resultColumn.addView(ScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(words)
        }, LayoutParams(-1, -1))
        val suggestionLabel = label("猜你想搜", 22f).apply { visibility = GONE }
        words.addView(suggestionLabel)
        suggestions =
            SearchSuggestionsView(
                context,
                input,
                listOf(keys.last()),
                { resultEntry() ?: input },
                { query -> fetch(zhuyin.suggestionQuery(query)) },
                { submit(it) },
                vertical = false,
                exitHorizontalEdges = true,
                grid = true,
            )
        words.addView(suggestions, LayoutParams(-1, -2))
        if (history.isNotEmpty()) words.addView(label("最近搜索", 22f))
        val historyLine = LinearLayout(context).apply { orientation = VERTICAL }
        val historyTitle = words.getChildAt(words.childCount - 1)
        val emptyHistory = label("输入剧名、拼音首字母或注音，联想词会显示在这里", 16f).apply {
            visibility = if (history.isEmpty()) VISIBLE else GONE
        }
        words.addView(emptyHistory)
        words.addView(historyLine)
        history.distinct().take(8).forEachIndexed { index, value ->
            val recent =
                action(value) { submit(value) }
                    .apply {
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        minHeight = maxOf(dp(76), (paint.fontSpacing * 2).toInt() + paddingTop + paddingBottom + dp(4))
                    }
            recents += recent
            if (index % 2 == 0) historyLine.addView(LinearLayout(context).apply { isBaselineAligned = false }, LayoutParams(-1, -2))
            (historyLine.getChildAt(historyLine.childCount - 1) as LinearLayout).addView(recent, LayoutParams(0, -2, 1f).apply { rightMargin = dp(10); bottomMargin = dp(10) })
        }
        suggestions.afterWords = { recents.firstOrNull() ?: resultEntry() ?: input }
        input.nextFocusUpId = input.id
        input.nextFocusDownId = keys.first().id
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        suggestions.keyboardEntry = { keys[lastKey] }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { candidatePage = 0; renderCandidates() }
        })
        applyMode()
        suggestions.navigationChanged = {
            suggestionLabel.visibility = if (input.text.isNotBlank()) VISIBLE else GONE
            emptyHistory.visibility = if (history.isEmpty() && input.text.isBlank()) VISIBLE else GONE
            historyLine.visibility = if (input.text.isBlank()) VISIBLE else GONE
            if (history.isNotEmpty()) historyTitle.visibility = historyLine.visibility
            updateNavigation()
        }
        updateNavigation()
    }

    private fun suggestionsNavigation() { suggestions.updateNavigation() }

    fun middleEntry(): View? = suggestions.entry() ?: recents.firstOrNull()?.takeIf { input.text.isBlank() }

    fun entry(): View = if (showingResults) backButton else keys[lastKey]

    fun updateNavigation() {
        backButton.nextFocusDownId = resultEntry()?.id ?: backButton.id
        val right = middleEntry()?.id ?: input.id
        val slots = candidateSlots.filter { it.visibility == VISIBLE && candidateGrid.visibility == VISIBLE }
        val candidateRows = slots.chunked(6)
        keys.forEachIndexed { i, key ->
            key.nextFocusLeftId = if (i % 6 == 0) upper.id else keys[i - 1].id
            key.nextFocusRightId = if (i % 6 == 5) right else keys[i + 1].id
            key.nextFocusUpId = keys.getOrNull(i - 6)?.id
                ?: candidateRows.lastOrNull()?.let { (it.getOrNull(i % 6) ?: it.last()).id } ?: input.id
            key.nextFocusDownId = keys.getOrNull(i + 6)?.id ?: actions.first().id
        }
        candidateRows.forEachIndexed { row, line ->
            line.forEachIndexed { i, slot ->
                slot.nextFocusLeftId = line.getOrNull(i - 1)?.id ?: upper.id
                slot.nextFocusRightId = line.getOrNull(i + 1)?.id ?: right
                slot.nextFocusUpId = candidateRows.getOrNull(row - 1)?.let { (it.getOrNull(i) ?: it.last()).id } ?: input.id
                slot.nextFocusDownId = candidateRows.getOrNull(row + 1)?.let { (it.getOrNull(i) ?: it.last()).id }
                    ?: keys.getOrNull(i)?.id ?: keys.first().id
            }
        }
        actions.forEachIndexed { i, view ->
            view.nextFocusLeftId = actions.getOrNull(i - 1)?.takeIf { i % 3 != 0 }?.id ?: view.id
            view.nextFocusRightId = if (i % 3 == 2) right else actions.getOrNull(i + 1)?.id ?: right
            view.nextFocusUpId = actions.getOrNull(i - 3)?.id ?: keys[keys.size - 6 + i % 3].id
            view.nextFocusDownId = actions.getOrNull(i + 3)?.id ?: view.id
        }
        recents.forEachIndexed { i, view ->
            view.nextFocusUpId = recents.getOrNull(i - 2)?.id ?: view.id
            view.nextFocusDownId = recents.getOrNull(i + 2)?.id ?: view.id
            view.nextFocusLeftId = if (i % 2 == 0) keys[lastKey].id else recents[i - 1].id
            view.nextFocusRightId = if (i % 2 == 0) recents.getOrNull(i + 1)?.id ?: view.id else view.id
        }
        input.nextFocusRightId = right
        input.nextFocusUpId = input.id
        input.nextFocusDownId = slots.firstOrNull()?.id ?: keys.first().id
    }

    private fun keyGrid(labels: List<String>, into: MutableList<TextView>, type: (String) -> Unit): LinearLayout {
        val grid = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(8), 0, 0) }
        labels.chunked(6).forEach { row ->
            val line = LinearLayout(context)
            row.forEach { label ->
                val key = action(label) { type(label) }
                val keyIndex = into.size
                val styleFocus = key.onFocusChangeListener
                key.setOnFocusChangeListener { view, focused ->
                    styleFocus?.onFocusChange(view, focused)
                    if (focused) { lastKey = keyIndex; suggestionsNavigation() }
                }
                into += key
                line.addView(key, LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(5); bottomMargin = dp(5) })
            }
            grid.addView(line)
        }
        return grid
    }

    private fun setZhuyinMode(enabled: Boolean) {
        if (enabled == zhuyinMode) return
        // Leaving Zhuyin turns whatever is still being composed into simplified characters.
        if (!enabled) input.setText(zhuyin.finish(input.text.toString(), suggestions.values()))
        input.setSelection(input.text.length)
        zhuyinMode = enabled
        preferences.edit().putBoolean("zhuyin", enabled).apply()
        lastKey = lastKey.coerceIn(0, keys.lastIndex)
        applyMode()
    }

    private fun applyMode() {
        abcGrid.visibility = if (zhuyinMode) GONE else VISIBLE
        zhuyinGrid.visibility = if (zhuyinMode) VISIBLE else GONE
        modeButton.text = if (zhuyinMode) "ABC" else "注音"
        renderCandidates()
    }

    private fun candidates(): List<String> = if (zhuyinMode) zhuyin.candidatesFor(input.text.toString()) else emptyList()

    private fun renderCandidates() {
        val all = candidates()
        val paged = all.size > candidateSlots.size
        val perPage = if (paged) candidateSlots.size - 1 else candidateSlots.size
        if (candidatePage * perPage >= all.size) candidatePage = 0
        val page = all.drop(candidatePage * perPage).take(perPage)
        val hadFocus = candidateSlots.any { it.hasFocus() }
        candidateSlots.forEachIndexed { i, slot ->
            val text = when {
                i < page.size -> page[i]
                paged && i == candidateSlots.lastIndex -> "›"
                else -> null
            }
            slot.text = text.orEmpty()
            slot.visibility = if (text != null) VISIBLE else INVISIBLE
            slot.isFocusable = text != null
        }
        candidateGrid.visibility = if (all.isEmpty()) GONE else VISIBLE
        (candidateGrid.getChildAt(1) as? LinearLayout)?.visibility =
            if (candidateSlots.drop(6).any { it.visibility == VISIBLE }) VISIBLE else GONE
        updateNavigation()
        if (hadFocus && all.isNotEmpty()) candidateSlots.first().requestFocus()
        else if (hadFocus) keys[lastKey].requestFocus()
    }

    private fun pickCandidate(index: Int) {
        val slot = candidateSlots.getOrNull(index) ?: return
        if (slot.text == "›") { candidatePage++; renderCandidates(); return }
        val chosen = slot.text.toString().takeIf { it.isNotEmpty() } ?: return
        input.setText(zhuyin.commit(input.text.toString(), chosen))
        input.setSelection(input.text.length)
    }

    companion object {
        @Volatile private var table: Zhuyin? = null
        private fun zhuyinFor(context: Context): Zhuyin =
            table ?: synchronized(this) {
                table ?: Zhuyin(context.assets.open("zhuyin.tsv").bufferedReader().use { it.readText() }).also { table = it }
            }
    }
}
