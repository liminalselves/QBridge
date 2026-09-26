package com.aliya2qq.bridge.platforms

/**
 * NapCat 平台适配器。
 * NapCat 是 OneBot 11 实现之一；协议细节与 OneBot 一致，
 * 配置键、默认端口与展示名按 NapCat 习惯处理。
 */
class NapCatAdapter(
    wsUrl: String = "ws://127.0.0.1:3001",
    accessToken: String = "",
    apiTimeout: Double = 30.0,
    reconnectInterval: Double = 3.0,
) : OneBotAdapter(
    transport = "forward_ws",
    wsUrl = wsUrl,
    accessToken = accessToken,
    apiTimeout = apiTimeout,
    reconnectInterval = reconnectInterval,
) {
    override val name: String = "napcat"

    override val displayName: String
        get() = "NapCat · $wsUrl"
}
