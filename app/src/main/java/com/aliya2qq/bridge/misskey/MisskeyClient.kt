package com.aliya2qq.bridge.misskey

import com.aliya2qq.bridge.config.BridgeConfig
import com.aliya2qq.bridge.util.BridgeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

open class MisskeyAPIError(val status: Int, val code: String = "", val apiMessage: String = "") :
    Exception("Misskey API 错误 $status: $code $apiMessage") {
    /** 面向 QQ 用户的中文说明。 */
    open fun friendly(): String = friendlyError(code, status, apiMessage)
}

open class RateLimitError(val retryAfter: Double) :
    MisskeyAPIError(429, "RATE_LIMIT_EXCEEDED", "请 ${retryAfter.toInt()}s 后重试") {
    override fun friendly() = "Misskey 限流中，请约 ${maxOf(1, retryAfter.toInt())} 秒后再试。"
}

open class BannedError(code: String) : MisskeyAPIError(403, code, "会话/角色被封禁")

/** Token 失效或权限不足：需要重新绑定。 */
class AuthError(status: Int, code: String) : MisskeyAPIError(status, code, "")

private val AUTH_CODES = setOf("AUTHENTICATION_FAILED", "CREDENTIAL_REQUIRED", "YOUR_ACCOUNT_SUSPENDED")

/** 常见错误码的中文说明；未收录的给出原始码便于排查。 */
fun friendlyError(code: String, status: Int, message: String = ""): String = when (code) {
    "AUTHENTICATION_FAILED", "CREDENTIAL_REQUIRED" -> "Misskey Token 已失效，请重新 /bind 绑定。"
    "YOUR_ACCOUNT_SUSPENDED" -> "Misskey 账号已被冻结。"
    "PERMISSION_DENIED" -> "Token 权限不足，请重新 /bind（MiAuth 会申请所需权限）。"
    "ROLE_PERMISSION_DENIED" -> "当前账号角色无权使用该功能。"
    "AGENT_SESSION_MODERATION_BANNED" -> "当前会话已被封禁，发送 /new 新建会话。"
    "AGENT_CHARACTER_MODERATION_BANNED" -> "角色已被封禁，暂时无法对话。"
    "AGENT_INSUFFICIENT_CREDIT", "AGENT_VISION_INSUFFICIENT_CREDIT" -> "Agent 余额不足，可 /checkin 签到或在网页端充值。"
    "AGENT_IMAGE_INSUFFICIENT_CREDIT" -> "余额不足，无法生成图片。"
    "AGENT_REPLY_PENDING" -> "上一条消息还在生成回复，请稍等（或发送 /stop 中止）。"
    "AGENTS_LLM_ABORTED" -> "本次回复已中止。"
    "AGENTS_LLM_TIMEOUT" -> "AI 回复超时，请稍后重试（/retry）。"
    "AGENTS_LLM_FAILED" -> "AI 回复失败，请稍后重试（/retry）。"
    "AGENTS_MODEL_UNAVAILABLE" -> "当前对话模型不可用，请 /model 换一个。"
    "AGENTS_MODEL_NOT_CONFIGURED" -> "实例未配置对话模型。"
    "AGENTS_DISABLED" -> "实例已关闭智能体功能。"
    "AGENT_DIALOGUE_STYLE_REQUIRED", "STYLE_NOT_AVAILABLE", "STYLE_NOT_SUBSCRIBED", "STYLE_NOT_PUBLISHED" ->
        "当前文风不可用，请 /style 重新选择。"
    "NO_SUCH_SESSION" -> "会话不存在，发送 /new 新建。"
    "NO_SUCH_CHARACTER", "AGENT_PUBLISHED_UNAVAILABLE" -> "角色不存在或已下架，请检查设置中的 character_id。"
    "AGENT_MESSAGE_EMPTY" -> "消息内容为空。"
    "MEMORY_NOT_AVAILABLE" -> "当前会话未开启长期记忆。"
    "MEMORY_PROVIDER_ERROR" -> "长期记忆服务暂时不可用。"
    "RATE_LIMIT_EXCEEDED" -> "Misskey 限流中，请稍后再试。"
    else -> "Misskey 错误（$status${if (code.isNotBlank()) " $code" else ""}）" +
        (if (message.isNotBlank()) "：${message.take(120)}" else "")
}

