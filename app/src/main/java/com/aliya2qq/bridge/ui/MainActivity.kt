package com.aliya2qq.bridge.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aliya2qq.bridge.R
import com.aliya2qq.bridge.config.BridgeConfig
import com.aliya2qq.bridge.config.ConfigLoader
import com.aliya2qq.bridge.databinding.ActivityMainBinding
import com.aliya2qq.bridge.engine.LogPump
import com.aliya2qq.bridge.engine.NapCatManager
import com.aliya2qq.bridge.engine.NapCatState
import com.aliya2qq.bridge.engine.SetupOrchestrator
import com.aliya2qq.bridge.engine.ShellExec
import com.aliya2qq.bridge.service.BridgeService
import com.aliya2qq.bridge.util.BridgeLog
import com.google.android.material.tabs.TabLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * 唯一用户界面：状态卡片 + 主按钮 + 「设置 / 日志」标签页共享区域。
 * 设置面板通过下拉框在 NapCat / 桥接两个子面板间切换，统一用「保存设置」手动保存。
 * 日志页含 WebUI Token 栏与运行日志（含二维码）。
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var logPump: LogPump? = null

    private val stateListener: (NapCatState) -> Unit = { s ->
        mainHandler.post { renderState(s) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestNotifPermission()
        requestBatteryWhitelist()
        binding.tvLog.typeface = Typeface.MONOSPACE
        setupTabs()
        setupSettingsDropdown()
        loadConfigIntoUi()
        bindButtons()
        NapCatManager.addStateListener(stateListener)
        BridgeLog.listener = { line ->
            mainHandler.post {
                appendLog(line)
            }
        }
        binding.tvLog.text = BridgeLog.buffer.joinToString("\n")
        if (binding.etNapcatWs.text.isNullOrBlank()) {
            binding.etNapcatWs.setText("ws://127.0.0.1:3001")
        }

        io.execute {
            SetupOrchestrator.extractAssets(this)
            NapCatManager.refreshState(this)
            val s = NapCatManager.state
            if (s == NapCatState.NEED_BOOTSTRAP || s == NapCatState.NEED_INSTALL) {
                appendLogSafe("» 自动开始初始化…")
                SetupOrchestrator.initialize(this) { msg ->
                    mainHandler.post { appendLog(msg) }
                }
            }
        }
        startLogPump()
    }

    override fun onDestroy() {
        logPump?.stop()
        NapCatManager.removeStateListener(stateListener)
        BridgeLog.listener = null
        super.onDestroy()
    }

    /** 首次启动申请电池优化白名单：防系统/厂商策略杀掉后台 proot/QQ 子进程。 */
    private fun requestBatteryWhitelist() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                )
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    /** 设置 / 日志 标签页切换（共享同一显示区域）。 */
    private fun setupTabs() {
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                switchPanel(tab.position == 0)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun switchPanel(showSettings: Boolean) {
        binding.panelSettings.visibility = if (showSettings) View.VISIBLE else View.GONE
        binding.panelLog.visibility = if (showSettings) View.GONE else View.VISIBLE
    }

    /** 出现二维码等登录信息时自动切到日志页。 */
    private fun showLogTab() {
        if (binding.tabs.selectedTabPosition != 1) binding.tabs.getTabAt(1)?.select()
    }

    /** 下拉框切换 NapCat / 桥接 两个设置子面板。 */
    private fun setupSettingsDropdown() {
        val items = listOf("NapCat 设置", "桥接设置")
        binding.spinnerPanel.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_menu, items))
        binding.spinnerPanel.setText(items[0], false)
        binding.spinnerPanel.setOnItemClickListener { _, _, pos, _ ->
            binding.panelNapcat.visibility = if (pos == 0) View.VISIBLE else View.GONE
            binding.panelBridge.visibility = if (pos == 0) View.GONE else View.VISIBLE
        }
    }

    private fun startLogPump() {
        val logFile = File(ShellExec.logsDir(this), "napcat.out.log")
        logPump?.stop()
        logPump = LogPump(
            file = logFile,
            onLine = {},
            onQrBlock = { block ->
                mainHandler.post {
                    showLogTab()
                    showQrBlock(block)
                }
            },
            onImage = { f ->
                mainHandler.post {
                    showLogTab()
                    appendLog("【二维码图片】${f.absolutePath}")
                    try {
                        binding.imgQr.setImageURI(Uri.fromFile(f))
                        binding.imgQr.visibility = View.VISIBLE
                    } catch (_: Exception) {
                    }
                }
            },
        ).also { it.start() }
    }

    private fun appendLog(line: String) {
        NapCatManager.noteWebUiToken(line)
        refreshWebUiTokenUi()
        binding.tvLog.append(line + "\n")
        binding.scroll.post {
            binding.scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun appendLogSafe(line: String) {
        mainHandler.post { appendLog(line) }
    }

    private fun refreshWebUiTokenUi() {
        val t = NapCatManager.webUiToken ?: NapCatManager.resolveWebUiToken(this)
        val text = if (t.isNullOrBlank()) {
            "启动 NapCat 后显示"
        } else {
            "$t\nhttp://127.0.0.1:6099/webui?token=$t"
        }
        if (binding.tvWebUiToken.text.toString() != text) {
            binding.tvWebUiToken.text = text
        }
    }

    private fun showQrBlock(block: List<String>) {
        appendLog("======== QQ 登录二维码 ========")
        val ssb = SpannableStringBuilder()
        block.forEachIndexed { i, l ->
            ssb.append(l)
            if (i < block.size - 1) ssb.append("\n")
        }
        // 放大便于扫码
        ssb.setSpan(AbsoluteSizeSpan(48, true), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvLog.append(ssb)
        binding.tvLog.append("\n请用手机 QQ 扫码或进入WebUI")
        binding.tvLog.typeface = Typeface.MONOSPACE
        binding.scroll.post {
            binding.scroll.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }

    private fun dotColor(resId: Int) = ColorStateList.valueOf(ContextCompat.getColor(this, resId))

    private fun renderState(s: NapCatState) {
        val (envText, envColor) = when (s) {
            NapCatState.NEED_BOOTSTRAP -> "环境：未安装（需初始化）" to R.color.dot_gray
            NapCatState.NEED_INSTALL -> "环境：待装 NapCat" to R.color.dot_amber
            NapCatState.INSTALLING -> "环境：安装中…" to R.color.dot_amber
            NapCatState.READY -> "环境：就绪" to R.color.dot_green
            NapCatState.STARTING -> "NapCat：启动中…" to R.color.dot_amber
            NapCatState.RUNNING -> "NapCat：已启动" to R.color.dot_green
            NapCatState.WAIT_LOGIN -> "NapCat：已启动（待登录）" to R.color.dot_green
            NapCatState.STOPPING -> "NapCat：停止中…" to R.color.dot_amber
            NapCatState.ERROR -> "环境：出错（可重试初始化）" to R.color.dot_red
        }
        binding.tvStatus.text = envText
        binding.dotEnv.backgroundTintList = dotColor(envColor)
        val bridgeRunning = BridgeService.isRunning
        binding.tvBridge.text = if (bridgeRunning) "桥接：运行中" else "桥接：已停止"
        binding.dotBridge.backgroundTintList =
            dotColor(if (bridgeRunning) R.color.dot_green else R.color.dot_gray)
        binding.btnStartBridge.isEnabled = !bridgeRunning
        binding.btnStopBridge.isEnabled = bridgeRunning
        binding.btnInit.isEnabled = s == NapCatState.NEED_BOOTSTRAP || s == NapCatState.NEED_INSTALL || s == NapCatState.ERROR
        binding.btnStartNapcat.isEnabled = s == NapCatState.READY || s == NapCatState.WAIT_LOGIN || s == NapCatState.ERROR || s == NapCatState.NEED_INSTALL
        binding.btnStopNapcat.isEnabled = s == NapCatState.RUNNING || s == NapCatState.STARTING || s == NapCatState.WAIT_LOGIN
    }

    private fun loadConfigIntoUi() {
        val file = BridgeService.configFile(this)
        val cfg = ConfigLoader.load(file, BridgeService.defaultDataDir(this))
        binding.etPlatform.setText(cfg.platform.adapter)
        binding.etNapcatWs.setText(cfg.napcat.wsUrl)
        binding.etNapcatToken.setText(cfg.napcat.accessToken)
        binding.etMisskeyHost.setText(cfg.misskey.host)
        binding.etCharacterId.setText(cfg.misskey.characterId)
        binding.etStyleId.setText(cfg.misskey.dialogueStyleId)
        binding.etPrefix.setText(cfg.commandPrefix)
        binding.etAllowedQq.setText(cfg.allowedQq.joinToString(","))
        binding.etAllowedGroups.setText(cfg.allowedGroups.joinToString(","))
        binding.etAdminQq.setText(cfg.adminQq.joinToString(","))
        val uinFile = NapCatManager.qqUinFile(this)
        binding.etQqUin.setText(if (uinFile.exists()) uinFile.readText().trim() else "")
    }

    private fun collectConfig(): BridgeConfig {
        val file = BridgeService.configFile(this)
        val base = ConfigLoader.load(file, BridgeService.defaultDataDir(this))
        fun csv(s: String): List<String> =
            s.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
        return base.copy(
            platform = base.platform.copy(adapter = binding.etPlatform.text.toString().trim().ifBlank { "napcat" }),
            napcat = base.napcat.copy(
                wsUrl = binding.etNapcatWs.text.toString().trim().ifBlank { base.napcat.wsUrl },
                accessToken = binding.etNapcatToken.text.toString(),
            ),
            misskey = base.misskey.copy(
                host = binding.etMisskeyHost.text.toString().trim().ifBlank { base.misskey.host },
                characterId = binding.etCharacterId.text.toString().trim().ifBlank { base.misskey.characterId },
                dialogueStyleId = binding.etStyleId.text.toString().trim().ifBlank { base.misskey.dialogueStyleId },
            ),
            commandPrefix = binding.etPrefix.text.toString().ifBlank { "/" },
            allowedQq = csv(binding.etAllowedQq.text.toString()),
            allowedGroups = csv(binding.etAllowedGroups.text.toString()),
            adminQq = csv(binding.etAdminQq.text.toString()),
            dataDir = base.dataDir.ifBlank { BridgeService.defaultDataDir(this) },
            logFile = base.logFile,
        )
    }

    private fun writeSettings() {
        ConfigLoader.save(BridgeService.configFile(this), collectConfig())
        NapCatManager.qqUinFile(this).writeText(binding.etQqUin.text.toString().trim())
    }

    private fun saveQuietly() {
        try {
            writeSettings()
        } catch (_: Exception) {
        }
    }

    private fun bindButtons() {
        binding.btnInit.setOnClickListener {
            saveQuietly()
            Toast.makeText(this, "开始初始化环境…", Toast.LENGTH_SHORT).show()
            io.execute {
                SetupOrchestrator.initialize(this) { msg ->
                    mainHandler.post { appendLog("» $msg") }
                }
            }
        }
        binding.btnStartNapcat.setOnClickListener {
            saveQuietly()
            io.execute {
                SetupOrchestrator.startNapCat(this, binding.etQqUin.text.toString().trim())
            }
        }
        binding.btnStopNapcat.setOnClickListener {
            io.execute { SetupOrchestrator.stopAll(this) }
        }
        binding.btnStartBridge.setOnClickListener {
            saveQuietly()
            BridgeService.start(this)
            mainHandler.postDelayed({ NapCatManager.refreshState(this) }, 500)
        }
        binding.btnStopBridge.setOnClickListener {
            BridgeService.stop(this)
            mainHandler.postDelayed({ NapCatManager.refreshState(this) }, 300)
        }
        binding.btnSaveConfig.setOnClickListener {
            try {
                writeSettings()
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "保存失败: $e", Toast.LENGTH_LONG).show()
            }
        }
        binding.btnClearLog.setOnClickListener {
            BridgeLog.clearBuffer()
            binding.tvLog.text = ""
        }
        binding.btnWebUi.setOnClickListener {
            val url = NapCatManager.webUiUrl(this)
            Log.i("bridge", "open WebUI $url")
            startActivity(
                Intent(this, WebLoginActivity::class.java)
                    .putExtra("url", url),
            )
        }
        binding.btnCopyToken.setOnClickListener {
            val t = NapCatManager.webUiToken ?: NapCatManager.resolveWebUiToken(this)
            if (t.isNullOrBlank()) {
                Toast.makeText(this, "Token 尚未生成，请先启动 NapCat", Toast.LENGTH_SHORT).show()
            } else {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("NapCat WebUI Token", t))
                Toast.makeText(this, "已复制 Token：$t", Toast.LENGTH_SHORT).show()
            }
        }
        refreshWebUiTokenUi()
        // 启动后轮询几次，直到拿到 token
        mainHandler.postDelayed({ refreshWebUiTokenUi() }, 3000)
        mainHandler.postDelayed({ refreshWebUiTokenUi() }, 8000)
        mainHandler.postDelayed({ refreshWebUiTokenUi() }, 15000)
    }
}
