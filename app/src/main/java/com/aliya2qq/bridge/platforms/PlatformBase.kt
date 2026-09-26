package com.aliya2qq.bridge.platforms

import com.aliya2qq.bridge.util.BridgeLog
import org.json.JSONObject
import java.util.regex.Pattern

typealias MessageHandler = suspend (PlatformMessage) -> Unit

class PlatformAPIError(val action: String, val retcode: Any?, message: String) :
    Exception("平台 API $action 失败: retcode=$retcode $message")

/** 统一入站消息：各平台适配器解析原始事件后上抛。 */
data class PlatformMessage(
    val platform: String,
    val chatType: String, // "private" | "group"
    val chatId: String,
    val userId: String,
    val messageId: String = "",
    val nickname: String = "",
    val card: String = "",
    val role: String = "",
    val text: String = "",
    val images: List<String> = emptyList(),
    val atTargets: List<String> = emptyList(),
    val mentionedSelf: Boolean = false,
    val raw: JSONObject = JSONObject(),
) {
    val chan: String
        get() = (if (chatType == "group") "g:" else "p:") + chatId
}

/** 平台适配器抽象基类。 */
abstract class PlatformAdapter {
    abstract val name: String

    // 登录信息由事件线程写入、其他线程读取
    @Volatile
    var selfQq: String? = null
    @Volatile
    var selfNickname: String? = null
    @Volatile
    var connected: Boolean = false
    private var messageHandler: MessageHandler? = null

    open val displayName: String get() = name

    fun setMessageHandler(handler: MessageHandler?) {
        messageHandler = handler
    }

    suspend fun emitMessage(message: PlatformMessage) {
        val h = messageHandler ?: return
        try {
            h(message)
        } catch (e: Exception) {
            BridgeLog.e("处理入站消息时出错: $message", e)
        }
    }

    abstract suspend fun runForever()
    abstract suspend fun close()
    abstract suspend fun sendTo(chan: String, segments: List<JSONObject>): Any?

    suspend fun sendPrivateMessage(userId: String, segments: List<JSONObject>): Any? =
        sendTo("p:$userId", segments)

    suspend fun sendGroupMessage(groupId: String, segments: List<JSONObject>): Any? =
        sendTo("g:$groupId", segments)

    abstract suspend fun callApi(action: String, params: JSONObject? = null, timeout: Double? = null): Any?

    suspend fun getLoginInfo(): JSONObject {
        val data = callApi("get_login_info", JSONObject())
        return data as? JSONObject ?: JSONObject()
    }

    suspend fun getGroupMemberInfo(groupId: String, userId: String): JSONObject? {
        return try {
            callApi(
                "get_group_member_info",
                JSONObject().put("group_id", groupId.toLongOrNull() ?: groupId)
                    .put("user_id", userId.toLongOrNull() ?: userId),
            ) as? JSONObject
        } catch (_: Exception) {
            null
        }
    }
}

// ---------- 消息段解析（OneBot 系平台共用）----------

private const val AT_PLACEHOLDER = "\u0000"

object OneBotSegments {
    private val CQ_AT = Pattern.compile("""\[CQ:at,qq=['"]?(\d+|all)['"]?(?:,[^\]]*)?\]""")

    data class Parsed(
        val text: String,
        val images: List<String>,
        val atTargets: List<String>,
        val mentionedSelf: Boolean,
    )

    /** 解析 OneBot 消息段，产出文本、图片 URL、@ 目标列表与是否被@自己的标记。 */
    fun parse(message: Any?, selfQq: String?): Parsed {
        if (message is String) {
            val (text, ats, mentioned) = parseCqString(message, selfQq)
            return Parsed(text, emptyList(), ats, mentioned)
        }
        if (message !is org.json.JSONArray) return Parsed("", emptyList(), emptyList(), false)

        val parts = StringBuilder()
        val images = ArrayList<String>()
        val atTargets = ArrayList<String>()
        var mentioned = false
        for (i in 0 until message.length()) {
            val seg = message.optJSONObject(i) ?: continue
            val stype = seg.optString("type")
            val data = seg.optJSONObject("data") ?: JSONObject()
            when (stype) {
                "text" -> parts.append(data.optString("text", ""))
                "image" -> {
                    val url = data.optString("url").ifBlank { data.optString("file") }
                    if (url.startsWith("http")) images.add(url)
                    else parts.append("[图片]")
                }
                "at" -> {
                    val qq = data.optString("qq", "")
                    if (selfQq != null && qq == selfQq) {
                        mentioned = true
                    } else {
                        atTargets.add(qq)
                        parts.append("$AT_PLACEHOLDER${atTargets.size - 1}$AT_PLACEHOLDER")
                    }
                }
                "record" -> parts.append("[语音]")
                "video" -> parts.append("[视频]")
                "face" -> parts.append("[表情${data.optString("id", "")}]")
            }
        }
        return Parsed(parts.toString().trim(), images, atTargets, mentioned)
    }

    private fun parseCqString(message: String, selfQq: String?): Triple<String, List<String>, Boolean> {
        val atTargets = ArrayList<String>()
        var mentioned = false
        var text = message
        if (!selfQq.isNullOrBlank()) {
            val pattern = Pattern.compile("\\[CQ:at,qq=['\"]?${Pattern.quote(selfQq)}['\"]?(?:,[^\\]]*)?\\]")
            if (pattern.matcher(text).find()) {
                mentioned = true
                text = pattern.matcher(text).replaceAll("")
            }
        }
        val m = CQ_AT.matcher(text)
        val sb = StringBuffer()
        while (m.find()) {
            val qq = m.group(1)!!
            atTargets.add(qq)
            m.appendReplacement(sb, Pattern.quote("$AT_PLACEHOLDER${atTargets.size - 1}$AT_PLACEHOLDER"))
        }
        m.appendTail(sb)
        return Triple(sb.toString().trim(), atTargets, mentioned)
    }

    suspend fun expandAtPlaceholders(
        text: String,
        atTargets: List<String>,
        resolve: suspend (String) -> String,
    ): String {
        var result = text
        for ((i, target) in atTargets.withIndex()) {
            val label = when {
                target.isEmpty() -> "@某人"
                target == "all" -> "@全体成员"
                else -> resolve(target)
            }
            result = result.replace("$AT_PLACEHOLDER$i$AT_PLACEHOLDER", label)
        }
        return result
    }
}
