package com.aliya2qq.bridge.commands

import com.aliya2qq.bridge.core.Bridge
import com.aliya2qq.bridge.db.bool
import com.aliya2qq.bridge.db.str
import com.aliya2qq.bridge.db.strOrNull
import com.aliya2qq.bridge.misskey.MisskeyAPIError
import com.aliya2qq.bridge.render.ReplyRender
import com.aliya2qq.bridge.util.BridgeLog
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeoutException

/** 一条 QQ 消息的通道上下文。 */
data class ChatContext(
    val chan: String,
    val isGroup: Boolean,
    val qq: String,
    val groupId: String? = null,
    val nickname: String = "",
    val role: String = "",
    val binderQq: String = "",
    val isManager: Boolean = false,
    val canManageAdmins: Boolean = false,
) {
    val scope: String get() = if (isGroup) "本群" else "你"
}

class PermissionException(message: String) : Exception(message)
class LookupException(message: String) : Exception(message)

/** /model、/imgmodel 中表示“恢复默认/关闭”的取值。 */
private val RESET_WORDS = setOf("off", "null", "default", "默认", "关闭")

// 列表类指令的编号缓存：列出后可用编号引用，按通道隔离
private val listCache = ConcurrentHashMap<String, List<JSONObject>>()
private fun cacheKey(ctx: ChatContext, kind: String) = "${ctx.chan}|$kind"

private const val HELP_TEXT = """QBridge 指令（直接发消息即可和 AI 聊天，群聊需 @机器人）

【绑定】
/bind — MiAuth 授权绑定（也可 /bind <token>）
/unbind — 解除绑定
/me — 账号与当前会话信息

【对话】
/retry — 重新生成上一条回复
/undo — 撤回上一轮对话
/stop — 中止正在生成的回复
/history [条数] — 最近消息

【会话】
/new [名称] — 新建会话并切换
/sessions — 会话列表
/switch <编号> · /del <编号> · /rename <名称>

【设置】
/model [编号|id|off] — 对话模型
/style [编号|id] — 文风
/imgmodel [编号|id|off] — 生图模型（off 关闭画图）
/memory [add <内容>|del <编号>] — 长期记忆
/proactive [pause|resume|del <编号>] — 主动消息计划
/set — 推送与分段开关

【余额】
/balance — 查询余额 · /checkin — 每日签到

群聊中除 /help 外的指令仅限绑定者、群管理员名单和全局管理员使用（/admin 管理名单）。"""

/** '/bind abc' -> Pair("bind", "abc") */
fun parse(text: String, prefix: String): Pair<String, String> {
    val body = text.removePrefix(prefix).trim()
    if (body.isEmpty()) return "" to ""
    val parts = body.split(Regex("\\s+"), limit = 2)
    return parts[0].lowercase() to (parts.getOrNull(1)?.trim() ?: "")
}

private fun sessionIdOf(binding: Map<String, Any?>): String =
    binding.strOrNull("current_session_id")?.ifBlank { null }
        ?: throw LookupException("还没有会话，发送任意消息或 /new 创建。")

/** 编号（1 起）或原始 id；编号越界返回 null。 */
private fun resolveRef(ref: String, items: List<JSONObject>, idKey: String = "id"): String? {
    val idx = ref.toIntOrNull() ?: return ref
    return items.getOrNull(idx - 1)?.optString(idKey)?.ifBlank { null }
}

private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

private val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
private val shortFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

private fun shortTime(iso: String): String = try {
    shortFmt.format(isoParser.parse(iso.take(19))!!)
} catch (_: Exception) {
    iso
}

private fun onOff(v: Boolean) = if (v) "开" else "关"

