package com.aliya2qq.bridge.commands

import com.aliya2qq.bridge.core.Bridge
import com.aliya2qq.bridge.db.bool
import com.aliya2qq.bridge.db.str
import com.aliya2qq.bridge.db.strOrNull
import com.aliya2qq.bridge.misskey.MisskeyAPIError
import com.aliya2qq.bridge.misskey.RateLimitError
import com.aliya2qq.bridge.render.ReplyRender
import com.aliya2qq.bridge.util.BridgeLog
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

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

private val ADMIN_LIST_CMDS = setOf("admin", "管理员")

/** /model、/imgmodel 中表示“恢复实例默认”的取值。 */
private val RESET_WORDS = setOf("off", "null", "default", "默认")

// 会话/计划列表的编号缓存：/sessions、/style、/proactive 列出后，
// 用户用编号引用，再从这里的缓存换算成真实 id。
private val stylesCache = ConcurrentHashMap<String, List<JSONObject>>()
private val proactiveCache = ConcurrentHashMap<String, List<JSONObject>>()

val HELP_TEXT = """Aliya2QQ 桥接指令
『消息』直接发送文字/图片 → 与 AI 角色(Aliya)会话聊天
（群聊绑定后全群共用一个账号与会话，发言人以 Misskey 用户名/群名片区分）

『绑定』
/bind — 生成 MiAuth 授权链接进行绑定
/bind <token> — 直接使用 API Token 绑定
/unbind — 解除绑定
/me — 查看绑定信息

『会话』
/new [名称] — 新建会话并切换
/sessions — 会话列表
/switch <编号> — 切换会话
/del <编号> — 删除会话
/rename <名称> — 重命名当前会话
/history [条数] — 查看当前会话最近消息

『Misskey 设置』
/model [list|<id>|off] — 查看/设置对话模型
/style [list|<编号|id>] — 查看/设置文风
/imgmodel [list|<id>] — 查看/设置生图模型
/balance — 查询 Agent 余额
/proactive — 主动消息计划列表
/proactive pause|resume|del <编号|id> — 管理计划

『桥接设置』
/set — 查看桥接开关
/set notify on|off — Misskey 通知(提及/回复等)推送
/set dm on|off — Misskey 私信转发
/set seg on|off — 分段输出（长回复分多条发送）
/admin — 查看群管理员名单
/admin add|del <QQ> — 管理群管理员名单
/确认 — 确认待确认的群绑定请求

群聊权限：全部指令（除 /help）仅限「绑定者、群管理员名单成员、
全局管理员」使用；群管理员名单由绑定者或全局管理员维护，
重新 /bind 可更新绑定者（非管理员的重新 /bind 需管理员在
指定时间内发送 /确认）。

众神榜：yjrgzsb.pages.dev"""

/** '/bind abc' -> Pair("bind", "abc") */
fun parse(text: String, prefix: String): Pair<String, String> {
    val body = text.removePrefix(prefix).trim()
    if (body.isEmpty()) return "" to ""
    val parts = body.split(Regex("\\s+"), limit = 2)
    val cmd = parts[0].lowercase()
    val args = if (parts.size > 1) parts[1].trim() else ""
    return cmd to args
}

/**
 * 判断一条会话数据是否属于指定角色：优先看平铺的 characterId 字段，
 * 其次看内嵌的 character 对象。
 */
internal fun isAliyaSession(characterId: String, session: JSONObject): Boolean {
    for (key in listOf("characterId", "agentCharacterId")) {
        val value = session.optString(key)
        if (value.isNotBlank() && value.trim() == characterId) return true
    }
    for (key in listOf("character", "agentCharacter")) {
        val ch = session.optJSONObject(key)
        if (ch != null && ch.optString("id").trim() == characterId) return true
    }
    return false
}

/** 会话数据是否携带角色信息（部分实例的列表接口不返回该字段）。 */
internal fun sessionHasCharacterInfo(session: JSONObject): Boolean {
    return listOf("characterId", "agentCharacterId", "character", "agentCharacter")
        .any { session.has(it) && !session.isNull(it) }
}

