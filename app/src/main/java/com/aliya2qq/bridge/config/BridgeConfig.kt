package com.aliya2qq.bridge.config

import com.aliya2qq.bridge.util.BridgeLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 桥接配置加载：JSON 文件，用户配置深度合并到默认值上。
 */
data class PlatformCfg(val adapter: String = "napcat")

data class NapCatCfg(
    val wsUrl: String = "ws://127.0.0.1:3001",
    val accessToken: String = "",
    val apiTimeout: Double = 30.0,
    val reconnectInterval: Double = 3.0,
)

data class OneBotCfg(
    val transport: String = "forward_ws",
    val wsUrl: String = "ws://127.0.0.1:6700",
    val httpUrl: String = "http://127.0.0.1:5700",
    val reverseWsHost: String = "0.0.0.0",
    val reverseWsPort: Int = 8080,
    val httpEventHost: String = "0.0.0.0",
    val httpEventPort: Int = 8081,
    val accessToken: String = "",
    val secret: String = "",
    val apiTimeout: Double = 30.0,
    val reconnectInterval: Double = 3.0,
)

data class MisskeyCfg(
    // 实例地址与角色信息必须由用户在设置面板或 config.json 中填写
    val host: String = "",
    val scheme: String = "https",
    val characterId: String = "",
    val dialogueStyleId: String = "",
    val miauthName: String = "Aliya2QQ",
    val miauthPermission: String = "read:account,read:chat,write:chat," +
        "read:messaging,write:messaging,read:drive,write:drive",
    val timeoutNormal: Double = 30.0,
    val timeoutSend: Double = 120.0,
    val timeoutUpload: Double = 60.0,
    val timeoutPlaceholder: Double = 90.0,
    val rateLimitLock: Double = 30.0,
)

data class BridgeConfig(
    val platform: PlatformCfg = PlatformCfg(),
    val napcat: NapCatCfg = NapCatCfg(),
    val onebot: OneBotCfg = OneBotCfg(),
    val misskey: MisskeyCfg = MisskeyCfg(),
    val commandPrefix: String = "/",
    val allowedQq: List<String> = emptyList(),
    val allowedGroups: List<String> = emptyList(),
    val adminQq: List<String> = emptyList(),
    val dataDir: String = "",
    val logFile: String = "",
    val qqMessageSplit: Int = 3500,
    val floodMergeWindow: Double = 2.0,
    val bindConfirmTimeout: Double = 300.0,
    val miauthWaitTotal: Double = 300.0,
    val miauthPollInterval: Double = 3.0,
) {
    fun misskeyBaseUrl(): String = "${misskey.scheme}://${misskey.host}"
    fun misskeyStreamUrl(token: String): String = "${misskeyBaseUrl()}/streaming?i=$token"
}

object ConfigLoader {
    /** 默认配置。 */
    fun defaults(dataDir: String): BridgeConfig = BridgeConfig(dataDir = dataDir)

    fun load(path: File, defaultDataDir: String): BridgeConfig {
        val merged = deepMerge(
            defaults(defaultDataDir).toJson(),
            if (path.exists()) JSONObject(path.readText(Charsets.UTF_8)) else JSONObject()
        )
        // 兼容旧配置：未写 platform.adapter 时按 napcat
        val platform = merged.optJSONObject("platform") ?: JSONObject().also { merged.put("platform", it) }
        if (platform.optString("adapter").isBlank()) {
            platform.put("adapter", "napcat")
        }
        val cfg = fromJson(merged)
        File(cfg.dataDir).mkdirs()
        BridgeLog.i("已加载配置文件: ${path.absolutePath}")
        return cfg
    }

    fun save(path: File, cfg: BridgeConfig) {
        path.parentFile?.mkdirs()
        path.writeText(cfg.toJson().toString(2), Charsets.UTF_8)
        BridgeLog.i("配置已保存: ${path.absolutePath}")
    }

