package com.aliya2qq.bridge.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.aliya2qq.bridge.R
import com.aliya2qq.bridge.config.BridgeConfig
import com.aliya2qq.bridge.config.ConfigLoader
import com.aliya2qq.bridge.config.normalizeHost
import com.aliya2qq.bridge.databinding.ActivityMainBinding
import com.aliya2qq.bridge.databinding.ItemStatusRowBinding
import com.aliya2qq.bridge.engine.LogPump
import com.aliya2qq.bridge.engine.NapCatManager
import com.aliya2qq.bridge.engine.NapCatState
import com.aliya2qq.bridge.engine.SetupOrchestrator
import com.aliya2qq.bridge.engine.ShellExec
import com.aliya2qq.bridge.service.BridgeService
import com.aliya2qq.bridge.util.BridgeLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.concurrent.Executors

/**
 * 唯一用户界面，三个标签页：
 * - 首页：下一步引导、环境 / QQ / 桥接三段状态与启停、扫码登录
 * - 设置：Misskey、使用范围、QQ 与 NapCat 三组表单，保存时逐项校验
 * - 日志：可筛选的运行日志
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var rowEnv: ItemStatusRowBinding
    private lateinit var rowNapcat: ItemStatusRowBinding
    private lateinit var rowBridge: ItemStatusRowBinding

    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var logPump: LogPump? = null

    /** 日志面板只渲染最近这么多行，避免 TextView 无限增长卡顿。 */
    private val logViewLimit = 600
    private var logFilter = LogFilter.ALL
    private var followLog = true

    /** BridgeLog 的回调来自任意线程，用原子标记合并刷新。 */
    private val pendingLogRefresh = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var webUiToken: String? = null

    private enum class LogFilter { ALL, BRIDGE, ERROR }

    private val stateListener: (NapCatState) -> Unit = { mainHandler.post { renderState() } }
    private val logListener: (String) -> Unit = { scheduleLogRefresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        rowEnv = binding.rowEnv
        rowNapcat = binding.rowNapcat
        rowBridge = binding.rowBridge
        setupRows()
        setupTabs()
        setupSettings()
        setupLogPanel()
        setupLoginCard()
        loadConfigIntoUi()

        requestNotifPermission()
        requestBatteryWhitelist()

        NapCatManager.addStateListener(stateListener)
        BridgeService.onStateChanged = { mainHandler.post { renderState() } }
        BridgeLog.listener = logListener
        refreshLog()
        renderState()

        io.execute {
            SetupOrchestrator.extractAssets(this)
            NapCatManager.refreshState(this)
            refreshTokenAsync()
            // 首次打开自动开始初始化（离线预置包下无需联网）
            val s = NapCatManager.state
            if (s == NapCatState.NEED_BOOTSTRAP || s == NapCatState.NEED_INSTALL) {
                BridgeLog.raw("» 自动开始初始化…")
                SetupOrchestrator.initialize(this) { msg -> BridgeLog.raw("» $msg") }
            }
        }
        startLogPump()
    }

    override fun onResume() {
        super.onResume()
        renderState()
    }

    override fun onDestroy() {
        logPump?.stop()
        NapCatManager.removeStateListener(stateListener)
        BridgeService.onStateChanged = null
        if (BridgeLog.listener === logListener) BridgeLog.listener = null
        mainHandler.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    // ---------- 权限 ----------

    /** 首次启动申请电池优化白名单：防系统/厂商策略杀掉后台 proot/QQ 子进程。 */
    private fun requestBatteryWhitelist() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    // ---------- 标签页 ----------

    private fun setupTabs() {
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPanel(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun showPanel(pos: Int) {
        binding.panelHome.visibility = if (pos == 0) View.VISIBLE else View.GONE
        binding.panelSettings.visibility = if (pos == 1) View.VISIBLE else View.GONE
        binding.panelLog.visibility = if (pos == 2) View.VISIBLE else View.GONE
        if (pos == 2) refreshLog()
    }

    private fun selectTab(pos: Int) {
        if (binding.tabs.selectedTabPosition != pos) binding.tabs.getTabAt(pos)?.select()
    }

    // ---------- 首页 ----------

    private fun setupRows() {
        rowEnv.icon.setImageResource(R.drawable.ic_env)
        rowEnv.title.setText(R.string.row_env)
        rowNapcat.icon.setImageResource(R.drawable.ic_qq)
        rowNapcat.title.setText(R.string.row_napcat)
        rowBridge.icon.setImageResource(R.drawable.ic_bridge)
        rowBridge.title.setText(R.string.row_bridge)

        rowEnv.action.setOnClickListener { startInit() }
        rowNapcat.action.setOnClickListener {
            if (isNapcatActive(NapCatManager.state)) confirmStopNapcat() else startNapcat()
        }
        rowBridge.action.setOnClickListener {
            if (BridgeService.isRunning) stopBridge() else startBridge()
        }
    }

    private fun isNapcatActive(s: NapCatState) =
        s == NapCatState.RUNNING || s == NapCatState.WAIT_LOGIN || s == NapCatState.STARTING

    private fun startInit() {
        saveQuietly()
        rowEnv.action.isEnabled = false
        selectTab(2)
        io.execute {
            SetupOrchestrator.initialize(this) { msg -> BridgeLog.raw("» $msg") }
        }
    }

    private fun startNapcat() {
        saveQuietly()
        rowNapcat.action.isEnabled = false
        val uin = binding.etQqUin.text.toString().trim()
        io.execute {
            SetupOrchestrator.startNapCat(this, uin)
            refreshTokenAsync()
        }
    }

    private fun confirmStopNapcat() {
        if (!BridgeService.isRunning) {
            stopNapcat()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dlg_stop_napcat_title)
            .setMessage(R.string.dlg_stop_napcat_msg)
            .setPositiveButton(R.string.act_stop) { _, _ -> stopNapcat() }
            .setNegativeButton(R.string.dlg_cancel, null)
            .show()
    }

    private fun stopNapcat() {
        rowNapcat.action.isEnabled = false
        clearQr()
        io.execute { SetupOrchestrator.stopAll(this) }
    }

    private fun startBridge() {
        val cfg = currentConfig()
        val missing = cfg.missingRequired()
        if (missing.isNotEmpty()) {
            toast(getString(R.string.toast_need_config, missing.joinToString("、")))
            selectTab(1)
            validateForm()
            return
        }
        saveQuietly()
        BridgeService.start(this)
        renderState()
    }

    private fun stopBridge() {
        BridgeService.stop(this)
        renderState()
    }

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    private fun setRow(
        row: ItemStatusRowBinding,
        status: String,
        dot: Int,
        busy: Boolean,
        actionText: Int?,
        actionEnabled: Boolean = true,
        primary: Boolean = false,
    ) {
        row.status.text = status
        row.dot.backgroundTintList = ColorStateList.valueOf(color(dot))
        row.progress.visibility = if (busy) View.VISIBLE else View.GONE
        if (actionText == null) {
            row.action.visibility = View.GONE
        } else {
            row.action.visibility = View.VISIBLE
            row.action.setText(actionText)
            row.action.isEnabled = actionEnabled && !busy
            // 下一步要做的操作用实心按钮突出，其余用描边按钮
            val bg = if (primary) resolveAttr(com.google.android.material.R.attr.colorPrimary) else android.graphics.Color.TRANSPARENT
            val fg = if (primary) resolveAttr(com.google.android.material.R.attr.colorOnPrimary) else resolveAttr(com.google.android.material.R.attr.colorPrimary)
            row.action.backgroundTintList = ColorStateList.valueOf(bg)
            row.action.setTextColor(fg)
            row.action.strokeWidth = if (primary) 0 else dp(1)
            row.action.strokeColor = ColorStateList.valueOf(color(R.color.app_outline))
        }
    }

    private fun resolveAttr(attr: Int): Int {
        val tv = TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun renderState() {
        if (isFinishing || isDestroyed) return
        val s = NapCatManager.state
        val envReady = s !in setOf(NapCatState.NEED_BOOTSTRAP, NapCatState.NEED_INSTALL, NapCatState.INSTALLING, NapCatState.ERROR)
        val bridgeOn = BridgeService.isRunning
        val missing = currentConfig().missingRequired()

        // 环境
        when (s) {
            NapCatState.NEED_BOOTSTRAP -> setRow(rowEnv, getString(R.string.st_env_missing), R.color.dot_gray, false, R.string.act_init, primary = true)
            NapCatState.NEED_INSTALL -> setRow(rowEnv, getString(R.string.st_env_partial), R.color.dot_amber, false, R.string.act_init, primary = true)
            NapCatState.INSTALLING -> setRow(rowEnv, getString(R.string.st_env_installing), R.color.dot_amber, true, null)
            NapCatState.ERROR -> setRow(rowEnv, getString(R.string.st_env_error), R.color.dot_red, false, R.string.act_retry, primary = true)
            else -> setRow(rowEnv, getString(R.string.st_env_ready), R.color.dot_green, false, null)
        }

        // QQ
        when {
            !envReady -> setRow(rowNapcat, getString(R.string.st_napcat_unavailable), R.color.dot_gray, false, R.string.act_start, actionEnabled = false)
            s == NapCatState.STARTING -> setRow(rowNapcat, getString(R.string.st_napcat_starting), R.color.dot_amber, true, null)
            s == NapCatState.STOPPING -> setRow(rowNapcat, getString(R.string.st_napcat_stopping), R.color.dot_amber, true, null)
            s == NapCatState.WAIT_LOGIN -> setRow(rowNapcat, getString(R.string.st_napcat_login), R.color.dot_amber, false, R.string.act_stop)
            s == NapCatState.RUNNING -> setRow(rowNapcat, getString(R.string.st_napcat_running), R.color.dot_green, false, R.string.act_stop)
            else -> setRow(rowNapcat, getString(R.string.st_napcat_off), R.color.dot_gray, false, R.string.act_start, primary = true)
        }

        // 桥接
        if (bridgeOn) {
            val label = BridgeService.connectionLabel ?: getString(R.string.st_napcat_starting)
            val connected = label.startsWith("QQ") || label.startsWith("已连接")
            setRow(rowBridge, getString(R.string.st_bridge_on, label), if (connected) R.color.dot_green else R.color.dot_amber, false, R.string.act_stop)
        } else {
            val canStart = isNapcatActive(s)
            setRow(rowBridge, getString(R.string.st_bridge_off), R.color.dot_gray, false, R.string.act_start, primary = canStart)
        }

        // 登录卡片：QQ 运行期间显示
        binding.cardLogin.visibility = if (isNapcatActive(s)) View.VISIBLE else View.GONE

        renderHint(s, envReady, bridgeOn, missing)
    }

    private fun renderHint(s: NapCatState, envReady: Boolean, bridgeOn: Boolean, missing: List<String>) {
        val (title, body, action) = when {
            s == NapCatState.ERROR -> Triple(R.string.hint_error_title, getString(R.string.hint_error_body), null)
            s == NapCatState.INSTALLING -> Triple(R.string.hint_installing_title, getString(R.string.hint_installing_body), null)
            missing.isNotEmpty() -> Triple(
                R.string.hint_config_title,
                getString(R.string.hint_config_body, missing.joinToString("、")),
                R.string.hint_config_action,
            )
            !envReady -> Triple(R.string.hint_init_title, getString(R.string.hint_init_body), null)
            s == NapCatState.STARTING -> Triple(R.string.hint_starting_title, getString(R.string.hint_starting_body), null)
            !isNapcatActive(s) -> Triple(R.string.hint_napcat_title, getString(R.string.hint_napcat_body), null)
            !bridgeOn && s == NapCatState.WAIT_LOGIN -> Triple(R.string.hint_login_title, getString(R.string.hint_login_body), null)
            !bridgeOn -> Triple(R.string.hint_bridge_title, getString(R.string.hint_bridge_body), null)
            else -> Triple(R.string.hint_ready_title, getString(R.string.hint_ready_body), null)
        }
        binding.tvHintTitle.setText(title)
        binding.tvHintBody.text = body
        if (action != null) {
            binding.btnHintAction.visibility = View.VISIBLE
            binding.btnHintAction.setText(action)
            binding.btnHintAction.setOnClickListener {
                selectTab(1)
                validateForm()
            }
        } else {
            binding.btnHintAction.visibility = View.GONE
        }
    }

    // ---------- 登录卡片 ----------

    private fun setupLoginCard() {
        binding.btnWebUi.setOnClickListener {
            startActivity(Intent(this, WebLoginActivity::class.java).putExtra("url", NapCatManager.webUiUrl(this)))
        }
        binding.btnCopyToken.setOnClickListener {
            val t = webUiToken
            if (t.isNullOrBlank()) {
                toast(getString(R.string.toast_token_missing))
                refreshTokenAsync()
            } else {
                copy("NapCat WebUI Token", t)
                toast(getString(R.string.toast_token_copied))
            }
        }
    }

    /** Token 要扫日志文件，放到后台线程。 */
    private fun refreshTokenAsync() {
        if (webUiToken != null) return
        Thread {
            val t = NapCatManager.resolveWebUiToken(this)
            if (t != null) webUiToken = t
        }.start()
    }

    private fun clearQr() {
        binding.imgQr.visibility = View.GONE
        binding.tvQr.visibility = View.GONE
    }

    /** 字符画二维码：按行宽算字号，保证一行不换行且整体放得进卡片。 */
    private fun showQrBlock(block: List<String>) {
        selectTab(0)
        binding.cardLogin.visibility = View.VISIBLE
        binding.imgQr.visibility = View.GONE
        val cols = block.maxOf { it.length }.coerceAtLeast(1)
        // 首页刚从 GONE 切回时还没测量，用根视图宽度
        val available = binding.root.width - dp(80)
        val paint = Paint().apply { typeface = Typeface.MONOSPACE; textSize = 100f }
        val charW = paint.measureText("█") / 100f
        val px = (available / (cols * charW)).coerceIn(2f, 24f)
        binding.tvQr.setTextSize(TypedValue.COMPLEX_UNIT_PX, px)
        binding.tvQr.setHorizontallyScrolling(true)
        binding.tvQr.text = block.joinToString("\n")
        binding.tvQr.visibility = View.VISIBLE
        binding.panelHome.post { binding.panelHome.smoothScrollTo(0, binding.cardLogin.top) }
    }

    private fun showQrImage(f: File) {
        val bmp: Bitmap = BitmapFactory.decodeFile(f.absolutePath) ?: return
        selectTab(0)
        binding.cardLogin.visibility = View.VISIBLE
        binding.tvQr.visibility = View.GONE
        binding.imgQr.setImageBitmap(bmp)
        binding.imgQr.visibility = View.VISIBLE
        binding.panelHome.post { binding.panelHome.smoothScrollTo(0, binding.cardLogin.top) }
    }

    private fun startLogPump() {
        val logFile = File(ShellExec.logsDir(this), "napcat.out.log")
        logPump?.stop()
        logPump = LogPump(
            file = logFile,
            onLine = { line ->
                if (webUiToken == null) {
                    NapCatManager.noteWebUiToken(line)
                    NapCatManager.webUiToken?.let { webUiToken = it }
                }
            },
            onQrBlock = { block -> mainHandler.post { showQrBlock(block) } },
            onImage = { f -> mainHandler.post { showQrImage(f) } },
        ).also { it.start() }
    }

    // ---------- 设置 ----------

    private fun setupSettings() {
        binding.ddPlatform.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, listOf("napcat", "onebot")))
        binding.btnAdvanced.setOnClickListener {
            val show = binding.groupAdvanced.visibility != View.VISIBLE
            binding.groupAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            binding.btnAdvanced.setText(if (show) R.string.advanced_hide else R.string.advanced_show)
        }
        binding.btnSaveConfig.setOnClickListener { saveFromButton() }
        // 修改后清除该项的错误提示
        listOf(
            binding.tilMisskeyHost, binding.tilCharacterId, binding.tilStyleId,
            binding.tilAllowedQq, binding.tilAllowedGroups, binding.tilAdminQq,
            binding.tilPrefix, binding.tilNapcatWs,
        ).forEach { til -> til.editText?.doAfterTextChanged { til.error = null } }
    }

    private fun loadConfigIntoUi() {
        val cfg = loadSavedConfig()
        binding.etMisskeyHost.setText(cfg.misskey.host)
        binding.etCharacterId.setText(cfg.misskey.characterId)
        binding.etStyleId.setText(cfg.misskey.dialogueStyleId)
        binding.etAllowedQq.setText(cfg.allowedQq.joinToString(", "))
        binding.etAllowedGroups.setText(cfg.allowedGroups.joinToString(", "))
        binding.etAdminQq.setText(cfg.adminQq.joinToString(", "))
        binding.etPrefix.setText(cfg.commandPrefix)
        binding.ddPlatform.setText(cfg.platform.adapter, false)
        binding.etNapcatWs.setText(cfg.napcat.wsUrl)
        binding.etNapcatToken.setText(cfg.napcat.accessToken)
        val uinFile = NapCatManager.qqUinFile(this)
        binding.etQqUin.setText(if (uinFile.exists()) uinFile.readText().trim() else "")
    }

    /** 已保存配置的缓存：renderState 频繁调用，避免每次都读盘解析。写入后刷新。 */
    private var savedConfig: BridgeConfig? = null

    private fun loadSavedConfig(): BridgeConfig =
        savedConfig ?: ConfigLoader.load(BridgeService.configFile(this), BridgeService.defaultDataDir(this))
            .also { savedConfig = it }

    private fun csv(s: String): List<String> =
        s.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** 表单当前内容合成的配置（未校验）。 */
    private fun currentConfig(): BridgeConfig {
        val base = loadSavedConfig()
        val (scheme, host) = normalizeHost(binding.etMisskeyHost.text.toString(), base.misskey.scheme)
        return base.copy(
            platform = base.platform.copy(adapter = binding.ddPlatform.text.toString().trim().ifBlank { "napcat" }),
            napcat = base.napcat.copy(
                wsUrl = binding.etNapcatWs.text.toString().trim().ifBlank { base.napcat.wsUrl },
                accessToken = binding.etNapcatToken.text.toString().trim(),
            ),
            misskey = base.misskey.copy(
                host = host,
                scheme = scheme,
                characterId = binding.etCharacterId.text.toString().trim(),
                dialogueStyleId = binding.etStyleId.text.toString().trim(),
            ),
            commandPrefix = binding.etPrefix.text.toString().trim().ifBlank { "/" },
            allowedQq = csv(binding.etAllowedQq.text.toString()),
            allowedGroups = csv(binding.etAllowedGroups.text.toString()),
            adminQq = csv(binding.etAdminQq.text.toString()),
            dataDir = base.dataDir.ifBlank { BridgeService.defaultDataDir(this) },
        )
    }

    /** 逐项校验，错误显示在对应输入框下方；返回是否全部通过。 */
    private fun validateForm(): Boolean {
        var ok = true
        fun check(til: TextInputLayout, error: String?) {
            til.error = error
            if (error != null) ok = false
        }
        val hostRaw = binding.etMisskeyHost.text.toString().trim()
        val host = normalizeHost(hostRaw).second
        check(
            binding.tilMisskeyHost,
            when {
                hostRaw.isEmpty() -> getString(R.string.err_required)
                !Regex("^[A-Za-z0-9.-]+(:\\d{1,5})?$").matches(host) || !host.contains('.') && !host.startsWith("localhost") ->
                    getString(R.string.err_host)
                else -> null
            },
        )
        check(binding.tilCharacterId, if (binding.etCharacterId.text.isNullOrBlank()) getString(R.string.err_required) else null)
        check(binding.tilStyleId, if (binding.etStyleId.text.isNullOrBlank()) getString(R.string.err_required) else null)
        for (til in listOf(binding.tilAllowedQq, binding.tilAllowedGroups, binding.tilAdminQq)) {
            val bad = csv(til.editText?.text.toString()).any { !it.all(Char::isDigit) }
            check(til, if (bad) getString(R.string.err_numbers) else null)
        }
        val prefix = binding.etPrefix.text.toString()
        check(binding.tilPrefix, if (prefix.isBlank() || prefix.trim().contains(' ')) getString(R.string.err_prefix) else null)
        val ws = binding.etNapcatWs.text.toString().trim()
        val wsBad = ws.isNotEmpty() && !ws.startsWith("ws://") && !ws.startsWith("wss://")
        check(binding.tilNapcatWs, if (wsBad) getString(R.string.err_ws) else null)
        if (wsBad) {
            binding.groupAdvanced.visibility = View.VISIBLE
            binding.btnAdvanced.setText(R.string.advanced_hide)
        }
        return ok
    }

    private fun writeSettings() {
        val cfg = currentConfig()
        ConfigLoader.save(BridgeService.configFile(this), cfg)
        savedConfig = cfg
        NapCatManager.qqUinFile(this).writeText(binding.etQqUin.text.toString().trim())
    }

    /** 启动类操作前顺手保存：仅在必填项齐全时写入，避免把半成品写进配置。 */
    private fun saveQuietly() {
        try {
            if (currentConfig().missingRequired().isEmpty()) writeSettings()
            else NapCatManager.qqUinFile(this).writeText(binding.etQqUin.text.toString().trim())
        } catch (e: Exception) {
            BridgeLog.w("保存设置失败: $e")
        }
    }

    private fun saveFromButton() {
        if (!validateForm()) {
            toast(getString(R.string.toast_fix_fields))
            return
        }
        try {
            writeSettings()
        } catch (e: Exception) {
            toast("保存失败：${e.message}")
            return
        }
        // 规范化后的地址回显，让用户看到实际生效的值
        binding.etMisskeyHost.setText(currentConfig().misskey.host)
        toast(getString(R.string.toast_saved))
        renderState()
        if (BridgeService.isRunning) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlg_restart_title)
                .setMessage(R.string.dlg_restart_msg)
                .setPositiveButton(R.string.dlg_restart_ok) { _, _ -> restartBridge() }
                .setNegativeButton(R.string.dlg_later, null)
                .show()
        } else {
            selectTab(0)
        }
    }

    private fun restartBridge() {
        BridgeService.stop(this)
        // 等服务 onDestroy 完成后再拉起，避免新实例被旧的 stop 带走
        mainHandler.postDelayed({ BridgeService.start(this) }, 800)
        selectTab(0)
    }

    // ---------- 日志 ----------

    private fun setupLogPanel() {
        binding.chipsLog.setOnCheckedStateChangeListener { _, ids ->
            logFilter = when (ids.firstOrNull()) {
                R.id.chipBridge -> LogFilter.BRIDGE
                R.id.chipError -> LogFilter.ERROR
                else -> LogFilter.ALL
            }
            followLog = true
            refreshLog()
        }
        binding.btnClearLog.setOnClickListener {
            BridgeLog.clearBuffer()
            refreshLog()
        }
        binding.btnCopyLog.setOnClickListener {
            copy("QBridge 日志", filteredLines().joinToString("\n"))
            toast(getString(R.string.toast_log_copied))
        }
        binding.btnScrollEnd.setOnClickListener {
            followLog = true
            binding.btnScrollEnd.visibility = View.GONE
            binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
        }
        // 用户向上翻看时暂停自动滚动
        binding.scroll.setOnScrollChangeListener { v, _, scrollY, _, _ ->
            val child = (v as android.widget.ScrollView).getChildAt(0) ?: return@setOnScrollChangeListener
            val atBottom = scrollY + v.height >= child.height - dp(24)
            followLog = atBottom
            binding.btnScrollEnd.visibility = if (atBottom) View.GONE else View.VISIBLE
        }
    }

    /** 日志可能一秒几十行，合并成每 300ms 最多刷新一次。 */
    private fun scheduleLogRefresh() {
        if (!pendingLogRefresh.compareAndSet(false, true)) return
        mainHandler.postDelayed({
            pendingLogRefresh.set(false)
            if (binding.panelLog.visibility == View.VISIBLE) refreshLog()
        }, 300)
    }

    private fun filteredLines(): List<String> {
        val all = BridgeLog.buffer.toList()
        return when (logFilter) {
            LogFilter.ALL -> all
            LogFilter.BRIDGE -> all.filter { "[bridge]" in it && "[napcat]" !in it }
            LogFilter.ERROR -> all.filter { " W [" in it || " E [" in it }
        }
    }

    private fun refreshLog() {
        val lines = filteredLines().takeLast(logViewLimit)
        if (lines.isEmpty()) {
            binding.tvLog.text = getString(R.string.log_empty)
            return
        }
        val warn = color(R.color.dot_amber)
        val err = color(R.color.dot_red)
        val ssb = SpannableStringBuilder()
        for (line in lines) {
            val start = ssb.length
            ssb.append(line).append('\n')
            val c = when {
                " E [" in line -> err
                " W [" in line -> warn
                else -> null
            }
            if (c != null) ssb.setSpan(ForegroundColorSpan(c), start, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.tvLog.text = ssb
        if (followLog) binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- 工具 ----------

    private fun copy(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