suspend fun dispatch(bridge: Bridge, ctx: ChatContext, cmd: String, args: String): String? {
    if (cmd in setOf("help", "帮助", "start", "h")) return HELP_TEXT.replace("/", bridge.cfg.commandPrefix)
    val handler = HANDLERS[cmd]
        ?: return "未知指令：$cmd。发送 ${bridge.cfg.commandPrefix}help 查看全部指令。"
    if (ctx.isGroup) {
        if (!ctx.isManager && cmd != "bind") return "群聊指令仅限绑定者或管理员使用。"
        val first = args.substringBefore(' ').lowercase()
        if (cmd in setOf("admin", "管理员") && first in setOf("add", "del", "delete", "remove") && !ctx.canManageAdmins) {
            return "管理员名单的修改仅限绑定者或全局管理员。"
        }
    }
    return try {
        handler(bridge, ctx, args)
    } catch (e: PermissionException) {
        e.message ?: "权限不足"
    } catch (e: LookupException) {
        e.message ?: "查询失败"
    } catch (e: MisskeyAPIError) {
        BridgeLog.w("指令 $cmd 失败: $e")
        "❌ ${e.friendly()}"
    } catch (e: TimeoutException) {
        "❌ Misskey 响应超时，请稍后再试。"
    } catch (e: IOException) {
        "❌ 无法连接 Misskey：${e.message ?: e}"
    } catch (e: Exception) {
        BridgeLog.e("指令 $cmd 处理出错", e)
        "❌ 操作失败：${e.message ?: e}"
    }
}

// ---------- 绑定类 ----------

private suspend fun cmdBind(bridge: Bridge, ctx: ChatContext, args: String): String {
    val token = args.trim()
    if (ctx.isGroup && !ctx.isManager && bridge.getBindingByChan(ctx.chan) != null) {
        return bridge.requestBindConfirm(ctx, if (token.isNotEmpty()) "token" else "miauth", token)
    }
    return if (token.isNotEmpty()) bridge.bindChanToken(ctx, token) else bridge.startMiauthBind(ctx)
}

private suspend fun cmdConfirm(bridge: Bridge, ctx: ChatContext, args: String): String {
    if (!ctx.isGroup) return "/确认 用于确认群聊的绑定请求，请在群聊中使用。"
    val pending = bridge.popPendingBind(ctx.chan) ?: return "当前没有待确认的绑定请求。"
    val requesterCtx = ctx.copy(qq = pending.qq, isManager = true)
    BridgeLog.i("群 ${ctx.groupId} 管理员 QQ ${ctx.qq} 确认了 QQ ${pending.qq} 的绑定请求")
    return if (pending.kind == "miauth") bridge.startMiauthBind(requesterCtx)
    else bridge.bindChanToken(requesterCtx, pending.token)
}

private suspend fun cmdUnbind(bridge: Bridge, ctx: ChatContext, args: String): String =
    bridge.unbindChan(ctx)

private suspend fun cmdMe(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val token = binding.str("misskey_token")
    val lines = arrayListOf(
        "账号：${binding.strOrNull("misskey_name")?.ifBlank { null } ?: binding.str("misskey_username")}" +
            " (@${binding.str("misskey_username")})"
    )
    if (ctx.isGroup && binding.str("binder_qq").isNotEmpty()) {
        lines.add("绑定者：QQ ${binding.str("binder_qq")}（全群共用）")
    }
    try {
        val info = bridge.loadSession(token, bridge.ensureSession(ctx.chan))
        lines.add("会话：${info.name.ifBlank { "未命名" }}（角色 ${info.characterName}）")
        val modelName = info.modelId?.let { id ->
            agentModels(bridge).firstOrNull { it.optString("id") == id }?.optString("name")?.ifBlank { null } ?: id
        } ?: "实例默认"
        lines.add("对话模型：$modelName")
        lines.add("生图：${if (info.imageModelId == null) "关闭" else "开启"}　分段输出：${onOff(info.segmented)}")
    } catch (e: MisskeyAPIError) {
        lines.add("会话：${e.friendly()}")
    }
    try {
        bridge.misskey.creditBalance(token)?.let { lines.add("余额：${formatNum(it)}") }
    } catch (e: Exception) {
        BridgeLog.d("余额查询失败: $e")
    }
    lines.add("推送：通知${onOff(binding.bool("notify_enabled", true))}　私信${onOff(binding.bool("dm_enabled", true))}")
    return lines.joinToString("\n")
}

private fun formatNum(v: Double): String =
    if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else "%.2f".format(v)

