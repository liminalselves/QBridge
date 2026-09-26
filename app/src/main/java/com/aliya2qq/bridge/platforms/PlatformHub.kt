package com.aliya2qq.bridge.platforms

import com.aliya2qq.bridge.config.BridgeConfig
import org.json.JSONObject

/**
 * 统一平台适配器接口：Bridge 只依赖本类，具体平台由 Hub 下放。
 */
class PlatformHub(val adapter: PlatformAdapter) {
    private var userHandler: MessageHandler? = null

    init {
        adapter.setMessageHandler { msg -> userHandler?.invoke(msg) }
    }

    val platformName: String get() = adapter.name
    val displayName: String get() = adapter.displayName
    val selfQq: String? get() = adapter.selfQq
    val selfNickname: String? get() = adapter.selfNickname
    val connected: Boolean get() = adapter.connected

    fun setMessageHandler(handler: MessageHandler) {
        userHandler = handler
    }

    suspend fun runForever() = adapter.runForever()
    suspend fun close() = adapter.close()

    suspend fun sendTo(chan: String, segments: List<JSONObject>): Any? =
        adapter.sendTo(chan, segments)

    suspend fun sendPrivateMessage(userId: String, segments: List<JSONObject>): Any? =
        adapter.sendPrivateMessage(userId, segments)

    suspend fun sendGroupMessage(groupId: String, segments: List<JSONObject>): Any? =
        adapter.sendGroupMessage(groupId, segments)

    suspend fun callApi(action: String, params: JSONObject? = null, timeout: Double? = null): Any? =
        adapter.callApi(action, params, timeout)

    suspend fun getLoginInfo(): JSONObject = adapter.getLoginInfo()

    suspend fun getGroupMemberInfo(groupId: String, userId: String): JSONObject? =
        adapter.getGroupMemberInfo(groupId, userId)
}

/** 按配置创建平台适配器，并包一层统一 Hub。 */
fun createPlatform(cfg: BridgeConfig): PlatformHub {
    val adapterName = cfg.platform.adapter.trim().lowercase().ifBlank { "napcat" }
    val adapter: PlatformAdapter = when (adapterName) {
        "napcat" -> NapCatAdapter(
            wsUrl = cfg.napcat.wsUrl,
            accessToken = cfg.napcat.accessToken,
            apiTimeout = cfg.napcat.apiTimeout,
            reconnectInterval = cfg.napcat.reconnectInterval,
        )
        "onebot" -> OneBotAdapter(
            transport = cfg.onebot.transport,
            wsUrl = cfg.onebot.wsUrl,
            httpUrl = cfg.onebot.httpUrl,
            reverseWsHost = cfg.onebot.reverseWsHost,
            reverseWsPort = cfg.onebot.reverseWsPort,
            httpEventHost = cfg.onebot.httpEventHost,
            httpEventPort = cfg.onebot.httpEventPort,
            accessToken = cfg.onebot.accessToken,
            secret = cfg.onebot.secret,
            apiTimeout = cfg.onebot.apiTimeout,
            reconnectInterval = cfg.onebot.reconnectInterval,
        )
        else -> throw IllegalArgumentException("未知平台适配器: $adapterName（可选 napcat / onebot）")
    }
    return PlatformHub(adapter)
}
