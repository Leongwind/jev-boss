package com.sanly.jevboss

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64
import android.util.AtomicFile

data class Resume(val id: String, val name: String, val text: String)
data class Match(val resumeId: String, val score: Double, val confidence: Double, val evidence: String)
enum class PageKind { JOB, CHAT, UNKNOWN }

object Session {
    private val jobLines = linkedSetOf<String>()
    private var pendingJobLines = emptyList<String>()
    private var jobAnchor: String? = null
    private var manualJobConfirmed = false
    private var chatAnchor: String? = null
    var jobText = ""
        private set
    var chatText = ""
        private set
    var latestHrMessage = ""
    var page = PageKind.UNKNOWN
        private set
    var confirmedForChat = false
    var hrMessageConfirmed = false
        private set
    var chatContextConfirmed = false
        private set
    var noReplyRecommended = false
    var ocrCaptures = 0
        private set
    var jobNeedsReview = false
        private set
    val needsJobIdentityConfirmation: Boolean
        get() = jobText.isNotBlank() && jobAnchor == null && !manualJobConfirmed
    var revision = 0L
        private set
    var chosenResumeId: String? = null
    var matches = emptyList<Match>()
    var drafts = emptyList<String>()
    var status = "请在 BOSS 打开职位详情"
    var busy = false

    fun invalidateDrafts() {
        revision++
        drafts = emptyList()
        noReplyRecommended = false
    }

    private fun jobChanged() {
        matches = emptyList()
        chosenResumeId = null
        confirmedForChat = false
        invalidateDrafts()
    }

    fun accept(kind: PageKind, visible: String) {
        if (kind == PageKind.UNKNOWN) return
        if (kind == PageKind.JOB) {
            val anchor = TextTools.jobAnchor(visible)
            val normalized = TextTools.lines(visible)
            if (anchor != null && anchor != jobAnchor) {
                clearJob()
                jobAnchor = anchor
            }
            page = PageKind.JOB
            when {
                anchor == null && jobAnchor == null && !manualJobConfirmed -> {
                    pendingJobLines = emptyList()
                    jobNeedsReview = false
                    val current = normalized.joinToString("\n").take(12000)
                    if (jobText != current) {
                        jobLines.clear()
                        jobLines.addAll(normalized)
                        jobText = current
                        jobChanged()
                    }
                    status = "未识别岗位身份，仅使用本屏文字；请核对后再生成"
                }
                anchor == null && normalized.none { it.length >= 8 && it in jobLines } && jobLines.isNotEmpty() -> {
                    pendingJobLines = normalized
                    jobNeedsReview = true
                    status = "本屏与已收集岗位无重合，已暂停累计；请确认是否同一岗位"
                }
                else -> {
                    pendingJobLines = emptyList()
                    jobNeedsReview = false
                    val before = jobText
                    normalized.forEach { jobLines.add(it) }
                    jobText = jobLines.joinToString("\n").take(12000)
                    if (jobText != before) jobChanged()
                    status = "已读取职位内容 ${jobText.length} 字；可继续手动滚动补全"
                }
            }
        } else {
            page = PageKind.CHAT
            val anchor = TextTools.chatAnchor(visible)
            if (anchor != null && chatAnchor != null && anchor != chatAnchor) {
                confirmedForChat = false
                chatText = ""
                latestHrMessage = ""
                hrMessageConfirmed = false
                chatContextConfirmed = false
                invalidateDrafts()
            }
            if (anchor != null) chatAnchor = anchor
            val current = TextTools.chatLines(visible).joinToString("\n").takeLast(5000)
            if (current != chatText) {
                chatText = current
                latestHrMessage = TextTools.latestChatLine(chatText)
                hrMessageConfirmed = false
                chatContextConfirmed = false
                confirmedForChat = false
                invalidateDrafts()
            }
            status = "已读取当前可见聊天 ${chatText.length} 字"
        }
    }

    fun confirmPendingJob() {
        if (!jobNeedsReview) return
        val before = jobText
        pendingJobLines.forEach { jobLines.add(it) }
        jobText = jobLines.joinToString("\n").take(12000)
        pendingJobLines = emptyList()
        jobNeedsReview = false
        if (jobText != before) jobChanged()
        status = "已人工确认并合并本屏岗位文字"
    }

    fun confirmCurrentJob() {
        if (jobAnchor == null && jobText.isNotBlank()) {
            manualJobConfirmed = true
            status = "已人工确认当前岗位；后续滚动会检查文字重合再累计"
        }
    }