private fun requireBinding(bridge: Bridge, ctx: ChatContext): Map<String, Any?> {
    val binding = bridge.getBindingByChan(ctx.chan)
        ?: throw PermissionException(
            if (ctx.isGroup) "本群尚未绑定 Misskey 账号，请绑定者或群管理员发送 /bind"
            else "尚未绑定 Misskey 账号，请先发送 /bind"
        )
    return binding
}

private fun sessionIdOf(bridge: Bridge, ctx: ChatContext, binding: Map<String, Any?>): String {
    return binding.strOrNull("current_session_id")
        ?: throw LookupException("还没有会话，发送任意消息或 /new 创建。")
}

class PermissionException(message: String) : Exception(message)
class LookupException(message: String) : Exception(message)

private fun err(e: Exception): String = when (e) {
    is RateLimitError -> "Misskey 限流中，请约 ${maxOf(1, e.retryAfter.toInt())} 秒后再试。"
    is MisskeyAPIError -> "❌ Misskey 错误：$e"
    else -> "❌ 操作失败：$e"
}

private fun resolveRef(ref: String, items: List<JSONObject>): String? {
    if (ref.all { it.isDigit() } && ref.isNotEmpty()) {
        val idx = ref.toInt()
        return if (idx in 1..items.size) items[idx - 1].optString("id").ifBlank { items[idx - 1].opt("id")?.toString() }
        else null
    }
    return ref
}

