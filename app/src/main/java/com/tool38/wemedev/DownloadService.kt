package com.tool38.wemedev

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class DownloadService : Service() {
    
    companion object {
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "download_channel"
        const val ACTION_START_DOWNLOAD = "start_download"
        const val ACTION_STOP_DOWNLOAD = "stop_download"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        
        // 广播相关常量
        const val ACTION_PROGRESS_UPDATE = "com.tool38.wemedev.PROGRESS_UPDATE"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STAGE = "stage"
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_SPEED = "speed"
        const val STAGE_DOWNLOAD = "download"
        const val STAGE_CONVERT = "convert"
        const val STAGE_COMPLETE = "complete"
        
        fun startDownload(context: Context, url: String, title: String) {
            startDownloadWithBitrate(context, url, title, null)
        }
        
        fun startDownloadWithBitrate(context: Context, url: String, title: String, bitrate: BitrateInfo?) {
            android.util.Log.d("DownloadService", "startDownloadWithBitrate called with url: $url, bitrate: ${bitrate?.name}")
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                bitrate?.let { 
                    putExtra("selected_bitrate", it.name)
                    putExtra("bitrate_url", it.url)
                }
            }
            android.util.Log.d("DownloadService", "Starting service with intent: $intent")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.util.Log.d("DownloadService", "Using startForegroundService")
                context.startForegroundService(intent)
            } else {
                android.util.Log.d("DownloadService", "Using startService")
                context.startService(intent)
            }
        }
        
        fun stopDownload(context: Context) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_STOP_DOWNLOAD
            }
            context.startService(intent)
        }
    }
    
    private lateinit var notificationManager: NotificationManager
    private lateinit var m3u8Downloader: M3U8Downloader
    private var downloadJob: Job? = null
    private var isDownloading = false
    
    // 电池优化相关
    private var wakeLock: PowerManager.WakeLock? = null
    private var connectivityManager: ConnectivityManager? = null
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m3u8Downloader = M3U8Downloader(this)
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createNotificationChannel()
        setupWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("DownloadService", "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.download_default_title)
                val bitrateName = intent.getStringExtra("selected_bitrate")
                val bitrateUrl = intent.getStringExtra("bitrate_url")
                
                android.util.Log.d("DownloadService", "开始下载: $url, 码率: $bitrateName")
                
                if (bitrateUrl != null && bitrateName != null) {
                    val bitrate = BitrateInfo(bitrateName, 0, "", "", bitrateUrl)
                    startDownloadWithBitrate(url, title, bitrate)
                } else {
                    startDownload(url, title)
                }
            }
            ACTION_STOP_DOWNLOAD -> {
                android.util.Log.d("DownloadService", "停止下载")
                stopDownload()
            }
        }
        return START_NOT_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    private fun createNotificationChannel() {
        android.util.Log.d("DownloadService", "创建通知渠道: $CHANNEL_ID")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.download_channel_description)
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
            android.util.Log.d("DownloadService", "通知渠道已创建: $CHANNEL_ID")
        } else {
            android.util.Log.d("DownloadService", "Android版本低于O，无需创建通知渠道")
        }
    }
    
    private fun startDownload(url: String, title: String) {
        startDownloadWithBitrate(url, title, null)
    }
    
    private fun startDownloadWithBitrate(url: String, title: String, bitrate: BitrateInfo?) {
        if (isDownloading) {
            android.util.Log.d("DownloadService", "已在下载中，忽略重复请求")
            return
        }
        
        isDownloading = true
        android.util.Log.d("DownloadService", "设置下载状态为true")
        
        // 启动保活机制
        acquireWakeLock()
        
        // 启动前台服务
        val notification = createNotification(title, getString(R.string.preparing_download), 0)
        startForeground(NOTIFICATION_ID, notification)
        android.util.Log.d("DownloadService", "前台服务已启动")
        
        // 开始下载
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            android.util.Log.d("DownloadService", "开始协程下载，码率: ${bitrate?.name}")
            m3u8Downloader.downloadM3U8WithBitrate(
                url = url,
                selectedBitrate = bitrate,
                onProgress = { progress, stage, speed ->
                    // 下载阶段：0-100%
                    val statusText = if (speed.isNotEmpty()) {
                        getString(R.string.downloading_with_speed, progress, speed)
                    } else {
                        getString(R.string.downloading_percent, progress)
                    }
                    updateNotification(title, statusText, progress)
                    sendProgressBroadcast(STAGE_DOWNLOAD, progress, statusText, null, speed)
                },
                onComplete = { message ->
                    // 下载完成，开始转换阶段
                    startConvertPhase(title)
                },
                onError = { error ->
                    updateNotification(title, getString(R.string.download_failed_with_reason, error), 0)
                    // 5秒后停止服务
                    CoroutineScope(Dispatchers.Main).launch {
                        kotlinx.coroutines.delay(5000)
                        stopSelf()
                    }
                }
            )
        }
    }
    
    private fun startConvertPhase(title: String) {
        // 开始转换阶段
        var convertProgress = 0
        val convertJob = CoroutineScope(Dispatchers.IO).launch {
            while (convertProgress < 100) {
                convertProgress += 2
                val converting = getString(R.string.converting_percent, convertProgress)
                updateNotification(title, converting, convertProgress)
                sendProgressBroadcast(STAGE_CONVERT, convertProgress, converting)
                kotlinx.coroutines.delay(300) // 转换比下载稍快一些
            }
            
            // 转换完成
            updateNotification(title, getString(R.string.download_complete), 100)
            // 查找实际下载的文件路径
            val actualFilePath = findActualDownloadedFile()
            sendProgressBroadcast(STAGE_COMPLETE, 100, getString(R.string.download_complete), actualFilePath)
            
            // 3秒后停止服务
            CoroutineScope(Dispatchers.Main).launch {
                kotlinx.coroutines.delay(3000)
                stopSelf()
            }
        }
        
        // 保存转换任务以便可以取消
        downloadJob = convertJob
    }
    
    private fun stopDownload() {
        downloadJob?.cancel()
        isDownloading = false
        releaseWakeLock()
        stopSelf()
    }
    
    private fun createNotification(title: String, content: String, progress: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
        
        // 设置进度条
        if (progress >= 0 && progress <= 100) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true) // 不确定进度
        }
        
        return builder.build()
    }
    
    private fun updateNotification(title: String, content: String, progress: Int) {
        android.util.Log.d("DownloadService", "更新通知: $title - $content - 进度: $progress%")
        val notification = createNotification(title, content, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
        android.util.Log.d("DownloadService", "通知已发送到通知管理器")
    }
    
    private fun sendProgressBroadcast(stage: String, progress: Int, status: String, filePath: String? = null, speed: String? = null) {
        val intent = Intent(ACTION_PROGRESS_UPDATE).apply {
            putExtra(EXTRA_STAGE, stage)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_STATUS, status)
            filePath?.let { putExtra(EXTRA_FILE_PATH, it) }
            speed?.let { putExtra(EXTRA_SPEED, it) }
            // 设置包名确保广播发送到正确的应用
            setPackage(packageName)
        }
        sendBroadcast(intent)
        android.util.Log.d("DownloadService", "发送进度广播: $stage - $progress% - $status${filePath?.let { " - 文件: $it" } ?: ""}${speed?.let { " - 速度: $it" } ?: ""}")
    }
    
    /**
     * 查找实际下载的文件
     */
    private fun findActualDownloadedFile(): String? {
        try {
            // 查找 M3U8 下载目录
            val m3u8Dir = java.io.File("/sdcard/Download/M3U8")
            if (m3u8Dir.exists()) {
                val files = m3u8Dir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ts"))
                }
                if (files != null && files.isNotEmpty()) {
                    // 返回最新文件的路径
                    val latestFile = files.maxByOrNull { it.lastModified() }
                    android.util.Log.d("DownloadService", "找到最新文件: ${latestFile?.absolutePath}")
                    return latestFile?.absolutePath
                }
            }
            
            // 查找 Downloads 目录
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (downloadsDir.exists()) {
                val files = downloadsDir.listFiles { file ->
                    file.isFile && (file.name.endsWith(".mp4") || file.name.endsWith(".ts"))
                }
                if (files != null && files.isNotEmpty()) {
                    // 返回最新文件的路径
                    val latestFile = files.maxByOrNull { it.lastModified() }
                    android.util.Log.d("DownloadService", "找到最新文件: ${latestFile?.absolutePath}")
                    return latestFile?.absolutePath
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "查找下载文件失败: ${e.message}")
        }
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        isDownloading = false
        releaseWakeLock()
    }
    
    /**
     * 设置WakeLock
     */
    private fun setupWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "M3U8Downloader::DownloadWakeLock"
        )
    }
    
    /**
     * 获取WakeLock，防止CPU休眠
     */
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(10*60*1000L /*10 minutes*/)
                android.util.Log.d("DownloadService", "WakeLock已获取")
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "获取WakeLock失败", e)
        }
    }
    
    /**
     * 释放WakeLock
     */
    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                android.util.Log.d("DownloadService", "WakeLock已释放")
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "释放WakeLock失败", e)
        }
    }
    
    /**
     * 检查网络连接状态
     */
    private fun isNetworkAvailable(): Boolean {
        return try {
            val network = connectivityManager?.activeNetwork ?: return false
            val capabilities = connectivityManager?.getNetworkCapabilities(network) ?: return false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } catch (e: Exception) {
            android.util.Log.e("DownloadService", "检查网络状态失败", e)
            false
        }
    }
}