    fun clearJob() {
        jobLines.clear()
        pendingJobLines = emptyList()
        jobAnchor = null
        manualJobConfirmed = false
        chatAnchor = null
        page = PageKind.UNKNOWN
        jobText = ""
        chatText = ""
        latestHrMessage = ""
        matches = emptyList()
        chosenResumeId = null
        invalidateDrafts()
        confirmedForChat = false
        hrMessageConfirmed = false
        chatContextConfirmed = false
        jobNeedsReview = false
        status = "已清空岗位，请打开职位详情"
    }

    fun setUnknownPage() {
        if (page != PageKind.UNKNOWN) invalidateDrafts()
        page = PageKind.UNKNOWN
        status = "当前不是职位详情或聊天页。上次岗位内容已保留；请打开目标岗位后再分析。"
    }

    fun updateChatForReply(text: String) {
        val current = text.takeLast(5000)
        if (chatText != current) {
            chatText = current
            hrMessageConfirmed = false
            chatContextConfirmed = false
            invalidateDrafts()
        }
    }

    fun updateLatestForReply(text: String) {
        if (latestHrMessage != text) {
            latestHrMessage = text
            hrMessageConfirmed = false
            invalidateDrafts()
        }
    }

    fun confirmHrMessage() {
        hrMessageConfirmed = latestHrMessage.isNotBlank()
    }

    fun confirmChatContext() {
        chatContextConfirmed = true
    }

    fun noteOcrCapture() {
        ocrCaptures++
    }

    fun leaveBoss() {
        chatText = ""
        latestHrMessage = ""
        chatAnchor = null
        confirmedForChat = false
        hrMessageConfirmed = false
        chatContextConfirmed = false
        page = PageKind.UNKNOWN
        invalidateDrafts()
    }
}

object TextTools {
    private val phone = Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val email = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
    private val idNumber = Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)")
    private val messenger = Regex("(?i)(微信|微信号|wechat|qq|qq号|身份证号?)\\s*[:：]?\\s*[A-Za-z0-9_-]{5,}")
    private val contactLine = Regex("(?i)^(姓名|性别|出生日期|生日|住址|现居住地|家庭住址|身份证|微信|wechat|qq|电话|手机号|邮箱|电子邮件|联系地址|籍贯)\\s*[:：]")
    private val namedPerson = Regex("[\\u4e00-\\u9fa5]{1,3}(先生|女士|小姐|老师|经理)")
    private val skills = listOf("Android", "Kotlin", "Java", "Jetpack Compose", "Compose", "协程", "Coroutines", "Flow", "MVVM", "性能优化", "架构", "Gradle", "Room", "Retrofit", "Flutter", "鸿蒙", "音视频")

    fun lines(raw: String): List<String> = raw.lines().map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length > 1 }.distinct()

    fun chatLines(raw: String): List<String> = raw.lines().map { it.trim().replace(Regex("\\s+"), " ") }
        .filter { it.length > 1 }

    fun redact(raw: String): String = namedPerson.replace(
        messenger.replace(email.replace(phone.replace(idNumber.replace(raw, "[已隐藏证件号]"), "[已隐藏电话]"), "[已隐藏邮箱]"),
            "$1：[已隐藏账号]"), "[已隐藏联系人]")

    fun redactResume(raw: String): String = redact(raw.lines().filterIndexed { index, line ->
        !contactLine.containsMatchIn(line.trim()) &&
            !(index < 3 && line.trim().matches(Regex("[\\u4e00-\\u9fa5]{2,3}")))
    }.joinToString("\n"))

    fun evidence(job: String, resume: String): String {
        val common = skills.filter { job.contains(it, true) && resume.contains(it, true) }.distinct().take(5)
        if (common.isEmpty()) return "未找到可直接引用的重合技能文字；请人工核对"
        val skill = common.first()
        val jobLine = lines(job).firstOrNull { it.contains(skill, true) }?.take(110).orEmpty()
        val resumeLine = lines(resume).firstOrNull { it.contains(skill, true) }?.take(110).orEmpty()
        return "重合词：${common.joinToString("、")}。JD 原文：「$jobLine」；简历原文：「$resumeLine」。原文摘录不等于满足岗位要求，请核对。"
    }

    fun pageKind(text: String): PageKind {
        val jobSignals = listOf("职位描述", "岗位职责", "任职要求", "职位要求", "工作内容", "职位详情")
        val chatSignals = listOf("发送", "聊天", "消息", "已读", "未读")
        return when {
            chatSignals.count { text.contains(it) } >= 2 -> PageKind.CHAT
            jobSignals.any { text.contains(it) } -> PageKind.JOB
            else -> PageKind.UNKNOWN
        }
    }

    fun jobAnchor(text: String): String? {
        val lines = lines(text)
        val salary = Regex("\\d+\\s*[-–]\\s*\\d+\\s*[Kk]")
        val position = lines.indexOfFirst { salary.containsMatchIn(it) }
        if (position <= 0) return null
        return lines[position - 1].take(100) + "|" + lines[position].take(40)
    }

    fun latestChatLine(chat: String): String = chatLines(chat).asReversed().firstOrNull { line ->
        line.length > 1 && !line.matches(Regex("\\d{1,2}:\\d{2}")) &&
            line !in setOf("回复消息", "发送", "换电话", "换微信", "发简历", "不感兴趣", "已读", "未读")
    }.orEmpty()

    fun mergeScreenText(nodes: String, ocr: String): String {
        val nodeLines = chatLines(nodes)
        val existing = nodeLines.groupingBy { it }.eachCount().toMutableMap()
        val extra = chatLines(ocr).filter { line ->
            val count = existing[line] ?: 0
            if (count > 0) {
                existing[line] = count - 1
                false
            } else true
        }
        return (nodeLines + extra).joinToString("\n")
    }

    fun chatAnchor(chat: String): String? {
        val header = lines(chat).take(3).filterNot { it in setOf("返回", "聊天", "消息") }
        return header.take(2).joinToString("|").takeIf { it.isNotBlank() }
    }
}