suspend fun dispatch(bridge: Bridge, ctx: ChatContext, cmd: String, args: String): String? {
    if (cmd in setOf("help", "帮助", "start")) return HELP_TEXT
    val handler = HANDLERS[cmd]
        ?: return "未知指令：$cmd。发送 /help 查看全部指令。"
    if (ctx.isGroup) {
        if (!ctx.isManager && cmd != "bind") {
            return "群聊指令仅限绑定者或管理员使用。"
        }
        val first = args.split(Regex("\\s+"), limit = 2).firstOrNull()?.lowercase() ?: ""
        if (cmd in ADMIN_LIST_CMDS && first in setOf("add", "del", "delete", "remove") && !ctx.canManageAdmins) {
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
        err(e)
    } catch (e: Exception) {
        BridgeLog.e("指令 $cmd 处理出错", e)
        err(e)
    }
}

// ---------- 绑定类 ----------

private suspend fun cmdBind(bridge: Bridge, ctx: ChatContext, args: String): String {
    if (ctx.isGroup && !ctx.isManager && bridge.getBindingByChan(ctx.chan) != null) {
        return bridge.requestBindConfirm(ctx, if (args.isNotBlank()) "token" else "miauth", args.trim())
    }
    return if (args.isNotBlank()) bridge.bindChanToken(ctx, args.trim())
    else bridge.startMiauthBind(ctx)
}

private suspend fun cmdConfirm(bridge: Bridge, ctx: ChatContext, args: String): String {
    if (!ctx.isGroup) return "/确认 用于确认群聊的绑定请求，请在群聊中使用。"
    val pending = bridge.popPendingBind(ctx.chan)
        ?: return "当前没有待确认的绑定请求。"
    val requesterCtx = ctx.copy(qq = pending.qq, isManager = true)
    BridgeLog.i("群 ${ctx.groupId} 管理员 QQ ${ctx.qq} 确认了 QQ ${pending.qq} 的绑定请求")
    return if (pending.kind == "miauth") bridge.startMiauthBind(requesterCtx)
    else bridge.bindChanToken(requesterCtx, pending.token)
}

private suspend fun cmdUnbind(bridge: Bridge, ctx: ChatContext, args: String): String =
    bridge.unbindChan(ctx)

private suspend fun cmdMe(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val token = binding.str("misskey_token")
    val lines = ArrayList<String>()
    lines.add(
        "${binding.strOrNull("misskey_name") ?: binding.str("misskey_username")}" +
            " (@${binding.str("misskey_username")})"
    )
    lines.add("ID: ${binding.str("misskey_user_id")}")
    if (ctx.isGroup && binding.str("binder_qq").isNotEmpty()) {
        lines.add("绑定者 QQ: ${binding.str("binder_qq")}（全群共用）")
    }
    val sid = binding.strOrNull("current_session_id")
    if (!sid.isNullOrBlank()) lines.add("当前会话: $sid")
    try {
        val balance = bridge.misskey.creditBalance(token)
        if (balance != null) lines.add("余额: $balance")
    } catch (e: Exception) {
        BridgeLog.d("余额查询失败: $e")
    }
    lines.add(
        "推送开关: 通知=${if (binding.bool("notify_enabled", true)) "开" else "关"} " +
            "私信=${if (binding.bool("dm_enabled", true)) "开" else "关"}"
    )
    return lines.joinToString("\n")
}

// ---------- 会话类 ----------

private fun filterCharacterSessions(bridge: Bridge, sessions: List<JSONObject>): List<JSONObject> {
    val cid = bridge.cfg.misskey.characterId
    val filtered = sessions.filter { isAliyaSession(cid, it) || !sessionHasCharacterInfo(it) }
    return filtered.ifEmpty { sessions }
}

private suspend fun cmdNew(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val token = binding.str("misskey_token")
    bridge.misskey.ensureStyleSubscribed(token, bridge.cfg.misskey.dialogueStyleId)
    val sid = bridge.misskey.sessionsCreate(
        token, bridge.cfg.misskey.characterId, bridge.cfg.misskey.dialogueStyleId
    )
    if (args.isNotBlank()) {
        try {
            bridge.misskey.sessionsUpdate(token, sid, JSONObject().put("name", args.take(256)))
        } catch (e: Exception) {
            BridgeLog.d("新会话命名失败: $e")
        }
    }
    bridge.setChanCurrentSession(ctx.chan, sid)
    return "✅ 已创建并切换到新会话：$sid" + (if (args.isNotBlank()) "（${args.take(256)}）" else "")
}

private suspend fun cmdSessions(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val sessions = filterCharacterSessions(
        bridge, bridge.misskey.sessionsListMine(binding.str("misskey_token"))
    )
    bridge.cacheSessions(ctx.chan, sessions)
    if (sessions.isEmpty()) return "暂无会话，发送消息或 /new 创建。"
    val current = binding.strOrNull("current_session_id")
    val lines = ArrayList<String>()
    lines.add("会话列表：")
    sessions.forEachIndexed { i, s ->
        val name = s.optString("name").ifBlank { "(未命名)" }
        val preview = s.optString("lastMessagePreview").take(30)
        val mark = if (s.optString("id") == current) " ✅" else ""
        lines.add("${i + 1}. $name — $preview$mark")
    }
    lines.add("切换: /switch <编号>  删除: /del <编号>")
    return lines.joinToString("\n")
}

private suspend fun cmdSwitch(bridge: Bridge, ctx: ChatContext, args: String): String {
    requireBinding(bridge, ctx)
    val session = if (args.isNotEmpty() && args.all { it.isDigit() })
        bridge.popCachedSession(ctx.chan, args.toInt()) else null
    if (session == null) return "请先 /sessions 查看列表，再 /switch <编号>。"
    bridge.setChanCurrentSession(ctx.chan, session.optString("id"))
    return "✅ 已切换到会话：${session.optString("name").ifBlank { session.optString("id") }}"
}

private suspend fun cmdDel(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val session = if (args.isNotEmpty() && args.all { it.isDigit() })
        bridge.popCachedSession(ctx.chan, args.toInt()) else null
    if (session == null) return "请先 /sessions 查看列表，再 /del <编号>。"
    bridge.misskey.sessionsDelete(binding.str("misskey_token"), session.optString("id"))
    if (binding.strOrNull("current_session_id") == session.optString("id")) {
        bridge.setChanCurrentSession(ctx.chan, null)
    }
    return "已删除会话：${session.optString("name").ifBlank { session.optString("id") }}"
}

private suspend fun cmdRename(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    if (args.isBlank()) return "用法：/rename <新名称>"
    val sid = sessionIdOf(bridge, ctx, binding)
    bridge.misskey.sessionsUpdate(binding.str("misskey_token"), sid, JSONObject().put("name", args.take(256)))
    return "✅ 已重命名当前会话为：${args.take(256)}"
}

private suspend fun cmdHistory(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val sid = sessionIdOf(bridge, ctx, binding)
    val limit = if (args.all { it.isDigit() } && args.isNotEmpty())
        args.toInt().coerceIn(1, 20) else 10
    var msgs = bridge.misskey.agentsTimeline(binding.str("misskey_token"), sid, limit = limit)
    msgs = msgs.reversed().filter { !it.optBoolean("isInternal") }
    if (msgs.isEmpty()) return "当前会话还没有消息。"
    val lines = ArrayList<String>()
    lines.add("最近消息：")
    for (m in msgs) {
        val who = if (m.optString("role") == "user") "我" else "AI"
        var content = m.optString("content")
        if (who == "AI") {
            content = ReplyRender.stripInstructions(content).clean
        }
        content = content.replace("\n", " ")
        if (m.has("file") && !m.isNull("file")) {
            content = "$content [图片]".trim()
        }
        if (content.length > 120) content = content.take(120) + "…"
        lines.add("$who: $content")
    }
    return lines.joinToString("\n")
}

// ---------- Misskey 设置类 ----------

private suspend fun cmdModel(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val token = binding.str("misskey_token")
    val sid = sessionIdOf(bridge, ctx, binding)
    if (args.isEmpty() || args == "list") {
        val meta = bridge.misskey.meta()
        val models = meta.optJSONArray("agentModels")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        } ?: emptyList()
        if (models.isEmpty()) return "实例未提供对话模型列表。"
        val lines = ArrayList<String>()
        lines.add("可用对话模型：")
        models.forEachIndexed { i, m ->
            lines.add("${i + 1}. ${m.optString("name").ifBlank { m.optString("id") }} — ${m.optString("id")}")
        }
        lines.add("设置: /model <id>   恢复默认: /model off")
        return lines.joinToString("\n")
    }
    var value: String? = null
    if (args.lowercase() !in RESET_WORDS) {
        if (args.all { it.isDigit() } && args.isNotEmpty()) {
            return "请使用 /model list 查看后输入模型 id 设置。"
        }
        value = args
    }
    val payload = JSONObject()
    if (value == null) payload.put("agentModelId", JSONObject.NULL)
    else payload.put("agentModelId", value)
    bridge.misskey.sessionsUpdate(token, sid, payload)
    return "✅ 对话模型已更新为：${if (value == null) "实例默认" else value}"
}