// ---------- 对话类 ----------

private suspend fun cmdRetry(bridge: Bridge, ctx: ChatContext, args: String): String = bridge.retryLast(ctx)

private suspend fun cmdUndo(bridge: Bridge, ctx: ChatContext, args: String): String = bridge.undoLast(ctx)

private suspend fun cmdStop(bridge: Bridge, ctx: ChatContext, args: String): String {
    bridge.requireBinding(ctx)
    return bridge.abortReply(ctx.chan)
}

private suspend fun cmdHistory(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val sid = sessionIdOf(binding)
    val limit = args.toIntOrNull()?.coerceIn(1, 20) ?: 10
    val msgs = bridge.misskey.agentsTimeline(binding.str("misskey_token"), sid, limit = limit).reversed()
    if (msgs.isEmpty()) return "当前会话还没有消息。"
    val ai = bridge.sessionInfo(sid)?.characterName ?: "AI"
    val lines = arrayListOf("最近 ${msgs.size} 条消息：")
    for (m in msgs) {
        val who = when (m.optString("role")) {
            "user" -> "我"
            "assistant" -> ai
            else -> "系统"
        }
        var content = ReplyRender.plainText(m.str("content")).replace(Regex("\\s*\n\\s*"), " ")
        if (m.optJSONObject("file") != null) content = "[图片] $content".trim()
        if (content.length > 100) content = content.take(100) + "…"
        lines.add("$who：$content")
    }
    return lines.joinToString("\n")
}

// ---------- 会话类 ----------

private suspend fun cmdNew(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    if (bridge.isReplying(ctx.chan)) return "回复还在生成中，请稍等或先发送 /stop。"
    val info = bridge.newSession(ctx.chan, binding.str("misskey_token"), args.ifBlank { null })
    return "✅ 已新建并切换到会话「${info.name.ifBlank { "未命名" }}」，可以开始新的对话了。"
}

private suspend fun cmdSessions(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val token = binding.str("misskey_token")
    val cid = bridge.cfg.misskey.characterId
    val list = bridge.misskey.sessionsListMine(token).filter { it.optString("characterId") == cid }.toMutableList()
    val current = binding.strOrNull("current_session_id")
    // list-mine 只返回有过消息的会话，刚新建的当前会话要手动补上
    if (current != null && list.none { it.optString("id") == current }) {
        bridge.sessionInfo(current)?.let { list.add(0, JSONObject().put("id", it.id).put("name", it.name)) }
    }
    bridge.cacheSessions(ctx.chan, list)
    if (list.isEmpty()) return "暂无会话，发送消息或 /new 创建。"
    val lines = arrayListOf("会话列表：")
    list.forEachIndexed { i, s ->
        val mark = if (s.optString("id") == current) "▶ " else ""
        val banned = if (s.optBoolean("sessionModerationBanned")) "【已封禁】" else ""
        val time = s.str("lastMessageAt").takeIf { it.isNotEmpty() }?.let { " · ${shortTime(it)}" } ?: " · 新会话"
        val preview = ReplyRender.plainText(s.str("lastMessagePreview")).take(24)
        lines.add("${i + 1}. $mark${s.str("name").ifBlank { "未命名" }}$banned$time")
        if (preview.isNotBlank()) lines.add("    $preview")
    }
    lines.add("切换 /switch <编号>　删除 /del <编号>")
    return lines.joinToString("\n")
}

private fun pickSession(bridge: Bridge, ctx: ChatContext, args: String, usage: String): JSONObject {
    val idx = args.toIntOrNull() ?: throw LookupException("用法：$usage（编号见 /sessions）")
    return bridge.cachedSession(ctx.chan, idx) ?: throw LookupException("编号无效，请先 /sessions 查看列表。")
}

private suspend fun cmdSwitch(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    if (bridge.isReplying(ctx.chan)) return "回复还在生成中，请稍等或先发送 /stop。"
    val s = pickSession(bridge, ctx, args, "/switch <编号>")
    val info = bridge.loadSession(binding.str("misskey_token"), s.optString("id"))
    if (info.banned) return "该会话已被封禁，无法切换。"
    bridge.setChanCurrentSession(ctx.chan, info.id)
    return "✅ 已切换到会话「${info.name.ifBlank { "未命名" }}」"
}

