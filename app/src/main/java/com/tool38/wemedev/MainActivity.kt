package com.tool38.wemedev

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// import com.google.android.exoplayer2.Player
// import com.google.android.exoplayer2.ui.PlayerView
import com.tool38.wemedev.databinding.ActivityMainBinding
// import com.dueeeke.videoplayer.player.VideoView
// import com.dueeeke.videoplayer.controller.StandardVideoController

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    
    // 下载文件路径
    private var downloadedFilePath: String? = null
    
    // 当前下载速度（用于保持显示）
    private var currentSpeed: String = ""
    
    // 是否正在下载状态
    private var isDownloadingState = false
    
    // 码率选择相关
    private var availableBitrates: List<BitrateInfo> = emptyList()
    private var selectedBitrate: BitrateInfo? = null
    private lateinit var bitrateAdapter: BitrateAdapter
    
    // 广播接收器
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_PROGRESS_UPDATE -> {
                    val stage = intent.getStringExtra(DownloadService.EXTRA_STAGE) ?: ""
                    val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                    val status = intent.getStringExtra(DownloadService.EXTRA_STATUS) ?: ""
                    val filePath = intent.getStringExtra(DownloadService.EXTRA_FILE_PATH)
                    val speed = intent.getStringExtra(DownloadService.EXTRA_SPEED) ?: ""
                    
                    // 保存文件路径
                    if (filePath != null) {
                        downloadedFilePath = filePath
                    }
                    
                    // 在主线程更新UI
                    runOnUiThread {
                        updateDownloadButton(stage, progress, status, speed)
                    }
                }
            }
        }
    }
    
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MANAGE_EXTERNAL_STORAGE_REQUEST_CODE = 1002
        private const val PREFS_NAME = "38tool_prefs"
        private const val KEY_FIRST_LAUNCH = "first_launch"
        private const val KEY_USER_AGREED = "user_agreed"
        private const val KEY_PERMISSIONS_SETUP_COMPLETED = "permissions_setup_completed"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 隐藏ActionBar
        supportActionBar?.hide()
        
        // 设置状态栏为白色背景
        setupStatusBar()
        
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        
        setupUI()
        setupObservers()
        
        // 检查是否首次启动，显示欢迎对话框
        checkFirstLaunch()
        
        // 注册广播接收器
        val intentFilter = IntentFilter(DownloadService.ACTION_PROGRESS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, intentFilter)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 如果正在下载状态，确保键盘不弹出
        if (isDownloadingState) {
            hideKeyboard()
            // 延迟再次隐藏键盘，确保完全隐藏
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                hideKeyboard()
            }, 100)
        }
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 当窗口获得焦点时，如果正在下载状态，确保键盘不弹出
        if (hasFocus && isDownloadingState) {
            hideKeyboard()
        }
    }
    
    private fun setupStatusBar() {
        // 状态栏样式由主题统一管理，按钮样式由布局文件统一管理
        // 这里不需要额外设置
    }
    
    private fun setupUI() {
        // 设置内置测试链接
        val defaultUrl = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
        binding.etM3u8Url.setText(defaultUrl)
        viewModel.setCurrentUrl(defaultUrl)
        
        // 监听URL输入变化
        binding.etM3u8Url.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val url = s?.toString()?.trim() ?: ""
                viewModel.setCurrentUrl(url)
                
                // 检测码率选项
                if (url.isNotEmpty() && url.contains(".m3u8")) {
                    showBitrateLoading()
                    checkBitrates(url)
                    // 粘贴完URL后自动收起键盘
                    hideKeyboard()
                } else {
                    hideBitrateSelector()
                }
            }
        })
        
        // 初始化码率选择RecyclerView
        setupBitrateRecyclerView()
        
        
        // 下载按钮启动后台下载
        binding.btnDownload.setOnClickListener {
            // 收起键盘
            hideKeyboard()
            
            // 检查所有权限的真实状态
            val allPermissionsGranted = checkAllPermissionsStatus()
            
            if (allPermissionsGranted) {
                // 所有权限都已授予，直接开始下载
                android.util.Log.d("MainActivity", "所有权限都已授予，直接开始下载")
                proceedWithDownload()
            } else {
                // 权限未完全授予，显示权限请求对话框
                android.util.Log.d("MainActivity", "权限未完全授予，显示权限请求对话框")
                showPermissionsRequestDialog()
            }
        }
        
        // 停止按钮点击事件
        binding.tvStop.setOnClickListener {
            stopDownload()
        }
        
        // 隐私政策链接点击事件
        binding.tvPrivacyPolicy.setOnClickListener {
            openPrivacyPolicy()
        }
        
        // 长按隐私政策链接重置权限设置状态（用于测试）
        binding.tvPrivacyPolicy.setOnLongClickListener {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_PERMISSIONS_SETUP_COMPLETED, false).apply()
            Toast.makeText(this, getString(R.string.toast_permissions_state_reset), Toast.LENGTH_SHORT).show()
            android.util.Log.d("MainActivity", "权限设置状态已重置")
            true
        }
    }
    
    
    
    private fun setupObservers() {
        // 简化观察者，因为现在使用后台下载服务
    }
    
    
    private fun showManageStoragePermissionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_storage_permission, null)
        
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        
        // 设置按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            openManageStorageSettings()
        }
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
            Toast.makeText(this, getString(R.string.toast_need_file_permission), Toast.LENGTH_LONG).show()
        }
        
        dialog.show()
    }
    
    private fun openManageStorageSettings() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivityForResult(intent, MANAGE_EXTERNAL_STORAGE_REQUEST_CODE)
        } catch (e: Exception) {
            // 如果上面的方法失败，尝试通用设置页面
            try {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivityForResult(intent, MANAGE_EXTERNAL_STORAGE_REQUEST_CODE)
            } catch (e2: Exception) {
                Toast.makeText(this, getString(R.string.toast_open_settings_failed), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MANAGE_EXTERNAL_STORAGE_REQUEST_CODE) {
            // 从设置页面返回后继续权限设置流程
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, getString(R.string.toast_all_files_permission_granted), Toast.LENGTH_SHORT).show()
                    android.util.Log.d("MainActivity", "文件权限已授予，继续权限检查")
                    // 继续下一步权限检查
                    checkNotificationPermissionForDownload()
                } else {
                    Toast.makeText(this, getString(R.string.toast_all_files_permission_required), Toast.LENGTH_LONG).show()
                    android.util.Log.d("MainActivity", "文件权限未授予")
                }
            }
        }
    }
    
    
    
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 权限已授予，继续下一步权限检查
                checkBatteryOptimizationForDownload()
            } else {
                Toast.makeText(this, getString(R.string.toast_permissions_required), Toast.LENGTH_SHORT).show()
                // 即使权限被拒绝，也继续下一步检查
                checkBatteryOptimizationForDownload()
            }
        }
    }
    
    
    
    override fun onBackPressed() {
        super.onBackPressed()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 注销广播接收器
        try {
            unregisterReceiver(progressReceiver)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "注销广播接收器失败: ${e.message}")
        }
    }
    
    private fun updateDownloadButton(stage: String, progress: Int, status: String, speed: String = "") {
        when (stage) {
            DownloadService.STAGE_DOWNLOAD -> {
                // 设置下载状态
                isDownloadingState = true
                
                // 禁用输入框焦点，防止键盘弹出
                binding.etM3u8Url.isFocusable = false
                binding.etM3u8Url.isFocusableInTouchMode = false
                
                // 隐藏底部介绍文案
                binding.tvUsageDescription.visibility = View.GONE
                
                // 更新当前速度（如果有新速度值）
                if (speed.isNotEmpty()) {
                    currentSpeed = speed
                }
                
                // 如果有保存的速度，就显示它
                if (currentSpeed.isNotEmpty()) {
                    val fullText = getString(R.string.downloading_with_speed, progress, currentSpeed)
                    val spannableString = SpannableString(fullText)
                    val speedStartIndex = fullText.indexOf(currentSpeed)
                    val speedEndIndex = speedStartIndex + currentSpeed.length
                    
                    // 设置速度文字为亮绿色
                    spannableString.setSpan(
                        ForegroundColorSpan(ContextCompat.getColor(this, android.R.color.holo_green_light)),
                        speedStartIndex,
                        speedEndIndex,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    binding.btnDownload.text = spannableString
                } else {
                    binding.btnDownload.text = getString(R.string.downloading_percent, progress)
                }
                binding.btnDownload.isEnabled = false
                // 显示停止按钮
                binding.tvStop.visibility = View.VISIBLE
            }
            DownloadService.STAGE_CONVERT -> {
                // 保持下载状态
                isDownloadingState = true
                
                // 禁用输入框焦点，防止键盘弹出
                binding.etM3u8Url.isFocusable = false
                binding.etM3u8Url.isFocusableInTouchMode = false
                
                // 隐藏底部介绍文案
                binding.tvUsageDescription.visibility = View.GONE
                
                // 显示转换进度
                binding.btnDownload.text = getString(R.string.converting_percent, progress)
                binding.btnDownload.isEnabled = false
                // 显示停止按钮
                binding.tvStop.visibility = View.VISIBLE
            }
            DownloadService.STAGE_COMPLETE -> {
                // 重置下载状态
                isDownloadingState = false
                
                // 恢复输入框焦点
                binding.etM3u8Url.isFocusable = true
                binding.etM3u8Url.isFocusableInTouchMode = true
                
                // 显示底部介绍文案
                binding.tvUsageDescription.visibility = View.VISIBLE
                
                binding.btnDownload.text = getString(R.string.download_complete)
                binding.btnDownload.isEnabled = true
                // 隐藏停止按钮
                binding.tvStop.visibility = View.GONE
                
                // 清空当前速度
                currentSpeed = ""
                
                // 显示Toast提示
                Toast.makeText(this@MainActivity, getString(R.string.download_complete), Toast.LENGTH_SHORT).show()
                
                // 使用系统播放器播放下载的文件
                trySystemPlayerFirst()
                
                // 2秒后恢复按钮状态
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    binding.btnDownload.text = getString(R.string.download)
                }, 2000)
            }
        }
    }
    
    
    /**
     * 使用系统播放器播放下载的文件
     */
    private fun trySystemPlayerFirst() {
        val downloadedFilePath = getDownloadedFilePath()
        if (downloadedFilePath != null) {
            // 尝试使用系统播放器
            if (canUseSystemPlayer(downloadedFilePath)) {
                openWithSystemPlayer(downloadedFilePath)
            } else {
                // 系统播放器不可用
                Toast.makeText(this, getString(R.string.toast_system_player_unavailable), Toast.LENGTH_LONG).show()
            }
        } else {
            // 没有文件路径
            Toast.makeText(this, getString(R.string.toast_file_not_found), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 检查是否可以使用系统播放器
     */
    private fun canUseSystemPlayer(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                return false
            }
            
            val uri = Uri.fromFile(file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "video/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            
            // 检查是否有应用可以处理这个Intent
            intent.resolveActivity(packageManager) != null
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "检查系统播放器可用性失败: ${e.message}")
            false
        }
    }
    
    /**
     * 获取下载文件的路径
     */
    private fun getDownloadedFilePath(): String? {
        return downloadedFilePath
    }
    
    /**
     * 调用系统视频播放器播放下载的文件
     */
    private fun openWithSystemPlayer(filePath: String) {
        try {
            android.util.Log.d("MainActivity", "尝试打开文件: $filePath")
            
            val file = File(filePath)
            if (!file.exists()) {
                android.util.Log.w("MainActivity", "文件不存在: $filePath，尝试查找实际下载的文件")
                
                // 尝试查找实际下载的文件
                val actualFile = findActualDownloadedFile()
                if (actualFile != null) {
                    android.util.Log.d("MainActivity", "找到实际文件: ${actualFile.absolutePath}")
                    openFileWithSystemPlayer(actualFile)
                } else {
                    android.util.Log.w("MainActivity", "未找到实际下载的文件")
                    Toast.makeText(this, getString(R.string.toast_file_not_found), Toast.LENGTH_SHORT).show()
                }
                return
            }
            
            openFileWithSystemPlayer(file)
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "调用系统播放器失败: ${e.message}")
            Toast.makeText(this, getString(R.string.toast_system_player_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 使用系统播放器打开文件
     */
    private fun openFileWithSystemPlayer(file: File) {
        try {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 使用 FileProvider
                androidx.core.content.FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    file
                )
            } else {
                // Android 7.0 以下使用 file://
                Uri.fromFile(file)
            }
            
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "video/*")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            android.util.Log.d("MainActivity", "创建Intent: $intent")
            android.util.Log.d("MainActivity", "URI: $uri")
            
            // 检查是否有应用可以处理这个Intent
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Toast.makeText(this, getString(R.string.toast_saved_to_gallery), Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.w("MainActivity", "没有找到可用的视频播放器")
                Toast.makeText(this, getString(R.string.toast_no_video_player_found), Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "打开文件失败: ${e.message}")
            throw e
        }
    }
    
    /**
     * 查找实际下载的文件
     */
    private fun findActualDownloadedFile(): File? {
        try {
            // 查找 M3U8 下载目录
            val m3u8Dir = File("/sdcard/Download/M3U8")
            if (m3u8Dir.exists()) {
                val files = m3u8Dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ts"))
                }
                if (files != null && files.isNotEmpty()) {
                    // 返回最新的文件
                    return files.maxByOrNull { it.lastModified() }
                }
            }
            
            // 查找 Downloads 目录
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir.exists()) {
                val files = downloadsDir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ts"))
                }
                if (files != null && files.isNotEmpty()) {
                    // 返回最新的文件
                    return files.maxByOrNull { it.lastModified() }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "查找下载文件失败: ${e.message}")
        }
        return null
    }
    
    /**
     * 检查M3U8链接的可用码率
     */
    private fun checkBitrates(url: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val m3u8Downloader = M3U8Downloader(this@MainActivity)
                val bitrates = m3u8Downloader.getAvailableBitrates(url)
                
                withContext(Dispatchers.Main) {
                    if (bitrates.isNotEmpty()) {
                        availableBitrates = bitrates
                        showBitrateSelector()
                        updateBitrateAdapter(bitrates)
                        selectedBitrate = bitrates.first() // 默认选择最高码率
                        showBitrateList() // 显示码率列表
                    } else {
                        hideBitrateSelector()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "检查码率失败", e)
                withContext(Dispatchers.Main) {
                    hideBitrateSelector()
                }
            }
        }
    }
    
    /**
     * 显示码率选择器UI
     */
    private fun showBitrateSelector() {
        binding.layoutBitrateSelector.visibility = View.VISIBLE
    }
    
    /**
     * 显示码率加载状态
     */
    private fun showBitrateLoading() {
        binding.layoutBitrateSelector.visibility = View.VISIBLE
        binding.layoutBitrateLoading.visibility = View.VISIBLE
        binding.rvBitrates.visibility = View.GONE
    }
    
    /**
     * 显示码率列表
     */
    private fun showBitrateList() {
        binding.layoutBitrateLoading.visibility = View.GONE
        binding.rvBitrates.visibility = View.VISIBLE
    }
    
    /**
     * 隐藏码率选择器
     */
    private fun hideBitrateSelector() {
        binding.layoutBitrateSelector.visibility = View.GONE
        binding.layoutBitrateLoading.visibility = View.GONE
        binding.rvBitrates.visibility = View.GONE
        availableBitrates = emptyList()
        selectedBitrate = null
    }
    
    /**
     * 初始化码率选择RecyclerView
     */
    private fun setupBitrateRecyclerView() {
        bitrateAdapter = BitrateAdapter(emptyList()) { bitrate ->
            selectedBitrate = bitrate
            android.util.Log.d("MainActivity", "选择码率: ${bitrate.name}")
        }
        
        binding.rvBitrates.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = bitrateAdapter
        }
    }
    
    /**
     * 更新码率适配器
     */
    private fun updateBitrateAdapter(bitrates: List<BitrateInfo>) {
        bitrateAdapter = BitrateAdapter(bitrates) { bitrate ->
            selectedBitrate = bitrate
            android.util.Log.d("MainActivity", "选择码率: ${bitrate.name}")
        }
        binding.rvBitrates.adapter = bitrateAdapter
    }
    
    /**
     * 隐藏软键盘
     */
    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        // 清除焦点
        binding.etM3u8Url.clearFocus()
        // 隐藏键盘
        imm.hideSoftInputFromWindow(binding.etM3u8Url.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
    }
    
    
    /**
     * 显示电池优化对话框
     */
    private fun showBatteryOptimizationDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_battery_optimization, null)
        
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        
        // 设置按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            openAppInfoSettings()
        }
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
            // 用户选择稍后，标记权限设置完成并直接开始下载
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_PERMISSIONS_SETUP_COMPLETED, true).apply()
            android.util.Log.d("MainActivity", "用户选择稍后设置电池优化，标记权限设置完成状态")
            proceedWithDownload()
        }
        
        dialog.show()
    }
    
    /**
     * 打开应用程序信息设置页面
     */
    private fun openAppInfoSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                Toast.makeText(this, getString(R.string.toast_find_battery_settings), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, getString(R.string.toast_cannot_open_app_settings), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "打开应用设置页面失败: ${e.message}")
            Toast.makeText(this, getString(R.string.toast_cannot_open_app_settings), Toast.LENGTH_SHORT).show()
        }
    }
    
    
    /**
     * 继续下载流程
     */
    private fun proceedWithDownload() {
        val url = binding.etM3u8Url.text.toString().trim()
        if (url.isNotEmpty()) {
            // 启动后台下载服务
            val title = getString(R.string.download_notification_title_format, url.substringAfterLast("/").substringBefore("."))
            android.util.Log.d("MainActivity", "启动后台下载: $url, 选择码率: ${selectedBitrate?.name}")
            DownloadService.startDownloadWithBitrate(this, url, title, selectedBitrate)
            
            // 重置当前速度
            currentSpeed = ""
            
            // 设置下载状态（进度将通过广播接收器更新）
            binding.btnDownload.isEnabled = false
            binding.btnDownload.text = getString(R.string.downloading_percent, 0)
            
            Toast.makeText(this, getString(R.string.toast_task_started), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.invalid_url), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 停止下载任务
     */
    private fun stopDownload() {
        try {
            // 停止下载服务
            DownloadService.stopDownload(this)
            
            // 重置下载状态
            isDownloadingState = false
            
            // 清空当前速度
            currentSpeed = ""
            
            // 恢复输入框焦点
            binding.etM3u8Url.isFocusable = true
            binding.etM3u8Url.isFocusableInTouchMode = true
            
            // 恢复UI状态
            binding.btnDownload.text = getString(R.string.download)
            binding.btnDownload.isEnabled = true
            binding.tvStop.visibility = View.GONE
            
            // 显示底部介绍文案
            binding.tvUsageDescription.visibility = View.VISIBLE
            
            Toast.makeText(this, getString(R.string.toast_download_stopped), Toast.LENGTH_SHORT).show()
            
            android.util.Log.d("MainActivity", "用户手动停止下载")
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "停止下载失败: ${e.message}")
            Toast.makeText(this, "停止下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 打开隐私政策页面
     */
    private fun openPrivacyPolicy() {
        try {
            val privacyPolicyUrl = "http://wemedev.com/38tool/Privacy.html"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(privacyPolicyUrl))
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "打开隐私政策失败: ${e.message}")
            Toast.makeText(this, getString(R.string.toast_privacy_open_failed), Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 检查是否需要显示欢迎对话框
     */
    private fun checkFirstLaunch() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userAgreed = prefs.getBoolean(KEY_USER_AGREED, false)
        
        if (!userAgreed) {
            // 用户未同意，显示欢迎对话框
            showWelcomeDialog()
        }
    }
    
    /**
     * 显示欢迎对话框
     */
    private fun showWelcomeDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_welcome, null)
        
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        
        // 设置对话框窗口属性
        dialog.window?.attributes?.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.attributes?.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        
        // 隐私政策链接点击事件
        dialogView.findViewById<TextView>(R.id.tv_privacy_link).setOnClickListener {
            openPrivacyPolicy()
        }
        
        // 不同意按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_disagree).setOnClickListener {
            dialog.dismiss()
            // 退出应用
            finishAffinity()
        }
        
        // 同意并继续按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_agree_continue).setOnClickListener {
            dialog.dismiss()
            // 标记用户已同意
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_USER_AGREED, true).apply()
        }
        
        dialog.show()
    }
    
    /**
     * 显示权限请求对话框
     */
    private fun showPermissionsRequestDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_permissions_request, null)
        
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(true)
        
        // 设置对话框窗口属性
        dialog.window?.attributes?.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.attributes?.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        
        // 取消按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        
        // 开始设置按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            android.util.Log.d("MainActivity", "用户点击开始设置，开始权限设置流程")
            // 开始权限设置流程
            startPermissionSetupFlow()
        }
        
        dialog.show()
    }
    
    /**
     * 开始权限设置流程
     */
    private fun startPermissionSetupFlow() {
        android.util.Log.d("MainActivity", "开始权限设置流程")
        // 1. 首先检查文件权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                android.util.Log.d("MainActivity", "文件权限未授予，显示文件权限对话框")
                showManageStoragePermissionDialog()
                return
            } else {
                android.util.Log.d("MainActivity", "文件权限已授予")
            }
        }
        
        // 2. 检查通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("MainActivity", "通知权限未授予，显示通知权限对话框")
                showNotificationPermissionDialog()
                return
            } else {
                android.util.Log.d("MainActivity", "通知权限已授予")
            }
        }
        
        // 3. 检查电池优化
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                android.util.Log.d("MainActivity", "电池优化未设置，显示电池优化对话框")
                showBatteryOptimizationDialog()
                return
            } else {
                android.util.Log.d("MainActivity", "电池优化已设置")
            }
        }
        
        // 所有权限都已设置，标记权限设置完成并开始下载
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_PERMISSIONS_SETUP_COMPLETED, true).apply()
        android.util.Log.d("MainActivity", "所有权限已设置完成，标记权限设置完成状态")
        proceedWithDownload()
    }
    
    /**
     * 显示通知权限对话框
     */
    private fun showNotificationPermissionDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_notification_permission, null)
        
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        
        // 设置对话框窗口属性
        dialog.window?.attributes?.width = (resources.displayMetrics.widthPixels * 0.9).toInt()
        dialog.window?.attributes?.height = android.view.WindowManager.LayoutParams.WRAP_CONTENT
        
        // 取消按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
            // 继续下一步权限检查
            checkBatteryOptimizationForDownload()
        }
        
        // 去设置按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            // 请求通知权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
        
        dialog.show()
    }
    
    /**
     * 检查通知权限（用于下载流程）
     */
    private fun checkNotificationPermissionForDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                showNotificationPermissionDialog()
                return
            }
        }
        
        // 通知权限已授予或不需要，继续电池优化检查
        checkBatteryOptimizationForDownload()
    }
    
    /**
     * 检查电池优化（用于下载流程）
     */
    private fun checkBatteryOptimizationForDownload() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            } else {
                // 已经在白名单中，标记权限设置完成并直接开始下载
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_PERMISSIONS_SETUP_COMPLETED, true).apply()
                proceedWithDownload()
            }
        } else {
            // Android 6.0以下不需要电池优化检查，标记权限设置完成并直接开始下载
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_PERMISSIONS_SETUP_COMPLETED, true).apply()
            proceedWithDownload()
        }
    }
    
    /**
     * 检查所有权限的真实状态
     */
    private fun checkAllPermissionsStatus(): Boolean {
        android.util.Log.d("MainActivity", "开始检查所有权限的真实状态")
        
        // 1. 检查文件权限
        val hasFilePermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        android.util.Log.d("MainActivity", "文件权限状态: $hasFilePermission")
        
        // 2. 检查通知权限
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Android 13以下不需要通知权限
        }
        android.util.Log.d("MainActivity", "通知权限状态: $hasNotificationPermission")
        
        // 3. 检查电池优化
        val hasBatteryOptimization = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true // Android 6.0以下不需要电池优化检查
        }
        android.util.Log.d("MainActivity", "电池优化状态: $hasBatteryOptimization")
        
        val allPermissionsGranted = hasFilePermission && hasNotificationPermission && hasBatteryOptimization
        android.util.Log.d("MainActivity", "所有权限状态: $allPermissionsGranted")
        
        return allPermissionsGranted
    }
    
}
