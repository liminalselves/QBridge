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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

open class MisskeyAPIError(val status: Int, val code: String = "", message: String = "") :
    Exception("Misskey API 错误 $status: $code $message")

open class RateLimitError(val retryAfter: Double) :
    MisskeyAPIError(429, "RATE_LIMIT_EXCEEDED", "请 ${retryAfter.toInt()}s 后重试")

open class BannedError(code: String) : MisskeyAPIError(403, code, "会话/角色被封禁")

/** agents/messages/send 的返回结果。 */
data class AgentSendResult(
    val ok: Boolean,
    val delivered: Boolean,
    val replyText: String?,
    val replyMessageId: String?,
    val error: String?,
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
    private val emojiCache = ConcurrentHashMap<String, String?>()
    @Volatile
    private var emojiListLoaded = false

    fun close() {
        // 连接池由 OkHttp 自行管理，无需显式释放
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

    private fun postJson(url: String, body: JSONObject, timeoutSec: Double, extraHeaders: Map<String, String> = emptyMap()): Response {
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

    private suspend fun post(
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
        val keys = payload.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            body.put(k, payload.get(k))
        }
        val url = "$base/api/$endpoint"
        try {
            postJson(url, body, timeout, extraHeaders).use { resp ->
                if (resp.code == 204) return 204 to null
                val text = resp.body?.string()
                val data: Any? = try {
                    when {
                        text.isNullOrBlank() -> null
                        text.trimStart().startsWith("[") -> JSONArray(text)
                        text.trimStart().startsWith("{") -> JSONObject(text)
                        else -> null
                    }
                } catch (_: Exception) {
                    null
                }
                if (resp.code == 429) {
                    rateLock[tokenFp(token ?: "")] =
                        System.currentTimeMillis() / 1000.0 + cfg.misskey.rateLimitLock
                    BridgeLog.w("Misskey 429 限流，token 锁定 ${cfg.misskey.rateLimitLock} 秒")
                }
                return resp.code to data
            }
        } catch (e: IOException) {
            if (e.message?.contains("timeout", true) == true || e is java.net.SocketTimeoutException) {
                throw TimeoutException("Misskey $endpoint 请求超时(${timeout}s)")
            }
            throw e
        }
    }

    private fun raiseFromError(status: Int, data: Any?): Nothing {
        val err = (data as? JSONObject)?.optJSONObject("error") ?: JSONObject()
        val code = err.optString("code", "")
        if (code in listOf(
                "AGENT_SESSION_MODERATION_BANNED",
                "AGENT_CHARACTER_MODERATION_BANNED",
            )
        ) {
            throw BannedError(code)
        }
        throw MisskeyAPIError(status, code, err.optString("message", ""))
    }

    private fun jsonArrayToList(arr: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { out.add(it) }
        }
        return out
    }

    // ---------- 鉴权 ----------

    suspend fun validateToken(token: String): JSONObject? {
        val (status, data) = post("i", JSONObject(), 15.0, token = token)
        return if (status == 200 && data is JSONObject) data else null
    }

    suspend fun miauthCheck(sessionId: String): JSONObject? {
        val url = "$base/api/miauth/$sessionId/check"
        val req = Request.Builder().url(url).post("{}".toRequestBody(jsonMedia)).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code != 200) return null
            val text = resp.body?.string() ?: return null
            val data = try {
                JSONObject(text)
            } catch (_: Exception) {
                return null
            }
            return if (data.optBoolean("ok") && data.has("token")) data else null
        }
    }

    // ---------- Drive ----------

    suspend fun uploadDriveFile(token: String, data: ByteArray, filename: String, contentType: String): String? {
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
            if (resp.code == 429) {
                rateLock[tokenFp(token)] =
                    System.currentTimeMillis() / 1000.0 + cfg.misskey.rateLimitLock
                throw RateLimitError(cfg.misskey.rateLimitLock)
            }
            if (resp.code != 200) {
                throw MisskeyAPIError(resp.code, "DRIVE_UPLOAD_FAILED", (resp.body?.string() ?: "").take(200))
            }
            val text = resp.body?.string() ?: return null
            return try {
                JSONObject(text).optString("id").ifBlank { null }
            } catch (_: Exception) {
                null
            }
        }
    }

    // ---------- Agents 会话 ----------

    suspend fun sessionsCreate(token: String, characterId: String, dialogueStyleId: String): String {
        val (status, data) = post(
            "agents/sessions/create",
            JSONObject()
                .put("characterId", characterId)
                .put("dialogueStyleId", dialogueStyleId)
                .put("sessionKind", "community"),
            cfg.misskey.timeoutNormal,
            token = token,
        )
        if (status !in listOf(200, 201) || data !is JSONObject || data.optString("id").isBlank()) {
            raiseFromError(status, data)
        }
        val sid = (data as JSONObject).getString("id")
        // 新会话默认选列表里的第一个生图模型；取不到或失败都不影响建会话
        try {
            val modelId = imageModelsList(token).firstOrNull()?.optString("id")?.ifBlank { null }
            if (modelId != null) {
                sessionsUpdate(token, sid, JSONObject().put("agentImageModelId", modelId))
            }
        } catch (e: Exception) {
            BridgeLog.w("设置默认生图模型失败（忽略）: $e")
        }
        return sid
    }

    suspend fun sessionsListMine(token: String): List<JSONObject> {
        val (status, data) = post("agents/sessions/list-mine", JSONObject(), cfg.misskey.timeoutNormal, token = token)
        if (status != 200 || data !is JSONArray) raiseFromError(status, data)
        return jsonArrayToList(data)
    }

    suspend fun sessionsShow(token: String, sessionId: String): JSONObject {
        val (status, data) = post(
            "agents/sessions/show",
            JSONObject().put("sessionId", sessionId),
            cfg.misskey.timeoutNormal,
            token = token,
        )
        if (status != 200 || data !is JSONObject) raiseFromError(status, data)
        return data
    }

    suspend fun sessionsUpdate(token: String, sessionId: String, fields: JSONObject): JSONObject {
        val payload = JSONObject().put("sessionId", sessionId)
        fields.keys().forEach { k -> payload.put(k, fields.get(k)) }
        val (status, data) = post("agents/sessions/update", payload, cfg.misskey.timeoutNormal, token = token)
        if (status !in listOf(200, 201)) raiseFromError(status, data)
        return data as? JSONObject ?: JSONObject()
    }

    suspend fun sessionsDelete(token: String, sessionId: String) {
        val (status, data) = post(
            "agents/sessions/delete",
            JSONObject().put("sessionId", sessionId),
            cfg.misskey.timeoutNormal,
            token = token,
        )
        if (status !in listOf(200, 201, 204)) raiseFromError(status, data)
    }

    // ---------- Agents 文风 ----------

    suspend fun stylesListUsable(token: String): List<JSONObject> {
        val (status, data) = post("agents/styles/list-usable", JSONObject(), cfg.misskey.timeoutNormal, token = token)
        if (status != 200 || data !is JSONArray) raiseFromError(status, data)
        return jsonArrayToList(data)
    }

    suspend fun stylesSubscribe(token: String, styleId: String) {
        val (status, data) = post(
            "agents/styles/subscribe",
            JSONObject().put("styleId", styleId),
            cfg.misskey.timeoutNormal,
            token = token,
        )
        val err = (data as? JSONObject)?.optJSONObject("error") ?: JSONObject()
        if (err.optString("code") == "NO_SUCH_STYLE") return
        if (status !in listOf(200, 201, 204)) raiseFromError(status, data)
    }

    suspend fun ensureStyleSubscribed(token: String, styleId: String) {
        val usable = stylesListUsable(token)
        if (usable.any { it.optString("id") == styleId }) return
        stylesSubscribe(token, styleId)
        val again = stylesListUsable(token)
        if (again.none { it.optString("id") == styleId }) {
            BridgeLog.w("文风 $styleId 订阅后仍不在 list-usable 中")
        }
    }

    // ---------- Agents 消息 ----------

    suspend fun agentsSend(
        token: String,
        sessionId: String,
        text: String,
        fileId: String?,
    ): AgentSendResult {
        val payload = JSONObject()
            .put("sessionId", sessionId)
            .put("text", text ?: "")
            .put("clientRequestId", UUID.randomUUID().toString())
        if (fileId != null) payload.put("fileId", fileId)
        val (status, data) = post(
            "agents/messages/send",
            payload,
            cfg.misskey.timeoutSend,
            token = token,
            extraHeaders = mapOf("Referer" to "$base/chat/agent/$sessionId"),
        )
        if (status in listOf(200, 201) && data is JSONObject) {
            val audit = if (data.has("auditCategory") && !data.isNull("auditCategory")) data.get("auditCategory") else null
            val auditReason = if (data.has("auditReason") && !data.isNull("auditReason")) data.get("auditReason") else null
            if (audit != null || auditReason != null) {
                return AgentSendResult(
                    ok = false, delivered = false, replyText = null, replyMessageId = null,
                    error = "内容被审核拦截($audit)：${auditReason ?: ""}",
                )
            }
            return AgentSendResult(
                ok = true, delivered = true,
                replyText = data.optString("assistantText").ifBlank { null },
                replyMessageId = data.optString("assistantMessageId").ifBlank { null },
                error = null,
            )
        }
        if (status == 204) {
            return AgentSendResult(ok = true, delivered = true, replyText = null, replyMessageId = null, error = null)
        }
        if (status == 403) {
            val err = (data as? JSONObject)?.optJSONObject("error") ?: JSONObject()
            throw BannedError(err.optString("code", "FORBIDDEN"))
        }
        if (status == 504) {
            BridgeLog.i("504 网关超时，视为已送达")
            return AgentSendResult(ok = true, delivered = true, replyText = null, replyMessageId = null, error = null)
        }
        if (status in listOf(502, 503)) {
            if (verifyDelivered(token, sessionId, text)) {
                return AgentSendResult(ok = true, delivered = true, replyText = null, replyMessageId = null, error = null)
            }
            return AgentSendResult(
                ok = false, delivered = false, replyText = null, replyMessageId = null,
                error = "网关错误且未能确认送达",
            )
        }
        if (status == 429) throw RateLimitError(cfg.misskey.rateLimitLock)
        raiseFromError(status, data)
        return AgentSendResult(ok = false, delivered = false, replyText = null, replyMessageId = null, error = "未知错误")
    }

    /** 502/503 时无法确定是否送达，回查时间线确认，避免重复发送。 */
    private suspend fun verifyDelivered(
        token: String,
        sessionId: String,
        text: String,
        tries: Int = 4,
        interval: Double = 4.0,
    ): Boolean {
        repeat(tries) {
            delay((interval * 1000).toLong())
            try {
                val msgs = agentsTimeline(token, sessionId, limit = 5)
                for (m in msgs) {
                    if (m.optString("role") != "user") continue
                    val content = m.optString("content").trim()
                    if (text.isNotBlank() && content == text.trim()) return true
                    if (text.isBlank() && m.has("file") && !m.isNull("file")) return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    suspend fun agentsTimeline(
        token: String,
        sessionId: String,
        limit: Int = 30,
        sinceId: String? = null,
        untilId: String? = null,
    ): List<JSONObject> {
        val payload = JSONObject().put("sessionId", sessionId).put("limit", limit)
        if (sinceId != null) payload.put("sinceId", sinceId)
        if (untilId != null) payload.put("untilId", untilId)
        val (status, data) = post("agents/messages/timeline", payload, cfg.misskey.timeoutNormal, token = token)
        if (status != 200 || data !is JSONArray) raiseFromError(status, data)
        return jsonArrayToList(data)
    }

    // ---------- 查询类 ----------

    suspend fun creditBalance(token: String): Double? {
        val (status, data) = post("agents/credit-balance", JSONObject(), cfg.misskey.timeoutNormal, token = token)
        if (status != 200) raiseFromError(status, data)
        val o = data as? JSONObject ?: return null
        return if (o.has("creditBalance") && !o.isNull("creditBalance")) o.getDouble("creditBalance") else null
    }

    suspend fun meta(): JSONObject {
        val (status, data) = post("meta", JSONObject().put("detail", false), cfg.misskey.timeoutNormal)
        if (status != 200) raiseFromError(status, data)
        return data as? JSONObject ?: JSONObject()
    }

    suspend fun imageModelsList(token: String): List<JSONObject> {
        val (status, data) = post("agents/images/models/list", JSONObject(), cfg.misskey.timeoutNormal, token = token)
        if (status != 200 || data !is JSONArray) raiseFromError(status, data)
        return jsonArrayToList(data)
    }

    suspend fun generatePlaceholder(
        token: String,
        sessionId: String,
        messageId: String,
        index: Int,
    ): String? {
        val (status, data) = post(
            "agents/images/generate-placeholder",
            JSONObject()
                .put("sessionId", sessionId)
                .put("messageId", messageId)
                .put("placeholderIndex", index)
                .put("regenerate", false)
                .put("regenerationOfId", JSONObject.NULL),
            cfg.misskey.timeoutPlaceholder,
            token = token,
        )
        if (status != 200 || data !is JSONObject) {
            val err: Any? = data?.let {
                val o = it as? JSONObject
                o?.opt("errorMessage") ?: o?.opt("errorCode") ?: o?.opt("error")
            } ?: "HTTP $status"
            val errText = when (err) {
                is JSONObject -> err.optString("code").ifBlank { err.optString("message") }.ifBlank { err.toString() }
                else -> err?.toString()
            }
            BridgeLog.w("占位图生成失败 (index=$index): ${errText ?: data}")
            return null
        }
        var url = data.optString("url").ifBlank { data.optString("thumbnailUrl") }
        if (url.isBlank() && data.optJSONObject("file") != null) {
            val file = data.optJSONObject("file") ?: JSONObject()
            url = file.optString("url").ifBlank { file.optString("thumbnailUrl") }
        }
        return url.ifBlank { null }
    }

    suspend fun proactiveList(token: String, sessionId: String): List<JSONObject> {
        val (status, data) = post(
            "agents/proactive-schedules/list",
            JSONObject().put("sessionId", sessionId),
            cfg.misskey.timeoutNormal,
            token = token,
        )
        if (status != 200 || data !is JSONArray) raiseFromError(status, data)
        return jsonArrayToList(data)
    }

    suspend fun proactiveSetStatus(token: String, sessionId: String, scheduleId: String, active: Boolean) {
        val (status, data) = post(
            "agents/proactive-schedules/set-status",
            JSONObject()
                .put("sessionId", sessionId)
                .put("scheduleId", scheduleId)
                .put("status", if (active) "active" else "paused"),
            cfg.misskey.timeoutNormal,
            token = token,
        )
        if (status !in listOf(200, 201, 204)) raiseFromError(status, data)
    }

    suspend fun proactiveDelete(token: String, sessionId: String, scheduleId: String) {
        val (status, data) = post(
            "agents/proactive-schedules/delete",
            JSONObject().put("sessionId", sessionId).put("scheduleId", scheduleId),
            cfg.misskey.timeoutNormal,
            token = token,
        )
        if (status !in listOf(200, 201, 204)) raiseFromError(status, data)
    }

    // ---------- 自定义表情 ----------

    private suspend fun loadEmojiCatalog() {
        if (emojiListLoaded) return
        try {
            val (status, data) = post("emojis", JSONObject(), 20.0)
            val items: JSONArray = when {
                data is JSONObject -> data.optJSONArray("emojis") ?: JSONArray()
                data is JSONArray -> data
                else -> JSONArray()
            }
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                if (name.isBlank()) continue
                val url = item.optString("url").ifBlank { item.optString("publicUrl") }
                    .ifBlank { item.optString("originalUrl") }
                if (url.isNotBlank()) emojiCache.putIfAbsent(name, url)
            }
            emojiListLoaded = true
        } catch (e: Exception) {
            BridgeLog.d("获取表情列表失败: $e")
        }
    }

    suspend fun emojiUrl(name: String): String? {
        val n = name.trim().trim(':')
        if (n.isBlank() || n.length > 100) return null
        if (emojiCache.containsKey(n)) return emojiCache[n]

        var url: String? = null
        try {
            val (status, data) = post("emoji", JSONObject().put("name", n), 15.0)
            if (status == 200 && data is JSONObject) {
                url = data.optString("url").ifBlank { data.optString("publicUrl") }
                    .ifBlank { data.optString("originalUrl") }.ifBlank { null }
            }
        } catch (e: Exception) {
            BridgeLog.d("查询表情 $n 失败: $e")
        }

        if (url == null) {
            loadEmojiCatalog()
            if (emojiCache.containsKey(n)) return emojiCache[n]
        }

        if (url == null) {
            // 实例未收录时按 Misskey 的 URL 规则猜一个，多数实例可用
            url = "$base/emoji/$n.webp"
        }
        emojiCache[n] = url
        return url
    }

    suspend fun download(url: String, timeout: Double = 60.0): Pair<ByteArray, String> {
        val req = Request.Builder().url(url).get().build()
        val call = client.newBuilder()
            .callTimeout((timeout * 1000).toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(req)
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
            var ctype = resp.header("Content-Type", "image/jpeg") ?: "image/jpeg"
            if (ctype.contains(";")) ctype = ctype.split(";", limit = 2)[0].trim()
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return bytes to (ctype.ifBlank { "image/jpeg" })
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
 * 执行耗时业务导致心跳超时；断线后按 reconnectInterval 自动重连。
 */
class MisskeyStream(
    private val cfg: BridgeConfig,
    private val token: String,
    private val chan: String,
    private val onEvent: StreamEvent,
    private val reconnectInterval: Double = 2.0,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val events = Channel<String>(Channel.UNLIMITED)
    private var webSocket: WebSocket? = null
    @Volatile
    private var stopped = false
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

    private fun streamUrl(): String {
        var url = cfg.misskeyStreamUrl(token)
        if (url.startsWith("https://")) url = "wss://" + url.removePrefix("https://")
        else if (url.startsWith("http://")) url = "ws://" + url.removePrefix("http://")
        return url
    }

    private suspend fun runLoop() {
        val url = streamUrl()
        while (!stopped) {
            closedSignal.drainPermits()
            try {
                val request = Request.Builder().url(url).build()
                val listener = object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send(
                            JSONObject()
                                .put("type", "connect")
                                .put("body", JSONObject().put("channel", "main").put("id", "msk_main"))
                                .toString()
                        )
                        BridgeLog.i("Misskey 流式已连接 (通道 $chan)")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (stopped) return
                        events.trySend(text)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!stopped) {
                            BridgeLog.w("Misskey 流式异常 (通道 $chan): $t，${reconnectInterval}s 后重连")
                        }
                        closedSignal.release()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        closedSignal.release()
                    }
                }
                val ws = client.newWebSocket(request, listener)
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
                if (!stopped) {
                    BridgeLog.w("Misskey 流式异常 (通道 $chan): $e，${reconnectInterval}s 后重连")
                }
            }
            if (!stopped) {
                delay((reconnectInterval * 1000).toLong())
            }
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
        val btype = body.optString("type")
        val payload = body.optJSONObject("body") ?: JSONObject()
        when (btype) {
            "newAgentMessage" -> safeCb("agent_message", payload)
            "newChatMessage" -> safeCb("chat", payload)
            "notification" -> safeCb("notification", payload)
        }
    }

    private suspend fun safeCb(etype: String, payload: JSONObject) {
        try {
            onEvent(chan, etype, payload)
        } catch (e: Exception) {
            BridgeLog.e("处理 Misskey 流式事件失败 (通道 $chan, $etype)", e)
        }
    }
}
