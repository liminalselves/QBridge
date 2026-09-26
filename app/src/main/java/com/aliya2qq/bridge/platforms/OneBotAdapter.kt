package com.aliya2qq.bridge.platforms

import com.aliya2qq.bridge.util.BridgeLog
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OneBot 11 协议适配器，支持 forward_ws / http / reverse_ws 三种传输方式。
 */
open class OneBotAdapter(
    val transport: String = "forward_ws",
    val wsUrl: String = "ws://127.0.0.1:3001",
    val httpUrl: String = "http://127.0.0.1:5700",
    val reverseWsHost: String = "0.0.0.0",
    val reverseWsPort: Int = 8080,
    val httpEventHost: String = "0.0.0.0",
    val httpEventPort: Int = 8081,
    val accessToken: String = "",
    val secret: String = "",
    val apiTimeout: Double = 30.0,
    val reconnectInterval: Double = 3.0,
) : PlatformAdapter() {

    override val name: String = "onebot"

    override val displayName: String
        get() {
            val labels = mapOf(
                "forward_ws" to "正向 WS",
                "http" to "HTTP",
                "reverse_ws" to "反向 WS",
            )
            return "OneBot · ${labels[transport] ?: transport}"
        }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var ws: WebSocket? = null
    private val pending = ConcurrentHashMap<String, CompletableFuture<JSONObject>>()
    private val echoSeq = AtomicLong(0)
    private val eventSemaphore = Semaphore(20)
    private val eventExecutor = Executors.newCachedThreadPool()
    private var httpServer: NanoHTTPD? = null
    private var reverseServer: WebSocketServer? = null
    private var reverseWs: org.java_websocket.WebSocket? = null
    @Volatile
    private var closed = false

    private fun authHeaders(): Map<String, String> =
        if (accessToken.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Bearer $accessToken")

    private fun wsUrlWithToken(): String {
        var url = wsUrl
        if (accessToken.isNotEmpty()) {
            val sep = if (url.contains("?")) "&" else "?"
            url = "$url${sep}access_token=$accessToken"
        }
        return url
    }

    private fun failAllPending(reason: String) {
        pending.forEach { (_, fut) ->
            if (!fut.isDone) fut.completeExceptionally(ConnectionException("OneBot 连接断开: $reason"))
        }
        pending.clear()
    }

    class ConnectionException(message: String) : Exception(message)

    override suspend fun runForever() {
        when (transport) {
            "forward_ws" -> runForwardWs()
            "http" -> runHttp()
            "reverse_ws" -> runReverseWs()
            else -> throw IllegalArgumentException("未知 OneBot transport: $transport")
        }
    }

    override suspend fun close() {
        closed = true
        failAllPending("客户端关闭")
        connected = false
        try {
            ws?.close(1000, "close")
        } catch (_: Exception) {
        }
        try {
            httpServer?.stop()
        } catch (_: Exception) {
        }
        try {
            reverseServer?.stop(1000)
        } catch (_: Exception) {
        }
        eventExecutor.shutdownNow()
    }

    // ---------- 正向 WebSocket ----------

    private suspend fun runForwardWs() {
        while (!closed) {
            try {
                connectForwardWs()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                BridgeLog.w("OneBot 正向 WS 连接异常: $e")
            }
            connected = false
            ws = null
            failAllPending("连接断开")
            if (closed) break
            BridgeLog.i("${reconnectInterval} 秒后重连 OneBot…")
            kotlinx.coroutines.delay((reconnectInterval * 1000).toLong())
        }
    }

    private suspend fun connectForwardWs() {
        val done = CountDownLatch(1)
        val request = Request.Builder()
            .url(wsUrlWithToken())
            .apply {
                authHeaders().forEach { (k, v) -> addHeader(k, v) }
            }
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                ws = webSocket
                BridgeLog.i("已连接 OneBot 正向 WS: $wsUrl")
                eventExecutor.execute {
                    try {
                        runBlocking {
                            bootstrapLogin(15.0)
                        }
                        connected = true
                    } catch (e: Exception) {
                        BridgeLog.w("获取登录信息失败（仍继续运行）: $e")
                        connected = true
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    handleFrame(data)
                } catch (e: Exception) {
                    BridgeLog.w("OneBot WS 帧解析失败: $e")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                BridgeLog.w("OneBot WS 错误: $t")
                connected = false
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
                done.countDown()
            }
        }
        client.newWebSocket(request, listener)
        // 阻塞直到连接断开，由 runForwardWs 负责重连
        done.await()
        connected = false
        ws = null
        failAllPending("连接断开")
    }

    // ---------- HTTP + HTTP POST 事件 ----------

    private suspend fun runHttp() {
        val server = object : NanoHTTPD(httpEventHost, httpEventPort) {
            override fun serve(session: IHTTPSession): Response {
                return try {
                    val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                    val body = if (contentLength > 0) {
                        val buf = ByteArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = session.inputStream.read(buf, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        buf
                    } else {
                        ByteArray(0)
                    }
                    val status = handleHttpEvent(session, body)
                    if (status == 403) {
                        NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.FORBIDDEN, "text/plain", "forbidden"
                        )
                    } else {
                        NanoHTTPD.newFixedLengthResponse(
                            NanoHTTPD.Response.Status.NO_CONTENT, "text/plain", ""
                        )
                    }
                } catch (e: Exception) {
                    BridgeLog.w("HTTP 事件处理失败: $e")
                    NanoHTTPD.newFixedLengthResponse(
                        NanoHTTPD.Response.Status.BAD_REQUEST, "text/plain", "bad request"
                    )
                }
            }
        }
        httpServer = server
        server.start()
        BridgeLog.i("OneBot HTTP 事件监听已启动: http://$httpEventHost:$httpEventPort/")
        bootstrapLogin(15.0)
        connected = true
        try {
            while (!closed) {
                kotlinx.coroutines.delay(3600_000)
            }
        } finally {
            connected = false
        }
    }

    /** 返回 204 表示处理成功，403 表示签名校验失败。 */
    private fun handleHttpEvent(session: NanoHTTPD.IHTTPSession, body: ByteArray): Int {
        if (secret.isNotEmpty()) {
            val sig = session.headers["x-signature"] ?: session.headers["X-Signature"] ?: ""
            val expect = "sha1=" + hmacSha1(secret, body)
            if (sig != expect) {
                BridgeLog.w("HTTP POST 签名校验失败，已拒绝")
                return 403
            }
        }
        val data = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (_: Exception) {
            return 204
        }
        if (data.has("post_type")) {
            val meta = data.optString("meta_event_type")
            if (meta !in listOf("heartbeat", "lifecycle")) {
                submitEvent(data)
            }
        }
        return 204
    }

    private fun hmacSha1(secret: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    // ---------- 反向 WebSocket ----------

    private suspend fun runReverseWs() {
        val server = object : WebSocketServer(InetSocketAddress(reverseWsHost, reverseWsPort)) {
            override fun onOpen(conn: org.java_websocket.WebSocket?, handshake: ClientHandshake?) {
                val role = handshake?.getFieldValue("X-Client-Role") ?: "Universal"
                val selfId = handshake?.getFieldValue("X-Self-ID") ?: ""
                BridgeLog.i("OneBot 反向 WS 已连入: role=$role self_id=$selfId")
                if (selfId.isNotEmpty()) selfQq = selfId
                reverseWs = conn
                failAllPending("连接重置")
                eventExecutor.execute {
                    try {
                        runBlocking {
                            if (role in listOf("API", "Universal")) bootstrapLogin(10.0)
                            if (selfQq.isNullOrEmpty() && selfId.isNotEmpty()) selfQq = selfId
                            connected = true
                        }
                    } catch (e: Exception) {
                        BridgeLog.w("反向 WS bootstrap 失败: $e")
                        connected = true
                    }
                }
            }

            override fun onMessage(conn: org.java_websocket.WebSocket?, message: String?) {
                if (message == null) return
                try {
                    handleFrame(JSONObject(message))
                } catch (e: Exception) {
                    BridgeLog.w("反向 WS 帧解析失败: $e")
                }
            }

            override fun onClose(conn: org.java_websocket.WebSocket?, code: Int, reason: String?, remote: Boolean) {
                if (reverseWs === conn) reverseWs = null
                connected = false
                failAllPending("反向 WS 断开")
                BridgeLog.i("OneBot 反向 WS 断开: $reason")
            }

            override fun onError(conn: org.java_websocket.WebSocket?, ex: Exception?) {
                BridgeLog.w("OneBot 反向 WS 错误: $ex")
            }

            override fun onStart() {
                BridgeLog.i("OneBot 反向 WS 监听已启动: ws://$reverseWsHost:$reverseWsPort/")
            }
        }
        reverseServer = server
        server.start()
        try {
            while (!closed) {
                kotlinx.coroutines.delay(3600_000)
            }
        } finally {
            connected = false
        }
    }

    // ---------- 事件与 API ----------

    private fun handleFrame(data: JSONObject) {
        if (data.has("echo")) {
            val echo = data.opt("echo")?.toString()
            if (echo != null) {
                val fut = pending.remove(echo)
                if (fut != null && !fut.isDone) fut.complete(data)
                return
            }
        }
        if (data.has("post_type")) {
            val meta = data.optString("meta_event_type")
            if (meta in listOf("heartbeat", "lifecycle")) return
            submitEvent(data)
        }
    }

    /** 事件在独立线程池里处理；任何异常都不允许逃出线程。 */
    private fun submitEvent(data: JSONObject) {
        eventExecutor.execute {
            try {
                runBlocking { dispatchEvent(data) }
            } catch (t: Throwable) {
                BridgeLog.w("事件处理被中止: $t")
            }
        }
    }

    private suspend fun bootstrapLogin(timeout: Double = 15.0) {
        try {
            val info = callApi("get_login_info", JSONObject(), timeout) as? JSONObject
            selfQq = info?.opt("user_id")?.toString()
            selfNickname = info?.opt("nickname")?.toString()
            BridgeLog.i("机器人登录信息: QQ=$selfQq 昵称=$selfNickname")
        } catch (e: Exception) {
            BridgeLog.w("获取登录信息失败（仍继续运行）: $e")
        }
    }

    private suspend fun dispatchEvent(data: JSONObject) {
        try {
            eventSemaphore.acquire()
        } catch (e: InterruptedException) {
            // 服务停止时线程被中断，放弃这条事件即可
            return
        }
        try {
            val message = toPlatformMessage(data) ?: return
            emitMessage(message)
        } catch (e: Exception) {
            BridgeLog.e("解析 OneBot 事件失败", e)
        } finally {
            eventSemaphore.release()
        }
    }

    private fun toPlatformMessage(event: JSONObject): PlatformMessage? {
        if (event.optString("post_type") != "message") return null
        val messageType = event.optString("message_type")
        if (messageType !in listOf("private", "group")) return null
        val chatId = if (messageType == "group") event.opt("group_id")?.toString() ?: ""
        else event.opt("user_id")?.toString() ?: ""
        val userId = event.opt("user_id")?.toString() ?: ""
        if (chatId.isEmpty() || userId.isEmpty()) return null
        val sender = event.optJSONObject("sender") ?: JSONObject()
        val parsed = OneBotSegments.parse(event.opt("message"), selfQq)
        return PlatformMessage(
            platform = name,
            chatType = messageType,
            chatId = chatId,
            userId = userId,
            messageId = event.opt("message_id")?.toString() ?: "",
            nickname = sender.optString("nickname", ""),
            card = sender.optString("card", ""),
            role = sender.optString("role", ""),
            text = parsed.text,
            images = parsed.images,
            atTargets = parsed.atTargets,
            mentionedSelf = parsed.mentionedSelf,
            raw = event,
        )
    }

    override suspend fun callApi(action: String, params: JSONObject?, timeout: Double?): Any? {
        return if (transport == "http") callHttpApi(action, params, timeout)
        else callWsApi(action, params, timeout)
    }

    private fun nextEcho(): String = "onebot-" + echoSeq.incrementAndGet()

    /** 正向 WS 走 OkHttp 客户端，反向 WS 走 Java-WebSocket 服务端，请求/响应逻辑相同。 */
    private suspend fun callWsApi(action: String, params: JSONObject?, timeout: Double?): Any? {
        val send: (String) -> Unit
        if (transport == "reverse_ws") {
            val conn = reverseWs ?: throw ConnectionException("OneBot 未连接")
            send = { conn.send(it) }
        } else {
            val socket = ws ?: throw ConnectionException("OneBot 未连接")
            send = { socket.send(it) }
        }
        val echo = nextEcho()
        val frame = JSONObject()
            .put("action", action)
            .put("params", params ?: JSONObject())
            .put("echo", echo)
        val fut = CompletableFuture<JSONObject>()
        pending[echo] = fut
        return try {
            send(frame.toString())
            val resp = fut.get(((timeout ?: apiTimeout) * 1000).toLong(), TimeUnit.MILLISECONDS)
            unwrapResponse(action, resp)
        } catch (e: java.util.concurrent.TimeoutException) {
            pending.remove(echo)
            throw TimeoutException("OneBot API $action 响应超时")
        } catch (e: Exception) {
            pending.remove(echo)
            throw e
        }
    }

    private suspend fun callHttpApi(action: String, params: JSONObject?, timeout: Double?): Any? {
        val url = "${httpUrl.trimEnd('/')}/$action"
        val media = "application/json; charset=utf-8".toMediaType()
        val body = (params ?: JSONObject()).toString().toRequestBody(media)
        val builder = Request.Builder().url(url).post(body)
            .addHeader("Content-Type", "application/json")
        authHeaders().forEach { (k, v) -> builder.addHeader(k, v) }
        val call = client.newBuilder()
            .callTimeout(((timeout ?: apiTimeout) * 1000).toLong(), TimeUnit.MILLISECONDS)
            .build()
            .newCall(builder.build())
        try {
            call.execute().use { resp ->
                when (resp.code) {
                    401 -> throw PlatformAPIError(action, 1401, "未提供 access token")
                    403 -> throw PlatformAPIError(action, 1403, "access token 不正确")
                    404 -> throw PlatformAPIError(action, 1404, "API 不存在")
                }
                if (resp.code != 200) {
                    throw PlatformAPIError(action, resp.code, resp.body?.string() ?: "")
                }
                val text = resp.body?.string() ?: "{}"
                val payload = JSONObject(text)
                return unwrapResponse(action, payload)
            }
        } catch (e: SocketTimeoutException) {
            throw TimeoutException("OneBot API $action 响应超时")
        }
    }

    private fun unwrapResponse(action: String, resp: JSONObject): Any? {
        val status = resp.optString("status")
        val retcode = resp.opt("retcode")
        if (status == "async" || retcode == 1) return resp.opt("data")
        if (status != "ok" || retcode != 0) {
            throw PlatformAPIError(action, retcode, resp.optString("message", ""))
        }
        return resp.opt("data")
    }

    override suspend fun sendTo(chan: String, segments: List<JSONObject>): Any? {
        val arr = JSONArray()
        segments.forEach { arr.put(it) }
        return if (chan.startsWith("g:")) {
            callApi("send_group_msg", JSONObject().put("group_id", chan.substring(2).toLongOrNull() ?: chan.substring(2)).put("message", arr))
        } else {
            callApi("send_private_msg", JSONObject().put("user_id", chan.substring(2).toLongOrNull() ?: chan.substring(2)).put("message", arr))
        }
    }
}
