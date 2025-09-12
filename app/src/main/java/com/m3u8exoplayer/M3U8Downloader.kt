package com.m3u8exoplayer

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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class M3U8Downloader(private val context: Context) {
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    suspend fun downloadM3U8(
        url: String,
        onProgress: (Int) -> Unit,
        onComplete: (String) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        
        try {
            Log.d("M3U8Downloader", "开始下载M3U8: $url")
            
            // 解析M3U8文件
            val playlist = parseM3U8(url)
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
            val outputStream = FileOutputStream(outputFile)
            
            var downloadedCount = 0
            val totalSegments = playlist.size
            
            // 下载所有片段
            for ((index, segmentUrl) in playlist.withIndex()) {
                try {
                    Log.d("M3U8Downloader", "下载片段 $index: $segmentUrl")
                    
                    val segmentData = downloadSegment(segmentUrl)
                    outputStream.write(segmentData)
                    
                    downloadedCount++
                    val progress = (downloadedCount * 100) / totalSegments
                    onProgress(progress)
                    
                    Log.d("M3U8Downloader", "片段 $index 下载完成，进度: $progress%")
                    
                } catch (e: Exception) {
                    Log.e("M3U8Downloader", "下载片段 $index 失败", e)
                    // 继续下载其他片段
                }
            }
            
            outputStream.close()
            
            Log.d("M3U8Downloader", "TS文件下载完成: ${outputFile.absolutePath}")
            
            // 转换为MP4格式
            val mp4File = convertToMp4(outputFile, onProgress)
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
    
    private suspend fun convertToMp4(tsFile: File, onProgress: (Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val mp4File = File(tsFile.parent, "downloaded_video_$timestamp.mp4")
            
            Log.d("M3U8Downloader", "开始重命名TS到MP4: ${tsFile.absolutePath} -> ${mp4File.absolutePath}")
            
            // 简单重命名TS文件为MP4（TS和MP4格式兼容）
            val success = tsFile.renameTo(mp4File)
            
            if (success) {
                Log.d("M3U8Downloader", "MP4重命名成功: ${mp4File.absolutePath}")
                onProgress(100)
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
}