private suspend fun cmdDel(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val s = pickSession(bridge, ctx, args, "/del <编号>")
    val sid = s.optString("id")
    if (sid == binding.strOrNull("current_session_id") && bridge.isReplying(ctx.chan)) {
        return "当前会话还在生成回复，请稍等或先发送 /stop。"
    }
    bridge.misskey.sessionsDelete(binding.str("misskey_token"), sid)
    bridge.invalidateSession(sid)
    bridge.cacheSessions(ctx.chan, emptyList())
    val wasCurrent = sid == binding.strOrNull("current_session_id")
    if (wasCurrent) bridge.setChanCurrentSession(ctx.chan, null)
    return "🗑 已删除会话「${s.str("name").ifBlank { "未命名" }}」" +
        (if (wasCurrent) "\n这是当前会话，下次发消息会自动切换到最近的会话。" else "")
}

private suspend fun cmdRename(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val name = args.trim().take(256)
    if (name.isEmpty()) return "用法：/rename <新名称>"
    val sid = sessionIdOf(binding)
    bridge.misskey.sessionsUpdate(binding.str("misskey_token"), sid, JSONObject().put("name", name))
    bridge.invalidateSession(sid)
    return "✅ 当前会话已重命名为「$name」"
}

// ---------- Misskey 设置类 ----------

private fun agentModels(bridge: Bridge): List<JSONObject> {
    val arr = bridge.misskey.meta().optJSONArray("agentModels") ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
}

/** /model 与 /imgmodel 的公共流程：无参数列出，编号/id 设置，off 恢复。 */
private suspend fun modelCommand(
    bridge: Bridge,
    ctx: ChatContext,
    args: String,
    kind: String,
    field: String,
    title: String,
    resetLabel: String,
    current: (com.aliya2qq.bridge.core.SessionInfo) -> String?,
    load: () -> List<JSONObject>,
    describe: (JSONObject) -> String,
): String {
    val binding = bridge.requireBinding(ctx)
    val token = binding.str("misskey_token")
    val sid = bridge.ensureSession(ctx.chan)
    val arg = args.trim()
    if (arg.isEmpty() || arg == "list") {
        val models = load()
        listCache[cacheKey(ctx, kind)] = models
        if (models.isEmpty()) return "实例没有可用的$title。"
        val cur = bridge.sessionInfo(sid)?.let(current)
        val lines = arrayListOf("可用$title（当前：${models.firstOrNull { it.optString("id") == cur }?.optString("name") ?: cur ?: resetLabel}）：")
        models.forEachIndexed { i, m ->
            val mark = if (m.optString("id") == cur) "▶ " else ""
            lines.add("${i + 1}. $mark${m.optString("name").ifBlank { m.optString("id") }}${describe(m)}")
        }
        lines.add("设置：/$kind <编号>　${resetLabel}：/$kind off")
        return lines.joinToString("\n")
    }
    val value: String? = if (arg.lowercase() in RESET_WORDS) null else {
        resolveRef(arg, listCache[cacheKey(ctx, kind)] ?: load())
            ?: return "编号无效，请先发送 /$kind 查看列表。"
    }
    bridge.misskey.sessionsUpdate(token, sid, JSONObject().put(field, value ?: JSONObject.NULL))
    val info = bridge.loadSession(token, sid)
    val name = value?.let { v -> (listCache[cacheKey(ctx, kind)] ?: emptyList()).firstOrNull { it.optString("id") == v }?.optString("name") }
    return "✅ $title 已设为：${if (current(info) == null) resetLabel else name ?: value}"
}

private suspend fun cmdModel(bridge: Bridge, ctx: ChatContext, args: String): String = modelCommand(
    bridge, ctx, args, "model", "agentModelId", "对话模型", "实例默认",
    current = { it.modelId },
    load = { agentModels(bridge) },
    describe = { m ->
        val free = m.optInt("dailyFreeQuota")
        if (free > 0) "（每日免费 $free 次）" else ""
    },
)