/** agents/messages/send 的返回结果。 */
data class AgentSendResult(
    val ok: Boolean,
    val replyText: String?,
    val replyMessageId: String?,
    val error: String? = null,
    /** 无需向用户展示任何内容：已中止，或回复只有控制指令没有可见文本。 */
    val silent: Boolean = false,
    /** 本轮回复对主动消息计划做的操作（create / update / cancel）。 */
    val scheduleActions: List<String> = emptyList(),
)

/**
 * Misskey 客户端：HTTP API 封装。按 Misskey 约定，token 放在请求体的 i 字段。
 */
class MisskeyClient(private val cfg: BridgeConfig) {
    private val base = cfg.misskeyBaseUrl()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val rateLock = ConcurrentHashMap<String, Double>()

    /** 表情名 → URL；null 表示实例没有这个表情。 */
    private val emojiCache = ConcurrentHashMap<String, String>()
    private val missingEmoji = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var emojiListLoaded = false

    fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ---------- 底层请求 ----------

    private fun tokenFp(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun checkRateLock(token: String) {
        val until = rateLock[tokenFp(token)] ?: 0.0
        val now = System.currentTimeMillis() / 1000.0
        if (until > 0 && now < until) {
            throw RateLimitError(until - now)
        }
    }

    private fun lockRate(token: String) {
        rateLock[tokenFp(token)] = System.currentTimeMillis() / 1000.0 + cfg.misskey.rateLimitLock
        BridgeLog.w("Misskey 429 限流，token 锁定 ${cfg.misskey.rateLimitLock} 秒")
    }

    private fun postJson(url: String, body: JSONObject, timeoutSec: Double, extraHeaders: Map<String, String>): Response {
        val headers = okhttp3.Headers.Builder()
            .add("Origin", base)
            .add("User-Agent", UA)
            .add("Accept", "application/json, text/plain, */*")
        extraHeaders.forEach { (k, v) -> headers.add(k, v) }
        val req = Request.Builder()
            .url(url)
            .headers(headers.build())
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        return client.newBuilder()
            .callTimeout((timeoutSec * 1000).toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(req)
            .execute()
    }

    private fun parseBody(text: String?): Any? = try {
        val t = text?.trimStart() ?: ""
        when {
            t.startsWith("[") -> JSONArray(t)
            t.startsWith("{") -> JSONObject(t)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun post(
        endpoint: String,
        payload: JSONObject,
        timeout: Double,
        token: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Pair<Int, Any?> {
        val body = JSONObject()
        if (token != null) {
            checkRateLock(token)
            body.put("i", token)
        }
        payload.keys().forEach { k -> body.put(k, payload.get(k)) }
        try {
            postJson("$base/api/$endpoint", body, timeout, extraHeaders).use { resp ->
                if (resp.code == 204) return 204 to null
                val data = parseBody(resp.body?.string())
                if (resp.code == 429 && token != null) lockRate(token)
                return resp.code to data
            }
        } catch (e: IOException) {
            if (e is java.io.InterruptedIOException || e.message?.contains("timeout", true) == true) {
                throw TimeoutException("Misskey $endpoint 请求超时(${timeout.toInt()}s)")
            }
            throw e
        }
    }

    private fun raiseFromError(status: Int, data: Any?): Nothing {
        val err = (data as? JSONObject)?.optJSONObject("error") ?: JSONObject()
        val code = err.optString("code", "")
        when {
            status == 429 -> throw RateLimitError(cfg.misskey.rateLimitLock)
            code in AUTH_CODES || (status == 401 && code.isBlank()) -> throw AuthError(status, code)
            code == "AGENT_SESSION_MODERATION_BANNED" || code == "AGENT_CHARACTER_MODERATION_BANNED" ->
                throw BannedError(code)
        }
        throw MisskeyAPIError(status, code, err.optString("message", ""))
    }

    private fun requireOk(status: Int, data: Any?, vararg ok: Int = intArrayOf(200)) {
        if (status !in ok) raiseFromError(status, data)
    }

    private fun jsonArrayToList(arr: JSONArray): List<JSONObject> =
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }

    private fun call(endpoint: String, token: String, payload: JSONObject = JSONObject()): Any? {
        val (status, data) = post(endpoint, payload, cfg.misskey.timeoutNormal, token = token)
        requireOk(status, data, 200, 201, 204)
        return data
    }

    private fun callObject(endpoint: String, token: String, payload: JSONObject = JSONObject()): JSONObject =
        call(endpoint, token, payload) as? JSONObject ?: JSONObject()

    private fun callList(endpoint: String, token: String, payload: JSONObject = JSONObject()): List<JSONObject> {
        val (status, data) = post(endpoint, payload, cfg.misskey.timeoutNormal, token = token)
        if (status != 200 || data !is JSONArray) raiseFromError(status, data)
        return jsonArrayToList(data)
    }

    // ---------- 鉴权 ----------

    /** 校验 token：有效返回用户对象；明确失效返回 null；网络错误抛出。 */
    fun validateToken(token: String): JSONObject? {
        val (status, data) = post("i", JSONObject(), 15.0, token = token)
        if (status == 200 && data is JSONObject) return data
        if (status in listOf(401, 403)) return null
        raiseFromError(status, data)
    }

    fun miauthCheck(sessionId: String): JSONObject? {
        val req = Request.Builder()
            .url("$base/api/miauth/$sessionId/check")
            .post("{}".toRequestBody(jsonMedia))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != 200) return null
            val data = parseBody(resp.body?.string()) as? JSONObject ?: return null
            return if (data.optBoolean("ok") && data.optString("token").isNotBlank()) data else null
        }
    }

    // ---------- Drive ----------

    fun uploadDriveFile(token: String, data: ByteArray, filename: String, contentType: String): String? {
        checkRateLock(token)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("i", token)
            .addFormDataPart("file", filename, data.toRequestBody(contentType.toMediaType()))
            .build()
        val req = Request.Builder().url("$base/api/drive/files/create").post(body).build()
        val call = client.newBuilder()
            .callTimeout(cfg.misskey.timeoutUpload.toLong() * 1000, TimeUnit.MILLISECONDS)
            .build()
            .newCall(req)
        call.execute().use { resp ->
            val parsed = parseBody(resp.body?.string())
            if (resp.code == 429) lockRate(token)
            if (resp.code != 200) raiseFromError(resp.code, parsed)
            return (parsed as? JSONObject)?.optString("id")?.ifBlank { null }
        }
    }

    // ---------- Agents 会话 ----------

    fun sessionsCreate(token: String, characterId: String, dialogueStyleId: String): String {
        val data = callObject(
            "agents/sessions/create", token,
            JSONObject()
                .put("characterId", characterId)
                .put("dialogueStyleId", dialogueStyleId)
                .put("sessionKind", "community"),
        )
        val sid = data.optString("id")
        if (sid.isBlank()) throw MisskeyAPIError(500, "SESSION_CREATE_FAILED", "返回中没有会话 id")
        // 新会话的生图模型默认关闭，选列表里第一个启用画图；失败不影响建会话
        try {
            val modelId = imageModelsList(token).firstOrNull()?.optString("id")?.ifBlank { null }
            if (modelId != null) sessionsUpdate(token, sid, JSONObject().put("agentImageModelId", modelId))
        } catch (e: Exception) {
            BridgeLog.w("设置默认生图模型失败（忽略）: $e")
        }
        return sid
    }

    fun sessionsListMine(token: String): List<JSONObject> =
        callList("agents/sessions/list-mine", token, JSONObject().put("limit", 100))

    fun sessionsShow(token: String, sessionId: String): JSONObject =
        callObject("agents/sessions/show", token, JSONObject().put("sessionId", sessionId))

    fun sessionsUpdate(token: String, sessionId: String, fields: JSONObject): JSONObject {
        val payload = JSONObject().put("sessionId", sessionId)
        fields.keys().forEach { k -> payload.put(k, fields.get(k)) }
        return callObject("agents/sessions/update", token, payload)
    }

    fun sessionsDelete(token: String, sessionId: String) {
        call("agents/sessions/delete", token, JSONObject().put("sessionId", sessionId))
    }

    /** 标记会话已读：避免 Misskey 在 3 秒后再推送一遍 newAgentMessage / 手机推送。 */
    fun sessionsRead(token: String, sessionId: String) {
        try {
            call("agents/sessions/read", token, JSONObject().put("sessionId", sessionId))
        } catch (e: Exception) {
            BridgeLog.d("标记已读失败（忽略）: $e")
        }
    }

    // ---------- Agents 文风 ----------

    fun stylesListUsable(token: String): List<JSONObject> = callList("agents/styles/list-usable", token)

    /** 已订阅时服务端同样返回成功。 */
    fun stylesSubscribe(token: String, styleId: String) {
        call("agents/styles/subscribe", token, JSONObject().put("styleId", styleId))
    }

    fun ensureStyleSubscribed(token: String, styleId: String) {
        if (stylesListUsable(token).any { it.optString("id") == styleId }) return
        stylesSubscribe(token, styleId)
    }

    // ---------- Agents 消息 ----------

    /**
     * 发送一轮消息并同步等待 AI 回复。clientRequestId 由调用方生成：
     * 同一 id 重试时服务端幂等返回已有回复，也可凭它调用 abort 中止。
     */
    fun agentsSend(
        token: String,
        sessionId: String,
        text: String,
        fileId: String?,
        clientRequestId: String,
    ): AgentSendResult {
        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("text", text)
            .put("clientRequestId", clientRequestId)
        if (fileId != null) payload.put("fileId", fileId)
        val (status, data) = post(
            "agents/messages/send", payload, cfg.misskey.timeoutSend, token = token,
            extraHeaders = mapOf("Referer" to "$base/chat/agent/$sessionId"),
        )
        if (status in listOf(200, 201) && data is JSONObject) {
            if (data.optBoolean("aborted")) {
                return AgentSendResult(ok = false, replyText = null, replyMessageId = null, error = "本次回复已中止。", silent = true)
            }
            if (data.optBoolean("auditBlocked")) {
                val reason = data.optString("auditReason").takeIf { !data.isNull("auditReason") && it.isNotBlank() }
                return AgentSendResult(
                    ok = false, replyText = null, replyMessageId = null,
                    error = "回复未通过内容审核，已被拦截" + (if (reason != null) "：$reason" else "。") + "可以换个说法重试。",
                )
            }
            val text = data.optString("assistantText")
            val mid = if (data.isNull("assistantMessageId")) "" else data.optString("assistantMessageId")
            val actions = data.optJSONArray("proactiveScheduleActionTypes")
                ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
            return AgentSendResult(
                ok = true,
                replyText = text.ifBlank { null },
                replyMessageId = mid.ifBlank { null },
                silent = text.isBlank(),
                scheduleActions = actions,
            )
        }
        if (status == 204) return AgentSendResult(ok = true, replyText = null, replyMessageId = null, silent = true)
        // 网关超时：服务端多半仍在生成，回复会经流式 newAgentMessage 推送过来
        if (status in listOf(502, 503, 504)) {
            BridgeLog.i("send 返回 $status，等待流式推送回复")
            return AgentSendResult(ok = true, replyText = null, replyMessageId = null)
        }
        raiseFromError(status, data)
    }

    fun agentsAbort(token: String, sessionId: String, clientRequestId: String): Boolean {
        val data = callObject(
            "agents/messages/abort", token,
            JSONObject().put("sessionId", sessionId).put("clientRequestId", clientRequestId),
        )
        return data.optBoolean("aborted")
    }

    /** 回滚到指定用户消息（含该条及之后的全部消息），返回被删条数与该用户消息原文。 */
    fun agentsRollback(token: String, sessionId: String, userMessageId: String): JSONObject =
        callObject(
            "agents/messages/rollback", token,
            JSONObject().put("sessionId", sessionId).put("messageId", userMessageId),
        )

    fun agentsMessageShow(token: String, sessionId: String, messageId: String): JSONObject =
        callObject(
            "agents/messages/show", token,
            JSONObject().put("sessionId", sessionId).put("messageId", messageId),
        )

    /** 时间线，按 id 倒序（最新在前），已排除内部消息。 */
    fun agentsTimeline(
        token: String,
        sessionId: String,
        limit: Int = 30,
        sinceId: String? = null,
        untilId: String? = null,
    ): List<JSONObject> {
        val payload = JSONObject().put("sessionId", sessionId).put("limit", limit.coerceIn(1, 100))
        if (sinceId != null) payload.put("sinceId", sinceId)
        if (untilId != null) payload.put("untilId", untilId)
        return callList("agents/messages/timeline", token, payload)
    }

    // ---------- 查询类 ----------

    fun creditBalance(token: String): Double? {
        val o = callObject("agents/credit-balance", token)
        return if (o.has("creditBalance") && !o.isNull("creditBalance")) o.getDouble("creditBalance") else null
    }

    fun checkin(token: String): JSONObject = callObject("agents/checkin", token)

    fun checkinStatus(token: String): JSONObject = callObject("agents/checkin-status", token)

    fun meta(): JSONObject {
        val (status, data) = post("meta", JSONObject().put("detail", false), cfg.misskey.timeoutNormal)
        requireOk(status, data)
        return data as? JSONObject ?: JSONObject()
    }

    fun imageModelsList(token: String): List<JSONObject> = callList("agents/images/models/list", token)

    /** 生成（或取回已生成的）画图占位符对应的图片，失败返回 null 并附原因。 */
    fun generatePlaceholder(token: String, sessionId: String, messageId: String, index: Int): Pair<String?, String?> {
        val (status, data) = try {
            post(
                "agents/images/generate-placeholder",
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("messageId", messageId)
                    .put("placeholderIndex", index),
                cfg.misskey.timeoutPlaceholder,
                token = token,
            )
        } catch (e: TimeoutException) {
            return null to "生成超时"
        }
        if (status != 200 || data !is JSONObject) {
            val code = (data as? JSONObject)?.optJSONObject("error")?.optString("code") ?: ""
            return null to friendlyError(code, status)
        }
        val url = data.optString("url").takeIf { !data.isNull("url") && it.isNotBlank() }
            ?: data.optJSONObject("file")?.optString("url")?.ifBlank { null }
        if (url != null) return url to null
        val reason = when (data.optString("status")) {
            "blocked" -> "图片已被屏蔽"
            "auto_cleaned" -> "图片已过期清理"
            else -> data.optString("errorCode").takeIf { !data.isNull("errorCode") && it.isNotBlank() }
                ?.let { friendlyError(it, 200) } ?: "生成失败"
        }
        return null to reason
    }

    fun proactiveList(token: String, sessionId: String): List<JSONObject> =
        callList("agents/proactive-schedules/list", token, JSONObject().put("sessionId", sessionId))

    fun proactiveSetStatus(token: String, sessionId: String, scheduleId: String, active: Boolean) {
        call(
            "agents/proactive-schedules/set-status", token,
            JSONObject()
                .put("sessionId", sessionId)
                .put("scheduleId", scheduleId)
                .put("status", if (active) "active" else "paused"),
        )
    }

    fun proactiveDelete(token: String, sessionId: String, scheduleId: String) {
        call(
            "agents/proactive-schedules/delete", token,
            JSONObject().put("sessionId", sessionId).put("scheduleId", scheduleId),
        )
    }

    // ---------- 长期记忆 ----------

    fun memoryList(token: String, sessionId: String, pageSize: Int = 20): JSONObject =
        callObject(
            "agents/memory/list", token,
            JSONObject().put("sessionId", sessionId).put("pageSize", pageSize.coerceIn(1, 50)),
        )

    fun memoryAdd(token: String, sessionId: String, content: String) {
        call("agents/memory/add", token, JSONObject().put("sessionId", sessionId).put("content", content))
    }

    fun memoryDelete(token: String, sessionId: String, nodeId: String) {
        call("agents/memory/delete", token, JSONObject().put("sessionId", sessionId).put("memoryNodeId", nodeId))
    }

    // ---------- 自定义表情 ----------

    private fun loadEmojiCatalog() {
        if (emojiListLoaded) return
        try {
            val (status, data) = post("emojis", JSONObject(), 20.0)
            if (status != 200) return
            val items = (data as? JSONObject)?.optJSONArray("emojis") ?: (data as? JSONArray) ?: return
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").ifBlank { item.optString("publicUrl") }
                if (name.isNotBlank() && url.isNotBlank()) emojiCache.putIfAbsent(name, url)
            }
            emojiListLoaded = true
        } catch (e: Exception) {
            BridgeLog.d("获取表情列表失败: $e")
        }
    }

    /** 实例自定义表情的图片地址；实例没有该表情时返回 null（调用方保留原文）。 */
    fun emojiUrl(name: String): String? {
        val n = name.trim().trim(':')
        if (n.isBlank() || n.length > 100) return null
        emojiCache[n]?.let { return it }
        if (n in missingEmoji) return null
        loadEmojiCatalog()
        emojiCache[n]?.let { return it }
        if (!emojiListLoaded) return null
        missingEmoji.add(n)
        return null
    }

    fun download(url: String, timeout: Double = 60.0): Pair<ByteArray, String> {
        val req = Request.Builder().url(url).get().build()
        val call = client.newBuilder()
            .callTimeout((timeout * 1000).toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(req)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
            val ctype = (resp.header("Content-Type") ?: "image/jpeg").substringBefore(";").trim()
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return bytes to ctype.ifBlank { "image/jpeg" }
        }
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}

typealias StreamEvent = suspend (String, String, JSONObject) -> Unit

/**
 * 单个绑定通道的 Misskey /streaming 监听。
 * 收到的帧先进内部队列，由独立协程串行消费，避免在 WebSocket 读线程上
 * 执行耗时业务导致心跳超时；断线后指数退避重连（2s 起，最长 2 分钟）。
 * 握手被 401/403 拒绝说明 token 失效，回调 onAuthFailed 后停止重连。
 */
class MisskeyStream(
    private val cfg: BridgeConfig,
    private val token: String,
    private val chan: String,
    private val onEvent: StreamEvent,
    private val onAuthFailed: suspend (String) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val events = Channel<String>(Channel.UNLIMITED)
    private var webSocket: WebSocket? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var authRejected = false

    @Volatile
    private var opened = false
    private var job: Job? = null
    private var consumer: Job? = null
    private val closedSignal = Semaphore(0)

    fun start(scope: CoroutineScope) {
        consumer = scope.launch {
            for (raw in events) dispatch(raw)
        }
        job = scope.launch { runLoop() }
    }

    fun stop() {
        stopped = true
        job?.cancel()
        consumer?.cancel()
        events.close()
        try {
            webSocket?.close(1000, "stop")
        } catch (_: Exception) {
        }
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (_: Exception) {
        }
        BridgeLog.i("Misskey 流式监听已退出 (通道 $chan)")
    }

    private suspend fun runLoop() {
        val url = cfg.misskeyStreamUrl(token)
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        var backoffMs = INITIAL_BACKOFF_MS
        while (!stopped) {
            closedSignal.drainPermits()
            opened = false
            val attemptStart = System.currentTimeMillis()
            try {
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened = true
                        webSocket.send(
                            JSONObject()
                                .put("type", "connect")
                                .put("body", JSONObject().put("channel", "main").put("id", "msk_main"))
                                .toString()
                        )
                        BridgeLog.i("Misskey 流式已连接 (通道 $chan)")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (!stopped) events.trySend(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val code = response?.code ?: 0
                        if (code == 401 || code == 403) authRejected = true
                        if (!stopped && !authRejected) {
                            BridgeLog.w("Misskey 流式断开 (通道 $chan): ${t.message ?: t}")
                        }
                        closedSignal.release()
                    }

                    // 服务端发来关闭帧时 OkHttp 只回调 onClosing，必须回应并放行，
                    // 否则等待循环永远不会结束，断线后不再重连
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        closedSignal.release()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closedSignal.release()
                    }
                }
                val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
                webSocket = ws
                while (!stopped && !closedSignal.tryAcquire(200, TimeUnit.MILLISECONDS)) {
                    if (job?.isCancelled == true) break
                }
                try {
                    ws.close(1000, "bye")
                } catch (_: Exception) {
                }
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                if (!stopped) BridgeLog.w("Misskey 流式异常 (通道 $chan): $e")
            }
            if (stopped) break
            if (authRejected) {
                BridgeLog.w("Misskey 流式握手被拒绝，token 可能已失效 (通道 $chan)")
                onAuthFailed(chan)
                break
            }
            // 连接稳定存活一段时间才算健康并重置退避；刚连上就被踢的不算，
            // 否则服务端异常时会一直以最短间隔重连
            if (opened && System.currentTimeMillis() - attemptStart >= HEALTHY_MS) backoffMs = INITIAL_BACKOFF_MS
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private suspend fun dispatch(raw: String) {
        val data = try {
            JSONObject(raw)
        } catch (_: Exception) {
            return
        }
        if (data.optString("type") != "channel") return
        val body = data.optJSONObject("body") ?: return
        val payload = body.optJSONObject("body") ?: JSONObject()
        val etype = when (body.optString("type")) {
            "newAgentMessage" -> "agent_message"
            "newChatMessage" -> "chat"
            "notification" -> "notification"
            else -> return
        }
        try {
            onEvent(chan, etype, payload)
        } catch (e: Exception) {
            BridgeLog.e("处理 Misskey 流式事件失败 (通道 $chan, $etype)", e)
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 120_000L
        const val HEALTHY_MS = 30_000L
    }
}
