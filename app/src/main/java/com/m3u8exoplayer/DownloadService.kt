package com.m3u8exoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
        const val ACTION_PROGRESS_UPDATE = "com.m3u8exoplayer.PROGRESS_UPDATE"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_STAGE = "stage"
        const val STAGE_DOWNLOAD = "download"
        const val STAGE_CONVERT = "convert"
        const val STAGE_COMPLETE = "complete"
        
        fun startDownload(context: Context, url: String, title: String) {
            android.util.Log.d("DownloadService", "startDownload called with url: $url")
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
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
    
    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        m3u8Downloader = M3U8Downloader(this)
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("DownloadService", "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "M3U8下载"
                android.util.Log.d("DownloadService", "开始下载: $url")
                startDownload(url, title)
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
                "M3U8下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "显示M3U8下载进度"
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
        if (isDownloading) {
            android.util.Log.d("DownloadService", "已在下载中，忽略重复请求")
            return
        }
        
        isDownloading = true
        android.util.Log.d("DownloadService", "设置下载状态为true")
        
        // 启动前台服务
        val notification = createNotification(title, "准备下载...", 0)
        startForeground(NOTIFICATION_ID, notification)
        android.util.Log.d("DownloadService", "前台服务已启动")
        
        // 开始下载
        downloadJob = CoroutineScope(Dispatchers.IO).launch {
            android.util.Log.d("DownloadService", "开始协程下载")
            m3u8Downloader.downloadM3U8(
                url = url,
                onProgress = { progress ->
                    // 下载阶段：0-100%
                    updateNotification(title, "下载中... $progress%", progress)
                    sendProgressBroadcast(STAGE_DOWNLOAD, progress, "下载中... $progress%")
                },
                onComplete = { message ->
                    // 下载完成，开始转换阶段
                    startConvertPhase(title)
                },
                onError = { error ->
                    updateNotification(title, "下载失败: $error", 0)
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
                updateNotification(title, "转换中... $convertProgress%", convertProgress)
                sendProgressBroadcast(STAGE_CONVERT, convertProgress, "转换中... $convertProgress%")
                kotlinx.coroutines.delay(300) // 转换比下载稍快一些
            }
            
            // 转换完成
            updateNotification(title, "下载完成", 100)
            sendProgressBroadcast(STAGE_COMPLETE, 100, "下载完成")
            
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
    
    private fun sendProgressBroadcast(stage: String, progress: Int, status: String) {
        val intent = Intent(ACTION_PROGRESS_UPDATE).apply {
            putExtra(EXTRA_STAGE, stage)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_STATUS, status)
            // 设置包名确保广播发送到正确的应用
            setPackage(packageName)
        }
        sendBroadcast(intent)
        android.util.Log.d("DownloadService", "发送进度广播: $stage - $progress% - $status")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        downloadJob?.cancel()
        isDownloading = false
    }
}