private suspend fun cmdImgmodel(bridge: Bridge, ctx: ChatContext, args: String): String {
    val token = bridge.requireBinding(ctx).str("misskey_token")
    return modelCommand(
        bridge, ctx, args, "imgmodel", "agentImageModelId", "生图模型", "关闭画图",
        current = { it.imageModelId },
        load = { bridge.misskey.imageModelsList(token) },
        describe = { m ->
            val cost = m.optDouble("costPerCall", 0.0)
            if (cost > 0) "（${formatNum(cost)}/张）" else ""
        },
    )
}

private suspend fun cmdStyle(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val token = binding.str("misskey_token")
    val sid = bridge.ensureSession(ctx.chan)
    val arg = args.trim()
    if (arg.isEmpty() || arg == "list") {
        val styles = bridge.misskey.stylesListUsable(token)
        listCache[cacheKey(ctx, "style")] = styles
        if (styles.isEmpty()) return "暂无可用文风。"
        val cur = bridge.sessionInfo(sid)?.styleId
        val lines = arrayListOf("可用文风：")
        styles.forEachIndexed { i, s ->
            val mark = if (s.optString("id") == cur) "▶ " else ""
            lines.add("${i + 1}. $mark${s.optString("name").ifBlank { s.optString("id") }}")
        }
        lines.add("设置：/style <编号 或 文风 id>")
        return lines.joinToString("\n")
    }
    val cached = listCache[cacheKey(ctx, "style")] ?: emptyList()
    val styleId = resolveRef(arg, cached) ?: return "编号无效，请先发送 /style 查看列表。"
    if (cached.none { it.optString("id") == styleId }) bridge.misskey.stylesSubscribe(token, styleId)
    bridge.misskey.sessionsUpdate(token, sid, JSONObject().put("dialogueStyleId", styleId))
    bridge.invalidateSession(sid)
    val name = cached.firstOrNull { it.optString("id") == styleId }?.optString("name")?.ifBlank { null }
    return "✅ 文风已切换为：${name ?: styleId}"
}

private suspend fun cmdBalance(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val balance = bridge.misskey.creditBalance(binding.str("misskey_token")) ?: 0.0
    return "💰 Agent 余额：${formatNum(balance)}"
}

private suspend fun cmdCheckin(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val token = binding.str("misskey_token")
    val r = bridge.misskey.checkin(token)
    val balance = try {
        bridge.misskey.creditBalance(token)?.let { "，当前余额 ${formatNum(it)}" } ?: ""
    } catch (_: Exception) {
        ""
    }
    if (r.optBoolean("alreadyCheckedIn")) {
        return "今天已经签到过了（连续 ${r.optInt("streak")} 天）$balance"
    }
    return "✅ 签到成功！获得 ${formatNum(r.optDouble("reward", 0.0))}，连续签到 ${r.optInt("streak")} 天$balance"
}

private fun formatSchedule(s: JSONObject): String {
    val status = when (s.optString("status")) {
        "active" -> "进行中"
        "paused" -> "已暂停"
        "completed" -> "已完成"
        else -> s.optString("status")
    }
    val parts = arrayListOf(s.str("description").ifBlank { "（无描述）" }, status)
    s.str("nextRunAt").takeIf { it.isNotEmpty() }?.let { parts.add("下次 ${shortTime(it)}") }
    if (s.has("remainingRuns") && !s.isNull("remainingRuns")) parts.add("剩余 ${s.optInt("remainingRuns")} 次")
    return parts.joinToString(" · ")
}

