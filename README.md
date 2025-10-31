# mpv for Android

[![Build Status](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/mpv-android/mpv-android/actions/workflows/build.yml)

mpv-android is a video player for Android based on [libmpv](https://github.com/mpv-player/mpv).

## Features

* Hardware and software video decoding
* Gesture-based seeking, volume/brightness control and more
* libass support for styled subtitles
* Secondary (or dual) subtitle support
* High-quality rendering with advanced settings (scalers, debanding, interpolation, ...)
* Play network streams with the "Open URL" function
* Background playback, Picture-in-Picture, keyboard input supported

### Library?

mpv-android is **not** a library/module (AAR) you can import into your app.

If you'd like to use libmpv in your app you can use our code as inspiration.
The important parts are [`MPVLib`](app/src/main/java/is/xyz/mpv/MPVLib.kt), [`BaseMPVView`](app/src/main/java/is/xyz/mpv/BaseMPVView.kt) and the [native code](app/src/main/jni/).
Native code is built by [these scripts](buildscripts/).

## Downloads

You can download mpv-android from the [Releases section](https://github.com/mpv-android/mpv-android/releases) or

[<img src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png" alt="Get it on Google Play" height="80">](https://play.google.com/store/apps/details?id=is.xyz.mpv)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/is.xyz.mpv)

**Note**: Android TV is supported, but only available on F-Droid or by installing the APK manually.

## NAS and SMB Support

This fork adds support for browsing and playing files from SMB (Server Message Block) shares, commonly used in NAS (Network Attached Storage) environments.

### Features
- **SMB Folder Browsing**: Connect to SMB servers, browse directories, and play media files directly.
- **Automatic Subtitle Loading**: Scans directories for subtitle files and automatically matches them to videos based on filename.
- **Playlist Management**: Generates playlists from directory contents, with alphabetical sorting and looping support.
- **Proxy Streaming**: Uses an internal HTTP proxy to stream SMB files securely to mpv.

### Usage
1. Open the app and select "Open NAS".
2. Enter your SMB server details (address, username, password).
3. Browse folders and tap a video to start playback with automatic playlist creation.

### Known Issues
- When selecting a video in a folder, the playlist titles may all display the title of the first video instead of their respective correct titles. This is due to title synchronization issues in the playlist management.
- This prevents navigation to videos before the first selected video via buttons, resolved by forcing playlist looping.

### Building
Follow the standard build instructions in [buildscripts/README.md](buildscripts/README.md). Ensure jcifs-ng dependency is included.

---

## NAS 和 SMB 支持

此分支添加了对 SMB (Server Message Block) 共享的支持，通常用于 NAS (网络附加存储) 环境。

### 功能
- **SMB 文件夹浏览**：连接到 SMB 服务器，浏览目录，直接播放媒体文件。
- **自动字幕加载**：扫描目录中的字幕文件，并根据文件名自动匹配到视频。
- **播放列表管理**：从目录内容生成播放列表，支持字母顺序排序和循环播放。
- **代理流媒体**：使用内部 HTTP 代理安全地将 SMB 文件流式传输到 mpv。

### 使用方法
1. 打开应用并选择“Open NAS”。
2. 输入您的 SMB 服务器详情（地址、用户名、密码）。
3. 浏览文件夹并点击视频开始播放，自动创建播放列表。

### 已知问题
- 在文件夹中选择视频时，播放列表标题无论选择哪个视频都是文件夹第一个视频的标题开始，而不是各自正确的标题。
- 导致无法通过按钮导航到第一个点击的视频之前，通过强制开启播放列表循环解决

### 构建
按照 [buildscripts/README.md](buildscripts/README.md) 中的标准构建说明操作。确保包含 jcifs-ng 依赖。
