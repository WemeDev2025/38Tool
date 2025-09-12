package com.m3u8exoplayer

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
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
                viewModel.setCurrentUrl(s?.toString()?.trim() ?: "")
            }
        })
        
        
        // 下载按钮启动后台下载
        binding.btnDownload.setOnClickListener {
            if (checkDownloadPermissions() && checkNotificationPermission()) {
                val url = binding.etM3u8Url.text.toString().trim()
                if (url.isNotEmpty()) {
                    // 启动后台下载服务
                    val title = "M3U8下载 - ${url.substringAfterLast("/").substringBefore(".")}"
                    android.util.Log.d("MainActivity", "启动后台下载: $url")
                    DownloadService.startDownload(this, url, title)
                    
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
        AlertDialog.Builder(this)
            .setTitle("需要所有文件访问权限")
            .setMessage("为了下载M3U8文件，需要授予所有文件访问权限。\n\n点击确定将跳转到设置页面，请开启\"所有文件访问权限\"。")
            .setPositiveButton("去设置") { _, _ ->
                openManageStorageSettings()
            }
            .setNegativeButton("取消") { _, _ ->
                Toast.makeText(this, "需要文件访问权限才能下载文件", Toast.LENGTH_LONG).show()
            }
            .setCancelable(false)
            .show()
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
    
}