    fun deepMerge(base: JSONObject, override: JSONObject): JSONObject {
        val keys = override.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = override.get(key)
            val baseVal = base.opt(key)
            if (baseVal is JSONObject && value is JSONObject) {
                deepMerge(baseVal, value)
            } else {
                base.put(key, value)
            }
        }
        return base
    }

    fun fromJson(o: JSONObject): BridgeConfig {
        val platform = o.optJSONObject("platform") ?: JSONObject()
        val napcat = o.optJSONObject("napcat") ?: JSONObject()
        val onebot = o.optJSONObject("onebot") ?: JSONObject()
        val misskey = o.optJSONObject("misskey") ?: JSONObject()
        val d = defaults("")
        return BridgeConfig(
            platform = PlatformCfg(adapter = platform.optString("adapter", d.platform.adapter)),
            napcat = NapCatCfg(
                wsUrl = napcat.optString("ws_url", d.napcat.wsUrl),
                accessToken = napcat.optString("access_token", d.napcat.accessToken),
                apiTimeout = napcat.optDouble("api_timeout", d.napcat.apiTimeout),
                reconnectInterval = napcat.optDouble("reconnect_interval", d.napcat.reconnectInterval),
            ),
            onebot = OneBotCfg(
                transport = onebot.optString("transport", d.onebot.transport),
                wsUrl = onebot.optString("ws_url", d.onebot.wsUrl),
                httpUrl = onebot.optString("http_url", d.onebot.httpUrl),
                reverseWsHost = onebot.optString("reverse_ws_host", d.onebot.reverseWsHost),
                reverseWsPort = onebot.optInt("reverse_ws_port", d.onebot.reverseWsPort),
                httpEventHost = onebot.optString("http_event_host", d.onebot.httpEventHost),
                httpEventPort = onebot.optInt("http_event_port", d.onebot.httpEventPort),
                accessToken = onebot.optString("access_token", d.onebot.accessToken),
                secret = onebot.optString("secret", d.onebot.secret),
                apiTimeout = onebot.optDouble("api_timeout", d.onebot.apiTimeout),
                reconnectInterval = onebot.optDouble("reconnect_interval", d.onebot.reconnectInterval),
            ),
            misskey = MisskeyCfg(
                host = misskey.optString("host", d.misskey.host),
                scheme = misskey.optString("scheme", d.misskey.scheme),
                characterId = misskey.optString("character_id", d.misskey.characterId),
                dialogueStyleId = misskey.optString("dialogue_style_id", d.misskey.dialogueStyleId),
                miauthName = misskey.optString("miauth_name", d.misskey.miauthName),
                miauthPermission = misskey.optString("miauth_permission", d.misskey.miauthPermission),
                timeoutNormal = misskey.optDouble("timeout_normal", d.misskey.timeoutNormal),
                timeoutSend = misskey.optDouble("timeout_send", d.misskey.timeoutSend),
                timeoutUpload = misskey.optDouble("timeout_upload", d.misskey.timeoutUpload),
                timeoutPlaceholder = misskey.optDouble("timeout_placeholder", d.misskey.timeoutPlaceholder),
                rateLimitLock = misskey.optDouble("rate_limit_lock", d.misskey.rateLimitLock),
            ),
            commandPrefix = o.optString("command_prefix", d.commandPrefix),
            allowedQq = jsonStrList(o.opt("allowed_qq")) ?: d.allowedQq,
            allowedGroups = jsonStrList(o.opt("allowed_groups")) ?: d.allowedGroups,
            adminQq = jsonStrList(o.opt("admin_qq")) ?: d.adminQq,
            dataDir = o.optString("data_dir", d.dataDir).ifBlank { d.dataDir },
            logFile = o.optString("log_file", d.logFile),
            qqMessageSplit = o.optInt("qq_message_split", d.qqMessageSplit),
            floodMergeWindow = o.optDouble("flood_merge_window", d.floodMergeWindow),
            bindConfirmTimeout = o.optDouble("bind_confirm_timeout", d.bindConfirmTimeout),
            miauthWaitTotal = o.optDouble("miauth_wait_total", 300.0),
            miauthPollInterval = o.optDouble("miauth_poll_interval", 3.0),
        )
    }

    private fun jsonStrList(v: Any?): List<String>? {
        return when (v) {
            is JSONArray -> {
                val out = ArrayList<String>()
                for (i in 0 until v.length()) out.add(v.optString(i))
                out
            }
            else -> null
        }
    }

    fun BridgeConfig.toJson(): JSONObject {
        val o = JSONObject()
        o.put("platform", JSONObject().put("adapter", platform.adapter))
        o.put(
            "napcat", JSONObject()
                .put("ws_url", napcat.wsUrl)
                .put("access_token", napcat.accessToken)
                .put("api_timeout", napcat.apiTimeout)
                .put("reconnect_interval", napcat.reconnectInterval)
        )
        o.put(
            "onebot", JSONObject()
                .put("transport", onebot.transport)
                .put("ws_url", onebot.wsUrl)
                .put("http_url", onebot.httpUrl)
                .put("reverse_ws_host", onebot.reverseWsHost)
                .put("reverse_ws_port", onebot.reverseWsPort)
                .put("http_event_host", onebot.httpEventHost)
                .put("http_event_port", onebot.httpEventPort)
                .put("access_token", onebot.accessToken)
                .put("secret", onebot.secret)
                .put("api_timeout", onebot.apiTimeout)
                .put("reconnect_interval", onebot.reconnectInterval)
        )
        o.put(
            "misskey", JSONObject()
                .put("host", misskey.host)
                .put("scheme", misskey.scheme)
                .put("character_id", misskey.characterId)
                .put("dialogue_style_id", misskey.dialogueStyleId)
                .put("miauth_name", misskey.miauthName)
                .put("miauth_permission", misskey.miauthPermission)
                .put("timeout_normal", misskey.timeoutNormal)
                .put("timeout_send", misskey.timeoutSend)
                .put("timeout_upload", misskey.timeoutUpload)
                .put("timeout_placeholder", misskey.timeoutPlaceholder)
                .put("rate_limit_lock", misskey.rateLimitLock)
        )
        o.put("command_prefix", commandPrefix)
        o.put("allowed_qq", JSONArray(allowedQq))
        o.put("allowed_groups", JSONArray(allowedGroups))
        o.put("admin_qq", JSONArray(adminQq))
        o.put("data_dir", dataDir)
        o.put("log_file", logFile)
        o.put("qq_message_split", qqMessageSplit)
        o.put("flood_merge_window", floodMergeWindow)
        o.put("bind_confirm_timeout", bindConfirmTimeout)
        o.put("miauth_wait_total", miauthWaitTotal)
        o.put("miauth_poll_interval", miauthPollInterval)
        return o
    }
}
