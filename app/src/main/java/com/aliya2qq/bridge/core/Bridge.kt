package com.aliya2qq.bridge.core

import com.aliya2qq.bridge.commands.ChatContext
import com.aliya2qq.bridge.commands.PermissionException
import com.aliya2qq.bridge.commands.dispatch
import com.aliya2qq.bridge.commands.isAliyaSession
import com.aliya2qq.bridge.commands.parse
import com.aliya2qq.bridge.config.BridgeConfig
import com.aliya2qq.bridge.db.Database
import com.aliya2qq.bridge.db.bool
import com.aliya2qq.bridge.db.str
import com.aliya2qq.bridge.db.strOrNull
import com.aliya2qq.bridge.misskey.AgentSendResult
import com.aliya2qq.bridge.misskey.BannedError
import com.aliya2qq.bridge.misskey.MisskeyAPIError
import com.aliya2qq.bridge.misskey.MisskeyClient
import com.aliya2qq.bridge.misskey.MisskeyStream
import com.aliya2qq.bridge.misskey.RateLimitError
import com.aliya2qq.bridge.platforms.OneBotSegments
import com.aliya2qq.bridge.platforms.PlatformHub
import com.aliya2qq.bridge.platforms.PlatformMessage
import com.aliya2qq.bridge.render.ReplyRender
import com.aliya2qq.bridge.util.AsyncEvent
import com.aliya2qq.bridge.util.BridgeLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
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

private val AI_USERNAMES = setOf("aliya", "agent", "ai")

private const val TEXT_DEDUP_SIZE = 20
private const val ID_DEDUP_SIZE = 500
private const val MEMBER_INFO_CACHE_SIZE = 512

/** 回复正文后紧跟发图前的间隔，给客户端一点渲染时间。 */
private const val REPLY_IMAGE_DELAY_MS = 300L

fun privChan(qq: String) = "p:$qq"
fun groupChan(gid: String) = "g:$gid"

/** 同一通道的消息聚合：短时间内的连发合并成一轮发送，等待 AI 回复后再发下一轮。 */
class ChatPipe {
    val lock = Mutex()
    val texts = ArrayList<String>()
    val images = ArrayList<String>()
    var timer: Job? = null
    var awaiting = false
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

    // mapsLock 保护 streams/pipes 的创建销毁与 memberInfo 的读写。
    // 其余共享状态使用并发容器，槽内对象（ArrayDeque 等）用 synchronized 保护。
    private val mapsLock = Mutex()