private suspend fun cmdStyle(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val token = binding.str("misskey_token")
    val sid = sessionIdOf(bridge, ctx, binding)
    if (args.isEmpty() || args == "list") {
        val styles = bridge.misskey.stylesListUsable(token)
        stylesCache[ctx.chan] = styles
        if (styles.isEmpty()) return "暂无可用文风。"
        val lines = ArrayList<String>()
        lines.add("可用文风：")
        styles.forEachIndexed { i, s ->
            lines.add("${i + 1}. ${s.optString("name")} — ${s.optString("id")}")
        }
        lines.add("设置: /style <编号 或 id>（自动订阅）")
        return lines.joinToString("\n")
    }
    val styleId = resolveRef(args, stylesCache[ctx.chan] ?: emptyList())
        ?: return "编号无效，请先 /style list。"
    try {
        bridge.misskey.stylesSubscribe(token, styleId)
    } catch (e: Exception) {
        BridgeLog.w("订阅文风 $styleId 失败（可能已在列表中）: $e")
    }
    bridge.misskey.sessionsUpdate(token, sid, JSONObject().put("dialogueStyleId", styleId))
    return "✅ 文风已切换为：$styleId"
}

private suspend fun cmdImgmodel(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val token = binding.str("misskey_token")
    val sid = sessionIdOf(bridge, ctx, binding)
    if (args.isEmpty() || args == "list") {
        val models = bridge.misskey.imageModelsList(token)
        val lines = ArrayList<String>()
        lines.add("可用生图模型：")
        models.forEachIndexed { i, m ->
            lines.add("${i + 1}. ${m.optString("name").ifBlank { m.optString("id") }} — ${m.optString("id")}")
        }
        lines.add("设置: /imgmodel <id>   恢复默认: /imgmodel off")
        return lines.joinToString("\n")
    }
    var value: String? = null
    if (args.lowercase() !in RESET_WORDS) {
        value = args
    }
    val payload = JSONObject()
    if (value == null) payload.put("agentImageModelId", JSONObject.NULL)
    else payload.put("agentImageModelId", value)
    bridge.misskey.sessionsUpdate(token, sid, payload)
    return "✅ 生图模型已更新为：${if (value == null) "实例默认" else value}"
}

