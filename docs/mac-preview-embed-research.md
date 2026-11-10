# macOS 视频预览嵌入 — 技术调研

> 背景：Windows 版把 ffplay 子窗口嵌入应用内（SetParent 挂到应用窗口，日志解析进度、拖动跟随）。
> mac 移植版当前行为：ffplay **独立窗口**播放（嵌入逻辑在 `FfplayPlayer.embedWindow` 中对非 Windows
> 直接放行为「已在独立窗口播放」）。本文档分析两条嵌入路线。

## 现状回顾

- `FfplayPlayer` 用 ProcessBuilder 拉起 ffplay 子进程，解析 `-loglevel info -stats` 的 stdout
  获取播放进度/结束事件；
- Windows 上 `FfplayWindowFx.findWindowForProcess(pid)` 轮询找到 ffplay 窗口 → `SetParent` 挂载；
- mac 上 ffplay 的窗口是 AppKit `NSWindow`（由 SDL2/ffmpeg 内部创建），AWT 无法直接命中。

## 方案 A：JNA 调 AppKit，把 ffplay 的 NSWindow 挂进应用窗口

### 思路
1. 用 CGWindowList 枚举定位 ffplay 的 `NSWindow`（按 owner 进程 pid 过滤）；
2. 取应用主窗口的 `contentView`；
3. `[ffplayWindow setParentWindow:mainNSWindow]`（NSWindow.addChildWindow）或
   `[ffplayView removeFromSuperview]; [contentView addSubview:ffplayView]`。

### 风险与难点
| 难点 | 说明 |
|---|---|
| **主线程限制** | 所有 AppKit 窗口操作必须在 **OS 主线程**执行。Java EDT ≠ OS 主线程（macOS AWT 的 AppKit 事件循环跑在独立线程）。JNA 直调会随机崩溃或行为异常。需要 `dispatch_async(dispatch_get_main_queue(), ...)` 派发，而创建 C block 需要 JNI 原生代码——JNA 无法直接构造 block。 |
| **拿 NSWindow 指针** | 需从 CGWindowID 映射到 NSWindow。公开 API 无此映射，常用手段：`CGWindowListCopyWindowInfo` 拿 windowNumber → `[NSApp windowWithWindowNumber:]`（私有/半公开）。 |
| **进程边界** | ffplay 是**独立进程**，它的 NSWindow 属于另一个 NSApplication。跨 app 的 addChildWindow 在 macOS 上行为未定义，窗口层级/焦点/关闭顺序都可能出现怪象。 |
| **生命周期** | ffplay 进程退出/崩溃时需及时摘除子窗口，否则应用出现「幽灵窗口」或崩溃。 |
| **全屏/缩放** | 用户拖动主窗口时需同步子窗口 frame，需监听 NSWindowDidChangeFrameNotification。 |

### 结论
技术上可行但工程风险高：跨进程窗口挂载在 AppKit 层就是灰色地带，加上 Java 侧无法直接跑
main-queue 代码。**需要原生 JNI dylib 支持**（dispatch_async 到主队列），预计 3-5 天，稳定性
无法保证。**不推荐首选**。

## 方案 B：JavaFX MediaView 直播流（推荐起步路线）

### 思路
放弃 ffplay，视频预览改用 **JavaFX MediaPlayer**（项目已依赖 JavaFX 17，`VideoPreviewOverlay`
已用 JFXPanel 承载 MediaView 的先例）。直接把 HTTP(S) 直链/HLS(m3u8) 喂给 MediaPlayer：

```kotlin
val media = Media(url)            // 支持 http(s)/file；mac 上 HLS 依赖系统框架
val player = MediaPlayer(media)
val view = MediaView(player)      // 挂进现有 JFXPanel → SwingPanel
player.play()
```

### 优势
- **同进程**渲染，无跨进程窗口操作，无主线程问题（JavaFX 有自己的渲染线程 + FX 应用线程）；
- 现有 `VideoPreviewOverlay` 已有完整的 MediaView 挂载、窗口嵌入、控制条、全屏逻辑（Windows 上
  在用的就是它）——mac 复用度高；
- 暂停/seek/音量全部走 MediaPlayer API，比解析 ffplay stdout 日志可靠得多。

### 风险与难点
| 难点 | 说明 |
|---|---|
| **编解码格式** | JavaFX Media 依赖平台 GStreamer 管线，mac 上 H.264/AAC 支持良好，但 **HEVC/H.265 不支持**（Windows 同样不支持）。部分网盘高清源是 HEVC → 需要降级逻辑。 |
| **HLS 直播** | JavaFX 支持 HLS（m3u8），但加密 HLS（AES-128 with key server）可能失败。 |
| **协议兼容** | 某些网盘直链需要 Referer/Cookie 头——JavaFX Media 的 HTTP 层不支持自定义请求头！需要本地代理中转（项目已有 `com.sun.net.httpserver` 回环代理基础设施，见 jdk.httpserver 模块引用）。 |
| **Live 图/多音轨** | 微博 Live 图、B 站多音轨 DASH 不支持——这些场景回退 ffplay 独立窗口或内置下载器。 |

### 结论
**推荐路线**：JavaFX MediaView 覆盖 80% 场景（普通 mp4/flv 直链 + HLS），其余场景（HEVC、加密
HLS、Live 图）回退「ffplay 独立窗口」或提示下载后本地播放。工作量估计 **1-2 天**（复用现有
VideoPreviewOverlay 基础设施，加 mac 分支与本地代理中转）。

## 方案 C（补充）：VLCJ（libVLC 绑定）

libVLC 几乎支持所有格式（含 HEVC）、自定义请求头、跨平台一致。但需要分发 VLC 原生库
（~100MB）或依赖用户安装，且与现有 JavaFX 渲染管线并存需要额外窗口嵌入工作。**除非方案 B
的格式兼容性被证明不足，否则不考虑**。

## 推荐执行顺序

1. 先做方案 B 的本地代理中转（复用现有回环代理）+ MediaView mac 分支（1 天）
2. 用夸克/B 站真实直链验证 H.264/HLS 场景（半天）
3. HEVC/加密流回退逻辑（半天）
4. （可选）后续若要「真·边下边播」，再评估方案 A + JNI dylib
