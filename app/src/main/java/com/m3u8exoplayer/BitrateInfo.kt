package com.m3u8exoplayer

data class BitrateInfo(
    val name: String,           // 如 "720p", "480p"
    val bandwidth: Int,         // 码率值
    val resolution: String,     // 如 "1280x720"
    val codecs: String,         // 编码信息
    val url: String            // 对应的M3U8 URL
) {
    override fun toString(): String {
        return "$name (${resolution}) - ${bandwidth/1000}kbps"
    }
}
