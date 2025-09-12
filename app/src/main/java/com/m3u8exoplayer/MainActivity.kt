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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.google.android.exoplayer2.Player
import com.m3u8exoplayer.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    
    // 自定义播放器控制器
    private lateinit var customPlayerControlView: CustomPlayerControlView
    private var progressUpdateHandler: Handler? = null
    private var progressUpdateRunnable: Runnable? = null
    
    // 广播接收器
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                DownloadService.ACTION_PROGRESS_UPDATE -> {
                    val stage = intent.getStringExtra(DownloadService.EXTRA_STAGE) ?: ""
                    val progress = intent.getIntExtra(DownloadService.EXTRA_PROGRESS, 0)
                    val status = intent.getStringExtra(DownloadService.EXTRA_STATUS) ?: ""
                    
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
    
    private fun setupUI() {
        // 绑定ExoPlayer到PlayerView
        binding.playerView.player = viewModel.getPlayer()

        // 禁用默认控制器
        binding.playerView.setUseController(false)
        binding.playerView.setControllerAutoShow(false)
        binding.playerView.setControllerHideOnTouch(false)
        binding.playerView.setUseArtwork(false)

        // 初始化自定义控制器
        setupCustomPlayerController()

        // 添加点击播放器显示控制条的功能
        binding.playerView.setOnClickListener {
            toggleCustomController()
        }
        
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
                    
                    Toast.makeText(this, "已开始后台下载", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, getString(R.string.invalid_url), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun setupCustomPlayerController() {
        // 创建自定义控制器
        customPlayerControlView = CustomPlayerControlView(this)
        
        // 设置全屏切换监听器
        customPlayerControlView.setOnFullscreenToggleListener { isFullscreen ->
            // 处理全屏切换逻辑
            handleFullscreenToggle(isFullscreen)
        }
        
        // 将控制器添加到播放器下方
        val parentLayout = binding.playerView.parent as android.view.ViewGroup
        val playerIndex = parentLayout.indexOfChild(binding.playerView)
        parentLayout.addView(customPlayerControlView, playerIndex + 1)
        
        // 初始时隐藏控制器
        customPlayerControlView.visibility = View.GONE
        
        // 设置播放器监听器
        viewModel.getPlayer().addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateCustomController()
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateCustomController()
            }
        })
        
        // 启动进度更新
        startProgressUpdate()
    }
    
    private fun toggleCustomController() {
        if (customPlayerControlView.visibility == View.VISIBLE) {
            customPlayerControlView.visibility = View.GONE
        } else {
            customPlayerControlView.visibility = View.VISIBLE
        }
    }
    
    private fun updateCustomController() {
        customPlayerControlView.updateProgress()
    }
    
    private fun handleFullscreenToggle(isFullscreen: Boolean) {
        if (isFullscreen) {
            // 进入全屏模式
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
            supportActionBar?.hide()
        } else {
            // 退出全屏模式
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            supportActionBar?.show()
        }
    }
    
    private fun startProgressUpdate() {
        progressUpdateHandler = Handler(Looper.getMainLooper())
        progressUpdateRunnable = object : Runnable {
            override fun run() {
                if (customPlayerControlView.visibility == View.VISIBLE) {
                    customPlayerControlView.updateProgress()
                }
                progressUpdateHandler?.postDelayed(this, 1000) // 每秒更新一次
            }
        }
        progressUpdateHandler?.post(progressUpdateRunnable!!)
    }
    
    private fun stopProgressUpdate() {
        progressUpdateRunnable?.let { progressUpdateHandler?.removeCallbacks(it) }
        progressUpdateHandler = null
        progressUpdateRunnable = null
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
        } else {
            Toast.makeText(this, "所有权限已授予，可以开始使用", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "所有权限已授予，可以开始使用", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "需要权限才能下载文件", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    
    
    override fun onDestroy() {
        super.onDestroy()
        
        // 停止进度更新
        stopProgressUpdate()
        
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
            }
            DownloadService.STAGE_CONVERT -> {
                binding.btnDownload.text = "转换中 $progress%"
            }
            DownloadService.STAGE_COMPLETE -> {
                binding.btnDownload.text = "下载完成"
                binding.btnDownload.isEnabled = true
                
                // 显示Toast提示
                Toast.makeText(this@MainActivity, "下载完成", Toast.LENGTH_SHORT).show()
                
                // 自动开始播放
                autoStartPlayback()
                
                // 2秒后恢复按钮状态
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    binding.btnDownload.text = getString(R.string.download)
                }, 2000)
            }
        }
    }
    
    private fun autoStartPlayback() {
        try {
            val url = binding.etM3u8Url.text.toString().trim()
            if (url.isNotEmpty()) {
                // 使用内置ExoPlayer自动播放
                viewModel.playM3U8()

                // 设置自定义播放器
                customPlayerControlView.setPlayer(viewModel.getPlayer())
                
                // 延迟1秒后隐藏自定义控制器
                Handler(Looper.getMainLooper()).postDelayed({
                    customPlayerControlView.visibility = View.GONE
                }, 1000)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "自动播放失败: ${e.message}")
        }
    }
    
}