    private val streams = HashMap<String, MisskeyStream>()
    private val sendLocks = HashMap<String, Mutex>()
    private val pipes = ConcurrentHashMap<String, ChatPipe>()
    private val memberInfo = object : LinkedHashMap<String, JSONObject>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>?): Boolean {
            return size > MEMBER_INFO_CACHE_SIZE
        }
    }

    private val recentTexts = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val deliveredIds = ConcurrentHashMap<String, LinkedHashMap<String, Unit>>()
    private val sessionCache = ConcurrentHashMap<String, List<JSONObject>>()
    private val miauthTasks = ConcurrentHashMap<String, Job>()
    private val segEnabledMap = ConcurrentHashMap<String, Boolean>()
    private val sessionChan = ConcurrentHashMap<String, String>()
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
        val ctx = ChatContext(
            chan = privChan(qq), isGroup = false, qq = qq,
            groupId = null, nickname = "", role = "",
        )
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
        val role = message.role
        val text = finalizeText(message.text, message.atTargets, groupId = gid)
        val images = message.images
        if (text.isEmpty() && images.isEmpty()) return
        val groupBinding = db.getGroupBinding(gid)
        val binderQq = groupBinding?.str("binder_qq") ?: ""
        val globalAdmins = cfg.adminQq
        val isManager = qq == binderQq || qq in db.getGroupAdmins(gid) || qq in globalAdmins
        val ctx = ChatContext(
            chan = groupChan(gid), isGroup = true, qq = qq, groupId = gid,
            nickname = nickname, role = role,
            binderQq = binderQq,
            isManager = isManager,
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
                val sink = groupSink.getOrPut(ctx.chan) { Collections.synchronizedList(ArrayList()) }
                val body = text.ifEmpty { if (images.isNotEmpty()) "[图片]" else "" }
                if (body.isNotEmpty()) {
                    val formatted = formatMessageForAgent(ctx, body)
                    sink.add(formatted)
                    BridgeLog.i("群 $gid 感知收集：${formatted.take(80)}")
                }
            }
        }
    }

    private suspend fun handleCommand(ctx: ChatContext, text: String) {
        val (cmd, args) = parse(text, cfg.commandPrefix)
        if (cmd.isEmpty()) {
            sendText(ctx.chan, "空指令。发送 /help 查看全部指令。")
            return
        }
        val reply = dispatch(this, ctx, cmd, args)
        if (!reply.isNullOrEmpty()) {
            sendText(ctx.chan, reply)
        }
    }

    // ---------- QQ 消息段解析 ----------

    private suspend fun finalizeText(text: String, atTargets: List<String>, groupId: String?): String {
        return OneBotSegments.expandAtPlaceholders(text, atTargets) { target ->
            if (groupId != null) resolveAt(groupId, target) else "@$target"
        }
    }

    private suspend fun resolveAt(groupId: String, targetQq: String): String {
        if (targetQq.isEmpty() || targetQq == "all") return "@全体成员"
        val personal = db.getBinding(targetQq)
        val uname = personal?.strOrNull("misskey_username")
        if (!uname.isNullOrBlank()) return "@$uname"
        val key = "$groupId|$targetQq"
        // memberInfo 是按访问顺序淘汰的 LRU，读也会改变结构，必须在锁内完成
        val info = mapsLock.withLock {
            if (!memberInfo.containsKey(key)) {
                platform.getGroupMemberInfo(groupId, targetQq)?.let { memberInfo[key] = it }
            }
            memberInfo[key]
        }
        return if (info != null) memberDisplayName(info) else "@某人"
    }

    private fun memberDisplayName(info: JSONObject): String {
        val name = info.optString("card").ifBlank { info.optString("nickname") }.trim()
        return if (name.isNotEmpty()) "@$name" else "@某人"
    }

    // ---------- QQ 发送 ----------

    private suspend fun speakerLabel(ctx: ChatContext): String {
        val nums = groupUserIds.getOrPut(ctx.chan) { HashMap() }
        val n: Int = synchronized(nums) {
            nums.getOrPut(ctx.qq) { nums.size + 1 }
        }
        val personal = db.getBinding(ctx.qq)
        val uname = personal?.strOrNull("misskey_username")
        val name = if (!uname.isNullOrBlank()) "@$uname" else ctx.nickname.ifBlank { "群成员" }
        return "用户$n $name"
    }

    /** 群聊消息发给 AI 前置上发言人标识；私聊原样透传。 */
    suspend fun formatMessageForAgent(ctx: ChatContext, text: String): String {
        if (!ctx.isGroup) return text
        val label = speakerLabel(ctx)
        return if (text.isNotEmpty()) "【$label】$text" else "【$label】"
    }

    suspend fun sendText(chan: String, text: String, imageUrl: String? = null) {
        val limit = cfg.qqMessageSplit
        val chunks = if (text.isEmpty() && imageUrl != null) listOf("")
        else text.chunked(limit.coerceAtLeast(1)).ifEmpty { listOf("") }
        chunks.forEachIndexed { i, chunk ->
            val segments = ArrayList<JSONObject>()
            if (chunk.isNotEmpty() || imageUrl == null) {
                segments.add(
                    JSONObject()
                        .put("type", "text")
                        .put("data", JSONObject().put("text", chunk))
                )
            }
            if (imageUrl != null && i == chunks.size - 1) {
                segments.add(
                    JSONObject()
                        .put("type", "image")
                        .put("data", JSONObject().put("file", imageUrl))
                )
            }
            if (segments.isEmpty()) return@forEachIndexed
            try {
                platform.sendTo(chan, segments)
            } catch (e: Exception) {
                BridgeLog.e("发送 QQ 消息失败 (通道 $chan)", e)
            }
        }
    }

    // ---------- 绑定查询辅助 ----------

    fun getBindingByChan(chan: String): Map<String, Any?>? =
        if (chan.startsWith("g:")) db.getGroupBinding(chan.substring(2))
        else db.getBinding(chan.substring(2))

    fun setChanCurrentSession(chan: String, sessionId: String?) {
        if (chan.startsWith("g:")) db.setGroupCurrentSession(chan.substring(2), sessionId)
        else db.setCurrentSession(chan.substring(2), sessionId)
    }

    fun setChanLastSeen(chan: String, messageId: String) {
        if (chan.startsWith("g:")) db.setGroupLastSeenMessage(chan.substring(2), messageId)
        else db.setLastSeenMessage(chan.substring(2), messageId)
    }

    suspend fun bindChanToken(ctx: ChatContext, token: String): String {
        val user = misskey.validateToken(token)
            ?: return "❌ Token 无效或已过期，请在 Misskey「设置 → 其他 → API」重新生成。"
        return finishBind(ctx, token, user)
    }

    private suspend fun finishBind(ctx: ChatContext, token: String, user: JSONObject): String {
        val userId = user.optString("id", "")
        val username = user.optString("username", "")
        val name = user.optString("name").ifBlank { user.optString("username", "") }
        var note = ""
        if (ctx.isGroup) {
            val oldBinder = db.getGroupBinding(ctx.groupId ?: "")?.str("binder_qq") ?: ""
            db.upsertGroupBinding(ctx.groupId ?: "", token, userId, username, name, ctx.qq)
            groupUserIds.remove(ctx.chan)
            groupSink.remove(ctx.chan)
            note = "\n本群现已共用该账号与会话（绑定者：QQ ${ctx.qq}）。"
            if (oldBinder.isNotEmpty() && oldBinder != ctx.qq) {
                note += "\n绑定者已由 QQ $oldBinder 更换为 QQ ${ctx.qq}。"
            }
        } else {
            val exists = db.getBindingByUserId(userId)
            db.upsertBinding(ctx.qq, token, userId, username, name)
            if (exists != null && exists.str("qq_number") != ctx.qq) {
                note = "\n该账号此前已绑定在 QQ ${exists.str("qq_number")}，现已改为绑定当前 QQ。"
            }
        }
        startStream(ctx.chan, getBindingByChan(ctx.chan) ?: emptyMap())
        try {
            val binding = getBindingByChan(ctx.chan)
            if (binding != null) {
                val sessionId = ensureSession(ctx.chan, binding)
                sessionChan.putIfAbsent(sessionId, ctx.chan)
                val msgs = misskey.agentsTimeline(token, sessionId, limit = 1)
                msgs.firstOrNull()?.let { setChanLastSeen(ctx.chan, it.optString("id")) }
            }
        } catch (e: Exception) {
            BridgeLog.w("初始化绑定游标失败: $e")
        }
        val scopeText = if (ctx.isGroup) "本群" else "你"
        return "✅ 绑定成功！\n" +
            "Misskey: ${user.optString("name").ifBlank { user.optString("username") }} (@${user.optString("username")})\n" +
            note + scopeText + "现在直接发送消息即可与 AI 角色对话，发送 /help 查看全部指令。"
    }

    suspend fun unbindChan(ctx: ChatContext): String {
        miauthTasks.remove(ctx.chan)?.cancel()
        stopStream(ctx.chan)
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

    suspend fun startMiauthBind(ctx: ChatContext): String {
        miauthTasks.remove(ctx.chan)?.cancel()
        val sessionId = UUID.randomUUID().toString().replace("-", "")
        db.addMiauthPending(sessionId, ctx.qq)
        val base = cfg.misskeyBaseUrl()
        val name = URLEncoder.encode(cfg.misskey.miauthName, "UTF-8")
        val permission = URLEncoder.encode(cfg.misskey.miauthPermission, "UTF-8")
        val url = "$base/miauth/$sessionId?name=$name&permission=$permission"
        miauthTasks[ctx.chan] = scope.launch { pollMiauth(ctx, sessionId) }
        val scopeText = if (ctx.isGroup) "本群" else ""
        return "请在浏览器打开以下链接并授权（5 分钟内有效）：\n" +
            "$url\n\n" +
            "授权完成后会自动完成${scopeText}绑定。也可以直接发送 /bind <token> " +
            "（token 在 Misskey「设置 → 其他 → API」生成）。"
    }

    private suspend fun pollMiauth(ctx: ChatContext, sessionId: String) {
        val deadline = System.currentTimeMillis() / 1000.0 + cfg.miauthWaitTotal
        val interval = cfg.miauthPollInterval
        try {
            while (System.currentTimeMillis() / 1000.0 < deadline) {
                delay((interval * 1000).toLong())
                if (db.getMiauthPending(sessionId) == null) return
                val res = try {
                    misskey.miauthCheck(sessionId)
                } catch (e: Exception) {
                    BridgeLog.w("MiAuth check 失败: $e")
                    continue
                } ?: continue
                val token = res.optString("token")
                // 授权刚完成的瞬间网络容易抖动，校验失败只记日志，下一轮继续
                val user = try {
                    misskey.validateToken(token)
                } catch (e: Exception) {
                    BridgeLog.w("MiAuth token 校验失败，稍后重试: $e")
                    continue
                }
                if (user == null) {
                    sendText(ctx.chan, "❌ 授权成功但 Token 校验失败，请重试 /bind")
                    return
                }
                db.deleteMiauthPending(sessionId)
                val reply = finishBind(ctx, token, user)
                sendText(ctx.chan, reply)
                return
            }
            db.deleteMiauthPending(sessionId)
            sendText(ctx.chan, "MiAuth 授权超时，请重新发送 /bind")
        } catch (e: CancellationException) {
            db.deleteMiauthPending(sessionId)
            throw e
        }
    }

    // ---------- 会话管理 ----------

    private fun rememberSessionData(chan: String, sessionData: JSONObject) {
        segEnabledMap[chan] = sessionData.opt("segmentedOutputEnabled") != false
    }

    fun isSegEnabled(chan: String): Boolean? = segEnabledMap[chan]
    fun setSegEnabled(chan: String, value: Boolean) {
        segEnabledMap[chan] = value
    }

    fun clearGroupSink(chan: String) {
        groupSink.remove(chan)
    }

    suspend fun ensureSession(chan: String, binding: Map<String, Any?>?): String {
        if (binding == null) {
            throw PermissionException("未绑定")
        }
        val token = binding.str("misskey_token")
        val sessionId = binding.strOrNull("current_session_id")
        if (!sessionId.isNullOrBlank()) {
            try {
                val current = misskey.sessionsShow(token, sessionId)
                if (isAliyaSession(cfg.misskey.characterId, current)) {
                    ensureSessionStyle(token, sessionId, current)
                    rememberSessionData(chan, current)
                    return sessionId
                }
                BridgeLog.i("当前会话角色不匹配，重新选择 (通道 $chan)")
            } catch (e: MisskeyAPIError) {
                if (e.status !in listOf(400, 403, 404)) throw e
                BridgeLog.i("当前会话不可用(${e.status})，重新选择 (通道 $chan)")
            }
            setChanCurrentSession(chan, null)
        }
        val sessions = misskey.sessionsListMine(token)
        for (s in sessions) {
            if (isAliyaSession(cfg.misskey.characterId, s) && s.optString("id").isNotEmpty()) {
                val sid = s.getString("id")
                ensureSessionStyle(token, sid, s)
                rememberSessionData(chan, s)
                setChanCurrentSession(chan, sid)
                BridgeLog.i("已复用已有 Aliya 会话 $sid (通道 $chan)")
                return sid
            }
        }
        misskey.ensureStyleSubscribed(token, cfg.misskey.dialogueStyleId)
        val sid = misskey.sessionsCreate(token, cfg.misskey.characterId, cfg.misskey.dialogueStyleId)
        setChanCurrentSession(chan, sid)
        BridgeLog.i("已为通道 $chan 创建会话 $sid")
        return sid
    }

    private suspend fun ensureSessionStyle(token: String, sessionId: String, sessionData: JSONObject) {
        if (sessionData.has("dialogueStyleId") && !sessionData.isNull("dialogueStyleId") &&
            sessionData.optString("dialogueStyleId").isNotEmpty()
        ) return
        val styleId = cfg.misskey.dialogueStyleId
        misskey.ensureStyleSubscribed(token, styleId)
        misskey.sessionsUpdate(token, sessionId, JSONObject().put("dialogueStyleId", styleId))
    }

    suspend fun recreateSession(chan: String, binding: Map<String, Any?>): String {
        setChanCurrentSession(chan, null)
        return ensureSession(chan, binding)
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
            // timer 的启动与 pipeSendLoop finally 里的重启都在同一把锁下，
            // 避免“旧循环收尾”与“新消息入队”互相覆盖 timer 造成双循环
            if (pipe.timer == null && !pipe.awaiting) {
                pipe.timer = scope.launch { pipeSendLoop(ctx, pipe) }
            }
        }
    }

    private suspend fun pipeSendLoop(ctx: ChatContext, pipe: ChatPipe) {
        try {
            var debounceFirst = true
            while (true) {
                if (debounceFirst) {
                    delay((cfg.floodMergeWindow * 1000).toLong())
                    debounceFirst = false
                }
                val texts: List<String>
                val images: List<String>
                pipe.lock.withLock {
                    texts = pipe.texts.toList()
                    images = pipe.images.toList()
                    pipe.texts.clear()
                    pipe.images.clear()
                    if (texts.isNotEmpty()) pipe.awaiting = true
                }
                if (texts.isEmpty()) return
                val merged = texts.filter { it.isNotEmpty() }.joinToString("\n")
                val replied = try {
                    sendRound(ctx, merged, images)
                } catch (e: Exception) {
                    BridgeLog.e("发送轮次失败 (通道 ${ctx.chan})", e)
                    true
                }
                if (replied) continue
                waitReplyWithTyping(ctx, pipe)
                pipe.replyEvent.clear()
            }
        } finally {
            pipe.lock.withLock {
                pipe.timer = null
                pipe.awaiting = false
                if (pipe.texts.isNotEmpty()) {
                    pipe.timer = scope.launch { pipeSendLoop(ctx, pipe) }
                }
            }
        }
    }

    /**
     * 消息已发往 Misskey、等待回复期间，通过 NapCat 让 QQ 对端看到“正在输入”。
     * QQ 的输入状态衰减很快，需要高频刷新（0.5s/次，与 AstrBot input_state 一致）。
     */
    private suspend fun waitReplyWithTyping(ctx: ChatContext, pipe: ChatPipe) {
        val totalMs = (cfg.misskey.timeoutSend * 1000.0).toLong() + 60_000L
        val step = 500L
        var waited = 0L
        while (waited < totalMs) {
            if (!ctx.isGroup) {
                try {
                    platform.callApi(
                        "set_input_status",
                        JSONObject().put("user_id", ctx.qq).put("event_type", 1),
                    )
                } catch (e: Exception) {
                    BridgeLog.w("设置输入状态失败（改为普通等待）: ${e.message}")
                    if (!pipe.replyEvent.wait(totalMs - waited)) {
                        BridgeLog.w("等待回复超时，继续处理队列 (通道 ${ctx.chan})")
                    }
                    return
                }
            }
            if (pipe.replyEvent.wait(step)) return
            waited += step
        }
        BridgeLog.w("等待回复超时，继续处理队列 (通道 ${ctx.chan})")
    }

    private suspend fun sendRound(ctx: ChatContext, text: String, images: List<String>): Boolean {
        val binding = getBindingByChan(ctx.chan)
        if (binding == null) {
            if (ctx.isGroup) {
                sendText(
                    ctx.chan,
                    "本群还未绑定 Misskey 账号。\n" +
                        "群管理员或任意成员可发送 /bind 完成绑定（绑定后全群共用），/help 查看帮助。"
                )
            } else {
                sendText(
                    ctx.chan,
                    "你还未绑定 Misskey 账号。\n" +
                        "发送 /bind 开始绑定（支持 MiAuth 授权），/help 查看帮助。"
                )
            }
            return true
        }
        var msgText = text
        if (ctx.isGroup) {
            val sink = groupSink.remove(ctx.chan)
            if (!sink.isNullOrEmpty()) {
                val collected = synchronized(sink) { sink.toList() }
                msgText = if (msgText.isNotEmpty()) (collected + msgText).joinToString("\n")
                else collected.joinToString("\n")
            }
        }
        val lock = getSendLock(ctx.chan)
        lock.withLock {
            try {
                val res = forwardLocked(ctx, binding, msgText, images)
                val replyText = if (res == null || res.isNull("reply_text")) null else res.optString("reply_text")
                return res != null && (!replyText.isNullOrEmpty() || !res.optBoolean("ok", true))
            } catch (e: BannedError) {
                sendText(ctx.chan, "发送失败：会话或角色被封禁（${e.code}）。可发送 /new 新建会话。")
            } catch (e: RateLimitError) {
                sendText(ctx.chan, "Misskey 限流中，请约 ${maxOf(1, e.retryAfter.toInt())} 秒后再试。")
            } catch (e: TimeoutException) {
                sendText(ctx.chan, "发送超时，请稍后重试。")
            } catch (e: PermissionException) {
                sendText(ctx.chan, "请先 /bind 绑定账号。")
            } catch (e: MisskeyAPIError) {
                sendText(ctx.chan, "❌ Misskey 错误：$e")
            }
            return true
        }
    }

    private suspend fun sendAgent(
        token: String,
        ctx: ChatContext,
        binding: Map<String, Any?>,
        sessionId0: String,
        text: String,
        fileId: String?,
    ): Pair<String, JSONObject> {
        fun pack(r: AgentSendResult) = JSONObject()
            .put("ok", r.ok)
            .put("delivered", r.delivered)
            .put("reply_text", r.replyText ?: JSONObject.NULL)
            .put("reply_message_id", r.replyMessageId ?: JSONObject.NULL)
            .put("error", r.error ?: JSONObject.NULL)

        var sessionId = sessionId0
        return try {
            sessionId to pack(misskey.agentsSend(token, sessionId, text, fileId))
        } catch (e: BannedError) {
            throw e
        } catch (e: MisskeyAPIError) {
            if (e.code.uppercase().contains("SESSION")) {
                BridgeLog.w("会话失效，重建后重试 (通道 ${ctx.chan})")
                sessionId = recreateSession(ctx.chan, binding)
                sessionId to pack(misskey.agentsSend(token, sessionId, text, fileId))
            } else throw e
        }
    }

    private suspend fun forwardLocked(
        ctx: ChatContext,
        binding: Map<String, Any?>,
        text: String,
        images: List<String>,
    ): JSONObject? {
        val token = binding.str("misskey_token")
        var fileId: String? = null
        var msgText = text
        if (images.isNotEmpty()) {
            try {
                val (data, ctype) = misskey.download(images[0])
                val ext = ctype.split("/", limit = 2).getOrNull(1) ?: "jpg"
                fileId = misskey.uploadDriveFile(token, data, "qq_${UUID.randomUUID().toString().take(8)}.$ext", ctype)
                if (images.size > 1) {
                    msgText += "\n[还有 ${images.size - 1} 张图片未发送]"
                }
            } catch (e: RateLimitError) {
                throw e
            } catch (e: Exception) {
                BridgeLog.w("图片上传失败: $e")
                msgText += "\n[图片上传失败]"
            }
        }

        val sessionId0 = ensureSession(ctx.chan, binding)
        sessionChan[sessionId0] = ctx.chan
        val (sessionId, res) = sendAgent(token, ctx, binding, sessionId0, msgText, fileId)
        sessionChan[sessionId] = ctx.chan

        if (!res.optBoolean("ok", true)) {
            sendText(ctx.chan, "❌ 发送失败：${res.optString("error").ifBlank { "未知错误" }}")
            return res
        }
        val replyText = if (res.isNull("reply_text")) null else res.optString("reply_text")
        if (replyText != null) {
            rememberText(ctx.chan, replyText)
            val replyId = if (res.isNull("reply_message_id")) null else res.optString("reply_message_id")
            if (!replyId.isNullOrBlank()) markId(ctx.chan, replyId)
            renderAndSendReply(ctx.chan, binding, replyText, replyId)
        }
        return res
    }

    private suspend fun renderAndSendReply(
        chan: String,
        binding: Map<String, Any?>,
        text: String,
        messageId: String?,
        prefix: String = "",
        extraImages: List<String> = emptyList(),
    ) {
        val sessionId = binding.strOrNull("current_session_id") ?: ""
        val rendered = ReplyRender.renderReply(
            misskey,
            binding.str("misskey_token"),
            sessionId,
            text,
            messageId,
            segEnabledMap[chan] ?: true,
        )
        val allImages = rendered.images + extraImages
        if (rendered.messages.isNotEmpty()) {
            rendered.messages.forEachIndexed { i, blocks ->
                val segs = ArrayList<JSONObject>()
                val bodyParts = StringBuilder()
                for (b in blocks) {
                    if (b.isImage) {
                        segs.add(
                            JSONObject().put("type", "image")
                                .put("data", JSONObject().put("file", b.value))
                        )
                    } else {
                        bodyParts.append(b.value)
                        segs.add(
                            JSONObject().put("type", "text")
                                .put("data", JSONObject().put("text", b.value))
                        )
                    }
                }
                val body = bodyParts.toString()
                if (i == 0 && prefix.isNotEmpty()) {
                    if (segs.isNotEmpty() && segs[0].optString("type") == "text") {
                        segs[0].getJSONObject("data").put("text", prefix + segs[0].getJSONObject("data").optString("text"))
                    } else {
                        segs.add(0, JSONObject().put("type", "text").put("data", JSONObject().put("text", prefix)))
                    }
                }
                if (segs.isEmpty()) return@forEachIndexed
                try {
                    platform.sendTo(chan, segs)
                } catch (e: Exception) {
                    BridgeLog.e("发送 QQ 混排消息失败 (通道 $chan)", e)
                }
                if (i < rendered.messages.size - 1) {
                    delay(ReplyRender.segmentDelayMs(body).toLong())
                }
            }
            for (url in allImages) {
                val inlined = rendered.messages.any { blocks ->
                    blocks.any { it.isImage && it.value == url }
                }
                if (inlined) continue
                sendText(chan, "", imageUrl = url)
            }
            return
        }

        val segments = rendered.segments.ifEmpty {
            if (text.trim().isNotEmpty()) listOf(text.trim()) else emptyList()
        }
        segments.forEachIndexed { i, seg ->
            val body = if (i == 0) prefix + seg else seg
            sendText(chan, body)
            if (i < segments.size - 1) {
                delay(ReplyRender.segmentDelayMs(seg).toLong())
            }
        }
        if (segments.isNotEmpty() && allImages.isNotEmpty()) {
            delay(REPLY_IMAGE_DELAY_MS)
        }
        for (url in allImages) {
            sendText(chan, "", imageUrl = url)
        }
    }

    private fun signalReply(chan: String) {
        pipes[chan]?.replyEvent?.set()
    }

    // ---------- Misskey → QQ ----------

    private fun rememberText(chan: String, text: String) {
        val dq = recentTexts.getOrPut(chan) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(text.trim())
            while (dq.size > TEXT_DEDUP_SIZE) dq.removeFirst()
        }
    }

    private fun seenText(chan: String, text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        val dq = recentTexts[chan] ?: return false
        return synchronized(dq) { normalized in dq }
    }

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

    private suspend fun handleAgentMessage(chan: String, msg: JSONObject) {
        val binding = getBindingByChan(chan) ?: return
        val msgId = msg.optString("messageId", "")
        val text = msg.optString("messageText", "")
        if (msgId.isNotEmpty() && !markId(chan, msgId)) return
        val payloadSid = msg.optString("sessionId", "")
        val boundSid = binding.strOrNull("current_session_id") ?: ""
        if (payloadSid.isNotEmpty() && boundSid.isNotEmpty() && payloadSid != boundSid) return
        if (payloadSid.isNotEmpty() && sessionChan[payloadSid] != null && sessionChan[payloadSid] != chan) return
        if (seenText(chan, text)) return
        if (text.trim().isEmpty()) return
        rememberText(chan, text)
        val sessionName = msg.optString("sessionName").ifBlank { "Aliya" }
        val file = msg.optJSONObject("file")
        val fileUrl = file?.optString("url")?.ifBlank { null }
        renderAndSendReply(
            chan, binding, text, msgId.ifBlank { null },
            prefix = "$sessionName：\n",
            extraImages = if (fileUrl != null) listOf(fileUrl) else emptyList(),
        )
        signalReply(chan)
    }

    private suspend fun handleChat(chan: String, msg: JSONObject) {
        val binding = getBindingByChan(chan) ?: return
        val msgId = msg.optString("id", "")
        if (msgId.isNotEmpty() && !markId(chan, msgId)) return
        val fromUser = msg.optJSONObject("fromUser") ?: JSONObject()
        val fromUserId = msg.optString("fromUserId").ifBlank { fromUser.optString("id") }
        if (fromUserId == binding.str("misskey_user_id")) return
        val text = msg.optString("text", "")
        val file = msg.optJSONObject("file")
        val fileUrl = file?.optString("url")?.ifBlank { null }
        if (text.isEmpty() && fileUrl == null) return
        if (seenText(chan, text)) return

        val isAi = fromUserId == cfg.misskey.characterId ||
            fromUser.optString("username").lowercase() in AI_USERNAMES
        val label = if (isAi) {
            "${fromUser.optString("name").ifBlank { "Aliya" }}（AI）"
        } else {
            val bindingDm = binding.bool("dm_enabled", true)
            if (!bindingDm) {
                BridgeLog.i("通道 $chan 已关闭私信转发，丢弃来自 @${fromUser.optString("username")} 的消息")
                return
            }
            "私信 ${fromUser.optString("name")} (@${fromUser.optString("username", "")})"
        }
        if (text.isNotEmpty()) rememberText(chan, text)
        val body = text.ifEmpty { "[图片消息]" }
        sendText(chan, "$label：\n$body", imageUrl = fileUrl)
        signalReply(chan)
    }

    private suspend fun handleNotification(chan: String, ntf: JSONObject) {
        val binding = getBindingByChan(chan) ?: return
        val ntype = ntf.optString("type")
        if (ntype == "agentProactiveMessage") {
            handleProactive(chan, binding, ntf)
            signalReply(chan)
            return
        }
        if (!binding.bool("notify_enabled", true)) return
        val base = cfg.misskeyBaseUrl()
        val user = ntf.optJSONObject("user") ?: JSONObject()
        val uname = user.optString("name").ifBlank { user.optString("username") }.ifBlank { "有人" }
        val handle = "$uname (@${user.optString("username", "")})"
        val note = ntf.optJSONObject("note") ?: JSONObject()
        when {
            ntype in NOTIFY_LABELS -> {
                val lines = ArrayList<String>()
                lines.add("$handle ${NOTIFY_LABELS[ntype]}")
                val noteText = note.optString("text", "")
                if (noteText.isNotEmpty()) {
                    lines.add(noteText.take(200) + (if (noteText.length > 200) "…" else ""))
                }
                if (note.has("id") && !note.isNull("id")) {
                    lines.add("链接：$base/notes/${note.opt("id")}")
                }
                sendText(chan, lines.joinToString("\n"))
            }
            ntype == "follow" -> sendText(chan, "$handle 与你建立了关注关系")
            ntype == "followed" -> sendText(chan, "$handle 关注了你")
            ntype == "receiveFollowRequest" -> sendText(chan, "$handle 请求关注你，请在 Misskey 网页端处理")
        }
    }

    private suspend fun handleProactive(chan: String, binding: Map<String, Any?>, ntf: JSONObject) {
        val sessionId = ntf.optString("sessionId").ifBlank { binding.strOrNull("current_session_id") ?: "" }
        if (sessionId.isEmpty()) return
        val token = binding.str("misskey_token")
        val lastSeen = binding.strOrNull("last_seen_message_id")
        val msgs = try {
            misskey.agentsTimeline(token, sessionId, limit = 20, sinceId = lastSeen)
        } catch (e: Exception) {
            BridgeLog.w("拉取主动消息失败 (通道 $chan): $e")
            return
        }
        if (msgs.isEmpty()) return
        var filtered = msgs.filter { !it.optBoolean("isInternal") }
        if (filtered.isEmpty()) return
        if (lastSeen.isNullOrBlank()) {
            // 首次没有游标时只取最新一条，避免把历史记录全部推给用户
            filtered = filtered.take(1)
        }
        val oldest = filtered.map { it.optString("id", "") }.filter { it.isNotEmpty() }.minByOrNull { it }
        if (oldest != null) setChanLastSeen(chan, oldest)
        for (m in filtered) {
            if (m.optString("role") != "assistant") continue
            val mid = m.optString("id", "")
            val text = m.optString("content", "")
            if (mid.isNotEmpty() && !markId(chan, mid)) continue
            if (seenText(chan, text)) continue
            val file = m.optJSONObject("file")
            val fileUrl = file?.optString("url")?.ifBlank { null }
            if (text.isNotEmpty()) rememberText(chan, text)
            if (text.isEmpty() && fileUrl == null) continue
            renderAndSendReply(
                chan, binding, text.ifEmpty { "[图片消息]" }, mid.ifBlank { null },
                prefix = "Aliya（主动消息）：\n",
                extraImages = if (fileUrl != null) listOf(fileUrl) else emptyList(),
            )
        }
    }

    // ---------- 供 commands 使用的辅助 ----------

    fun requestBindConfirm(ctx: ChatContext, kind: String, token: String = ""): String {
        cancelPendingBind(ctx.chan)
        val timeout = cfg.bindConfirmTimeout
        val p = PendingBind(
            chan = ctx.chan, qq = ctx.qq, kind = kind, token = token,
            expiresAt = System.currentTimeMillis() / 1000.0 + timeout,
        )
        p.task = scope.launch {
            delay((timeout * 1000).toLong().coerceAtLeast(100))
            if (pendingBinds[ctx.chan] === p) {
                pendingBinds.remove(ctx.chan)
                sendText(p.chan, "QQ ${p.qq} 的绑定请求超时未确认，已自动取消。")
            }
        }
        pendingBinds[ctx.chan] = p
        BridgeLog.i("群 ${ctx.groupId} 收到待确认绑定请求（QQ ${ctx.qq}，$kind）")
        return "本群已有绑定。QQ ${ctx.qq} 发起的重新绑定请求需要管理员确认：\n" +
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

    fun cacheSessions(chan: String, sessions: List<JSONObject>) {
        sessionCache[chan] = sessions
    }

    fun popCachedSession(chan: String, index: Int): JSONObject? {
        val sessions = sessionCache[chan] ?: emptyList()
        return if (index in 1..sessions.size) sessions[index - 1] else null
    }
}
