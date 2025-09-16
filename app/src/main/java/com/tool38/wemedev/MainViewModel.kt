package com.tool38.wemedev

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val context = getApplication<Application>()
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(context).build()
    }
    
    private val _progress = MutableLiveData<Int>()
    val progress: LiveData<Int> = _progress
    
    private val _status = MutableLiveData<String>()
    val status: LiveData<String> = _status
    
    private val _isDownloading = MutableLiveData<Boolean>()
    val isDownloading: LiveData<Boolean> = _isDownloading
    
    private val _currentUrl = MutableLiveData<String>()
    val currentUrl: LiveData<String> = _currentUrl
    
    private val _isPlaying = MutableLiveData<Boolean>()
    val isPlaying: LiveData<Boolean> = _isPlaying
    
    private val m3u8Downloader = M3U8Downloader(context)
    
    // 进度持久化
    private val prefs: SharedPreferences = context.getSharedPreferences("download_progress", Context.MODE_PRIVATE)
    
    // MediaSession for system media controls
    private var mediaSession: MediaSessionCompat? = null
    
    init {
        setupExoPlayer()
        setupMediaSession()
        restoreProgress()
    }
    
    private fun restoreProgress() {
        // 恢复保存的进度
        val savedProgress = prefs.getInt("progress", 0)
        val savedStatus = prefs.getString("status", "就绪")
        val savedIsDownloading = prefs.getBoolean("isDownloading", false)
        val savedUrl = prefs.getString("currentUrl", "")
        
        _progress.value = savedProgress
        _status.value = savedStatus ?: "就绪"
        _isDownloading.value = savedIsDownloading
        _currentUrl.value = savedUrl ?: ""
        
        android.util.Log.d("MainViewModel", "恢复进度: $savedProgress%, 状态: $savedStatus, 下载中: $savedIsDownloading")
    }
    
    private fun saveProgress(progress: Int, status: String, isDownloading: Boolean, url: String? = null) {
        prefs.edit().apply {
            putInt("progress", progress)
            putString("status", status)
            putBoolean("isDownloading", isDownloading)
            if (url != null) {
                putString("currentUrl", url)
            }
            apply()
        }
    }
    
    private fun setupMediaSession() {
        android.util.Log.d("MainViewModel", "设置MediaSession")
        mediaSession = MediaSessionCompat(context, "M3U8Player").apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            
            val callback = object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    android.util.Log.d("MainViewModel", "MediaSession onPlay")
                    exoPlayer.play()
                }
                
                override fun onPause() {
                    android.util.Log.d("MainViewModel", "MediaSession onPause")
                    exoPlayer.pause()
                }
                
                override fun onStop() {
                    android.util.Log.d("MainViewModel", "MediaSession onStop")
                    exoPlayer.stop()
                }
            }
            setCallback(callback)
            isActive = true
            android.util.Log.d("MainViewModel", "MediaSession已激活")
        }
    }
    
    private fun updateMediaSessionMetadata(title: String, artist: String = "M3U8播放器") {
        android.util.Log.d("MainViewModel", "更新MediaSession元数据: $title")
        mediaSession?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "M3U8视频")
                .build()
        )
    }
    
    private fun updateMediaSessionPlaybackState(state: Int) {
        android.util.Log.d("MainViewModel", "更新MediaSession播放状态: $state")
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, 0, 1.0f)
            .build()
        mediaSession?.setPlaybackState(playbackState)
    }
    
    private fun setupExoPlayer() {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        _status.value = "空闲"
                        updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    }
                    Player.STATE_BUFFERING -> {
                        _status.value = "缓冲中..."
                        updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_BUFFERING)
                    }
                    Player.STATE_READY -> {
                        _status.value = "准备就绪"
                        // 准备就绪后自动开始播放
                        if (!exoPlayer.isPlaying) {
                            exoPlayer.play()
                        }
                        updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    }
                    Player.STATE_ENDED -> {
                        _status.value = "播放结束"
                        updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
                    }
                }
            }
            
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    _status.value = "正在播放"
                    updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
                } else {
                    _status.value = "暂停播放"
                    updateMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
                }
            }
            
            override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                _status.value = "播放错误: ${error.message}"
            }
        })
    }
    
    fun setCurrentUrl(url: String) {
        _currentUrl.value = url
        saveProgress(_progress.value ?: 0, _status.value ?: "就绪", _isDownloading.value ?: false, url)
    }
    
    fun downloadM3U8(url: String) {
        viewModelScope.launch {
            _isDownloading.value = true
            _status.value = "开始下载..."
            _progress.value = 0
            saveProgress(0, "开始下载...", true, url)
            
            try {
                withContext(Dispatchers.IO) {
                    m3u8Downloader.downloadM3U8(
                        url = url,
                        onProgress = { progress, stage, speed ->
                            _progress.postValue(progress)
                            val statusText = if (speed.isNotEmpty()) {
                                "下载中 $progress% $speed"
                            } else {
                                "下载中... $progress%"
                            }
                            saveProgress(progress, statusText, true, url)
                        },
                        onComplete = { filePath ->
                            _status.postValue("下载完成: $filePath")
                            _isDownloading.postValue(false)
                            saveProgress(100, "下载完成: $filePath", false, url)
                        },
                        onError = { error ->
                            _status.postValue("下载失败: $error")
                            _isDownloading.postValue(false)
                            saveProgress(_progress.value ?: 0, "下载失败: $error", false, url)
                        }
                    )
                }
            } catch (e: Exception) {
                _status.value = "下载异常: ${e.message}"
                _isDownloading.value = false
                saveProgress(_progress.value ?: 0, "下载异常: ${e.message}", false, url)
            }
        }
    }
    
    fun playM3U8() {
        try {
            // 获取当前URL
            val currentUrl = _currentUrl.value
            if (currentUrl.isNullOrEmpty()) {
                _status.value = "请先输入M3U8链接"
                saveProgress(_progress.value ?: 0, "请先输入M3U8链接", _isDownloading.value ?: false, currentUrl)
                return
            }
            
            // 创建媒体项
            val mediaItem = MediaItem.fromUri(currentUrl as String)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            _status.value = "开始播放: $currentUrl"
            
            // 更新MediaSession元数据
            val title = currentUrl.substringAfterLast("/").substringBefore(".")
            updateMediaSessionMetadata(title, "M3U8播放器")
            
            saveProgress(_progress.value ?: 0, "开始播放: $currentUrl", _isDownloading.value ?: false, currentUrl)
        } catch (e: Exception) {
            _status.value = "播放失败: ${e.message}"
            saveProgress(_progress.value ?: 0, "播放失败: ${e.message}", _isDownloading.value ?: false, _currentUrl.value)
        }
    }
    
    fun pauseM3U8() {
        try {
            exoPlayer.pause()
            _status.value = "暂停播放"
        } catch (e: Exception) {
            _status.value = "暂停失败: ${e.message}"
        }
    }
    
    fun stopM3U8() {
        try {
            exoPlayer.stop()
            _status.value = "停止播放"
        } catch (e: Exception) {
            _status.value = "停止失败: ${e.message}"
        }
    }
    
    fun togglePlayPause() {
        try {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                // 如果播放器没有媒体项，先设置当前URL
                val currentUrl = _currentUrl.value
                if (currentUrl.isNullOrEmpty()) {
                    _status.value = "请先输入M3U8链接"
                    return
                }
                
                if (exoPlayer.mediaItemCount == 0) {
                    val mediaItem = MediaItem.fromUri(currentUrl)
                    exoPlayer.setMediaItem(mediaItem)
                    exoPlayer.prepare()
                    
                    // 更新MediaSession元数据
                    val title = currentUrl.substringAfterLast("/").substringBefore(".")
                    updateMediaSessionMetadata(title, "M3U8播放器")
                }
                exoPlayer.play()
            }
        } catch (e: Exception) {
            _status.value = "切换播放状态失败: ${e.message}"
        }
    }
    
    fun getPlayer(): ExoPlayer = exoPlayer
    
    override fun onCleared() {
        super.onCleared()
        exoPlayer.release()
        mediaSession?.release()
    }
}
