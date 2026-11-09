package com.aliya2qq.bridge.core

import com.aliya2qq.bridge.commands.ChatContext
import com.aliya2qq.bridge.commands.PermissionException
import com.aliya2qq.bridge.commands.dispatch
import com.aliya2qq.bridge.commands.parse
import com.aliya2qq.bridge.config.BridgeConfig
import com.aliya2qq.bridge.db.Database
import com.aliya2qq.bridge.db.bool
import com.aliya2qq.bridge.db.str
import com.aliya2qq.bridge.db.strOrNull
import com.aliya2qq.bridge.misskey.AgentSendResult
import com.aliya2qq.bridge.misskey.AuthError
import com.aliya2qq.bridge.misskey.BannedError
import com.aliya2qq.bridge.misskey.MisskeyAPIError
import com.aliya2qq.bridge.misskey.MisskeyClient
import com.aliya2qq.bridge.misskey.MisskeyStream
import com.aliya2qq.bridge.misskey.RateLimitError
import com.aliya2qq.bridge.platforms.OneBotSegments
import com.aliya2qq.bridge.platforms.PlatformHub
import com.aliya2qq.bridge.platforms.PlatformMessage
import com.aliya2qq.bridge.render.ContentBlock
import com.aliya2qq.bridge.render.ReplyRender
import com.aliya2qq.bridge.util.AsyncEvent
import com.aliya2qq.bridge.util.BridgeLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

private val NOTIFY_LABELS = mapOf(
    "mention" to "提到了你",
    "reply" to "回复了你的帖子",
    "quote" to "引用了你的帖子",
    "reaction" to "对你的帖子作出了反应",
    "renote" to "转发了你的帖子",
)

private const val ID_DEDUP_SIZE = 500
private const val MEMBER_INFO_CACHE_SIZE = 512

/** 会话信息（角色、文风、封禁状态、表情包）的复查间隔。 */
private const val SESSION_CHECK_TTL_MS = 10 * 60_000L

/** QQ 的“正在输入”衰减很快，需要高频刷新（与 AstrBot input_state 一致）。 */
private const val TYPING_INTERVAL_MS = 500L

fun privChan(qq: String) = "p:$qq"
fun groupChan(gid: String) = "g:$gid"

/** 同一通道的消息聚合：短时间内的连发合并成一轮发送，等 AI 回复后再发下一轮。 */
class ChatPipe {
    val lock = Mutex()
    val texts = ArrayList<String>()
    val images = ArrayList<String>()
    var timer: Job? = null

    /** 本轮 send 未直接带回回复，正在等流式推送。 */
    @Volatile
    var awaitingStream = false
    val replyEvent = AsyncEvent()
}

data class PendingBind(
    val chan: String,
    val qq: String,
    val kind: String, // "token" / "miauth"
    val token: String = "",
    val expiresAt: Double = 0.0,
    var task: Job? = null,
)

/** 会话的缓存快照，来自 agents/sessions/show。 */
data class SessionInfo(
    val id: String,
    val name: String,
    val characterId: String,
    val characterName: String,
    val styleId: String?,
    val modelId: String?,
    val imageModelId: String?,
    val segmented: Boolean,
    val banned: Boolean,
    val characterBanned: Boolean,
    /** 角色表情包 key → 图片地址。 */
    val stickers: Map<String, String>,
    val checkedAt: Long,
)

private data class Inflight(val token: String, val sessionId: String, val requestId: String)

/**
 * 双向桥接核心：QQ 私聊/群聊与 Misskey（Aliya 会话）之间的消息转发、
 * 指令路由与会话管理。QQ 侧事件由平台适配器上抛，Misskey 侧事件由
 * 每个绑定通道各自的流式连接上抛。
 */
