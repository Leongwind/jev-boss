package com.sanly.jevboss

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HrQuestion(val text: String, val needsUserFact: Boolean)
data class DraftAudit(val warnings: List<String>)

class DirectApi(private val keys: SecretStore) {
    private fun post(url: String, key: String, body: JSONObject): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val response = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = response?.bufferedReader()?.use { it.readText() } ?: ""
            if (connection.responseCode !in 200..299) {
                val message = runCatching { JSONObject(responseText).optJSONObject("error")?.optString("message") }.getOrNull()
                error("API 请求失败 ${connection.responseCode}${message?.let { "：${TextTools.redact(it).take(160)}" } ?: ""}")
            }
            return JSONObject(responseText)
        } finally { connection.disconnect() }
    }

    suspend fun match(job: String, resumes: List<Resume>): List<Match> = withContext(Dispatchers.IO) {
        require(job.length >= 50) { "职位内容不足，请在职位详情页继续滚动或检查读屏权限" }
        require(resumes.isNotEmpty()) { "请先导入至少一份 PDF 简历" }
        val key = keys.get("typesafe")
        require(key.isNotBlank()) { "请先在主界面填写 Jev/TypeSafe API 密钥" }
        resumes.chunked(4).flatMap { group ->
            val questions = JSONObject()
            group.forEachIndexed { index, resume ->
                questions.put("resume_$index", JSONObject()
                    .put("type", "score")
                    .put("instructions", JSONObject()
                        .put("question", "根据职位JD评估这份真实简历与岗位的匹配程度。仅依据有明确证据的技术、年限和项目经历，不要臆测。")
                        .put("resume", TextTools.redactResume(resume.text).take(4000)))
                    .put("criteria", JSONArray(listOf(
                        "几乎无相关技能和经历", "少量相关技能但关键要求缺失", "部分满足岗位要求",
                        "多数关键要求有实际经历支撑", "核心要求均有明确经历支撑"
                    ))))
            }
            val body = JSONObject().put("model", "jev-latest")
                .put("state", JSONObject().put("job", TextTools.redact(job).take(8000)))
                .put("questions", questions)
            val answers = post("https://api.typesafe.ai/v1/systemone", key, body).getJSONObject("answers")
            group.mapIndexed { index, resume ->
                val answer = answers.getJSONObject("resume_$index")
                Match(resume.id, answer.getDouble("score"), answer.getDouble("confidence"),
                    TextTools.evidence(job, resume.text))
            }
        }.sortedWith(compareByDescending<Match> { it.score }.thenByDescending { it.confidence })
    }

    suspend fun hrIntent(latest: String, chat: String): String = withContext(Dispatchers.IO) {
        val key = keys.get("typesafe")
        require(key.isNotBlank()) { "请先填写 Jev/TypeSafe API 密钥" }
        val choices = JSONObject()
            .put("resume", "HR 要求发送简历或介绍经历")
            .put("interview", "HR 邀约或确认面试时间")
            .put("skills", "HR 询问技术、项目或工作经验")
            .put("salary", "HR 询问薪资、期望或到岗时间")
            .put("no_reply", "HR 仅确认、结束对话或当前没有需要回应的问题")
            .put("other", "其他问题、问候或上下文不足")
        val question = JSONObject().put("type", "choice")
            .put("instructions", "只判断 latest_hr_message 的意图，history 仅用于理解背景；若只是收到、好的、谢谢或结束语且继续回复可能多余，选 no_reply；上下文不足选择 other")
            .put("criteria", choices)
        val body = JSONObject().put("model", "jev-latest")
            .put("state", JSONObject().put("latest_hr_message", TextTools.redact(latest).take(1500))
                .put("history", TextTools.redact(chat).takeLast(3500)))
            .put("questions", JSONObject().put("intent", question))
        post("https://api.typesafe.ai/v1/systemone", key, body)
            .getJSONObject("answers").getJSONObject("intent").optString("choice", "other")
    }

    fun ensureGenerationConfigured() {
        require(keys.get("typesafe").isNotBlank()) { "请先在主界面填写 Jev/TypeSafe API 密钥" }
        require(keys.get("deepseek").isNotBlank()) { "请先在主界面填写 DeepSeek API 密钥" }
    }

    fun previewMatch(job: String, resumes: List<Resume>): String = buildString {
        append("发送至 Jev/TypeSafe：\n职位 JD：\n${TextTools.redact(job).take(8000)}\n")
        resumes.forEachIndexed { index, resume ->
            append("\n简历 ${index + 1}（最多 4000 字）：\n${TextTools.redactResume(resume.text).take(4000)}\n")
        }
    }

    fun previewGreeting(job: String, resume: Resume): String =
        "发送至 DeepSeek：\n职位 JD：\n${TextTools.redact(job).take(10000)}\n\n简历：\n${TextTools.redactResume(resume.text).take(10000)}\n\n" +
            "发送至 Jev/TypeSafe 进行候选初筛：简历前 7000 字和 DeepSeek 返回的候选文案。"

    fun previewReply(job: String, resume: Resume?, chat: String, latest: String,
        questions: List<HrQuestion> = emptyList(), userAnswers: List<String> = emptyList()): String =
        "发送至 Jev/TypeSafe 判断意图：本次 HR 消息及可见聊天末尾 3500 字。\n" +
            "发送至 DeepSeek，并发送候选至 Jev/TypeSafe 初筛：\n" +
            "本次 HR 消息：\n${TextTools.redact(latest).take(1500)}\n\n" +
            "聊天背景：\n${TextTools.redact(chat).takeLast(5000)}\n\n" +
            "职位 JD：\n${TextTools.redact(job).take(8000).ifBlank { "未提供" }}\n\n" +
            "简历：\n${resume?.text?.let { TextTools.redactResume(it).take(8000) } ?: "未提供"}\n\n" +
            "已核对的问题与事实：\n${questions.mapIndexed { index, question ->
                "${index + 1}. ${TextTools.redact(question.text)}；你的答案：${TextTools.redact(userAnswers.getOrNull(index).orEmpty()).ifBlank { "未提供" }}"
            }.joinToString("\n")}"

    fun previewQuestionAnalysis(latest: String, chat: String, resume: Resume?): String =
        "发送至 DeepSeek 拆解问题：\n最新 HR 消息：\n${TextTools.redact(latest).take(1500)}\n\n" +
            "可见聊天：\n${TextTools.redact(chat).takeLast(3500)}\n\n" +
            "可选简历：\n${resume?.text?.let { TextTools.redactResume(it).take(5000) } ?: "未提供"}"

    fun previewEditedAudit(draft: String, latest: String, chat: String, resume: Resume?,
        questions: List<HrQuestion>, answers: List<String>): String =
        "发送至 Jev/TypeSafe 复核：\n编辑后的文案：\n${TextTools.redact(draft).take(1800)}\n\n" +
            "最新 HR 消息：\n${TextTools.redact(latest).take(1500)}\n\n" +
            "可见聊天：\n${TextTools.redact(chat).takeLast(3000)}\n\n" +
            "简历：\n${resume?.text?.let { TextTools.redactResume(it).take(7000) } ?: "未提供"}\n\n" +
            questions.mapIndexed { index, question ->
                "${index + 1}. ${TextTools.redact(question.text)}；你的事实：${TextTools.redact(answers.getOrNull(index).orEmpty()).ifBlank { "未提供" }}"
            }.joinToString("\n")

    suspend fun analyzeQuestions(latest: String, chat: String, resume: Resume?): List<HrQuestion> = withContext(Dispatchers.IO) {
        val key = keys.get("deepseek")
        require(key.isNotBlank()) { "请先在主界面填写 DeepSeek API 密钥" }
        val item = JSONObject().put("type", "object")
            .put("properties", JSONObject()
                .put("question", JSONObject().put("type", "string"))
                .put("needs_user_fact", JSONObject().put("type", "boolean")))
            .put("required", JSONArray(listOf("question", "needs_user_fact")))
            .put("additionalProperties", false)
        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("questions", JSONObject().put("type", "array")
                .put("items", item).put("minItems", 1).put("maxItems", 10)))
            .put("required", JSONArray(listOf("questions"))).put("additionalProperties", false)
        val input = "最新 HR 消息：\n${TextTools.redact(latest).take(1500)}\n\n" +
            "可见聊天（只用于理解，不要把旧消息当成新问题）：\n${TextTools.redact(chat).takeLast(3500)}\n\n" +
            "简历：\n${resume?.text?.let { TextTools.redactResume(it).take(5000) } ?: "未提供"}\n\n" +
            "只拆解最新消息实际提出的独立问题或请求，保留原意和顺序，不补造问题。若只是确认或结束语，列出一个简短回应事项。" +
            "needs_user_fact 仅在回答必须知道求职者个人状态、具体时间、薪资意愿或简历未明确记载的经历时为 true。"
        val body = JSONObject().put("model", "deepseek-flash")
            .put("instructions", "你是求职对话问题拆解器。只输出符合 JSON schema 的内容。")
            .put("input", input).put("reasoning", JSONObject().put("effort", "none"))
            .put("max_output_tokens", 700)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema")
                .put("name", "hr_questions").put("schema", schema)))
        val array = JSONObject(responseText(post("https://api.deepseek.com/responses", key, body))).getJSONArray("questions")
        (0 until minOf(array.length(), 10)).map { index ->
            val value = array.getJSONObject(index)
            HrQuestion(value.getString("question").trim().take(250), value.getBoolean("needs_user_fact"))
        }.filter { it.text.isNotBlank() }.ifEmpty { listOf(HrQuestion(latest.take(250), false)) }
    }

    suspend fun greeting(job: String, resume: Resume, onStage: (String) -> Unit = {}): List<String> = generateChecked(
        "招呼语", resume, "", "", 3,
        "职位JD：\n${TextTools.redact(job).take(10000)}\n\n本人简历：\n${TextTools.redactResume(resume.text).take(10000)}\n\n" +
            "请写三条适合向招聘者首次打招呼的简短中文消息，分别偏向直接、专业、亲切。突出确有依据的匹配点，不编造经历、薪资、到岗承诺或联系方式。",
        onStage
    )

    suspend fun reply(job: String, resume: Resume?, chat: String, latest: String, intent: String, onStage: (String) -> Unit = {},
        questions: List<HrQuestion> = emptyList(), userAnswers: List<String> = emptyList()): List<String> = generateChecked(
        "HR 回复", resume, chat, latest, 3,
        "职位JD：\n${TextTools.redact(job).take(8000).ifBlank { "未提供，不要推测岗位要求" }}\n\n" +
            "本人简历：\n${resume?.text?.let { TextTools.redactResume(it).take(8000) } ?: "未提供，不要编造求职者经历或技能"}\n\n" +
            "当前屏幕可见聊天背景（可能不完整）：\n${TextTools.redact(chat).takeLast(5000)}\n\n" +
            "本次必须针对的最新 HR 消息：\n${TextTools.redact(latest).take(1500)}\n\n" +
            "逐项待答：\n${questions.mapIndexed { index, question -> "${index + 1}. ${TextTools.redact(question.text)}；用户确认的答案：${TextTools.redact(userAnswers.getOrNull(index).orEmpty()).ifBlank { "未提供；不能猜测，应合理澄清" }}" }.joinToString("\n")}\n\n" +
            "Jev 判断的最新消息意图：$intent。请写三条简短、礼貌、自然的中文回复候选，逐项回应或合理澄清上述每个问题；不要把历史中的旧问题当作当前问题，也不要写通用初次招呼或称呼对方具体姓名。聊天中只有以“我：”或“求职者：”明确标注的内容可作为求职者已说过的事实；未标注的旧消息只作背景。仅依据提供的聊天、可选简历和用户确认的答案回答；未知经历、时间、薪资、意愿等不得替用户承诺。",
        onStage, questions.mapIndexed { index, question ->
            "问题：${question.text}；用户确认答案：${userAnswers.getOrNull(index).orEmpty()}"
        }.joinToString("\n")
    )

    private suspend fun generateChecked(kind: String, resume: Resume?, chat: String, latest: String, count: Int, context: String,
        onStage: (String) -> Unit = {}, confirmedFacts: String = ""): List<String> {
        ensureGenerationConfigured()
        onStage("正在生成候选文案（DeepSeek）")
        val messages = generate(kind, count, context)
        onStage("正在核查事实与话题（Jev）")
        val valid = validateCandidates(resume, chat, latest, messages, confirmedFacts)
        val accepted = messages.filterIndexed { index, message -> valid[index] && TextTools.redact(message) == message }.distinct()
        if (accepted.isNotEmpty()) return accepted
        error("候选未通过事实或上下文初筛。请核对输入后手动重试")
    }

    private suspend fun validateCandidates(resume: Resume?, chat: String, latest: String, messages: List<String>, confirmedFacts: String): List<Boolean> = withContext(Dispatchers.IO) {
        val key = keys.get("typesafe")
        require(key.isNotBlank()) { "请先填写 Jev/TypeSafe API 密钥" }
        val questions = JSONObject()
        messages.forEachIndexed { index, message ->
            questions.put("draft_$index", JSONObject()
                .put("type", "noul")
                .put("instructions", JSONObject()
                    .put("question", "候选文案是否声称了所提供简历（若有）、明确以“我：”或“求职者：”标注的已发消息及用户本次确认事实均不支持的具体经历、技能、学历、薪资、到岗时间或承诺？未标注说话人的聊天文字不能作为求职者事实。HR 的提问不能当作求职者事实；礼貌与兴趣表达不算无依据事实。")
                    .put("candidate", message))
                .put("criteria", JSONObject()
                    .put("true", "存在未获支持的具体求职者事实或承诺")
                    .put("false", "只有有依据的事实、礼貌表达或澄清问题")))
            if (latest.isNotBlank()) questions.put("relevant_$index", JSONObject()
                .put("type", "noul")
                .put("instructions", JSONObject()
                    .put("question", "候选回复是否直接且恰当地回应 latest_hr_message？history 只用于背景。若最新消息只是确认/结束语，简短跟进或说明无需回复算恰当；通用初次招呼、自我介绍、回答旧问题均算不恰当。")
                    .put("candidate", message))
                .put("criteria", JSONObject()
                    .put("true", "围绕最新 HR 消息推进当前对话")
                    .put("false", "通用招呼、答非所问或只回应旧话题")))
        }
        val state = JSONObject()
            .put("resume", resume?.text?.let { TextTools.redactResume(it).take(7000) } ?: "未提供")
            .put("visible_chat", TextTools.redact(chat).takeLast(3000))
            .put("latest_hr_message", TextTools.redact(latest).take(1500))
            .put("confirmed_user_facts", TextTools.redact(confirmedFacts).take(1500))
        val body = JSONObject().put("model", "jev-latest").put("state", state).put("questions", questions)
        val answers = post("https://api.typesafe.ai/v1/systemone", key, body).getJSONObject("answers")
        messages.indices.map { index ->
            answers.getJSONObject("draft_$index").getDouble("noul") < 0.5 &&
                (latest.isBlank() || answers.getJSONObject("relevant_$index").getDouble("noul") >= 0.5)
        }
    }

    suspend fun auditEditedReply(draft: String, latest: String, chat: String, resume: Resume?,
        questions: List<HrQuestion>, userAnswers: List<String>): DraftAudit = withContext(Dispatchers.IO) {
        val key = keys.get("typesafe")
        require(key.isNotBlank()) { "请先在主界面填写 Jev/TypeSafe API 密钥" }
        val criteria = JSONObject().put("true", "存在所描述的问题").put("false", "不存在所描述的问题")
        val checks = JSONObject()
        questions.take(10).forEachIndexed { index, question ->
            checks.put("missing_$index", JSONObject().put("type", "noul")
                .put("instructions", JSONObject().put("question",
                    "候选回复是否漏答或未合理澄清这一项：${TextTools.redact(question.text)}？仅提到关键词但没有回答或澄清也算漏答。")
                    .put("candidate", TextTools.redact(draft).take(1800)))
                .put("criteria", criteria))
        }
        listOf(
            "unsupported" to "候选回复是否声称了简历、求职者明确说过的话及本次用户确认答案均不支持的具体事实？",
            "promise" to "候选回复是否把未确认的面试时间、薪资、到岗日期或意愿写成确定承诺？",
            "tone" to "候选回复是否明显失礼、答非所问或过度模板化，以致妨碍本轮沟通？"
        ).forEach { (name, question) ->
            checks.put(name, JSONObject().put("type", "noul")
                .put("instructions", JSONObject().put("question", question)
                    .put("candidate", TextTools.redact(draft).take(1800)))
                .put("criteria", criteria))
        }
        val state = JSONObject()
            .put("latest_hr_message", TextTools.redact(latest).take(1500))
            .put("visible_chat", TextTools.redact(chat).takeLast(3000))
            .put("resume", resume?.text?.let { TextTools.redactResume(it).take(7000) } ?: "未提供")
            .put("confirmed_user_facts", TextTools.redact(questions.mapIndexed { index, question ->
                "问题：${question.text}；用户确认答案：${userAnswers.getOrNull(index).orEmpty()}"
            }.joinToString("\n")).take(1500))
        val body = JSONObject().put("model", "jev-latest").put("state", state).put("questions", checks)
        val values = post("https://api.typesafe.ai/v1/systemone", key, body).getJSONObject("answers")
        val warnings = mutableListOf<String>()
        if (TextTools.redact(draft) != draft) warnings.add("文案含联系方式或其他可识别信息，请确认是否需要发送")
        questions.take(10).forEachIndexed { index, question ->
            if (values.getJSONObject("missing_$index").getDouble("noul") >= 0.5) {
                warnings.add("可能漏答或未澄清：${question.text}")
            }
        }
        if (values.getJSONObject("unsupported").getDouble("noul") >= 0.5) warnings.add("可能包含未经确认的个人事实")
        if (values.getJSONObject("promise").getDouble("noul") >= 0.5) warnings.add("可能把未确认事项写成了承诺")
        if (values.getJSONObject("tone").getDouble("noul") >= 0.7) warnings.add("语气或话题可能不适合当前对话")
        DraftAudit(warnings)
    }

    private fun responseText(result: JSONObject): String {
        val output = result.optJSONArray("output") ?: error("DeepSeek 未返回内容")
        return (0 until output.length()).asSequence()
            .map { output.getJSONObject(it) }.filter { it.optString("type") == "message" }
            .flatMap { item ->
                val content = item.optJSONArray("content") ?: JSONArray()
                (0 until content.length()).asSequence().map { content.getJSONObject(it) }
            }.firstOrNull { it.optString("type") == "output_text" }?.optString("text")
            ?: error("DeepSeek 未返回文字")
    }

    private suspend fun generate(kind: String, count: Int, context: String): List<String> = withContext(Dispatchers.IO) {
        val key = keys.get("deepseek")
        require(key.isNotBlank()) { "请先在主界面填写 DeepSeek API 密钥" }
        val schema = JSONObject().put("type", "object")
            .put("properties", JSONObject().put("messages", JSONObject().put("type", "array")
                .put("items", JSONObject().put("type", "string"))
                .put("minItems", count).put("maxItems", count)))
            .put("required", JSONArray(listOf("messages")))
            .put("additionalProperties", false)
        val body = JSONObject().put("model", "deepseek-flash")
            .put("instructions", "你是求职文案助手。只输出符合 JSON schema 的候选文字。不要编造用户简历没有的事实。")
            .put("input", "任务：$kind。\n$context")
            .put("reasoning", JSONObject().put("effort", "none"))
            .put("max_output_tokens", 800)
            .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema")
                .put("name", "message_candidates").put("schema", schema)))
        val text = responseText(post("https://api.deepseek.com/responses", key, body))
        val array = JSONObject(text).getJSONArray("messages")
        require(array.length() == count) { "DeepSeek 返回的候选数量不正确" }
        (0 until array.length()).map { array.getString(it).trim() }.filter { it.isNotBlank() }
    }
}
