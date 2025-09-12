package com.m3u8exoplayer

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
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
import com.m3u8exoplayer.databinding.ActivityMainBinding
// import com.dueeeke.videoplayer.player.VideoView
// import com.dueeeke.videoplayer.controller.StandardVideoController

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    
    // 下载文件路径
    private var downloadedFilePath: String? = null
    
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
                    
                    // 保存文件路径
                    if (filePath != null) {
                        downloadedFilePath = filePath
                    }
                    
                    // 在主线程更新UI
                    runOnUiThread {
                        updateDownloadButton(stage, progress, status)
                    }
                }
            }
        }
    }
    
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val MANAGE_EXTERNAL_STORAGE_REQUEST_CODE = 1002
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
        checkPermissions()
        
        // 注册广播接收器
        val intentFilter = IntentFilter(DownloadService.ACTION_PROGRESS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(progressReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(progressReceiver, intentFilter)
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
            
            // 检查电池优化设置（如果不在白名单中会显示对话框）
            checkBatteryOptimization()
            
            // 如果已经在白名单中，直接开始下载
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    // 已经在白名单中，直接开始下载
                    proceedWithDownload()
                }
                // 如果不在白名单中，checkBatteryOptimization()会显示对话框
                // 用户确认后会调用proceedWithDownload()
            } else {
                // Android 6.0以下不需要电池优化检查
                proceedWithDownload()
            }
        }
    }
    
    
    
    private fun setupObservers() {
        // 简化观察者，因为现在使用后台下载服务
    }
    
    private fun checkPermissions() {
        // 检查所有文件访问权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showManageStoragePermissionDialog()
                return
            }
        }
        
        // 检查其他权限
        checkOtherPermissions()
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
            Toast.makeText(this, "需要文件访问权限才能下载文件", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "无法打开设置页面，请手动在设置中开启所有文件访问权限", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MANAGE_EXTERNAL_STORAGE_REQUEST_CODE) {
            // 从设置页面返回后重新检查权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "所有文件访问权限已授予", Toast.LENGTH_SHORT).show()
                    // 继续检查其他权限
                    checkOtherPermissions()
                } else {
                    Toast.makeText(this, "需要授予所有文件访问权限才能下载文件", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    private fun checkOtherPermissions() {
        val permissions = mutableListOf<String>()
        
        // 检查网络权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.INTERNET)
        }
        
        // 检查存储权限（Android 10及以下）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        
        // 检查通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }
    
    private fun checkDownloadPermissions(): Boolean {
        // 检查所有文件访问权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                showManageStoragePermissionDialog()
                return false
            }
        }
        
        // 检查网络权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "需要网络权限才能下载文件", Toast.LENGTH_LONG).show()
            return false
        }
        
        // 检查存储权限（Android 10及以下）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "需要存储权限才能下载文件", Toast.LENGTH_LONG).show()
                return false
            }
        }
        
        return true
    }
    
    private fun checkNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                android.util.Log.d("MainActivity", "通知权限未授予，请求权限")
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
                Toast.makeText(this, "需要通知权限才能显示下载进度", Toast.LENGTH_LONG).show()
                return false
            }
        }
        android.util.Log.d("MainActivity", "通知权限已授予")
        return true
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                // 权限已授予，无需显示toast
            } else {
                Toast.makeText(this, "需要权限才能下载文件", Toast.LENGTH_SHORT).show()
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
    
    private fun updateDownloadButton(stage: String, progress: Int, status: String) {
        when (stage) {
            DownloadService.STAGE_DOWNLOAD -> {
                binding.btnDownload.text = "下载中 $progress%"
                binding.btnDownload.isEnabled = false
            }
            DownloadService.STAGE_CONVERT -> {
                binding.btnDownload.text = "转换MP4 $progress%"
                binding.btnDownload.isEnabled = false
            }
            DownloadService.STAGE_COMPLETE -> {
                binding.btnDownload.text = "下载完成"
                binding.btnDownload.isEnabled = true
                
                // 隐藏下载动画
                binding.lottieDownload.visibility = View.GONE
                binding.lottieDownload.pauseAnimation()
                
                // 显示Toast提示
                Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_SHORT).show()
                
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
                Toast.makeText(this, "系统播放器不可用，请手动打开下载的文件", Toast.LENGTH_LONG).show()
            }
        } else {
            // 没有文件路径
            Toast.makeText(this, "未找到下载的文件", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "未找到下载的文件", Toast.LENGTH_SHORT).show()
                }
                return
            }
            
            openFileWithSystemPlayer(file)
            
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "调用系统播放器失败: ${e.message}")
            Toast.makeText(this, "系统播放器启动失败: ${e.message}", Toast.LENGTH_LONG).show()
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
                Toast.makeText(this, "正在使用系统播放器打开视频", Toast.LENGTH_SHORT).show()
            } else {
                android.util.Log.w("MainActivity", "没有找到可用的视频播放器")
                Toast.makeText(this, "没有找到可用的视频播放器", Toast.LENGTH_LONG).show()
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
        imm.hideSoftInputFromWindow(binding.etM3u8Url.windowToken, 0)
    }
    
    /**
     * 检查电池优化白名单
     */
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                showBatteryOptimizationDialog()
            }
        }
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
            openBatteryOptimizationSettings()
        }
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    /**
     * 打开电池优化设置页面
     */
    private fun openBatteryOptimizationSettings() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        android.util.Log.d("MainActivity", "设备厂商: $manufacturer")
        
        // 根据厂商使用不同的跳转方法
        when {
            manufacturer.contains("xiaomi") -> {
                openXiaomiBatterySettings()
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                openHuaweiBatterySettings()
            }
            manufacturer.contains("samsung") -> {
                openSamsungBatterySettings()
            }
            manufacturer.contains("oppo") -> {
                openOppoBatterySettings()
            }
            manufacturer.contains("vivo") -> {
                openVivoBatterySettings()
            }
            manufacturer.contains("oneplus") -> {
                openOnePlusBatterySettings()
            }
            else -> {
                openGenericBatterySettings()
            }
        }
    }
    
    /**
     * 小米设备电池设置
     */
    private fun openXiaomiBatterySettings() {
        try {
            // 方法1: 小米电池与性能设置
            val intent1 = Intent().apply {
                component = android.content.ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            }
            if (intent1.resolveActivity(packageManager) != null) {
                startActivity(intent1)
                Toast.makeText(this, "请在列表中找到本应用并设置为\"无限制\"", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "小米方法1失败: ${e.message}")
        }
        
        try {
            // 方法2: 小米应用管理
            val intent2 = Intent().apply {
                component = android.content.ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            if (intent2.resolveActivity(packageManager) != null) {
                startActivity(intent2)
                Toast.makeText(this, "请在列表中找到本应用并允许自启动", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "小米方法2失败: ${e.message}")
        }
        
        // 回退到通用方法
        openGenericBatterySettings()
    }
    
    /**
     * 华为设备电池设置
     */
    private fun openHuaweiBatterySettings() {
        try {
            // 方法1: 华为电池管理
            val intent1 = Intent().apply {
                component = android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            }
            if (intent1.resolveActivity(packageManager) != null) {
                startActivity(intent1)
                Toast.makeText(this, "请在列表中找到本应用并关闭电池优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "华为方法1失败: ${e.message}")
        }
        
        try {
            // 方法2: 华为启动管理
            val intent2 = Intent().apply {
                component = android.content.ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            }
            if (intent2.resolveActivity(packageManager) != null) {
                startActivity(intent2)
                Toast.makeText(this, "请在列表中找到本应用并允许后台活动", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "华为方法2失败: ${e.message}")
        }
        
        // 回退到通用方法
        openGenericBatterySettings()
    }
    
    /**
     * 三星设备电池设置 (针对S23 One UI 6.0/6.1优化)
     */
    private fun openSamsungBatterySettings() {
        try {
            // 方法1: 三星S23 直接请求忽略电池优化 (最新发现)
            val intent1 = Intent().apply {
                component = android.content.ComponentName("com.android.settings", "com.android.settings.fuelgauge.RequestIgnoreBatteryOptimizations")
                data = Uri.parse("package:$packageName")
            }
            if (intent1.resolveActivity(packageManager) != null) {
                startActivity(intent1)
                Toast.makeText(this, "请在后台控制页面中找到本应用并关闭优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "三星S23方法1失败: ${e.message}")
        }
        
        try {
            // 方法2: 三星S23 应用详情页面 (最新发现)
            val intent2 = Intent().apply {
                component = android.content.ComponentName("com.android.settings", "com.android.settings.applications.InstalledAppDetails")
                data = Uri.parse("package:$packageName")
            }
            if (intent2.resolveActivity(packageManager) != null) {
                startActivity(intent2)
                Toast.makeText(this, "请在应用详情中找到\"后台控制\"选项并关闭优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "三星S23方法2失败: ${e.message}")
        }
        
        try {
            // 方法3: 三星S23 特殊访问权限 (One UI 6.0+)
            val intent3 = Intent().apply {
                component = android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.appmanagement.SpecialAccessActivity")
            }
            if (intent3.resolveActivity(packageManager) != null) {
                startActivity(intent3)
                Toast.makeText(this, "请选择\"优化电池使用\"并找到本应用", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "三星S23方法3失败: ${e.message}")
        }
        
        try {
            // 方法4: 三星S23 设置应用 (One UI 6.0+)
            val intent4 = Intent().apply {
                component = android.content.ComponentName("com.android.settings", "com.android.settings.Settings\$BatteryOptimizationActivity")
            }
            if (intent4.resolveActivity(packageManager) != null) {
                startActivity(intent4)
                Toast.makeText(this, "请在电池优化列表中找到本应用并关闭优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "三星S23方法4失败: ${e.message}")
        }
        
        try {
            // 方法5: 三星S23 One UI 6.0+ 设置
            val intent5 = Intent().apply {
                component = android.content.ComponentName("com.samsung.android.settings", "com.samsung.android.settings.battery.BatteryOptimizationActivity")
            }
            if (intent5.resolveActivity(packageManager) != null) {
                startActivity(intent5)
                Toast.makeText(this, "请在电池优化列表中找到本应用并关闭优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "三星S23方法5失败: ${e.message}")
        }
        
        try {
            // 方法6: 三星S23 直接跳转到应用详情
            val intent6 = Intent().apply {
                component = android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.appmanagement.AppInfoActivity")
                putExtra("packageName", packageName)
            }
            if (intent6.resolveActivity(packageManager) != null) {
                startActivity(intent6)
                Toast.makeText(this, "请在应用详情中找到\"电池\"选项并设置为\"未受限制\"", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "三星S23方法6失败: ${e.message}")
        }
        
        try {
            // 方法7: 三星S23 通用应用设置
            val intent7 = Intent().apply {
                component = android.content.ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.appmanagement.AppsActivity")
            }
            if (intent7.resolveActivity(packageManager) != null) {
                startActivity(intent7)
                Toast.makeText(this, "请找到本应用并进入\"电池\"设置", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "三星S23方法7失败: ${e.message}")
        }
        
        // 回退到通用方法
        openGenericBatterySettings()
    }
    
    /**
     * OPPO设备电池设置
     */
    private fun openOppoBatterySettings() {
        try {
            // 方法1: OPPO电池管理
            val intent1 = Intent().apply {
                component = android.content.ComponentName("com.coloros.oppoguardelf", "com.coloros.powermanager.PowerConsumptionActivity")
            }
            if (intent1.resolveActivity(packageManager) != null) {
                startActivity(intent1)
                Toast.makeText(this, "请在电池管理中找到本应用并关闭优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "OPPO方法1失败: ${e.message}")
        }
        
        // 回退到通用方法
        openGenericBatterySettings()
    }
    
    /**
     * vivo设备电池设置
     */
    private fun openVivoBatterySettings() {
        try {
            // 方法1: vivo电池管理
            val intent1 = Intent().apply {
                component = android.content.ComponentName("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
            }
            if (intent1.resolveActivity(packageManager) != null) {
                startActivity(intent1)
                Toast.makeText(this, "请在电池管理中找到本应用并关闭优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "vivo方法1失败: ${e.message}")
        }
        
        // 回退到通用方法
        openGenericBatterySettings()
    }
    
    /**
     * 一加设备电池设置
     */
    private fun openOnePlusBatterySettings() {
        try {
            // 方法1: 一加电池优化
            val intent1 = Intent().apply {
                component = android.content.ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            }
            if (intent1.resolveActivity(packageManager) != null) {
                startActivity(intent1)
                Toast.makeText(this, "请在列表中找到本应用并允许后台活动", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "一加方法1失败: ${e.message}")
        }
        
        // 回退到通用方法
        openGenericBatterySettings()
    }
    
    /**
     * 通用电池设置方法
     */
    private fun openGenericBatterySettings() {
        try {
            // 方法1: 直接请求忽略电池优化（推荐）
            val intent1 = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent1.resolveActivity(packageManager) != null) {
                startActivity(intent1)
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "通用方法1失败: ${e.message}")
        }
        
        try {
            // 方法2: 打开应用详情页面
            val intent2 = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            if (intent2.resolveActivity(packageManager) != null) {
                startActivity(intent2)
                Toast.makeText(this, "请在应用详情中找到\"电池优化\"选项并关闭", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "通用方法2失败: ${e.message}")
        }
        
        try {
            // 方法3: 打开电池优化设置页面
            val intent3 = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            if (intent3.resolveActivity(packageManager) != null) {
                startActivity(intent3)
                Toast.makeText(this, "请在列表中找到本应用并关闭电池优化", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "通用方法3失败: ${e.message}")
        }
        
        try {
            // 方法4: 打开电池设置页面
            val intent4 = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            if (intent4.resolveActivity(packageManager) != null) {
                startActivity(intent4)
                Toast.makeText(this, "请在电池设置中找到应用优化选项", Toast.LENGTH_LONG).show()
                return
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "通用方法4失败: ${e.message}")
        }
        
        // 所有方法都失败，显示手动指导
        showManualBatteryOptimizationGuide()
    }
    
    /**
     * 显示手动电池优化指导
     */
    private fun showManualBatteryOptimizationGuide() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_manual_battery_guide, null)
        
        val dialog = Dialog(this)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        
        // 设置按钮点击事件
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_confirm).setOnClickListener {
            dialog.dismiss()
            // 用户确认后继续下载流程
            proceedWithDownload()
        }
        
        dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
            // 用户取消，不进行下载
        }
        
        dialog.show()
    }
    
    /**
     * 继续下载流程
     */
    private fun proceedWithDownload() {
        if (checkDownloadPermissions() && checkNotificationPermission()) {
            val url = binding.etM3u8Url.text.toString().trim()
            if (url.isNotEmpty()) {
                // 启动后台下载服务
                val title = "M3U8下载 - ${url.substringAfterLast("/").substringBefore(".")}"
                android.util.Log.d("MainActivity", "启动后台下载: $url, 选择码率: ${selectedBitrate?.name}")
                DownloadService.startDownloadWithBitrate(this, url, title, selectedBitrate)
                
                // 设置下载状态（进度将通过广播接收器更新）
                binding.btnDownload.isEnabled = false
                binding.btnDownload.text = "下载中 0%"
                
                // 隐藏下载动画
                binding.lottieDownload.visibility = View.GONE
                binding.lottieDownload.pauseAnimation()
                
                Toast.makeText(this, "已开始后台下载", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.invalid_url), Toast.LENGTH_SHORT).show()
            }
        }
    }
    
}