private suspend fun cmdBalance(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val balance = bridge.misskey.creditBalance(binding.str("misskey_token"))
    return "Agent 余额：$balance"
}

private fun formatSchedule(s: JSONObject): String {
    val parts = ArrayList<String>()
    parts.add(s.optString("id", ""))
    for ((key, label) in listOf(
        "status" to "状态", "scheduledAt" to "时间",
        "type" to "类型", "createdAt" to "创建",
    )) {
        if (s.has(key) && !s.isNull(key)) {
            parts.add("$label=${s.opt(key)}")
        }
    }
    return parts.joinToString(" ")
}

private suspend fun cmdProactive(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val token = binding.str("misskey_token")
    val sid = sessionIdOf(bridge, ctx, binding)
    val parts = args.trim().split(Regex("\\s+"), limit = 2)
    val sub = parts.firstOrNull()?.lowercase() ?: ""
    val ref = if (parts.size > 1) parts[1].trim() else ""
    if (sub in setOf("pause", "resume", "del", "delete")) {
        if (ref.isEmpty()) return "用法：/proactive pause|resume|del <编号|id>"
        val scheduleId = resolveRef(ref, proactiveCache[ctx.chan] ?: emptyList())
            ?: return "编号无效，请先 /proactive 查看列表。"
        when (sub) {
            "pause" -> {
                bridge.misskey.proactiveSetStatus(token, sid, scheduleId, false)
                return "已暂停计划 $scheduleId"
            }
            "resume" -> {
                bridge.misskey.proactiveSetStatus(token, sid, scheduleId, true)
                return "已恢复计划 $scheduleId"
            }
            else -> {
                bridge.misskey.proactiveDelete(token, sid, scheduleId)
                return "已删除计划 $scheduleId"
            }
        }
    }
    val schedules = bridge.misskey.proactiveList(token, sid)
    proactiveCache[ctx.chan] = schedules
    if (schedules.isEmpty()) return "当前会话暂无主动消息计划。"
    val lines = ArrayList<String>()
    lines.add("主动消息计划：")
    schedules.forEachIndexed { i, s -> lines.add("${i + 1}. ${formatSchedule(s)}") }
    lines.add("管理: /proactive pause|resume|del <编号>")
    return lines.joinToString("\n")
}

// ---------- 桥接设置 ----------

private suspend fun cmdSet(bridge: Bridge, ctx: ChatContext, args: String): String {
    val binding = requireBinding(bridge, ctx)
    val parts = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (parts.isEmpty()) {
        val segState = bridge.isSegEnabled(ctx.chan)
        val segText = if (segState != false) "开" else "关"
        var lines = "桥接设置：\n" +
            "通知推送 (notify): ${if (binding.bool("notify_enabled", true)) "开" else "关"}\n" +
            "私信转发 (dm): ${if (binding.bool("dm_enabled", true)) "开" else "关"}\n" +
            "分段输出 (seg): $segText\n"
        if (ctx.isGroup) {
            lines += "消息感知 (aware): ${if (binding.bool("awareness_enabled", false)) "开" else "关"}\n" +
                "  （开启后未@机器人的群消息会附到下一轮对话）\n"
        }
        lines += "修改: /set notify on|off  /set dm on|off  /set seg on|off"
        if (ctx.isGroup) lines += "  /set aware on|off"
        return lines
    }
    if (parts.size != 2 || parts[1].lowercase() !in setOf("on", "off")) {
        return "用法：/set notify on|off  /set dm on|off  /set seg on|off  /set aware on|off"
    }
    val value = parts[1].lowercase() == "on"
    return when (parts[0]) {
        "notify" -> {
            if (!setFlag(bridge, ctx, "notify_enabled", value)) return "尚未绑定 Misskey 账号。"
            "✅ 通知推送已${if (value) "开启" else "关闭"}"
        }
        "dm" -> {
            if (!setFlag(bridge, ctx, "dm_enabled", value)) return "尚未绑定 Misskey 账号。"
            "✅ 私信转发已${if (value) "开启" else "关闭"}"
        }
        "aware" -> {
            if (!ctx.isGroup) return "消息感知是群聊功能，请在群聊中开启。"
            if (!setFlag(bridge, ctx, "awareness_enabled", value)) return "本群尚未绑定 Misskey 账号。"
            if (!value) bridge.clearGroupSink(ctx.chan)
            "✅ 群聊消息感知已${if (value) "开启" else "关闭"}" +
                (if (value) "（未@机器人的群消息将附到下一轮对话）" else "")
        }
        "seg" -> {
            val sid = binding.strOrNull("current_session_id")
                ?: return "还没有会话，发送任意消息或 /new 创建。"
            bridge.misskey.sessionsUpdate(
                binding.str("misskey_token"), sid,
                JSONObject().put("segmentedOutputEnabled", value),
            )
            bridge.setSegEnabled(ctx.chan, value)
            "✅ 分段输出已${if (value) "开启" else "关闭"}" +
                (if (value) "（长回复将分多条延迟发送）" else "（回复整条发送）")
        }
        else -> "用法：/set notify on|off  /set dm on|off  /set seg on|off  /set aware on|off"
    }
}