class Bridge(
    val cfg: BridgeConfig,
    val db: Database,
    val platform: PlatformHub,
    val misskey: MisskeyClient,
    parentScope: CoroutineScope? = null,
) {
    private val job = SupervisorJob(parentScope?.coroutineContext?.get(Job))
    val scope = CoroutineScope(
        Dispatchers.IO + job + CoroutineExceptionHandler { _, e ->
            BridgeLog.e("桥接后台任务未捕获异常", e)
        }
    )

    // mapsLock 保护 streams/sendLocks/pipes 的创建销毁与 memberInfo 的读写。
    // 其余共享状态使用并发容器，槽内对象（LinkedHashMap 等）用 synchronized 保护。
    private val mapsLock = Mutex()

    private val streams = HashMap<String, MisskeyStream>()
    private val sendLocks = HashMap<String, Mutex>()
    private val pipes = ConcurrentHashMap<String, ChatPipe>()
    private val memberInfo = object : LinkedHashMap<String, JSONObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean {
            return size > MEMBER_INFO_CACHE_SIZE
        }
    }

    private val deliveredIds = ConcurrentHashMap<String, LinkedHashMap<String, Unit>>()
    private val sessionListCache = ConcurrentHashMap<String, List<JSONObject>>()
    private val sessions = ConcurrentHashMap<String, SessionInfo>()
    private val inflight = ConcurrentHashMap<String, Inflight>()
    private val miauthTasks = ConcurrentHashMap<String, Job>()
    private val pendingBinds = ConcurrentHashMap<String, PendingBind>()
    private val groupSink = ConcurrentHashMap<String, MutableList<String>>()
    private val groupUserIds = ConcurrentHashMap<String, MutableMap<String, Int>>()

    // ---------- 生命周期 ----------

    suspend fun start() {
        for (binding in db.getAllBindings()) {
            startStream(privChan(binding.str("qq_number")), binding)
        }
        for (binding in db.getAllGroupBindings()) {
            startStream(groupChan(binding.str("group_id")), binding)
        }
    }

    suspend fun stop() {
        mapsLock.withLock {
            miauthTasks.values.forEach { it.cancel() }
            miauthTasks.clear()
            pendingBinds.values.forEach { it.task?.cancel() }
            pendingBinds.clear()
            streams.values.forEach { it.stop() }
            streams.clear()
        }
        misskey.close()
        platform.close()
        job.cancel()
    }

    private suspend fun startStream(chan: String, binding: Map<String, Any?>) {
        mapsLock.withLock {
            streams.remove(chan)?.stop()
            val stream = MisskeyStream(
                cfg,
                binding.str("misskey_token"),
                chan,
                onEvent = { c, etype, payload -> onMisskeyEvent(c, etype, payload) },
                onAuthFailed = { c -> onStreamAuthFailed(c) },
            )
            streams[chan] = stream
            stream.start(scope)
        }
    }

    private suspend fun stopStream(chan: String) {
        mapsLock.withLock {
            streams.remove(chan)?.stop()
        }
    }

    private suspend fun onStreamAuthFailed(chan: String) {
        sendText(chan, "⚠️ Misskey Token 已失效，消息推送已停止。请发送 /bind 重新绑定。")
        stopStream(chan)
    }

    // ---------- QQ 事件入口 ----------

    suspend fun handlePlatformMessage(message: PlatformMessage) {
        if (message.userId == platform.selfQq) return
        when (message.chatType) {
            "private" -> handlePrivate(message)
            "group" -> handleGroup(message)
        }
    }

    private suspend fun handlePrivate(message: PlatformMessage) {
        val qq = message.userId
        if (qq.isEmpty()) return
        val allowed = cfg.allowedQq
        if (allowed.isNotEmpty() && qq !in allowed) {
            BridgeLog.i("QQ $qq 不在白名单内，忽略")
            return
        }
        val text = finalizeText(message.text, message.atTargets, groupId = null)
        val images = message.images
        if (text.isEmpty() && images.isEmpty()) return
        val ctx = ChatContext(chan = privChan(qq), isGroup = false, qq = qq)
        BridgeLog.i("QQ $qq ->: ${text.take(100)}" + (if (images.isNotEmpty()) " [+${images.size}图]" else ""))
        if (text.startsWith(cfg.commandPrefix)) {
            handleCommand(ctx, text)
        } else {
            forwardToMisskey(ctx, text, images)
        }
    }

    private suspend fun handleGroup(message: PlatformMessage) {
        val gid = message.chatId
        val qq = message.userId
        if (gid.isEmpty() || qq.isEmpty()) return
        val allowedGroups = cfg.allowedGroups
        if (allowedGroups.isNotEmpty() && gid !in allowedGroups) return
        val nickname = (message.card.ifBlank { message.nickname }).trim()
        val text = finalizeText(message.text, message.atTargets, groupId = gid)
        val images = message.images
        if (text.isEmpty() && images.isEmpty()) return
        val groupBinding = db.getGroupBinding(gid)
        val binderQq = groupBinding?.str("binder_qq") ?: ""
        val globalAdmins = cfg.adminQq
        val ctx = ChatContext(
            chan = groupChan(gid), isGroup = true, qq = qq, groupId = gid,
            nickname = nickname, role = message.role,
            binderQq = binderQq,
            isManager = qq == binderQq || qq in db.getGroupAdmins(gid) || qq in globalAdmins,
            canManageAdmins = qq == binderQq || qq in globalAdmins,
        )
        BridgeLog.i(
            "群 $gid QQ $qq ->: ${text.take(100)}" +
                (if (images.isNotEmpty()) " [+${images.size}图]" else "")
        )
        when {
            text.startsWith(cfg.commandPrefix) -> handleCommand(ctx, text)
            message.mentionedSelf -> forwardToMisskey(ctx, text, images)
            groupBinding != null && groupBinding.bool("awareness_enabled", false) -> {
                // 未@机器人的群消息收进“感知池”，下一轮对话时一并带给 AI
                val body = text.ifEmpty { if (images.isNotEmpty()) "[图片]" else "" }
                if (body.isNotEmpty()) {
                    val sink = groupSink.getOrPut(ctx.chan) { Collections.synchronizedList(ArrayList()) }
                    val formatted = formatMessageForAgent(ctx, body)
                    sink.add(formatted)
                    // 感知池只留最近的消息，避免长时间没人@时无限增长
                    synchronized(sink) { while (sink.size > GROUP_SINK_LIMIT) sink.removeAt(0) }
                }
            }
        }
    }

    private suspend fun handleCommand(ctx: ChatContext, text: String) {
        val (cmd, args) = parse(text, cfg.commandPrefix)
        if (cmd.isEmpty()) {
            sendText(ctx.chan, "空指令。发送 ${cfg.commandPrefix}help 查看全部指令。")
            return
        }
        val reply = dispatch(this, ctx, cmd, args)
        if (!reply.isNullOrEmpty()) sendText(ctx.chan, reply)
    }

    // ---------- QQ 消息段解析 ----------

    private suspend fun finalizeText(text: String, atTargets: List<String>, groupId: String?): String {
        return OneBotSegments.expandAtPlaceholders(text, atTargets) { target ->
            if (groupId != null) resolveAt(groupId, target) else "@$target"
        }
    }

    private suspend fun resolveAt(groupId: String, targetQq: String): String {
        if (targetQq.isEmpty() || targetQq == "all") return "@全体成员"
        val uname = db.getBinding(targetQq)?.strOrNull("misskey_username")
        if (!uname.isNullOrBlank()) return "@$uname"
        val key = "$groupId|$targetQq"
        // memberInfo 是按访问顺序淘汰的 LRU，读也会改变结构，必须在锁内完成
        val info = mapsLock.withLock {
            if (!memberInfo.containsKey(key)) {
                platform.getGroupMemberInfo(groupId, targetQq)?.let { memberInfo[key] = it }
            }
            memberInfo[key]
        }
        val name = info?.let { it.optString("card").ifBlank { it.optString("nickname") }.trim() } ?: ""
        return if (name.isNotEmpty()) "@$name" else "@某人"
    }

    // ---------- QQ 发送 ----------

    private suspend fun speakerLabel(ctx: ChatContext): String {
        val nums = groupUserIds.getOrPut(ctx.chan) { HashMap() }
        val n: Int = synchronized(nums) { nums.getOrPut(ctx.qq) { nums.size + 1 } }
        val uname = db.getBinding(ctx.qq)?.strOrNull("misskey_username")
        val name = if (!uname.isNullOrBlank()) "@$uname" else ctx.nickname.ifBlank { "群成员" }
        return "用户$n $name"
    }

    /** 群聊消息发给 AI 前置上发言人标识；私聊原样透传。 */
    private suspend fun formatMessageForAgent(ctx: ChatContext, text: String): String {
        if (!ctx.isGroup) return text
        val label = speakerLabel(ctx)
        return if (text.isNotEmpty()) "【$label】$text" else "【$label】"
    }

    suspend fun sendText(chan: String, text: String, imageUrl: String? = null) {
        val chunks = if (text.isEmpty()) listOf("") else splitForQq(text, cfg.qqMessageSplit)
        chunks.forEachIndexed { i, chunk ->
            val segments = ArrayList<JSONObject>()
            if (chunk.isNotEmpty()) segments.add(textSeg(chunk))
            if (imageUrl != null && i == chunks.size - 1) segments.add(imageSeg(imageUrl))
            if (segments.isEmpty()) return@forEachIndexed
            try {
                platform.sendTo(chan, segments)
            } catch (e: Exception) {
                BridgeLog.e("发送 QQ 消息失败 (通道 $chan)", e)
            }
        }
    }

    private fun textSeg(text: String) = JSONObject().put("type", "text").put("data", JSONObject().put("text", text))
    private fun imageSeg(url: String) = JSONObject().put("type", "image").put("data", JSONObject().put("file", url))

    /** 私聊里周期性刷新 QQ“正在输入”；群聊没有对应接口，返回 null。 */
    private fun startTyping(chan: String): Job? {
        if (!chan.startsWith("p:")) return null
        val qq = chan.substring(2)
        return scope.launch {
            while (isActive) {
                try {
                    platform.callApi("set_input_status", JSONObject().put("user_id", qq).put("event_type", 1))
                } catch (e: Exception) {
                    BridgeLog.d("设置输入状态失败，停止刷新: ${e.message}")
                    return@launch
                }
                delay(TYPING_INTERVAL_MS)
            }
        }
    }

    // ---------- 绑定 ----------

    fun getBindingByChan(chan: String): Map<String, Any?>? =
        if (chan.startsWith("g:")) db.getGroupBinding(chan.substring(2))
        else db.getBinding(chan.substring(2))

    fun requireBinding(ctx: ChatContext): Map<String, Any?> =
        getBindingByChan(ctx.chan) ?: throw PermissionException(
            if (ctx.isGroup) "本群尚未绑定 Misskey 账号，请绑定者或群管理员发送 /bind"
            else "尚未绑定 Misskey 账号，请先发送 /bind"
        )

    fun setChanCurrentSession(chan: String, sessionId: String?) {
        if (chan.startsWith("g:")) db.setGroupCurrentSession(chan.substring(2), sessionId)
        else db.setCurrentSession(chan.substring(2), sessionId)
    }

    suspend fun bindChanToken(ctx: ChatContext, token: String): String {
        val user = try {
            misskey.validateToken(token)
        } catch (e: MisskeyAPIError) {
            return "❌ ${e.friendly()}"
        } catch (e: Exception) {
            return "❌ 无法连接 Misskey：${e.message ?: e}"
        } ?: return "❌ Token 无效或已过期，请在 Misskey「设置 → 其他 → API」重新生成。"
        return finishBind(ctx, token, user)
    }

    private suspend fun finishBind(ctx: ChatContext, token: String, user: JSONObject): String {
        val userId = user.optString("id", "")
        val username = user.optString("username", "")
        val name = user.optString("name").takeIf { !user.isNull("name") && it.isNotBlank() } ?: username
        var note = ""
        if (ctx.isGroup) {
            val gid = ctx.groupId ?: ""
            val oldBinder = db.getGroupBinding(gid)?.str("binder_qq") ?: ""
            db.upsertGroupBinding(gid, token, userId, username, name, ctx.qq)
            groupUserIds.remove(ctx.chan)
            groupSink.remove(ctx.chan)
            note = "本群现已共用该账号与会话（绑定者：QQ ${ctx.qq}）。\n"
            if (oldBinder.isNotEmpty() && oldBinder != ctx.qq) {
                note += "绑定者已由 QQ $oldBinder 更换为 QQ ${ctx.qq}。\n"
            }
        } else {
            val exists = db.getBindingByUserId(userId)
            db.upsertBinding(ctx.qq, token, userId, username, name)
            if (exists != null && exists.str("qq_number") != ctx.qq) {
                note = "该账号此前已绑定在 QQ ${exists.str("qq_number")}，两个 QQ 将共用同一账号。\n"
            }
        }
        startStream(ctx.chan, getBindingByChan(ctx.chan) ?: emptyMap())
        var sessionLine = ""
        try {
            val info = sessions[ensureSession(ctx.chan)]
            if (info != null) sessionLine = "会话：${info.name.ifBlank { "未命名" }}（角色 ${info.characterName}）\n"
        } catch (e: MisskeyAPIError) {
            sessionLine = "⚠️ 会话准备失败：${e.friendly()}\n"
        } catch (e: Exception) {
            BridgeLog.w("绑定后准备会话失败: $e")
        }
        return "✅ 绑定成功！\n" +
            "Misskey：$name (@$username)\n" +
            sessionLine + note +
            "${ctx.scope}现在直接发送消息即可与 AI 对话" +
            (if (ctx.isGroup) "（群聊需 @机器人）" else "") +
            "，发送 /help 查看全部指令。"
    }

    suspend fun unbindChan(ctx: ChatContext): String {
        miauthTasks.remove(ctx.chan)?.cancel()
        stopStream(ctx.chan)
        sessionListCache.remove(ctx.chan)
        if (ctx.isGroup) {
            if (db.deleteGroupBinding(ctx.groupId ?: "")) {
                groupSink.remove(ctx.chan)
                groupUserIds.remove(ctx.chan)
                return "✅ 本群已解绑。重新绑定请发送 /bind"
            }
            return "本群尚未绑定 Misskey 账号。"
        }
        return if (db.deleteBinding(ctx.qq)) "✅ 已解绑。重新绑定请发送 /bind"
        else "你尚未绑定 Misskey 账号。"
    }

    fun startMiauthBind(ctx: ChatContext): String {
        miauthTasks.remove(ctx.chan)?.cancel()
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        db.addMiauthPending(sessionId, ctx.qq)
        val name = URLEncoder.encode(cfg.misskey.miauthName, "UTF-8")
        val permission = URLEncoder.encode(cfg.misskey.miauthPermission, "UTF-8")
        val url = "${cfg.misskeyBaseUrl()}/miauth/$sessionId?name=$name&permission=$permission"
        miauthTasks[ctx.chan] = scope.launch { pollMiauth(ctx, sessionId) }
        val minutes = (cfg.miauthWaitTotal / 60).toInt().coerceAtLeast(1)
        return "请在浏览器打开以下链接并授权（$minutes 分钟内有效）：\n" +
            "$url\n\n" +
            "授权后会自动完成${if (ctx.isGroup) "本群" else ""}绑定。" +
            "也可以发送 /bind <token>（在 Misskey「设置 → 其他 → API」生成）。"
    }

    private suspend fun pollMiauth(ctx: ChatContext, sessionId: String) {
        val deadline = System.currentTimeMillis() + (cfg.miauthWaitTotal * 1000).toLong()
        val interval = (cfg.miauthPollInterval * 1000).toLong().coerceAtLeast(1000)
        try {
            while (System.currentTimeMillis() < deadline) {
                delay(interval)
                if (db.getMiauthPending(sessionId) == null) return
                val res = try {
                    misskey.miauthCheck(sessionId)
                } catch (e: Exception) {
                    BridgeLog.w("MiAuth check 失败: $e")
                    continue
                } ?: continue
                // 授权刚完成的瞬间网络容易抖动，校验失败只记日志，下一轮继续
                val user = try {
                    misskey.validateToken(res.optString("token"))
                } catch (e: Exception) {
                    BridgeLog.w("MiAuth token 校验失败，稍后重试: $e")
                    continue
                }
                db.deleteMiauthPending(sessionId)
                if (user == null) {
                    sendText(ctx.chan, "❌ 授权成功但 Token 校验失败，请重新 /bind")
                    return
                }
                sendText(ctx.chan, finishBind(ctx, res.optString("token"), user))
                return
            }
            db.deleteMiauthPending(sessionId)
            sendText(ctx.chan, "MiAuth 授权已超时，如需绑定请重新发送 /bind")
        } catch (e: CancellationException) {
            db.deleteMiauthPending(sessionId)
            throw e
        } finally {
            miauthTasks.remove(ctx.chan, kotlinx.coroutines.currentCoroutineContext()[Job])
        }
    }

    fun requestBindConfirm(ctx: ChatContext, kind: String, token: String = ""): String {
        cancelPendingBind(ctx.chan)
        val timeout = cfg.bindConfirmTimeout
        val p = PendingBind(
            chan = ctx.chan, qq = ctx.qq, kind = kind, token = token,
            expiresAt = System.currentTimeMillis() / 1000.0 + timeout,
        )
        p.task = scope.launch {
            delay((timeout * 1000).toLong().coerceAtLeast(100))
            if (pendingBinds.remove(ctx.chan, p)) {
                sendText(p.chan, "QQ ${p.qq} 的绑定请求超时未确认，已自动取消。")
            }
        }
        pendingBinds[ctx.chan] = p
        BridgeLog.i("群 ${ctx.groupId} 收到待确认绑定请求（QQ ${ctx.qq}，$kind）")
        return "本群已有绑定。QQ ${ctx.qq} 发起的重新绑定需要管理员确认：\n" +
            "管理员请在 ${timeout.toInt()} 秒内发送 /确认（超时自动取消）。"
    }

    fun popPendingBind(chan: String): PendingBind? {
        val p = pendingBinds.remove(chan) ?: return null
        p.task?.cancel()
        if (p.expiresAt > 0 && System.currentTimeMillis() / 1000.0 > p.expiresAt) return null
        return p
    }

    private fun cancelPendingBind(chan: String) {
        pendingBinds.remove(chan)?.task?.cancel()
    }

    // ---------- 会话管理 ----------

    /** 读取并缓存会话详情；会话缺文风时补上配置里的默认文风。 */
    fun loadSession(token: String, sessionId: String): SessionInfo {
        var data = misskey.sessionsShow(token, sessionId)
        if (data.isNull("dialogueStyleId") || data.optString("dialogueStyleId").isBlank()) {
            misskey.ensureStyleSubscribed(token, cfg.misskey.dialogueStyleId)
            misskey.sessionsUpdate(token, sessionId, JSONObject().put("dialogueStyleId", cfg.misskey.dialogueStyleId))
            data = misskey.sessionsShow(token, sessionId)
        }
        val stickers = HashMap<String, String>()
        data.optJSONArray("characterStickers")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val url = s.optJSONObject("file")?.optString("url")?.ifBlank { null } ?: continue
                stickers[s.optString("key")] = url
            }
        }
        fun nullable(key: String) = if (data.isNull(key)) null else data.optString(key).ifBlank { null }
        val info = SessionInfo(
            id = sessionId,
            name = data.optString("name"),
            characterId = data.optString("characterId"),
            characterName = data.optString("characterName").ifBlank { "Aliya" },
            styleId = nullable("dialogueStyleId"),
            modelId = nullable("agentModelId"),
            imageModelId = nullable("agentImageModelId"),
            segmented = data.optBoolean("segmentedOutputEnabled", true),
            banned = data.optBoolean("sessionModerationBanned"),
            characterBanned = data.optBoolean("characterModerationBanned"),
            stickers = stickers,
            checkedAt = System.currentTimeMillis(),
        )
        sessions[sessionId] = info
        return info
    }

    fun sessionInfo(sessionId: String): SessionInfo? = sessions[sessionId]

    /** 已读回执不影响主流程，放后台执行，失败只记日志。 */
    private fun markReadAsync(token: String, sessionId: String) {
        scope.launch { misskey.sessionsRead(token, sessionId) }
    }

    fun invalidateSession(sessionId: String?) {
        if (sessionId != null) sessions.remove(sessionId)
    }

    /**
     * 返回通道当前可用的会话 id：当前会话有效则沿用，否则复用最近一个本角色会话，
     * 都没有就新建。会话被封禁时抛出 BannedError，交给用户决定是否 /new。
     */
    suspend fun ensureSession(chan: String): String {
        val binding = getBindingByChan(chan) ?: throw PermissionException("未绑定")
        val token = binding.str("misskey_token")
        val characterId = cfg.misskey.characterId
        val current = binding.strOrNull("current_session_id")?.ifBlank { null }
        if (current != null) {
            val cached = sessions[current]
            val info = if (cached != null && System.currentTimeMillis() - cached.checkedAt < SESSION_CHECK_TTL_MS) {
                cached
            } else try {
                loadSession(token, current)
            } catch (e: MisskeyAPIError) {
                if (e is AuthError || e is RateLimitError || e is BannedError || e.status >= 500) throw e
                BridgeLog.i("当前会话不可用(${e.code})，重新选择 (通道 $chan)")
                null
            }
            if (info != null && info.characterId == characterId) {
                if (info.characterBanned) throw BannedError("AGENT_CHARACTER_MODERATION_BANNED")
                if (info.banned) throw BannedError("AGENT_SESSION_MODERATION_BANNED")
                return current
            }
            if (info != null) BridgeLog.i("当前会话不属于配置的角色，重新选择 (通道 $chan)")
            invalidateSession(current)
            setChanCurrentSession(chan, null)
        }
        val reuse = misskey.sessionsListMine(token).firstOrNull {
            it.optString("characterId") == characterId && !it.optBoolean("sessionModerationBanned") &&
                it.optString("id").isNotBlank()
        }
        val sid = reuse?.optString("id") ?: createSession(token)
        loadSession(token, sid)
        setChanCurrentSession(chan, sid)
        BridgeLog.i("通道 $chan 使用会话 $sid" + if (reuse != null) "（复用）" else "（新建）")
        return sid
    }

    private fun createSession(token: String): String {
        misskey.ensureStyleSubscribed(token, cfg.misskey.dialogueStyleId)
        return misskey.sessionsCreate(token, cfg.misskey.characterId, cfg.misskey.dialogueStyleId)
    }

    /** /new：新建会话并切换，返回会话详情。 */
    fun newSession(chan: String, token: String, name: String?): SessionInfo {
        val sid = createSession(token)
        if (!name.isNullOrBlank()) {
            try {
                misskey.sessionsUpdate(token, sid, JSONObject().put("name", name.take(256)))
            } catch (e: Exception) {
                BridgeLog.d("新会话命名失败: $e")
            }
        }
        setChanCurrentSession(chan, sid)
        return loadSession(token, sid)
    }

    fun cacheSessions(chan: String, list: List<JSONObject>) {
        sessionListCache[chan] = list
    }

    fun cachedSession(chan: String, index: Int): JSONObject? =
        sessionListCache[chan]?.getOrNull(index - 1)

    fun clearGroupSink(chan: String) {
        groupSink.remove(chan)
    }

    private suspend fun getSendLock(chan: String): Mutex = mapsLock.withLock {
        sendLocks.getOrPut(chan) { Mutex() }
    }

    // ---------- QQ → Misskey（防连发管线） ----------

    suspend fun forwardToMisskey(ctx: ChatContext, text: String, images: List<String>) {
        val formatted = formatMessageForAgent(ctx, text)
        val pipe = mapsLock.withLock { pipes.getOrPut(ctx.chan) { ChatPipe() } }
        pipe.lock.withLock {
            pipe.texts.add(formatted)
            pipe.images.addAll(images)
            // timer 的启动与 pipeSendLoop finally 里的重启都在同一把锁下，避免双循环
            if (pipe.timer == null) {
                pipe.timer = scope.launch { pipeSendLoop(ctx, pipe) }
            }
        }
    }

    private suspend fun pipeSendLoop(ctx: ChatContext, pipe: ChatPipe) {
        try {
            delay((cfg.floodMergeWindow * 1000).toLong())
            while (true) {
                val texts: List<String>
                val images: List<String>
                pipe.lock.withLock {
                    texts = pipe.texts.toList()
                    images = pipe.images.toList()
                    pipe.texts.clear()
                    pipe.images.clear()
                }
                if (texts.isEmpty() && images.isEmpty()) return
                val merged = texts.filter { it.isNotEmpty() }.joinToString("\n")
                pipe.replyEvent.clear()
                val awaitStream = try {
                    sendRound(ctx, merged, images)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    BridgeLog.e("发送轮次失败 (通道 ${ctx.chan})", e)
                    false
                }
                if (awaitStream) waitStreamReply(ctx, pipe)
            }
        } finally {
            pipe.lock.withLock {
                pipe.timer = null
                if (pipe.texts.isNotEmpty() || pipe.images.isNotEmpty()) {
                    pipe.timer = scope.launch { pipeSendLoop(ctx, pipe) }
                }
            }
        }
    }

    /** send 没有直接带回回复（网关超时等）时，等流式推送补上，期间保持“正在输入”。 */
    private suspend fun waitStreamReply(ctx: ChatContext, pipe: ChatPipe) {
        pipe.awaitingStream = true
        val typing = startTyping(ctx.chan)
        try {
            val totalMs = (cfg.misskey.timeoutSend * 1000.0).toLong() + 60_000L
            if (!pipe.replyEvent.wait(totalMs)) {
                BridgeLog.w("等待流式回复超时 (通道 ${ctx.chan})")
                sendText(ctx.chan, "AI 迟迟没有回复，可能生成失败了。可以发送 /retry 重新生成。")
            }
        } finally {
            typing?.cancel()
            pipe.awaitingStream = false
        }
    }

    /**
     * 发送一轮对话。返回 true 表示回复还没到，需要等流式推送。
     * @param fileId 已上传的图片（/retry 复用原图）；为 null 时按 images 上传 QQ 图片
     * @param useSink 是否把群聊感知池的消息一并带上
     */
    private suspend fun sendRound(
        ctx: ChatContext,
        text: String,
        images: List<String>,
        fileId: String? = null,
        useSink: Boolean = true,
    ): Boolean {
        val binding = getBindingByChan(ctx.chan)
        if (binding == null) {
            sendText(
                ctx.chan,
                if (ctx.isGroup) "本群还未绑定 Misskey 账号。\n群管理员或任意成员可发送 /bind 完成绑定（绑定后全群共用），/help 查看帮助。"
                else "你还未绑定 Misskey 账号。\n发送 /bind 开始绑定（支持 MiAuth 授权），/help 查看帮助。"
            )
            return false
        }
        var msgText = text
        if (ctx.isGroup && useSink) {
            val sink = groupSink.remove(ctx.chan)
            if (!sink.isNullOrEmpty()) {
                val collected = synchronized(sink) { sink.toList() }
                msgText = (collected + listOf(msgText)).filter { it.isNotEmpty() }.joinToString("\n")
            }
        }
        getSendLock(ctx.chan).withLock {
            try {
                return deliverRound(ctx, binding, msgText, images, fileId)
            } catch (e: TimeoutException) {
                // 服务端多半还在生成，完成后会经流式 newAgentMessage 推送
                BridgeLog.w("发送等待超时，转为等待流式推送 (通道 ${ctx.chan})")
                return true
            } catch (e: MisskeyAPIError) {
                sendText(ctx.chan, "❌ ${e.friendly()}")
            } catch (e: PermissionException) {
                sendText(ctx.chan, e.message ?: "请先 /bind 绑定账号。")
            } catch (e: IOException) {
                sendText(ctx.chan, "❌ 无法连接 Misskey：${e.message ?: e}")
            }
            return false
        }
    }

    private suspend fun deliverRound(
        ctx: ChatContext,
        binding: Map<String, Any?>,
        text: String,
        images: List<String>,
        presetFileId: String?,
    ): Boolean {
        val token = binding.str("misskey_token")
        var fileId = presetFileId
        if (fileId == null && images.isNotEmpty()) {
            try {
                val (data, ctype) = misskey.download(images[0])
                val ext = ctype.substringAfter("/", "jpg").substringBefore("+")
                fileId = misskey.uploadDriveFile(token, data, "qq_${UUID.randomUUID().toString().take(8)}.$ext", ctype)
            } catch (e: RateLimitError) {
                throw e
            } catch (e: Exception) {
                BridgeLog.w("图片上传失败: $e")
                sendText(ctx.chan, if (text.isBlank()) "❌ 图片上传失败，本条未发送。" else "⚠️ 图片上传失败，只发送了文字。")
            }
            if (fileId != null && images.size > 1) {
                sendText(ctx.chan, "⚠️ 每条消息只能附带 1 张图片，已发送第 1 张。")
            }
        }
        if (text.isBlank() && fileId == null) return false

        var sid = ensureSession(ctx.chan)
        val typing = startTyping(ctx.chan)
        val res: AgentSendResult
        try {
            val pair = sendWithRecovery(ctx.chan, token, sid, text, fileId)
            sid = pair.first
            res = pair.second
        } finally {
            typing?.cancel()
        }

        if (!res.ok) {
            if (!res.silent) sendText(ctx.chan, "❌ ${res.error ?: "发送失败"}")
            return false
        }
        val replyText = res.replyText
        if (replyText == null) {
            if (res.silent) misskey.sessionsRead(token, sid)
            return !res.silent
        }
        res.replyMessageId?.let { markId(ctx.chan, it) }
        // 服务端 3 秒内未读就会推送提醒，分段发送可能超过这个时间，所以先标记已读再发
        markReadAsync(token, sid)
        renderAndSendReply(ctx.chan, token, sid, replyText, res.replyMessageId)
        return false
    }

    /**
     * 发送并处理可自愈的错误：会话被删则换会话重发，文风失效则补订阅后重发。
     * 发送期间登记 inflight，供 /stop 中止。
     */
    private suspend fun sendWithRecovery(
        chan: String,
        token: String,
        sessionId: String,
        text: String,
        fileId: String?,
    ): Pair<String, AgentSendResult> {
        var sid = sessionId
        var attempt = 0
        while (true) {
            val requestId = UUID.randomUUID().toString()
            inflight[chan] = Inflight(token, sid, requestId)
            try {
                return sid to misskey.agentsSend(token, sid, text, fileId, requestId)
            } catch (e: MisskeyAPIError) {
                if (e is BannedError || e is AuthError || e is RateLimitError || attempt > 0) throw e
                when (e.code) {
                    "NO_SUCH_SESSION" -> {
                        BridgeLog.w("会话已失效，换会话重发 (通道 $chan)")
                        invalidateSession(sid)
                        setChanCurrentSession(chan, null)
                        sid = ensureSession(chan)
                    }
                    "AGENT_DIALOGUE_STYLE_REQUIRED", "STYLE_NOT_AVAILABLE", "STYLE_NOT_SUBSCRIBED" -> {
                        BridgeLog.w("会话文风不可用，恢复默认文风后重发 (通道 $chan)")
                        misskey.ensureStyleSubscribed(token, cfg.misskey.dialogueStyleId)
                        misskey.sessionsUpdate(token, sid, JSONObject().put("dialogueStyleId", cfg.misskey.dialogueStyleId))
                        invalidateSession(sid)
                    }
                    else -> throw e
                }
                attempt++
            } finally {
                inflight.remove(chan)
            }
        }
    }

    /** /stop：中止当前正在生成的回复。 */
    fun abortReply(chan: String): String {
        val f = inflight[chan] ?: return "当前没有正在生成的回复。"
        return if (misskey.agentsAbort(f.token, f.sessionId, f.requestId)) "⏹ 已中止本次回复。"
        else "回复已经生成完毕，来不及中止了。"
    }

    fun isReplying(chan: String): Boolean = inflight.containsKey(chan)

    /** /retry：撤回最近一轮并用原消息重新生成。 */
    suspend fun retryLast(ctx: ChatContext): String {
        val binding = requireBinding(ctx)
        if (isReplying(ctx.chan)) return "回复还在生成中，请稍等或先发送 /stop。"
        val token = binding.str("misskey_token")
        val sid = ensureSession(ctx.chan)
        val last = misskey.agentsTimeline(token, sid, limit = 20).firstOrNull { it.optString("role") == "user" }
            ?: return "当前会话还没有可以重新生成的消息。"
        val res = misskey.agentsRollback(token, sid, last.optString("id"))
        val content = res.optString("content").ifBlank { last.optString("content") }
        val fileId = last.optJSONObject("file")?.optString("id")?.ifBlank { null }
        val pipe = mapsLock.withLock { pipes.getOrPut(ctx.chan) { ChatPipe() } }
        scope.launch {
            pipe.replyEvent.clear()
            if (sendRound(ctx, content, emptyList(), fileId, useSink = false)) waitStreamReply(ctx, pipe)
        }
        return "🔄 正在重新生成…"
    }

    /** /undo：撤回最近一轮对话（最后一条用户消息及之后的回复）。 */
    suspend fun undoLast(ctx: ChatContext): String {
        val binding = requireBinding(ctx)
        if (isReplying(ctx.chan)) return "回复还在生成中，请稍等或先发送 /stop。"
        val token = binding.str("misskey_token")
        val sid = ensureSession(ctx.chan)
        val last = misskey.agentsTimeline(token, sid, limit = 20).firstOrNull { it.optString("role") == "user" }
            ?: return "当前会话没有可以撤回的消息。"
        val res = misskey.agentsRollback(token, sid, last.optString("id"))
        val preview = ReplyRender.plainText(res.optString("content")).replace("\n", " ").let {
            if (it.length > 40) it.take(40) + "…" else it
        }
        return "↩️ 已撤回上一轮对话（删除 ${res.optInt("deletedCount")} 条消息）" +
            (if (preview.isNotBlank()) "：\n「$preview」" else "")
    }

    // ---------- AI 回复渲染 ----------

    private suspend fun renderAndSendReply(
        chan: String,
        token: String,
        sessionId: String,
        text: String,
        messageId: String?,
        prefix: String = "",
    ) {
        val info = sessions[sessionId] ?: try {
            loadSession(token, sessionId)
        } catch (e: Exception) {
            BridgeLog.w("读取会话详情失败，按默认设置渲染: $e")
            null
        }
        val rendered = ReplyRender.render(
            text,
            splitEnabled = info?.segmented ?: true,
            resolveEmoji = { misskey.emojiUrl(it) },
            resolveSticker = { info?.stickers?.get(it) },
        )
        rendered.messages.forEachIndexed { i, blocks ->
            val withPrefix = if (i == 0 && prefix.isNotEmpty()) {
                val first = blocks.firstOrNull()
                if (first != null && !first.isImage) listOf(first.copy(value = prefix + first.value)) + blocks.drop(1)
                else listOf(ContentBlock("text", prefix.trimEnd())) + blocks
            } else blocks
            sendBlocks(chan, withPrefix)
            if (i < rendered.messages.size - 1) {
                delay(ReplyRender.segmentDelayMs(blocks.filter { !it.isImage }.joinToString("") { it.value }))
            }
        }
        if (rendered.messages.isEmpty() && rendered.drawCount > 0 && prefix.isNotEmpty()) {
            sendText(chan, prefix.trimEnd())
        }
        if (rendered.drawCount > 0) {
            if (messageId == null) {
                BridgeLog.w("回复含画图指令但缺少消息 id，无法生成图片 (通道 $chan)")
                return
            }
            // 生图可能要几十秒，期间继续显示“正在输入”
            val typing = startTyping(chan)
            try {
                for (index in 0 until rendered.drawCount) {
                    val (url, reason) = misskey.generatePlaceholder(token, sessionId, messageId, index)
                    if (url != null) sendText(chan, "", imageUrl = url)
                    else sendText(chan, "（图片${if (rendered.drawCount > 1) " ${index + 1}" else ""}生成失败：$reason）")
                }
            } finally {
                typing?.cancel()
            }
        }
    }

    private suspend fun sendBlocks(chan: String, blocks: List<ContentBlock>) {
        if (blocks.none { it.isImage }) {
            sendText(chan, blocks.joinToString("") { it.value })
            return
        }
        val segs = blocks.map { if (it.isImage) imageSeg(it.value) else textSeg(it.value) }
        try {
            platform.sendTo(chan, segs)
        } catch (e: Exception) {
            BridgeLog.e("发送 QQ 图文消息失败 (通道 $chan)", e)
        }
    }

    private fun signalReply(chan: String) {
        pipes[chan]?.replyEvent?.set()
    }

    // ---------- Misskey → QQ ----------

    private fun markId(chan: String, msgId: String): Boolean {
        val lru = deliveredIds.getOrPut(chan) { LinkedHashMap() }
        synchronized(lru) {
            if (lru.containsKey(msgId)) return false
            lru[msgId] = Unit
            val it = lru.keys.iterator()
            while (lru.size > ID_DEDUP_SIZE && it.hasNext()) {
                it.next()
                it.remove()
            }
            return true
        }
    }

    private suspend fun onMisskeyEvent(chan: String, etype: String, payload: JSONObject) {
        when (etype) {
            "agent_message" -> handleAgentMessage(chan, payload)
            "chat" -> handleChat(chan, payload)
            "notification" -> handleNotification(chan, payload)
        }
    }

    /**
     * newAgentMessage：AI 回复在 3 秒内未被阅读时推送，涵盖主动消息与 send 超时后补到的回复。
     * 事件里的 messageText 只是 200 字预览，正文从时间线取。
     */
    private suspend fun handleAgentMessage(chan: String, msg: JSONObject) {
        val binding = getBindingByChan(chan) ?: return
        val msgId = msg.optString("messageId")
        val sid = msg.optString("sessionId")
        if (msgId.isEmpty() || sid.isEmpty()) return
        if (sid != binding.strOrNull("current_session_id")) return
        if (!markId(chan, msgId)) return
        val token = binding.str("misskey_token")
        val full = try {
            misskey.agentsTimeline(token, sid, limit = 10).firstOrNull { it.optString("id") == msgId }
        } catch (e: Exception) {
            BridgeLog.w("拉取消息正文失败，改用预览文本: $e")
            null
        }
        if (full != null && full.optString("role") != "assistant") return
        val text = full?.optString("content") ?: msg.optString("messageText")
        if (text.isBlank()) return
        val isReply = pipes[chan]?.awaitingStream == true
        val prefix = if (isReply) "" else "💌 ${sessions[sid]?.characterName ?: "Aliya"}（主动消息）\n"
        markReadAsync(token, sid)
        renderAndSendReply(chan, token, sid, text, msgId, prefix)
        signalReply(chan)
    }

    private suspend fun handleChat(chan: String, msg: JSONObject) {
        val binding = getBindingByChan(chan) ?: return
        val msgId = msg.optString("id")
        if (msgId.isNotEmpty() && !markId(chan, msgId)) return
        val fromUser = msg.optJSONObject("fromUser") ?: JSONObject()
        val fromUserId = msg.optString("fromUserId").ifBlank { fromUser.optString("id") }
        if (fromUserId == binding.str("misskey_user_id")) return
        if (!binding.bool("dm_enabled", true)) return
        val text = if (msg.isNull("text")) "" else msg.optString("text")
        val fileUrl = msg.optJSONObject("file")?.optString("url")?.ifBlank { null }
        if (text.isEmpty() && fileUrl == null) return
        val name = fromUser.optString("name").takeIf { !fromUser.isNull("name") && it.isNotBlank() }
            ?: fromUser.optString("username")
        val room = msg.optJSONObject("toRoom")?.optString("name")?.ifBlank { null }
        val label = if (room != null) "💬 群聊「$room」$name" else "💬 私信 $name (@${fromUser.optString("username")})"
        sendText(chan, "$label：\n${text.ifEmpty { "[图片]" }}", imageUrl = fileUrl)
    }

    private suspend fun handleNotification(chan: String, ntf: JSONObject) {
        val binding = getBindingByChan(chan) ?: return
        when (val ntype = ntf.optString("type")) {
            // 封禁影响能否继续对话，不受通知开关控制
            "agentSessionBanned" -> {
                val sid = ntf.optString("sessionId")
                invalidateSession(sid)
                val banned = ntf.optBoolean("banned", true)
                val isCurrent = sid == binding.strOrNull("current_session_id")
                sendText(
                    chan,
                    if (banned) "⚠️ 会话「${ntf.optString("sessionName")}」已被封禁" + (if (isCurrent) "，发送 /new 新建会话后继续。" else "。")
                    else "✅ 会话「${ntf.optString("sessionName")}」已解除封禁。"
                )
            }
            "agentCharacterBanned" -> {
                sessions.values.removeIf { it.characterId == ntf.optString("characterId") }
                if (ntf.optString("characterId") != cfg.misskey.characterId) return
                sendText(
                    chan,
                    if (ntf.optBoolean("banned", true)) "⚠️ 角色「${ntf.optString("characterName")}」已被封禁，暂时无法对话。"
                    else "✅ 角色「${ntf.optString("characterName")}」已解除封禁。"
                )
            }
            else -> {
                if (!binding.bool("notify_enabled", true)) return
                val user = ntf.optJSONObject("user") ?: JSONObject()
                val uname = user.optString("name").takeIf { !user.isNull("name") && it.isNotBlank() }
                    ?: user.optString("username").ifBlank { "有人" }
                val handle = "$uname (@${user.optString("username")})"
                val note = ntf.optJSONObject("note") ?: JSONObject()
                when {
                    ntype in NOTIFY_LABELS -> {
                        val lines = arrayListOf("🔔 $handle ${NOTIFY_LABELS[ntype]}")
                        if (ntype == "reaction") ntf.optString("reaction").ifBlank { null }?.let { lines[0] += " $it" }
                        val noteText = if (note.isNull("text")) "" else note.optString("text")
                        if (noteText.isNotEmpty()) lines.add(noteText.take(200) + if (noteText.length > 200) "…" else "")
                        note.optString("id").ifBlank { null }?.let { lines.add("${cfg.misskeyBaseUrl()}/notes/$it") }
                        sendText(chan, lines.joinToString("\n"))
                    }
                    ntype == "follow" -> sendText(chan, "🔔 $handle 关注了你")
                    ntype == "receiveFollowRequest" -> sendText(chan, "🔔 $handle 请求关注你，请在 Misskey 网页端处理")
                    ntype == "followRequestAccepted" -> sendText(chan, "🔔 $handle 通过了你的关注请求")
                }
            }
        }
    }

    private companion object {
        const val GROUP_SINK_LIMIT = 50
    }
}

/**
 * 按 QQ 单条长度上限切分长文本：优先在换行处断开，且不拆开代理对（emoji）。
 */
fun splitForQq(text: String, limit: Int): List<String> {
    val max = limit.coerceAtLeast(100)
    if (text.length <= max) return listOf(text)
    val out = ArrayList<String>()
    var rest = text
    while (rest.length > max) {
        var cut = rest.lastIndexOf('\n', max)
        if (cut < max / 2) cut = max
        if (Character.isHighSurrogate(rest[cut - 1])) cut--
        out.add(rest.substring(0, cut).trimEnd())
        rest = rest.substring(cut).trimStart('\n')
    }
    if (rest.isNotEmpty()) out.add(rest)
    return out
}