class ResumeStore(private val context: Context) {
    private val file get() = File(context.filesDir, "resumes.enc")
    private val legacyFile get() = File(context.filesDir, "resumes.json")
    private val alias = "jev_boss_resume_key"
    private var readFailed = false

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(raw: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(raw)
    }

    private fun decrypt(raw: ByteArray): ByteArray {
        require(raw.size > 12) { "简历库内容无效" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return cipher.doFinal(raw.copyOfRange(12, raw.size))
    }

    fun list(): List<Resume> = try {
        val json = when {
            file.exists() -> String(decrypt(AtomicFile(file).readFully()), Charsets.UTF_8)
            legacyFile.exists() -> legacyFile.readText()
            else -> "[]"
        }
        val array = JSONArray(json)
        (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Resume(item.getString("id"), item.getString("name"), item.getString("text"))
        }.also { items ->
            if (legacyFile.exists() && !file.exists()) {
                try {
                    save(items)
                    if (!legacyFile.delete()) readFailed = true
                } catch (_: Exception) {
                    readFailed = true
                }
            } else if (legacyFile.exists() && file.exists()) {
                if (!legacyFile.delete()) readFailed = true
            }
        }
    } catch (_: Exception) {
        readFailed = true
        emptyList()
    }

    fun errorMessage(): String? = if (readFailed) "简历库读取、解密或迁移失败；已禁止覆盖，请保留应用数据" else null

    private fun save(items: List<Resume>) {
        check(!readFailed) { "简历库读取失败，已禁止覆盖原数据" }
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("text", it.text)) }
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(encrypt(array.toString().toByteArray(Charsets.UTF_8)))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    suspend fun import(uri: Uri): Result<Resume> = withContext(Dispatchers.IO) {
        runCatching {
            val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "简历.pdf"
            require(name.endsWith(".pdf", true)) { "只支持 PDF 文件" }
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                PdfText.extract(context, input)
            } ?: error("无法打开 PDF")
            val clean = TextTools.lines(text).joinToString("\n")
            require(clean.length >= 100) { "PDF 文字不足，可能是扫描件；请使用文字版 PDF" }
            val resume = Resume(java.util.UUID.randomUUID().toString(), name, clean.take(40000))
            save(list() + resume)
            resume
        }
    }

    fun remove(id: String) = save(list().filterNot { it.id == id })
}

object PdfText {
    fun extract(context: Context, input: InputStream): String {
        PDFBoxResourceLoader.init(context)
        return PDDocument.load(input).use { document -> PDFTextStripper().getText(document) }
    }
}

class SecretStore(context: Context) {
    private val preferences = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "jev_boss_api_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String = try {
        val bytes = Base64.decode(preferences.getString(name, "") ?: "", Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    } catch (_: Exception) { "" }
}
