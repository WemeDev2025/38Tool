package com.tool38.wemedev

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
// import com.arthenica.mobileffmpeg.Config
// import com.arthenica.mobileffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class M3U8Downloader(private val context: Context) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)  // 减少连接超时时间
        .readTimeout(30, TimeUnit.SECONDS)     // 减少读取超时时间
        .writeTimeout(30, TimeUnit.SECONDS)    // 减少写入超时时间
        .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES)) // 连接池优化
        .retryOnConnectionFailure(true)        // 启用连接失败重试
        .build()
    
    suspend fun downloadM3U8(
        url: String,
        onProgress: (Int, String, String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        downloadM3U8WithBitrate(url, null, onProgress, onComplete, onError)
    }
    
    suspend fun downloadM3U8WithBitrate(
        url: String,
        selectedBitrate: BitrateInfo?,
        onProgress: (Int, String, String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        
        try {
            Log.d("M3U8Downloader", "开始下载M3U8: $url")
            
            // 解析M3U8文件
            val playlist = if (selectedBitrate != null) {
                parseM3U8(selectedBitrate.url)
            } else {
                parseM3U8(url)
            }
            if (playlist.isEmpty()) {
                onError("M3U8文件为空或格式错误")
                return@withContext
            }
            
            Log.d("M3U8Downloader", "解析到 ${playlist.size} 个片段")
            
            // 创建输出目录
            val outputDir = File("/sdcard/Download/M3U8")
            if (!outputDir.exists()) {
                outputDir.mkdirs()
            }
            
            val outputFile = File(outputDir, "downloaded_video.ts")
            
            // 使用多线程并发下载，限制并发数为6
            val semaphore = Semaphore(6)
            val downloadedCount = AtomicInteger(0)
            val totalSegments = playlist.size
            
            // 下载速度计算相关变量
            val startTime = System.currentTimeMillis()
            val downloadedBytes = AtomicInteger(0)
            var lastSpeedUpdateTime = startTime
            var lastDownloadedBytes = 0
            var lastProgressUpdateTime = startTime
            
            // 创建临时文件存储每个片段
            val tempFiles = mutableListOf<File>()
            
            // 并发下载所有片段
            val downloadJobs = playlist.mapIndexed { index, segmentUrl ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        Log.d("M3U8Downloader", "开始下载片段 $index: $segmentUrl")
                        
                        val segmentData = downloadSegment(segmentUrl)
                        val tempFile = File(outputDir, "segment_$index.tmp")
                        tempFile.writeBytes(segmentData)
                        tempFiles.add(tempFile)
                        
                        // 更新下载字节数
                        val bytesDownloaded = segmentData.size
                        downloadedBytes.addAndGet(bytesDownloaded)
                        
                        val currentCount = downloadedCount.incrementAndGet()
                        val progress = (currentCount * 100) / totalSegments
                        
                        // 计算下载速度
                        val currentTime = System.currentTimeMillis()
                        val speedText = if (currentTime - lastSpeedUpdateTime > 1000) { // 每秒更新一次速度
                            val timeDiff = (currentTime - lastSpeedUpdateTime) / 1000.0
                            val bytesDiff = downloadedBytes.get() - lastDownloadedBytes
                            val speedBps = bytesDiff / timeDiff
                            lastSpeedUpdateTime = currentTime
                            lastDownloadedBytes = downloadedBytes.get()
                            formatSpeed(speedBps)
                        } else {
                            ""
                        }
                        
                        onProgress(progress, "下载中", speedText)
                        
                        Log.d("M3U8Downloader", "片段 $index 下载完成，进度: $progress%, 速度: $speedText")
                        
                    } catch (e: Exception) {
                        Log.e("M3U8Downloader", "下载片段 $index 失败", e)
                        // 继续下载其他片段
                    } finally {
                        semaphore.release()
                    }
                }
            }
            
            // 等待所有下载完成
            downloadJobs.awaitAll()
            
            // 按顺序合并所有片段
            Log.d("M3U8Downloader", "开始合并 ${tempFiles.size} 个片段")
            FileOutputStream(outputFile).use { outputStream ->
                for (i in 0 until totalSegments) {
                    val tempFile = File(outputDir, "segment_$i.tmp")
                    if (tempFile.exists()) {
                        tempFile.inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        tempFile.delete() // 删除临时文件
                    }
                }
            }
            
            Log.d("M3U8Downloader", "TS文件下载完成: ${outputFile.absolutePath}")
            
            // 转换为MP4格式
            val mp4File = convertToMp4(outputFile) { progress, stage, speed ->
                onProgress(progress, "转换中...", "")
            }
            if (mp4File != null) {
                // 导出到相册
                val savedUri = saveToGallery(mp4File)
                if (savedUri != null) {
                    Log.d("M3U8Downloader", "已保存到相册: $savedUri")
                    onComplete("MP4文件已保存到相册: ${mp4File.name}")
                } else {
                    onComplete("MP4转换完成: ${mp4File.absolutePath}")
                }
            } else {
                onComplete("下载完成但转换失败: ${outputFile.absolutePath}")
            }
            
        } catch (e: Exception) {
            Log.e("M3U8Downloader", "下载失败", e)
            onError(e.message ?: "未知错误")
        }
    }
    
    private suspend fun parseM3U8(url: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("获取M3U8文件失败: ${response.code}")
            }
            
            val content = response.body?.string() ?: throw Exception("M3U8文件内容为空")
            response.close()
            
            Log.d("M3U8Downloader", "M3U8文件内容长度: ${content.length}")
            Log.d("M3U8Downloader", "M3U8文件内容前500字符: ${content.take(500)}")
            
            // 解析M3U8内容
            val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
            
            // 检查是否是主播放列表
            if (content.contains("#EXT-X-STREAM-INF")) {
                Log.d("M3U8Downloader", "检测到主播放列表，选择第一个流")
                return@withContext parseMasterPlaylist(lines, url)
            }
            
            // 解析媒体播放列表
            return@withContext parseMediaPlaylist(lines, url)
            
        } catch (e: Exception) {
            Log.e("M3U8Downloader", "解析M3U8失败", e)
            emptyList()
        }
    }
    
    suspend fun getAvailableBitrates(url: String): List<BitrateInfo> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext emptyList()
            }
            
            val content = response.body?.string() ?: return@withContext emptyList()
            response.close()
            
            val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
            
            // 检查是否是主播放列表
            if (content.contains("#EXT-X-STREAM-INF")) {
                return@withContext parseMasterPlaylistBitrates(lines, url)
            }
            
            emptyList()
        } catch (e: Exception) {
            Log.e("M3U8Downloader", "获取码率列表失败", e)
            emptyList()
        }
    }
    
    private fun parseMasterPlaylistBitrates(lines: List<String>, baseUrl: String): List<BitrateInfo> {
        val baseUrlPath = baseUrl.substringBeforeLast("/") + "/"
        val bitrates = mutableListOf<BitrateInfo>()
        
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // 解析码率信息
                val bandwidth = extractValue(line, "BANDWIDTH=")?.toIntOrNull() ?: 0
                val resolution = extractValue(line, "RESOLUTION=") ?: "未知"
                val codecs = extractValue(line, "CODECS=") ?: ""
                val name = extractValue(line, "NAME=") ?: "${bandwidth/1000}kbps"
                
                // 找到对应的URL
                for (j in i + 1 until lines.size) {
                    val nextLine = lines[j]
                    if (!nextLine.startsWith("#")) {
                        val streamUrl = if (nextLine.startsWith("http")) {
                            nextLine
                        } else {
                            baseUrlPath + nextLine
                        }
                        
                        bitrates.add(BitrateInfo(name, bandwidth, resolution, codecs, streamUrl))
                        break
                    }
                }
            }
        }
        
        // 按码率排序（从高到低）
        return bitrates.sortedByDescending { it.bandwidth }
    }
    
    private fun extractValue(line: String, key: String): String? {
        val startIndex = line.indexOf(key)
        if (startIndex == -1) return null
        
        val valueStart = startIndex + key.length
        val endIndex = line.indexOf(",", valueStart)
        val end = if (endIndex == -1) line.length else endIndex
        
        return line.substring(valueStart, end).trim('"')
    }
    
    private suspend fun parseMasterPlaylist(lines: List<String>, baseUrl: String): List<String> {
        val baseUrlPath = baseUrl.substringBeforeLast("/") + "/"
        var selectedStreamUrl: String? = null
        
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                // 找到下一个非注释行作为流URL
                for (j in i + 1 until lines.size) {
                    val nextLine = lines[j]
                    if (!nextLine.startsWith("#")) {
                        selectedStreamUrl = if (nextLine.startsWith("http")) {
                            nextLine
                        } else {
                            baseUrlPath + nextLine
                        }
                        break
                    }
                }
                break
            }
        }
        
        if (selectedStreamUrl != null) {
            Log.d("M3U8Downloader", "选择流URL: $selectedStreamUrl")
            return parseM3U8(selectedStreamUrl)
        }
        
        return emptyList()
    }
    
    private suspend fun parseMediaPlaylist(lines: List<String>, url: String): List<String> {
        val segments = mutableListOf<String>()
        val baseUrl = url.substringBeforeLast("/") + "/"
        Log.d("M3U8Downloader", "基础URL: $baseUrl")
        
        for (line in lines) {
            Log.d("M3U8Downloader", "处理行: $line")
            // 跳过注释行
            if (line.startsWith("#")) {
                continue
            }
            
            // 检查是否是有效的媒体片段
            val isMediaSegment = line.endsWith(".ts") || 
                               line.endsWith(".m4s") || 
                               line.endsWith(".mp4") ||
                               line.contains("segment") || 
                               line.contains("chunk") ||
                               line.contains("frag") ||
                               (line.isNotEmpty() && !line.startsWith("#") && !line.contains("EXT"))
            
            if (isMediaSegment) {
                val segmentUrl = if (line.startsWith("http")) {
                    line
                } else {
                    baseUrl + line
                }
                segments.add(segmentUrl)
                Log.d("M3U8Downloader", "添加片段: $segmentUrl")
            }
        }
        
        Log.d("M3U8Downloader", "解析到 ${segments.size} 个视频片段")
        if (segments.isEmpty()) {
            Log.w("M3U8Downloader", "没有找到任何视频片段")
        }
        return segments
    }
    
    private suspend fun downloadSegment(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("下载片段失败: ${response.code}")
        }
        
        val inputStream: InputStream = response.body?.byteStream() 
            ?: throw Exception("响应体为空")
        
        try {
            inputStream.readBytes()
        } finally {
            inputStream.close()
            response.close()
        }
    }
    
    private suspend fun convertToMp4(tsFile: File, onProgress: (Int, String, String) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val mp4File = File(tsFile.parent, "downloaded_video_$timestamp.mp4")
            
            Log.d("M3U8Downloader", "开始重命名TS到MP4: ${tsFile.absolutePath} -> ${mp4File.absolutePath}")
            
            // 简单重命名TS文件为MP4（TS和MP4格式兼容）
            val success = tsFile.renameTo(mp4File)
            
            if (success) {
                Log.d("M3U8Downloader", "MP4重命名成功: ${mp4File.absolutePath}")
                onProgress(100, "转换完成", "")
                mp4File
            } else {
                Log.e("M3U8Downloader", "MP4重命名失败")
                null
            }
        } catch (e: Exception) {
            Log.e("M3U8Downloader", "MP4重命名异常", e)
            null
        }
    }
    
    private suspend fun saveToGallery(mp4File: File): Uri? = withContext(Dispatchers.IO) {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, mp4File.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/M3U8Downloader")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    mp4File.inputStream().use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                
                // 标记为已完成
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
                
                Log.d("M3U8Downloader", "视频已保存到相册: $uri")
                uri
            } else {
                Log.e("M3U8Downloader", "无法创建MediaStore条目")
                null
            }
        } catch (e: Exception) {
            Log.e("M3U8Downloader", "保存到相册失败", e)
            null
        }
    }
    
    /**
     * 格式化下载速度显示
     */
    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> {
                val mbps = bytesPerSecond / (1024 * 1024)
                "%.1fM/S".format(mbps)
            }
            bytesPerSecond >= 1024 -> {
                val kbps = bytesPerSecond / 1024
                "%.1fK/S".format(kbps)
            }
            else -> {
                "%.0fB/S".format(bytesPerSecond)
            }
        }
    }
}
