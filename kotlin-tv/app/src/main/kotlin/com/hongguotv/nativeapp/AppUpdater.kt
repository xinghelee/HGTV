// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

// Android 应用更新流程：检查版本、下载 APK，并通过系统安装器请求安装。

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import com.hongguotv.core.AppUpdate
import com.hongguotv.core.AppUpdateSource
import com.hongguotv.core.AppUpdateRepository
import com.hongguotv.core.UpdateFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only updater: no background service, and no networking or prompts during playback. */
class AppUpdater(
    private val activity: Activity,
    private val repository: AppUpdateSource = AppUpdateRepository(android.os.Build.VERSION.SDK_INT),
    private val idle: () -> Boolean,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val preferences = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE)
    private val apk = File(activity.cacheDir, "updates/update.apk")
    private var task: Future<*>? = null
    private var generation = 0
    private var active = false
    private var busy = false
    private var available: AppUpdate? = null
    private var ready = false
    private var permissionRequested = false
    private var dialog: AlertDialog? = null
    val showing
        get() = dialog?.isShowing == true

    private var statusView: TextView? = null
    private var notesView: TextView? = null
    private var progressView: ProgressBar? = null
    private var actionView: TextView? = null
    private var anchor: View? = null
    private var status = "点击下方按钮检查是否有新版本"
    private var notes = ""
    private var progress = -1
    private var retryAfter = 0L
    // Each app launch checks once, so a new release shows up the next time the app is opened.
    private var checkedThisLaunch = false
    private val auto
        get() = automatic

    /** Persisted switch shared by the settings page; turning it off stops a running background task. */
    var automatic: Boolean
        get() = preferences.getBoolean("automatic", !BuildConfig.DEBUG)
        set(enabled) {
            preferences.edit().putBoolean("automatic", enabled).apply()
            if (!enabled && busy) {
                interrupt()
                updateStatus("自动更新已关闭，可手动检查")
                renderActions()
            }
        }

    /** Short state for the settings row value, e.g. 已是最新 / 发现 0.16.15 / 下载中 40%. */
    var summary = ""
        private set
    var onSummary: ((String) -> Unit)? = null

    private val poll =
        object : Runnable {
            override fun run() {
                if (!active) return
                if (idle() && dialog == null && !busy && auto) {
                    val now = System.currentTimeMillis()
                    val last = preferences.getLong("last_attempt", 0)
                    val update = available
                    when {
                        // A verified download is offered once per version, only while browsing.
                        update != null && ready &&
                            preferences.getLong("prompted_version", 0) != update.versionCode -> prompt(update)
                        update != null && !ready && now >= retryAfter -> download()
                        !ready && (!checkedThisLaunch || now < last || now - last >= 6 * 60 * 60 * 1000L) -> check()
                    }
                }
                main.postDelayed(this, 15000)
            }
        }

    fun resume() {
        active = true
        main.removeCallbacks(poll)
        main.postDelayed(poll, 8000)
        val sessionState = activity.getSharedPreferences("update_install_session", 0)
        sessionState.getString("result_text", null)?.let {
            sessionState.edit().remove("result_text").apply()
            updateStatus(it)
        }
        if (permissionRequested) {
            permissionRequested = false
            if (Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls())
                install()
            else {
                status = "安装权限未开启，可在这里再次安装"
                show(anchor)
            }
        }
    }

    fun pause() {
        active = false
        main.removeCallbacks(poll)
        interrupt()
        dialog?.dismiss()
    }

    fun interrupt() {
        generation++
        task?.cancel(true)
        task = null
        repository.cancel()
        busy = false
    }

    fun destroy() {
        pause()
        worker.shutdownNow()
        // TLS socket close may send close_notify; it must not run on Android's main thread.
        Thread({ repository.close() }, "hongguotv-update-cleanup").start()
    }

    private fun updateStatus(text: String, notes: String = "", progress: Int = -1, summary: String? = null) {
        status = text
        this.notes = notes
        this.progress = progress
        statusView?.text = text
        notesView?.apply { this.text = notes; visibility = if (notes.isBlank()) View.GONE else View.VISIBLE }
        progressView?.apply {
            visibility = if (progress >= 0) View.VISIBLE else View.GONE
            this.progress = progress.coerceAtLeast(0)
        }
        val short = summary ?: text.lineSequence().first().take(18)
        if (short != this.summary) { this.summary = short; onSummary?.invoke(short) }
    }

    private fun renderActions() {
        actionView?.apply {
            text =
                when {
                    busy -> "处理中…"
                    ready -> "安装新版"
                    available != null -> "下载新版"
                    else -> "检查更新"
                }
            // Keep D-pad focus stable while the request runs; action methods reject duplicates.
            isEnabled = true
        }
    }

    fun show(from: View?) {
        if (!active) return
        anchor = from
        dialog?.dismiss()
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun label(value: String, size: Float, color: Int = TvStyle.text, bold: Boolean = false) =
            TextView(activity).apply {
                text = value
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(null, Typeface.BOLD)
            }
        val content =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(32), dp(28), dp(32), dp(22))
                background = TvVisual.panel(activity, modal = true)
                clipChildren = false
                clipToPadding = false
            }
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(
            ImageView(activity).apply {
                setImageDrawable(activity.applicationInfo.loadIcon(activity.packageManager))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { rightMargin = dp(16) },
        )
        val names = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        names.addView(label(activity.applicationInfo.loadLabel(activity.packageManager).toString(), 22f, bold = true))
        names.addView(label("当前版本 ${BuildConfig.VERSION_NAME}", 14f, TvStyle.muted).apply { setPadding(0, dp(2), 0, 0) })
        header.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(header)

        val card =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
                background = TvStyle.shape(activity, TvStyle.raised, radius = 12)
            }
        statusView = label(status, 18f, bold = true).also { card.addView(it) }
        progressView =
            ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progressDrawable = TvVisual.progress(activity)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }.also { card.addView(it, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) }) }
        notesView =
            label(notes, 14f, TvStyle.muted).apply { setLineSpacing(0f, 1.25f); setPadding(0, dp(10), 0, 0) }
        card.addView(
            ScrollView(activity).apply { isVerticalScrollBarEnabled = false; addView(notesView) },
            LinearLayout.LayoutParams(-1, -2),
        )
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })

        // Focused buttons scale up; leave room so the edge is not clipped.
        val buttons = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(6), dp(4), 0, dp(4))
        }
        fun button(value: String) =
            label(value, 16f).apply {
                id = View.generateViewId()
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                minWidth = dp(148)
                setPadding(dp(26), dp(11), dp(26), dp(11))
            }
        val action = button("检查更新").also { TvVisual.primary(it) }
        val close =
            button("关闭").apply {
                fun paint(focused: Boolean) {
                    background = TvStyle.shape(activity, if (focused) Color.WHITE else TvVisual.QUIET, radius = 999)
                    setTextColor(if (focused) TvStyle.background else TvStyle.text)
                }
                paint(false)
                setOnFocusChangeListener { _, focused -> paint(focused); TvMotion.focus(this, focused, 1.06f) }
            }
        actionView = action
        buttons.addView(action)
        buttons.addView(close, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        action.nextFocusRightId = close.id
        close.nextFocusLeftId = action.id
        listOf(action, close).forEach { it.nextFocusUpId = it.id; it.nextFocusDownId = it.id }
        content.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22) })
        content.addView(
            label(
                "${if (auto) "自动检查已开启" else "自动检查已关闭"}，可在设置中更改 · 安装前校验签名，收藏与观看记录保留",
                12f,
                TvStyle.muted,
            ).apply { setPadding(0, dp(16), 0, 0) }
        )
        updateStatus(status, notes, progress, summary)

        val next = AlertDialog.Builder(activity).create()
        next.setView(content, 0, 0, 0, 0)
        dialog = next
        next.setOnDismissListener {
            if (dialog === next) {
                dialog = null
                statusView = null
                notesView = null
                progressView = null
                actionView = null
            }
            if (from?.isAttachedToWindow == true) from.requestFocus()
        }
        next.show()
        next.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        next.window?.setDimAmount(.6f)
        val width = minOf(dp(560), (activity.resources.displayMetrics.widthPixels * .82f).toInt())
        next.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        action.setOnClickListener {
            when {
                ready -> install()
                available != null -> download()
                else -> check()
            }
        }
        close.setOnClickListener { next.dismiss() }
        renderActions()
        action.post { if (dialog === next) action.requestFocus() }
        // Opening the panel is itself the request to check; skip the extra button press.
        if (!busy && !ready && available == null) check()
    }

    private fun <T> work(action: () -> T, done: (T) -> Unit) {
        busy = true
        renderActions()
        val ticket = ++generation
        task =
            worker.submit {
                try {
                    val result = action()
                    main.post {
                        if (active && generation == ticket) {
                            busy = false
                            done(result)
                            renderActions()
                        }
                    }
                } catch (e: Exception) {
                    main.post {
                        if (active && generation == ticket) {
                            busy = false
                            ready = false
                            retryAfter = System.currentTimeMillis() + 30 * 60 * 1000L
                            updateStatus(
                                if (e is IOException) e.message ?: "更新失败，请重试" else "更新校验失败，请重试",
                                summary = "检查失败",
                            )
                            renderActions()
                        }
                    }
                }
            }
    }

    private fun prompt(update: AppUpdate) {
        preferences.edit().putLong("prompted_version", update.versionCode).apply()
        show(activity.currentFocus)
    }

    private fun check() {
        if (busy || !active || !idle()) return
        checkedThisLaunch = true
        preferences.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        updateStatus("正在检查新版…", summary = "检查中…")
        work({ repository.latest() }) { update ->
            available =
                update.takeIf {
                    it.newerThan(BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT)
                }
            ready = false
            if (available == null) {
                if (update.versionCode > BuildConfig.VERSION_CODE && update.minSdk > Build.VERSION.SDK_INT)
                    updateStatus("新版需要 Android API ${update.minSdk}，当前电视继续使用本版", summary = "系统版本过低")
                else updateStatus("已是最新版本", summary = "已是最新")
                worker.execute { apk.delete() }
            } else {
                updateStatus(
                    "发现新版 ${update.versionName} · ${"%.1f".format(update.size/1048576.0)} MB",
                    update.notes,
                    summary = "发现 ${update.versionName}",
                )
                if (auto || dialog != null) download()
            }
        }
    }

    private fun download() {
        val update = available ?: return
        if (busy || !active || !idle()) return
        updateStatus("正在下载 ${update.versionName}", update.notes, 0, "下载中")
        val ticket = generation + 1
        work({
            repository.download(update, apk) { percent ->
                main.post {
                    if (generation == ticket && active)
                        updateStatus("正在下载 ${update.versionName} · $percent%", update.notes, percent, "下载中 $percent%")
                }
            }
            validateApk(update)
        }) {
            ready = true
            updateStatus(
                "${update.versionName} 已下载，可以安装",
                (update.notes.takeIf { it.isNotBlank() }?.plus("\n\n") ?: "") + "确认后由系统覆盖安装，保留本机记录。",
                summary = "可安装 ${update.versionName}",
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(update: AppUpdate) {
        if (!UpdateFiles.verify(apk, update)) throw IOException("安装包校验未通过，请重新下载")
        val pm = activity.packageManager
        val flags = UpdateApkSignatures.flags()
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("安装包无法识别")
        val installed = pm.getPackageInfo(activity.packageName, flags)
        val version =
            if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode
            else candidate.versionCode.toLong()
        // ApplicationInfo.minSdkVersion is unavailable on API 23. The system installer
        // still enforces the APK's declared minimum there; retain all hash/signature checks.
        if (
            candidate.packageName != activity.packageName ||
                version != update.versionCode ||
                candidate.versionName != update.versionName ||
                version <= BuildConfig.VERSION_CODE ||
                (Build.VERSION.SDK_INT >= 24 &&
                    candidate.applicationInfo?.minSdkVersion != update.minSdk)
        )
            throw IOException("新版包名或版本不匹配，已阻止安装")
        UpdateApkSignatures.verify(installed, candidate)
    }

    private var legacyApk: File? = null
    private var installerFailure = ""

    /** 仅 Android 6 准备旧式入口；外部目录不可用时仍保留内容授权入口。 */
    private fun prepareLegacyApk(update: AppUpdate) {
        legacyApk = null
        if (Build.VERSION.SDK_INT >= 24) return
        legacyApk = runCatching {
            val directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("外部目录不可用")
            stageLegacyApk(apk, directory) { UpdateFiles.verify(it, update) }
        }.onFailure {
            android.util.Log.w("HongguoTV", "Legacy update copy failed: ${installerError(it)}")
        }.getOrNull()
    }

    /** 依次尝试安装入口；查询结果不能作为是否允许启动的依据。 */
    private fun launchInstaller(uri: Uri): Boolean {
        val fileUri = if (Build.VERSION.SDK_INT < 24) legacyApk?.let(Uri::fromFile) else null
        val sources = installerIntents(uri, fileUri)
        val failures = mutableMapOf<String, Int>()
        sources.forEachIndexed { index, candidate ->
            candidate.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            candidate.clipData = ClipData.newRawUri("更新安装包", uri)
            val result = runCatching { activity.startActivity(candidate) }
            if (result.isSuccess) return true
            val reason = installerError(result.exceptionOrNull()!!)
            failures[reason] = (failures[reason] ?: 0) + 1
            // 只记录入口序号、传递方式和异常类别，不输出文件地址或异常正文。
            android.util.Log.w("HongguoTV", "Update installer entry=${index + 1} scheme=${candidate.data?.scheme} failure=$reason")
        }
        installerFailure = "已尝试 ${sources.size} 个入口：" +
            failures.entries.joinToString("，") { "${it.key} ${it.value}" }
        return false
    }

    companion object {
        /** 常见电视/手机安装器的组件名；隐式匹配失败时按组件直达。 */
        private val INSTALLER_COMPONENTS =
            listOf(
                // Android 7+ 的公开入口是 InstallStart，PackageInstallerActivity 多为内部页面，
                // 只在少数厂商 ROM 上可直接拉起，放在后面兜底。
                "com.android.packageinstaller" to "com.android.packageinstaller.InstallStart",
                "com.google.android.packageinstaller" to "com.android.packageinstaller.InstallStart",
                "com.miui.packageinstaller" to "com.miui.packageinstaller.PackageInstallerActivity",
                "com.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
                "com.google.android.packageinstaller" to "com.android.packageinstaller.PackageInstallerActivity",
            )

        /** 先尝试内容授权入口，再尝试 Android 6 外部副本的旧式文件入口。 */
        internal fun installerIntents(uri: Uri, fileUri: Uri?): List<Intent> {
            val type = "application/vnd.android.package-archive"
            val sources = mutableListOf<Intent>()
            val uris = listOfNotNull(uri, fileUri?.takeIf { Build.VERSION.SDK_INT < 24 })
            for (source in uris) {
                if (Build.VERSION.SDK_INT < 26)
                    sources += Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(source, type)
                sources += Intent(Intent.ACTION_VIEW).setDataAndType(source, type)
                for ((pkg, cls) in INSTALLER_COMPONENTS) {
                    sources += Intent(Intent.ACTION_VIEW).setClassName(pkg, cls).setDataAndType(source, type)
                    if (Build.VERSION.SDK_INT < 26)
                        sources += Intent(Intent.ACTION_INSTALL_PACKAGE).setClassName(pkg, cls).setDataAndType(source, type)
                }
            }
            return sources
        }

        /** 不放宽私有目录权限；复制完成并校验后才暴露旧式文件路径。 */
        internal fun stageLegacyApk(source: File, directory: File, verify: (File) -> Boolean): File {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法准备外部安装目录")
            val target = File(directory, "guoying-tv-update.apk")
            val temporary = File(directory, "guoying-tv-update.apk.part")
            try {
                source.inputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                if (!verify(temporary)) throw IOException("外部安装副本校验失败")
                if (!temporary.renameTo(target)) throw IOException("无法保存外部安装副本")
                return target
            } finally {
                temporary.delete()
            }
        }

        internal fun installerError(error: Throwable): String = when (error) {
            is android.content.ActivityNotFoundException -> "入口不存在"
            is SecurityException -> "系统拒绝"
            else -> "调用失败"
        }
    }

    private fun install() {
        val update = available ?: return
        if (busy || !active || !ready) return
        work({
            validateApk(update)
            prepareLegacyApk(update)
        }) {
            try {
                // Android 8+ 未授权时先尝试打开授权页；但很多电视没有这个页面，
                // 打不开时不能直接放弃——继续走安装入口，由系统安装器自己提示未知来源。
                if (
                    Build.VERSION.SDK_INT >= 26 &&
                        !activity.packageManager.canRequestPackageInstalls()
                ) {
                    val opened =
                        runCatching {
                                activity.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:${activity.packageName}"),
                                    )
                                )
                                true
                            }
                            .getOrDefault(false)
                    if (opened) {
                        permissionRequested = true
                        updateStatus("已打开安装权限设置，允许后返回这里再次点安装新版")
                        return@work
                    }
                }
                run {
                    val uri =
                        FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    // 为 Android 6/7 增加标准安装 action；厂商 ROM 是否提供对应入口仍需实机验证。
                    if (!launchInstaller(uri)) {
                        updateStatus("正在准备系统兼容安装…")
                        work({ runCatching { UpdateInstallSession.submit(activity, apk) } }) { result ->
                            if (result.isSuccess) {
                                updateStatus("已提交系统安装会话，请在系统确认页继续安装")
                            } else {
                                android.util.Log.w("HongguoTV", "Update session failed: ${installerError(result.exceptionOrNull()!!)}")
                                val openedSettings =
                                    runCatching {
                                            activity.startActivity(
                                                Intent(Settings.ACTION_SECURITY_SETTINGS)
                                            )
                                            true
                                        }
                                        .getOrDefault(false)
                                if (openedSettings) {
                                    // 这里只打开设置，不能视为已经授权，也不在返回时自动重试，
                                    // 否则安装入口始终缺失的 ROM 会反复跳回设置。
                                    updateStatus(
                                        "已打开系统安全设置，请查找并允许「未知来源」，返回后再次点安装新版"
                                    )
                                } else {
                                    updateStatus(
                                        "无法打开系统安装器（$installerFailure）。" +
                                            (if (legacyApk != null) "安装副本位于 Android/data/${activity.packageName}/files/Download/guoying-tv-update.apk，可尝试用文件管理器打开。"
                                            else "请从正式发布页下载并用系统文件管理器安装新版。")
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                permissionRequested = false
                android.util.Log.w("HongguoTV", "Update installer setup failed: ${installerError(error)}")
                updateStatus("安装入口准备失败（${installerError(error)}），请通过系统文件管理器安装新版")
            }
        }
    }
}