private suspend fun cmdProactive(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val token = binding.str("misskey_token")
    val sid = sessionIdOf(binding)
    val sub = args.substringBefore(' ').lowercase()
    val ref = args.substringAfter(' ', "").trim()
    val key = cacheKey(ctx, "proactive")
    if (sub in setOf("pause", "resume", "del", "delete")) {
        if (ref.isEmpty()) return "用法：/proactive pause|resume|del <编号>"
        val scheduleId = resolveRef(ref, listCache[key] ?: emptyList())
            ?: return "编号无效，请先发送 /proactive 查看列表。"
        return when (sub) {
            "pause" -> {
                bridge.misskey.proactiveSetStatus(token, sid, scheduleId, false)
                "⏸ 已暂停该计划"
            }
            "resume" -> {
                bridge.misskey.proactiveSetStatus(token, sid, scheduleId, true)
                "▶ 已恢复该计划"
            }
            else -> {
                bridge.misskey.proactiveDelete(token, sid, scheduleId)
                listCache.remove(key)
                "🗑 已删除该计划"
            }
        }
    }
    val schedules = bridge.misskey.proactiveList(token, sid)
    listCache[key] = schedules
    if (schedules.isEmpty()) return "当前会话暂无主动消息计划。\n可以直接让 AI 帮你安排，比如「明早 8 点叫我起床」。"
    val lines = arrayListOf("主动消息计划：")
    schedules.forEachIndexed { i, s -> lines.add("${i + 1}. ${formatSchedule(s)}") }
    lines.add("管理：/proactive pause|resume|del <编号>")
    return lines.joinToString("\n")
}

private suspend fun cmdMemory(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val token = binding.str("misskey_token")
    val sid = sessionIdOf(binding)
    val sub = args.substringBefore(' ').lowercase()
    val rest = args.substringAfter(' ', "").trim()
    val key = cacheKey(ctx, "memory")
    when (sub) {
        "add" -> {
            if (rest.isEmpty()) return "用法：/memory add <要让 AI 记住的内容>"
            bridge.misskey.memoryAdd(token, sid, rest.take(8000))
            return "🧠 已添加到长期记忆（整理入库可能需要片刻）。"
        }
        "del", "delete" -> {
            val nodeId = resolveRef(rest, listCache[key] ?: emptyList(), "memoryNodeId")
                ?: return "编号无效，请先发送 /memory 查看列表。"
            bridge.misskey.memoryDelete(token, sid, nodeId)
            listCache.remove(key)
            return "🗑 已删除该条记忆。"
        }
    }
    val data = bridge.misskey.memoryList(token, sid)
    val arr = data.optJSONArray("memoryNodes")
    val nodes = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    listCache[key] = nodes
    if (nodes.isEmpty()) return "当前会话还没有长期记忆。\n添加：/memory add <内容>"
    val lines = arrayListOf("长期记忆（共 ${data.optInt("total", nodes.size)} 条，显示前 ${nodes.size} 条）：")
    nodes.forEachIndexed { i, n ->
        val c = n.optString("content").replace("\n", " ")
        lines.add("${i + 1}. ${if (c.length > 60) c.take(60) + "…" else c}")
    }
    lines.add("添加 /memory add <内容>　删除 /memory del <编号>")
    return lines.joinToString("\n")
}

// ---------- 桥接设置 ----------

private suspend fun cmdSet(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = bridge.requireBinding(ctx)
    val parts = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val usage = "用法：/set notify|dm|seg${if (ctx.isGroup) "|aware" else ""} on|off"
    if (parts.isEmpty()) {
        val seg = binding.strOrNull("current_session_id")?.let { bridge.sessionInfo(it)?.segmented }
        val lines = arrayListOf(
            "桥接设置：",
            "notify 通知推送（提及/回复等）：${onOff(binding.bool("notify_enabled", true))}",
            "dm 私信转发：${onOff(binding.bool("dm_enabled", true))}",
            "seg 分段输出：${seg?.let(::onOff) ?: "未知"}",
        )
        if (ctx.isGroup) lines.add("aware 群消息感知（未@的消息附到下一轮）：${onOff(binding.bool("awareness_enabled", false))}")
        lines.add(usage)
        return lines.joinToString("\n")
    }
    if (parts.size != 2 || parts[1].lowercase() !in setOf("on", "off", "开", "关")) return usage
    val value = parts[1].lowercase() in setOf("on", "开")
    val state = if (value) "开启" else "关闭"
    return when (parts[0].lowercase()) {
        "notify" -> {
            setFlag(bridge, ctx, "notify_enabled", value)
            "✅ 通知推送已$state"
        }
        "dm" -> {
            setFlag(bridge, ctx, "dm_enabled", value)
            "✅ 私信转发已$state"
        }
        "aware" -> {
            if (!ctx.isGroup) return "消息感知是群聊功能，请在群聊中设置。"
            setFlag(bridge, ctx, "awareness_enabled", value)
            if (!value) bridge.clearGroupSink(ctx.chan)
            "✅ 群消息感知已$state" + if (value) "（未@机器人的消息会附到下一轮对话）" else ""
        }
        "seg" -> {
            val sid = sessionIdOf(binding)
            bridge.misskey.sessionsUpdate(
                binding.str("misskey_token"), sid, JSONObject().put("segmentedOutputEnabled", value),
            )
            bridge.invalidateSession(sid)
            "✅ 分段输出已$state" + if (value) "（长回复分多条发送）" else "（回复整条发送）"
        }
        else -> usage
    }
}

