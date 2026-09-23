package com.sanly.jevboss

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executor

class BossAccessibilityService : AccessibilityService() {
    private enum class PanelMode { JOB, REPLY }
    private data class PreparedRequest(val revision: Long, val title: String, val body: String,
        val callCount: String, val action: () -> Unit)
    private data class QuestionEntry(var text: String, val needsUserFact: Boolean, var answer: String = "")
    private data class ReplyPlan(val latest: String, val chat: String,
        val questions: MutableList<QuestionEntry>, var confirmed: Boolean = false)
    private data class AuditNotice(val index: Int, val text: String, val warnings: List<String>,
        val revision: Long, val draftVersion: Long)
    private val bossPackage = "com.hpbr.bosszhipin"
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val resumeStore by lazy { ResumeStore(this) }
    private val api by lazy { DirectApi(SecretStore(this)) }
    private val windows by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private var bubble: TextView? = null
    private var panel: View? = null
    private var panelOpen = false
    private var captureInProgress = false
    private var scanPending = false
    private var lastCaptureAt = 0L
    private var lastPage = PageKind.UNKNOWN
    private var lastVisible = ""
    private var lastNodeVisible = ""
    private var lastOcrInput = ""
    private var panelMode = PanelMode.JOB
    private var showFullJob = false
    private var replyResumeId: String? = null
    private var replyPlan: ReplyPlan? = null
    private var draftVersion = 0L
    private var auditNotice: AuditNotice? = null
    private var preparedRequest: PreparedRequest? = null
    private var activeTask: Job? = null
    private var requestSerial = 0L
    private val scan = Runnable { scanCurrentPage() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == this.packageName) return
        if (packageName != bossPackage) {
            val inputMethod = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && packageName != inputMethod) {
                hideOverlay()
                Session.leaveBoss()
                lastNodeVisible = ""
                lastOcrInput = ""
            }
            return
        }
        if (!Settings.canDrawOverlays(this)) return
        if (!panelOpen) showBubble()
        handler.removeCallbacks(scan)
        handler.postDelayed(scan, if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) 120 else 300)
    }

    override fun onInterrupt() { hideOverlay() }

    override fun onDestroy() {
        handler.removeCallbacks(scan)
        hideOverlay()
        recognizer.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun scanCurrentPage() {
        if (captureInProgress) { scanPending = true; return }
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != bossPackage) return
        val lines = mutableListOf<String>()
        collect(root, lines, 0)
        val text = lines.joinToString("\n").take(12000)
        if (text != lastNodeVisible) {
            lastNodeVisible = text
            val kind = TextTools.pageKind(text)
            if (kind != PageKind.UNKNOWN && text != lastVisible) {
                accept(kind, text)
                lastVisible = text
            }
        }
        if (text != lastOcrInput) captureScreen(text)
    }

    private fun collect(node: AccessibilityNodeInfo, lines: MutableList<String>, depth: Int) {
        if (depth > 25 || lines.size > 300) return
        val nodeText = node.text?.toString()?.trim().orEmpty()
        if (nodeText.length > 1) lines.add(nodeText)
        node.contentDescription?.toString()?.trim()?.takeIf { it.length > 1 && it != nodeText }?.let(lines::add)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> collect(child, lines, depth + 1) }
        }
    }

    private fun accept(kind: PageKind, visible: String) {
        val pageChanged = kind != lastPage
        if (pageChanged) Session.invalidateDrafts()
        if (kind == PageKind.CHAT && pageChanged) Session.confirmedForChat = false
        val beforeRevision = Session.revision
        Session.accept(kind, visible)
        if (kind == PageKind.CHAT && (pageChanged || Session.revision != beforeRevision)) replyResumeId = null
        lastPage = kind
        if (pageChanged && kind == PageKind.CHAT) {
            panelMode = PanelMode.REPLY
        } else if (pageChanged && kind == PageKind.JOB) {
            panelMode = PanelMode.JOB
        }
        if (panelOpen) renderPanel()
    }

    private fun captureScreen(nodeText: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCaptureAt < 400) {
            handler.postDelayed(scan, 400 - (now - lastCaptureAt))
            return
        }
        lastCaptureAt = now
        lastOcrInput = nodeText
        captureInProgress = true
        bubble?.visibility = View.INVISIBLE
        panel?.visibility = View.INVISIBLE
        handler.postDelayed({
            if (rootInActiveWindow?.packageName?.toString() != bossPackage) {
                finishCapture()
                return@postDelayed
            }
            takeScreenshot(Display.DEFAULT_DISPLAY, Executor { command -> handler.post(command) },
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val buffer = screenshot.hardwareBuffer
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                        val copy = bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        buffer.close()
                        if (copy == null) {
                            finishCapture()
                            return
                        }
                        recognizer.process(InputImage.fromBitmap(copy, 0))
                            .addOnSuccessListener { result ->
                                val ocr = result.text.trim()
                                val combined = TextTools.mergeScreenText(nodeText, ocr)
                                val kind = TextTools.pageKind(combined)
                                if (rootInActiveWindow?.packageName?.toString() == bossPackage &&
                                    nodeText == lastOcrInput && kind != PageKind.UNKNOWN && combined != lastVisible) {
                                    accept(kind, combined)
                                    lastVisible = combined
                                    Session.noteOcrCapture()
                                } else if (rootInActiveWindow?.packageName?.toString() == bossPackage && kind == PageKind.UNKNOWN) {
                                    Session.setUnknownPage()
                                    lastPage = PageKind.UNKNOWN
                                    lastVisible = ""
                                }
                            }
                            .addOnFailureListener { Session.status = "本机 OCR 失败：${it.message ?: "未知错误"}" }
                            .addOnCompleteListener { copy.recycle(); finishCapture() }
                    }

                    override fun onFailure(errorCode: Int) {
                        Session.status = "屏幕截图不可用（代码 $errorCode），请检查读屏权限"
                        finishCapture()
                    }
                })
        }, 120)
    }

    private fun finishCapture() {
        captureInProgress = false
        bubble?.visibility = if (panelOpen) View.GONE else View.VISIBLE
        panel?.visibility = if (panelOpen) View.VISIBLE else View.GONE
        if (panelOpen) renderPanel()
        if (rootInActiveWindow?.packageName?.toString() == bossPackage && (scanPending || lastNodeVisible != lastOcrInput)) {
            scanPending = false
            handler.postDelayed(scan, 150)
        }
    }

    private fun rounded(color: Int, radius: Float = 20f) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
    }

    private fun params(width: Int, height: Int, gravity: Int, focusable: Boolean): WindowManager.LayoutParams =
        WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply { this.gravity = gravity }

    private fun showBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        val size = dp(56)
        bubble = TextView(this).apply {
            text = "荐"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(35, 91, 171), size / 2f)
            elevation = dp(8).toFloat()
            setOnClickListener {
                panelMode = if (Session.page == PageKind.CHAT) PanelMode.REPLY else PanelMode.JOB
                panelOpen = true
                visibility = View.GONE
                renderPanel()
            }
        }
        windows.addView(bubble, params(size, size, Gravity.END or Gravity.CENTER_VERTICAL, false).apply { x = dp(12) })
    }

    private fun hideOverlay() {
        requestSerial++
        activeTask?.cancel()
        activeTask = null
        Session.busy = false
        preparedRequest = null
        replyPlan = null
        auditNotice = null
        panelOpen = false
        panel?.let { runCatching { windows.removeView(it) } }
        bubble?.let { runCatching { windows.removeView(it) } }
        panel = null
        bubble = null
    }

    private fun closePanel() {
        panelOpen = false
        panel?.let { runCatching { windows.removeView(it) } }
        panel = null
        bubble?.visibility = View.VISIBLE
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderPanel() {
        if (!panelOpen || captureInProgress) return
        panel?.let { runCatching { windows.removeView(it) } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, dp(20).toFloat())
            elevation = dp(16).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(label(if (panelMode == PanelMode.REPLY) "HR 回复助手" else "职位与简历助手", 19f),
            LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(button("关闭") { closePanel() })
        root.addView(header)
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modes.addView(button(if (panelMode == PanelMode.JOB) "✓ 岗位 / 招呼语" else "岗位 / 招呼语") {
            switchMode(PanelMode.JOB)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        modes.addView(button(if (panelMode == PanelMode.REPLY) "✓ HR 对话回复" else "HR 对话回复") {
            switchMode(PanelMode.REPLY)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(modes)
        val scroller = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(body)
        root.addView(scroller)
        body.addView(label(Session.status, 14f))
        if (Session.busy) {
            body.addView(label("正在请求；取消等待后，已发出的 API 请求仍可能计费。", 14f))
            body.addView(button("取消等待") { cancelRequest() })
        }
        val prepared = preparedRequest?.takeIf { it.revision == Session.revision }
        if (preparedRequest != null && prepared == null) preparedRequest = null
        if (prepared != null) {
            renderPreparedRequest(body, prepared)
        } else {
            renderDrafts(body)
            when (panelMode) {
                PanelMode.JOB -> renderJob(body)
                PanelMode.REPLY -> renderChat(body)
            }
        }
        val height = (resources.displayMetrics.heightPixels * 0.72).toInt()
        panel = root
        windows.addView(root, params(-1, height, Gravity.BOTTOM, true))
    }

    private fun prepareRequest(title: String, body: String, callCount: String, action: () -> Unit) {
        preparedRequest = PreparedRequest(Session.revision, title, body, callCount, action)
        renderPanel()
    }

    private fun renderPreparedRequest(body: LinearLayout, request: PreparedRequest) {
        body.addView(label("发送前预览：${request.title}", 17f))
        body.addView(label(request.callCount, 13f))
        body.addView(label("以下是将发送的数据字段；服务提示词和候选文案也会参与对应请求。请检查是否仍含私人信息。", 13f))
        body.addView(TextView(this).apply {
            text = request.body
            textSize = 13f
            setTextColor(Color.rgb(30, 38, 50))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(Color.rgb(242, 245, 250), dp(10).toFloat())
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
        }, LinearLayout.LayoutParams(-1, dp(260)))
        body.addView(button("确认内容并发送请求") {
            preparedRequest = null
            if (request.revision != Session.revision) {
                Session.status = "页面或输入已变化，请重新预览"
                renderPanel()
            } else request.action()
        })
        body.addView(button("返回修改") { preparedRequest = null; renderPanel() })
    }

    private fun cancelRequest() {
        requestSerial++
        activeTask?.cancel()
        activeTask = null
        Session.busy = false
        Session.invalidateDrafts()
        Session.status = "已取消等待；已发出的 API 请求可能仍在服务端处理"
        renderPanel()
    }

    private fun renderDrafts(body: LinearLayout) {
        if (Session.drafts.isNotEmpty()) {
            body.addView(label("候选文案（可先修改再复制）", 16f))
            Session.drafts.forEachIndexed { index, draft ->
                val edit = EditText(this).apply {
                    setText(draft)
                    textSize = 15f
                    minLines = 2
                    maxLines = 6
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                        override fun afterTextChanged(s: Editable?) {
                            if (index < Session.drafts.size) {
                                Session.drafts = Session.drafts.toMutableList().apply { set(index, s.toString()) }
                                draftVersion++
                                auditNotice = null
                            }
                        }
                    })
                }
                body.addView(edit, LinearLayout.LayoutParams(-1, -2))
                val notice = auditNotice?.takeIf {
                    it.index == index && it.text == edit.text.toString() &&
                        it.revision == Session.revision && it.draftVersion == draftVersion
                }
                if (notice != null) {
                    body.addView(label("发送前复核提示：\n${notice.warnings.joinToString("\n")}", 14f))
                    body.addView(button("返回修改") { auditNotice = null; renderPanel() })
                    body.addView(button("我已核对，仍复制第 ${index + 1} 条") {
                        if (auditNotice == notice && edit.text.toString() == notice.text &&
                            Session.revision == notice.revision && draftVersion == notice.draftVersion) copyDraft(index, notice.text)
                        else { auditNotice = null; renderPanel() }
                    })
                } else {
                    body.addView(button(if (panelMode == PanelMode.REPLY) "复核并复制第 ${index + 1} 条" else "复制第 ${index + 1} 条") {
                        val edited = edit.text.toString().trim()
                        if (panelMode == PanelMode.REPLY) auditBeforeCopy(index, edited) else copyDraft(index, edited)
                    })
                }
            }
        }
    }

    private fun auditBeforeCopy(index: Int, edited: String) {
        val plan = replyPlan?.takeIf { it.latest == Session.latestHrMessage && it.chat == Session.chatText }
        if (plan == null || !plan.confirmed) {
            Session.status = "请先核对 HR 问题与真实答案，再复制回复"
            renderPanel()
            return
        }
        val revision = Session.revision
        val version = draftVersion
        val latest = Session.latestHrMessage
        val chat = Session.chatText
        val questions = plan.questions.map { HrQuestion(it.text, it.needsUserFact) }
        val answers = plan.questions.map { it.answer }
        val resume = resumeStore.list().firstOrNull { it.id == replyResumeId }
        prepareRequest("复核编辑后的回复", api.previewEditedAudit(edited, latest, chat, resume, questions, answers), "预计调用 Jev 1 次") {
            runTask("正在逐项检查编辑后的回复") {
                val audit = api.auditEditedReply(edited, latest, chat, resume, questions, answers)
                if (Session.revision == revision && draftVersion == version && index < Session.drafts.size &&
                    Session.drafts[index].trim() == edited) {
                    if (audit.warnings.isEmpty()) copyDraft(index, edited)
                    else {
                        auditNotice = AuditNotice(index, edited, audit.warnings, revision, version)
                        Session.status = "发现 ${audit.warnings.size} 项可能影响沟通的问题；请修改或人工核对"
                    }
                }
            }
        }
    }

    private fun copyDraft(index: Int, copied: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("求职文案", copied))
        handler.postDelayed({
            runCatching {
                val current = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
                if (current == copied) clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, 120_000)
        auditNotice = null
        Session.status = "已复制第 ${index + 1} 条；请手动发送，剪贴板将在 2 分钟后尝试清除"
        closePanel()
    }

    private fun switchMode(mode: PanelMode) {
        if (panelMode == mode) return
        panelMode = mode
        preparedRequest = null
        auditNotice = null
        Session.invalidateDrafts()
        renderPanel()
    }

    private fun renderJob(body: LinearLayout) {
        body.addView(label("当前职位内容（${Session.jobText.length} 字）", 16f))
        body.addView(label("本机 OCR 已补读 ${Session.ocrCaptures} 次；请逐屏稍停并核对 JD，快速划过的屏幕仍可能漏读。", 13f))
        body.addView(label(Session.jobText.let { if (showFullJob) it else it.take(350) }.ifBlank { "尚未读到 JD。请打开职位详情并手动滚动。" }, 13f))
        if (Session.jobText.length > 350) body.addView(button(if (showFullJob) "收起 JD" else "展开完整 JD 核对") {
            showFullJob = !showFullJob
            renderPanel()
        })
        if (Session.needsJobIdentityConfirmation) {
            body.addView(button("确认当前岗位并开始累计滚动文字") {
                Session.confirmCurrentJob()
                renderPanel()
            })
        }
        if (Session.jobNeedsReview) {
            body.addView(label("检测到一屏无法确认归属的职位内容，已停止自动合并。", 14f))
            body.addView(button("确认属于同一岗位并合并") {
                Session.confirmPendingJob()
                renderPanel()
            })
        }
        body.addView(button("清空并读取新岗位") {
            Session.clearJob()
            lastVisible = ""
            closePanel()
            handler.postDelayed(scan, 300)
        })
        body.addView(button("补读当前屏幕") {
            lastOcrInput = ""
            lastCaptureAt = 0L
            closePanel()
            handler.postDelayed(scan, 150)
        })
        val resumes = resumeStore.list()
        if (resumes.isEmpty()) body.addView(label("请在主界面导入文字版 PDF 简历。", 14f))
        body.addView(button("① 分析并推荐简历") {
            if (Session.jobNeedsReview) { Session.status = "请先确认本屏是否属于同一岗位"; renderPanel(); return@button }
            if (resumes.isEmpty()) { Session.status = "请先在主界面导入文字版 PDF 简历"; renderPanel(); return@button }
            if (Session.jobText.length < 50) { Session.status = "职位内容不足，请继续逐屏读取 JD"; renderPanel(); return@button }
            val revision = Session.revision
            val job = Session.jobText
            prepareRequest("分析简历", api.previewMatch(job, resumes), "预计调用 Jev ${(resumes.size + 3) / 4} 次") {
                runTask("正在使用 Jev 分析简历") {
                    val ranked = api.match(job, resumes)
                    if (Session.revision == revision) {
                        Session.matches = ranked
                        Session.chosenResumeId = ranked.firstOrNull()?.resumeId
                        Session.drafts = emptyList()
                        Session.status = "已推荐简历；可在下方改选"
                    }
                }
            }
        })
        if (Session.matches.isNotEmpty()) {
            body.addView(label("简历推荐（Jev 分数仅供参考；依据为原文摘录）", 16f))
            Session.matches.forEachIndexed { index, match ->
                val resume = resumes.firstOrNull { it.id == match.resumeId } ?: return@forEachIndexed
                val selected = Session.chosenResumeId == match.resumeId
                body.addView(RadioButton(this).apply {
                    text = "${if (index == 0) "推荐：" else ""}${resume.name}  评分 ${"%.2f".format(match.score)} / 4，置信度 ${"%.2f".format(match.confidence)}\n${match.evidence}"
                    isChecked = selected
                    setOnClickListener { Session.chosenResumeId = resume.id; Session.invalidateDrafts(); renderPanel() }
                })
            }
        }
        body.addView(button("② 生成最多 3 条招呼语") {
            val resume = resumes.firstOrNull { it.id == Session.chosenResumeId }
            if (resume == null) { Session.status = "请先分析并选择简历"; renderPanel(); return@button }
            if (Session.jobNeedsReview) { Session.status = "请先确认本屏是否属于同一岗位"; renderPanel(); return@button }
            val revision = Session.revision
            val job = Session.jobText
            prepareRequest("生成招呼语", api.previewGreeting(job, resume), "预计调用 DeepSeek 1 次、Jev 1 次") {
                runTask("正在生成招呼语") {
                    val drafts = api.greeting(job, resume) { stage -> Session.status = stage; renderPanel() }
                    if (Session.revision == revision) {
                        Session.drafts = drafts
                        Session.status = "已生成 ${drafts.size} 条经模型初筛的招呼语；请人工核对事实后复制"
                    }
                }
            }
        })
    }

    private fun renderChat(body: LinearLayout) {
        body.addView(label("可直接根据当前对话生成回复，无需先推荐简历或生成招呼语。", 14f))
        if (Session.page != PageKind.CHAT) {
            body.addView(label("当前未识别到 HR 聊天页；请进入聊天页，或在下方手动填写对话。", 14f))
        }
        body.addView(label("当前可见对话背景（可补充或修正）", 16f))
        body.addView(label("请按从旧到新的顺序核对；可用“HR：”“我：”标记说话人。未标注内容仅作背景，不作为你的经历依据。", 13f))
        body.addView(EditText(this).apply {
            setText(Session.chatText.takeLast(5000))
            hint = "尚未读到聊天文字，可手动补充当前上下文"
            textSize = 14f
            minLines = 4
            maxLines = 10
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { Session.updateChatForReply(s.toString()) }
            })
        })
        body.addView(button(if (Session.chatContextConfirmed) "✓ 已核对当前上下文" else "确认当前上下文（可为空）") {
            Session.confirmChatContext()
            renderPanel()
        })
        body.addView(label("本次要回复的 HR 消息（请核对，可修改）", 16f))
        body.addView(EditText(this).apply {
            setText(Session.latestHrMessage)
            textSize = 15f
            minLines = 2
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) { Session.updateLatestForReply(s.toString()) }
            })
        })
        body.addView(label("上方是自动提取的候选消息，可能来自求职者或页面控件。请核对说话人和内容。", 13f))
        body.addView(button(if (Session.hrMessageConfirmed) "✓ 已确认：这条消息来自 HR" else "确认：这条消息来自 HR") {
            Session.confirmHrMessage()
            renderPanel()
        })
        if (Session.jobText.isNotBlank()) {
            body.addView(label("可选岗位背景：${Session.jobText.lines().take(3).joinToString(" / ").take(100)}", 13f))
            body.addView(button(if (Session.confirmedForChat) "✓ 已确认使用该岗位（点击取消）" else "确认聊天关联上次岗位（可选）") {
                Session.confirmedForChat = !Session.confirmedForChat
                Session.invalidateDrafts()
                renderPanel()
            })
        } else {
            body.addView(label("未关联岗位；仍可仅根据聊天内容生成回复。", 13f))
        }
        val resumes = resumeStore.list()
        body.addView(label("可选简历背景（可直接挑选，不依赖岗位分析）", 14f))
        body.addView(RadioButton(this).apply {
            text = "不使用简历，仅根据聊天回复"
            isChecked = replyResumeId == null
            setOnClickListener { replyResumeId = null; replyPlan = null; Session.invalidateDrafts(); renderPanel() }
        })
        resumes.forEach { item ->
            body.addView(RadioButton(this).apply {
                text = item.name
                isChecked = replyResumeId == item.id
                setOnClickListener { replyResumeId = item.id; replyPlan = null; Session.invalidateDrafts(); renderPanel() }
            })
        }
        val resume = resumes.firstOrNull { it.id == replyResumeId }
        val latestNow = Session.latestHrMessage.trim()
        val plan = replyPlan?.takeIf { it.latest == latestNow && it.chat == Session.chatText }
        if (replyPlan != null && plan == null) replyPlan = null
        body.addView(button("① 拆解 HR 消息中的问题") {
            if (latestNow.isBlank() || !Session.hrMessageConfirmed || !Session.chatContextConfirmed) {
                Session.status = "请先核对上下文，并确认待回复消息来自 HR"
                renderPanel(); return@button
            }
            replyPlan = null
            auditNotice = null
            Session.invalidateDrafts()
            val revision = Session.revision
            val sourceChat = Session.chatText
            val chatForApi = sourceChat.ifBlank { latestNow }
            prepareRequest("拆解 HR 问题", api.previewQuestionAnalysis(latestNow, chatForApi, resume), "预计调用 DeepSeek 1 次") {
                runTask("正在拆解 HR 消息中的独立问题") {
                    val questions = api.analyzeQuestions(latestNow, chatForApi, resume)
                    if (Session.revision == revision) {
                        replyPlan = ReplyPlan(latestNow, sourceChat,
                            questions.map { QuestionEntry(it.text, it.needsUserFact) }.toMutableList())
                        Session.status = "已拆出 ${questions.size} 项，请核对并补充真实答案"
                    }
                }
            }
        })
        if (plan != null) renderQuestionPlan(body, plan)
        if (Session.noReplyRecommended) body.addView(label("Jev 判断当前消息可能无需回复；你可以直接结束本轮对话。", 15f))
        body.addView(button(if (Session.noReplyRecommended) "③ 仍生成回复候选" else "③ 根据问题清单生成最多 3 条回复") {
            if (plan == null || !plan.confirmed || !Session.hrMessageConfirmed || !Session.chatContextConfirmed) {
                Session.status = "请先拆解并确认每个 HR 问题与真实答案"
                renderPanel(); return@button
            }
            val forceReply = Session.noReplyRecommended
            val revision = Session.revision
            val latest = latestNow
            val chat = Session.chatText.ifBlank { latest }
            val job = if (Session.confirmedForChat) Session.jobText else ""
            val questions = plan.questions.map { HrQuestion(it.text, it.needsUserFact) }
            val answers = plan.questions.map { it.answer }
            prepareRequest("根据聊天生成回复", api.previewReply(job, resume, chat, latest, questions, answers),
                if (forceReply) "预计调用 DeepSeek 1 次、Jev 1 次" else "最多调用 Jev 2 次、DeepSeek 1 次") {
                runTask("正在判断 HR 消息意图（Jev）") {
                    api.ensureGenerationConfigured()
                    val intent = if (forceReply) "no_reply" else api.hrIntent(latest, chat)
                    if (Session.revision != revision) return@runTask
                    if (intent == "no_reply" && !forceReply) {
                        Session.noReplyRecommended = true
                        Session.status = "当前消息可能无需回复；如确需回应，可点击“仍生成回复候选”"
                        return@runTask
                    }
                    val drafts = api.reply(job, resume, chat, latest, intent,
                        { stage -> Session.status = stage; renderPanel() }, questions, answers)
                    if (Session.revision == revision) {
                        Session.drafts = drafts
                        Session.noReplyRecommended = false
                        Session.status = "HR 意图：$intent；已生成 ${drafts.size} 条经模型初筛的回复，请人工核对后复制"
                    }
                }
            }
        })
    }

    private fun renderQuestionPlan(body: LinearLayout, plan: ReplyPlan) {
        body.addView(label("HR 问题清单（${plan.questions.size} 项，可改写、删除或补充）", 16f))
        plan.questions.forEachIndexed { index, entry ->
            body.addView(label("${index + 1}. ${if (entry.needsUserFact) "需你确认事实" else "可依据已提供内容回答"}", 14f))
            body.addView(EditText(this).apply {
                setText(entry.text)
                hint = "HR 的第 ${index + 1} 个问题或请求"
                textSize = 14f
                minLines = 1
                maxLines = 3
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        entry.text = s.toString().trim()
                        plan.confirmed = false
                        auditNotice = null
                        Session.invalidateDrafts()
                    }
                })
            })
            body.addView(EditText(this).apply {
                setText(entry.answer)
                hint = if (entry.needsUserFact) "填写你的真实答案；留空则不作承诺，改为澄清" else "可选：补充你确认过的事实"
                textSize = 14f
                minLines = 1
                maxLines = 3
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        entry.answer = s.toString().trim()
                        plan.confirmed = false
                        auditNotice = null
                        Session.invalidateDrafts()
                    }
                })
            })
            body.addView(button("删除第 ${index + 1} 项") {
                plan.questions.remove(entry)
                plan.confirmed = false
                Session.invalidateDrafts()
                renderPanel()
            })
        }
        if (plan.questions.size < 10) {
            val extra = EditText(this).apply { hint = "模型漏掉的问题，可在这里补充"; textSize = 14f }
            body.addView(extra)
            body.addView(button("添加遗漏问题") {
                val text = extra.text.toString().trim()
                if (text.isNotBlank()) {
                    plan.questions.add(QuestionEntry(text.take(250), true))
                    plan.confirmed = false
                    Session.invalidateDrafts()
                    renderPanel()
                }
            })
        }
        body.addView(label("需要个人事实的项目请填写准确答案；不确定可留空，让回复明确澄清。", 13f))
        body.addView(button(if (plan.confirmed) "✓ 已确认问题与事实" else "② 确认问题清单与真实答案") {
            if (plan.questions.isEmpty() || plan.questions.any { it.text.isBlank() }) {
                Session.status = "请至少保留一项，并填写问题文字"
            } else {
                plan.confirmed = true
                Session.status = "问题与事实已确认；现在可以生成回复"
            }
            renderPanel()
        })
    }

    private fun label(text: String, size: Float): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.rgb(30, 38, 50))
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun runTask(message: String, block: suspend () -> Unit) {
        if (Session.busy) return
        val serial = ++requestSerial
        Session.busy = true
        Session.status = message
        renderPanel()
        activeTask = scope.launch {
            try { block() }
            catch (_: CancellationException) { /* Cancelled by the user or when leaving BOSS. */ }
            catch (error: Exception) {
                if (serial == requestSerial) Session.status = error.message ?: "请求失败，请稍后重试"
            }
            finally {
                if (serial == requestSerial) {
                    Session.busy = false
                    activeTask = null
                    renderPanel()
                }
            }
        }
    }
}