private fun setFlag(bridge: Bridge, ctx: ChatContext, key: String, value: Boolean): Boolean {
    return if (ctx.isGroup) {
        if (ctx.groupId == null) false
        else bridge.db.setGroupFlag(ctx.groupId, key, value)
    } else {
        bridge.db.setFlag(ctx.qq, key, value)
    }
}

// ---------- 群管理员名单 ----------

private suspend fun cmdAdmin(bridge: Bridge, ctx: ChatContext, args: String): String {
    if (!ctx.isGroup) {
        return "/admin 用于管理群管理员名单，请在群聊中使用" +
            "（全局管理员在 config.json 的 admin_qq 配置）。"
    }
    val gid = ctx.groupId ?: return "/admin 用于管理群管理员名单，请在群聊中使用"
    val parts = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val sub = parts.firstOrNull()?.lowercase() ?: ""
    if (sub in setOf("", "list", "列表")) {
        val admins = bridge.db.getGroupAdmins(gid)
        val lines = ArrayList<String>()
        lines.add("本群管理员名单（绑定者：${ctx.binderQq.ifBlank { "无" }}）：")
        if (admins.isNotEmpty()) lines.addAll(admins.map { "- $it" })
        else lines.add("(空)")
        lines.add("管理: /admin add|del <QQ>（仅绑定者或全局管理员）")
        return lines.joinToString("\n")
    }
    if (sub in setOf("add", "del", "delete", "remove")) {
        if (parts.size < 2 || !parts[1].all { it.isDigit() }) {
            return "用法：/admin add|del <QQ号>"
        }
        val qq = parts[1]
        return if (sub == "add") {
            if (bridge.db.addGroupAdmin(gid, qq)) "✅ 已将 QQ $qq 加入本群管理员名单"
            else "QQ $qq 已在管理员名单中"
        } else {
            if (bridge.db.removeGroupAdmin(gid, qq)) "✅ 已将 QQ $qq 移出本群管理员名单"
            else "QQ $qq 不在管理员名单中"
        }
    }
    return "用法：/admin  /admin add|del <QQ号>"
}

private val HANDLERS: Map<String, suspend (Bridge, ChatContext, String) -> String> = mapOf(
    "bind" to ::cmdBind,
    "unbind" to ::cmdUnbind,
    "me" to ::cmdMe,
    "new" to ::cmdNew,
    "sessions" to ::cmdSessions,
    "switch" to ::cmdSwitch,
    "del" to ::cmdDel,
    "delete" to ::cmdDel,
    "rename" to ::cmdRename,
    "history" to ::cmdHistory,
    "model" to ::cmdModel,
    "style" to ::cmdStyle,
    "imgmodel" to ::cmdImgmodel,
    "balance" to ::cmdBalance,
    "proactive" to ::cmdProactive,
    "set" to ::cmdSet,
    "admin" to ::cmdAdmin,
    "管理员" to ::cmdAdmin,
    "确认" to ::cmdConfirm,
    "confirm" to ::cmdConfirm,
)