private fun setFlag(bridge: Bridge, ctx: ChatContext, key: String, value: Boolean) {
    if (ctx.isGroup) bridge.db.setGroupFlag(ctx.groupId ?: "", key, value)
    else bridge.db.setFlag(ctx.qq, key, value)
}

// ---------- 群管理员名单 ----------

private suspend fun cmdAdmin(bridge: Bridge, ctx: ChatContext, args: String): String {
    val gid = ctx.groupId
    if (!ctx.isGroup || gid == null) {
        return "/admin 用于管理群管理员名单，请在群聊中使用（全局管理员在应用设置里配置）。"
    }
    val parts = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val sub = parts.firstOrNull()?.lowercase() ?: ""
    if (sub in setOf("", "list", "列表")) {
        val admins = bridge.db.getGroupAdmins(gid)
        val lines = arrayListOf("本群管理员名单（绑定者：${ctx.binderQq.ifBlank { "无" }}）：")
        if (admins.isNotEmpty()) lines.addAll(admins.map { "- $it" }) else lines.add("（空）")
        lines.add("管理：/admin add|del <QQ>（仅绑定者或全局管理员）")
        return lines.joinToString("\n")
    }
    if (sub in setOf("add", "del", "delete", "remove")) {
        val qq = parts.getOrNull(1)?.removePrefix("@") ?: ""
        if (qq.isEmpty() || !qq.all { it.isDigit() }) return "用法：/admin add|del <QQ号>"
        return if (sub == "add") {
            if (bridge.db.addGroupAdmin(gid, qq)) "✅ 已将 QQ $qq 加入本群管理员名单" else "QQ $qq 已在名单中"
        } else {
            if (bridge.db.removeGroupAdmin(gid, qq)) "✅ 已将 QQ $qq 移出本群管理员名单" else "QQ $qq 不在名单中"
        }
    }
    return "用法：/admin　/admin add|del <QQ号>"
}

private val HANDLERS: Map<String, suspend (Bridge, ChatContext, String) -> String> = mapOf(
    "bind" to ::cmdBind,
    "unbind" to ::cmdUnbind,
    "me" to ::cmdMe,
    "retry" to ::cmdRetry,
    "undo" to ::cmdUndo,
    "stop" to ::cmdStop,
    "history" to ::cmdHistory,
    "new" to ::cmdNew,
    "sessions" to ::cmdSessions,
    "switch" to ::cmdSwitch,
    "del" to ::cmdDel,
    "delete" to ::cmdDel,
    "rename" to ::cmdRename,
    "model" to ::cmdModel,
    "style" to ::cmdStyle,
    "imgmodel" to ::cmdImgmodel,
    "memory" to ::cmdMemory,
    "balance" to ::cmdBalance,
    "checkin" to ::cmdCheckin,
    "签到" to ::cmdCheckin,
    "proactive" to ::cmdProactive,
    "set" to ::cmdSet,
    "admin" to ::cmdAdmin,
    "管理员" to ::cmdAdmin,
    "确认" to ::cmdConfirm,
    "confirm" to ::cmdConfirm,
)
