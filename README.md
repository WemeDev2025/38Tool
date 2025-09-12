# M3U8 ExoPlayer 项目

基于Google ExoPlayer的Android原生M3U8下载和播放应用。

## 功能特性

- ✅ **M3U8下载**：支持下载M3U8格式的视频文件
- ✅ **实时进度**：显示下载进度和状态
- ✅ **视频播放**：使用ExoPlayer播放下载的视频
- ✅ **原生开发**：基于Android原生开发，性能优异

## 技术栈

- **ExoPlayer 2.19.1**：Google官方媒体播放库
- **Kotlin**：现代Android开发语言
- **MVVM架构**：使用ViewModel和LiveData
- **OkHttp**：网络请求库
- **Material Design**：现代化UI设计

## 项目结构

```
app/
├── src/main/java/com/m3u8exoplayer/
│   ├── MainActivity.kt          # 主Activity
│   ├── MainViewModel.kt         # 主ViewModel
│   └── M3U8Downloader.kt       # M3U8下载器
├── src/main/res/
│   ├── layout/
│   │   └── activity_main.xml    # 主界面布局
│   ├── values/
│   │   ├── strings.xml          # 字符串资源
│   │   ├── colors.xml           # 颜色资源
│   │   └── themes.xml           # 主题资源
│   └── xml/
│       ├── backup_rules.xml     # 备份规则
│       └── data_extraction_rules.xml # 数据提取规则
└── build.gradle                 # 应用级构建配置
```

## 核心功能

### 1. M3U8下载
- 解析M3U8播放列表
- 并发下载视频片段
- 实时进度更新
- 错误处理和重试机制

### 2. 视频播放
- 使用ExoPlayer播放视频
- 支持播放控制（播放/暂停/停止）
- 播放状态监控

### 3. 用户界面
- Material Design风格
- 进度条显示
- 状态信息展示
- 响应式布局

## 使用方法

1. **输入M3U8 URL**：在输入框中输入M3U8播放列表的URL
2. **开始下载**：点击"下载"按钮开始下载M3U8视频
3. **查看进度**：观察进度条和状态信息
4. **播放视频**：下载完成后可以使用播放控制按钮

## 依赖库

```gradle
// ExoPlayer核心库
implementation 'com.google.android.exoplayer:exoplayer:2.19.1'
implementation 'com.google.android.exoplayer:exoplayer-hls:2.19.1'
implementation 'com.google.android.exoplayer:exoplayer-ui:2.19.1'
implementation 'com.google.android.exoplayer:exoplayer-core:2.19.1'

// 网络库
implementation 'com.squareup.okhttp3:okhttp:4.12.0'

// 协程
implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
```

## 权限要求

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

## 开发说明

### 编译项目
由于gradle wrapper jar文件需要从官方下载，建议：
1. 使用Android Studio打开项目
2. 让Android Studio自动下载gradle wrapper
3. 或者手动下载gradle-wrapper.jar文件

### 项目特点
- **原生开发**：使用Android原生技术栈
- **性能优化**：基于ExoPlayer的高性能播放
- **代码简洁**：清晰的MVVM架构
- **易于扩展**：模块化设计，便于功能扩展

## 注意事项

1. 确保设备有网络连接
2. 需要存储权限来保存下载的文件
3. 某些M3U8链接可能需要特殊的请求头
4. 大文件下载可能需要较长时间

## 未来计划

- [ ] 添加下载队列管理
- [ ] 支持断点续传
- [ ] 添加视频缓存功能
- [ ] 支持更多视频格式
- [ ] 添加播放历史记录

## 许可证

MIT License
