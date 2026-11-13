# YunX-Desktop-For-Mac 三个遗留问题 — 完整交接文档（供 GPT 处理）

> 生成时间：2026-11-11
> 仓库：/Volumes/ZhiTai-iPlus7100/my-git/YunX-Desktop-For-Mac（分支 `mac-port`，最新提交见 git log）
> 构建命令：`/Users/penn/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle :desktop:packageDmg`
> DMG 自动产出到 `desktop/build/compose/binaries/main/dmg/` 并复制到 `~/Downloads/`
> 运行日志：`~/.yunx-desktop/files/yunx-desktop.log`
> 技术栈：Kotlin + Compose Multiplatform 1.7.3（Desktop/JVM）+ JCEF 132.3.1 + JNA 5.14，jpackage 打包（未签名），编译/测试/运行/打包统一 JDK 21（字节码 21）/ CI macos-14

---

## 问题 1：DMG 安装盘样式难看（裸 Finder 视图，无美化布局）

### 现象
打开 DMG 后是 Finder 默认视图：两个小图标挤在左上角，白底无布局。用户期望的是标准 mac 安装盘样式：灰底/带背景图、app 与 Applications 并排居中、128px 图标。

### 已实施的样式与交付修复

1. jpackage 初始 DMG 只含 `.app`；之前的重建脚本还重复创建 `Applications`，触发 `create-dmg --app-drop-link` 失败，而静默降级把原始布局继续交付。
2. 当前脚本只由 `create-dmg` 创建一次 Applications 链接，设置灰色背景、白色箭头、128px 图标与固定位置；无降级，失败即返回非零并保留原 DMG。
3. Gradle 在 `packageDmg` 输出生成后先重建并验证同一个 DMG，再复制到 Downloads；打包/样式失败不会分发旧的或未样式化产物。
4. 实测重建 DMG 并重新挂载：`.DS_Store` 含持久化图标位置 `(160,200)` / `(480,200)`、`iconSize=128`，Applications 链接、背景文件均正确；`hdiutil verify` checksum 有效。
5. 故障注入确认 create-dmg 失败时原 DMG checksum 不变；隔离 Gradle fixture 确认样式任务失败时下载目录内已有 DMG 不被覆盖。`packageDmg` 与 `packageDistributionForCurrentOS` dry-run 图均通过。

### 当前验证边界

- 最终工作区执行 `:desktop:packageDmg` 时，Gradle 成功生成 jpackage 原始 DMG 并验证内嵌 Java 21.0.8，但样式脚本在 `hdiutil attach` 收到系统“权限被拒绝”后退出；Gradle 正确阻止复制到 Downloads。
- Orca worker 在同一机器上对隔离 DMG 完成了真实重建、重新挂载与 checksum 校验；当前执行环境未能对最终 jpackage DMG 做完整样式化或 Finder 视觉验收。需在允许 hdiutil 挂载并允许 Finder 自动化的 macOS 会话重跑 `:desktop:packageDmg`。


## 问题 2：磁力链接下载失败/报错 502

### 现象分解（三个独立子问题，均已查明）

#### 2a. 解析页贴磁力报「解析失败 502」— 根因：口令服务宕机 + 解析页不识别磁力

- 解析页 `startResolve`（ResolveViewModel.kt:886）只认网盘分享链接/视频链接/口令/GitHub；
- 磁力贴进去 → `ShareLinkParser.parse` 返回 null → 走口令服务兜底（先迅雷后星海）→ **星海/口令服务 `yunx.cyqawa.os.kg` 正在宕机（HTTP 502）** → 报「解析失败（HTTP 502）」——这个 502 是上游服务宕机，不是磁力解析；
- **已修复**（`a344a80`）：`startResolve` 入口加 `P2PLink.matches(link)` 判断（磁力/ed2k 识别，MagnetLink.kt 的 `P2PLink` object），命中即调 `startP2PDirectDownload`：构造 `DownloadLink(fid="p2p:direct", filename=磁力dn=字段, downloadUrl=链接)` 设 `downloadLink` 状态 → 自动弹下载确认框 → `startDownload` 按 url 前缀路由（magnet:→MAGNET，ed2k:→ED2K）→ 引擎执行。
- 相关文件：`ResolveViewModel.kt`（startResolve / startP2PDirectDownload / startDownload）、`MagnetLink.kt`（P2PLink.matches / displayName）。

#### 2b. 引擎「启动超时 60s」死循环 — 根因：Gatekeeper 校验 + 轮询超时后杀进程重排队

- mac 上未签名内核首次运行触发 **Gatekeeper 安全校验**（实测 20-60s+，期间进程存活但不监听端口）；
- 旧逻辑：`awaitReady()` 轮询 60s 超时 → 杀进程 → 重启 → **重新排队校验** → 死循环（用户连续三次超时）；
- **已修复**（`43fd961`）：`READY_TIMEOUT_MS` 20s→60s；`awaitReady` 进程存活即继续轮询（不提前杀），30s 未就绪记日志提示；失败文案带重试指引；
- **注意**：`GopeedEngine.kt` 的 HTTP client 已是 `Proxy.NO_PROXY`（localhost 请求不走代理），路由正常；残留的多个 gopeed 进程来自超时后未清理（见 killStaleProcess 只按 pidFile 清理，pid 文件被删过就失效）。

#### 2c. 引擎自动路由后的 UI 残留 — 已修复

- 引擎管理页两个卡片的「切换到此引擎」按钮已移除（`0686ca3`），改为职责徽章（内置卡「HTTP 任务」/ Gopeed 卡「P2P 任务」）；
- BT 连接设置区（DHT + tracker）显示条件从 `currentEngine == GOPEED`（新语义下恒 false → 设置消失）改为 `GopeedEngine.isInstalled()`（`7c0ee23`）；
- 引擎状态文案「已导入，未启动」→「已就绪（按需自动启动）」。

### 遗留：磁力下载本身的可用性

- 引擎链路已全通（实测：任务 13 成功交给 Gopeed，engineId aAk_lmsR7H，状态 running）；
- 用户测试的两个磁力（btih b41604b5 / 0329c095）在 DHT 网络**无活跃 peers**——冷门资源，任何下载工具都下不动，非软件 bug；
- 可选改进：无 peers 5 分钟提示「暂无做种者」；引擎支持手动添加 tracker（BT 连接设置区已有管理入口）。

---

## 问题 2.5：UC 网盘文件「分享」报错（下载正常 / 文件夹分享正常）

### 现象

UC 网盘单个视频文件分享曾报错，文件夹分享正常。此前分享失败无持久日志，无法确认现场根因。

### 已实施的协议修正与诊断（2026-11-11）

- `UCApi.createShare` 的异步任务轮询原来每次都固定发 `retry_index=0`；现在依次递增，仍为最多 15 次、每次间隔 1 秒。
- 原逻辑将 `finished_at>0 || status==2` 均视为成功，且吞掉轮询错误；现在只有 `status==2` 返回分享 ID，失败状态立即报告。HTTP、业务码、JSON、缺少 task/share ID、详情 URL 缺失都会保留明确阶段和错误。
- 单项、批量及视频下载内部分享入口均有持久化关联日志；记录事件、轮次、任务状态、耗时及脱敏错误，不记录 Cookie、文件名/FID、服务端 ID、分享 URL 或提取码。筛选 `I/UCShare`，详见 [UC 诊断说明](uc-share-diagnostics.md)。
- 第三方 UC 客户端实现 `ctwj/urldb` 的 `uc_pan.go` 与 retry_index 递增和 status 2 完成逻辑相符；这是协议交叉证据，不是 UC 官方规范，也不能证明它就是本次真实单视频失败的现场原因。
- 未增加创建重试或延长 15 秒预算，避免延时掩盖服务端拒绝或重复创建分享。

### 验证与待验收

- `UCShareTest`：9 项测试通过；本地固定响应，不连接 UC；覆盖 retry_index、pending→成功、终态失败、HTTP/JSON/业务错误、请求字段及详情响应。
- 隔离日志检查确认 UCShare 事件持久化，测试 Cookie 未泄露。`git diff --check` 通过。
- 真实 UC 账号复测仍需执行：单项普通文件、单项视频、多选一项、文件夹、多文件及带提取码。以 operation 关联号及任务状态判断残余服务端问题。
- 代码和协议参考不足以证明单文件报错的真实服务端根因；不要把“已修协议”表述成“真实场景已确认修复”。

---

## 问题 3：应用意外闪退（输入法与 Java2D Metal 两类崩溃）

> 本节主体记录的是 2026-11-11 的 AWTView/输入法 SIGTRAP。2026-11-13 又收到一份**不同崩溃签名/路径**的
> Java2D Metal 崩溃，见下面第一小节；不要把 2026-11-11 的 JDK 21 升级或旧补丁对应关系当作它的解释。

### 2026-11-13 新增：Java2D Metal 崩溃（与输入法 SIGTRAP 不同崩溃签名/路径）

- 报告：`/Users/penn/Library/Application Support/orca/agent-session-attachments/f11a5891-6373-4f2f-939d-b1e72ef31c4e/orca-paste-1794535856937-908d9832-446c-4336-a9de-0fcbdbabb15c.txt`
- 时间/进程：2026-11-13 10:03:43，`YunX-Desktop` 1.3.3（安装包内嵌 Java 21.0.8），崩溃前运行约 28 分钟。
- 崩溃：`EXC_BAD_ACCESS (SIGABRT)`，Thread 36 `Java: Java2D Queue Flusher`：
  `MTLPipelineStatesStorage dealloc` → `MTLContext dealloc` → `MTLGC_DestroyMTLGraphicsConfig` →
  `MTLRenderQueue_flushBuffer`。这是 Java2D 的 Metal 管线，不是 `AWTView selectedRange`。
- 现场限制：未找到对应 `hs_err`；应用日志显示 JCEF 初始化成功，且“上次未正常退出”记录不能证明 JCEF 是根因。
- 缓解（未证明根除）：macOS 的 run/jpackage 启动参数加 `-Dsun.java2d.metal=false`，让
  `sun.java2d.MacOSFlags` 选 OpenGL/CGL。JDK 源码明确：`CGLGraphicsConfig.isCGLAvailable()` 为 false 时
  仍会回退 Metal，因此必须用实际运行证据（GraphicsConfiguration/surface 类、线程栈）验收。
  Skiko 的 `skiko.renderApi` 独立，本次不关闭 Skiko/Chromium GPU，不更换 JDK 21，也不升级 Compose/JCEF。
- 边界：没有刻意制造堆破坏复现；不能据此宣称 28 分钟量级的长期故障已消除，需新包在实际使用中观察是否仍出现 MTL 栈。

### 2026-11-13 后续：构建、测试、运行、打包统一 JDK 21

- 当前 `kotlin.jvmToolchain(21)`，Kotlin/Java 字节码目标为 21；macOS 仍保留
  `-PruntimeJavaHome` / `YUNX_RUNTIME_JAVA_HOME` / 自动发现 JDK 21 的选择与
  `release` 主版本、`java/javac/jlink/jpackage` 校验，`-Dsun.java2d.metal=false` 保留。
- `release.yml` 只安装 JDK 21，并同时作为编译、测试、jlink、jpackage 运行时；
  `pr-portable.yml` 与 Windows `jdk-setup.ps1`/`run.ps1` 同步到 21。`jdk-setup.ps1`
  以 `release` 的 `JAVA_VERSION` 强制主版本 21，并检查 java/javac/jlink（打包时另查 jpackage），
  避免仅有 JRE 或 17/25 被误用；固定下载地址为 Microsoft 官方
  `https://aka.ms/download-jdk/microsoft-jdk-21-windows-x64.zip`。
- JavaFX 仍为独立的 17.0.13 四个模块，本次只做类加载验证，不代表视频播放通过；
  Compose/Skiko/JCEF 未升级。
- 本轮验证：JDK 21 执行 Gradle 8.13，强制重编译后 `MainKt` 与代表性测试类均为
  `major version: 65`；测试 worker 实际命令使用 `jdk-21.jdk`；21 类 / 91 个自动化测试通过；
  `verifyPackagedRuntime` 内嵌 Java 21.0.8 且 cfg 含唯一 `-Dsun.java2d.metal=false`。
  打包 launcher smoke：100 轮 AWT 均为 CGLGraphicsConfig、无 MTL Java 帧；3 轮 Compose 窗口内
  JCEF 本地 HTTP 200 加载/关闭全部成功（wall 16.13s）。JavaFX `Scene/Media/MediaPlayer/MediaView/JFXPanel`
  仅 `Class.forName(load-only)` 通过，未启动 JavaFX toolkit、未播放。
- 本文件 2026-11-11 的 JDK 17 编译/打包证据保留为历史记录；它不再描述当前构建配置。
  Windows PowerShell 新逻辑已静态核对调用名/引用，但未在本机执行；统一字节码 21 也不等于 Metal 崩溃根除。

### 崩溃签名（~/Library/Logs/DiagnosticReports/YunX-Desktop-2026-11-11-{163338,185815,190454,193355,200229,202103}.ips，共 6 份，栈完全一致）

```
Exception Type:  EXC_BREAKPOINT (SIGTRAP)
崩溃线程: Thread 0（主线程，含输入法线程 com.apple.inputmethod.SCIM）
栈顶:
  2  +[NSException exceptionWithName:reason:userInfo:]
  3  libawt_lwawt.dylib  -[AWTView selectedRange] + 868     ← 崩溃点
  4  AppKit              -[NSTextInputContext selectedRangeWithCompletionHandler:]
  ...
  IMKInputSession_Modern → selectedRange_withCompletionHandler → ...
触发:  macOS 输入法系统（TSM）向 AWTView 请求 selectedRange 时,
      AWTView 抛出 NSException → NSApplication reportException → 主线程崩溃
```

### 根因

**当前定位为 AWT 与 macOS 输入法交互的 native 崩溃，采用 JDK 21 运行时升级缓解，仍需新包复测**：
- Compose Desktop 的 ComposeWindow 内嵌 AWTView 作为文本输入目标；
- 中文输入法（SCIM/搜狗等）通过 TSM/XPC 请求 `selectedRange`；
- 崩溃报告显示 AWTView 在输入法查询状态时抛出 `NSException`；具体前置状态尚未通过独立最小复现确认；
- NSApplication 未捕获 → `-[NSApplication _crashOnException:]` → **主线程 EXC_BREAKPOINT 崩溃**；
- 原文列出的 OpenJDK bug 编号及“某补丁版本已修复”的对应关系未核实，不据此保证升级后消失；以新包实际 runtime 和输入法复测为准。

### 触发场景（用户日志时间线）
- 粘贴磁力到输入框（输入法激活）→ 切换输入法/焦点 → 崩溃；
- 20:02 / 20:21 两份新报告显示触发点是**输入法菜单构建**（栈中出现 `utTryToSetupInputMethodMenu` → `IMKInputSessionGetMenuIntoMenuWithCompletionHandler`）——即**点击输入法菜单/切换输入法时**，输入法系统也会向 AWTView 请求 selectedRange，同样崩。意味着不只是打字，任何激活输入法面板的操作都可能触发。

### 已实施的打包配置修复（未提交，2026-11-11）

- `desktop/build.gradle.kts` 保留 `jvmToolchain(17)`；macOS 的 Compose `application.javaHome`
  显式指向 JDK 21，覆盖 `-PruntimeJavaHome` 优先，其次 `YUNX_RUNTIME_JAVA_HOME`，最后使用 Gradle 已安装工具链发现。
  校验 JDK 的 `release` 主版本和 `java/jlink/jpackage`，防止本地 Gradle 运行在 17/25 时把错误 JRE 带入发布包。
- `.github/workflows/release.yml` 分别安装编译 JDK 17 与运行时 JDK 21，把后者的输出路径传给 Gradle；
  Windows PR 便携包流程保持原有 Java 17，不属于本次 macOS 崩溃修复范围。
- `:desktop:verifyPackagedRuntime` 创建 app image 后读取实际内嵌 `release`，要求 Java 21；
  `:desktop:packageDmg` 自动先执行该校验，再生成 DMG。使用方法与选择优先级见 `docs/BUILD.md`。
- 本机已安装 17.0.16 与 21.0.8。构建结果不能代替实际输入法验收：请用新包中文输入、粘贴文本、切换输入法菜单及焦点，
  检查 `DiagnosticReports` 是否新增同栈报告；本次没有关闭用户应用、替换已安装应用或宣称 native 崩溃已彻底解决。

### 本次验证证据（2026-11-11）

- `JAVA_HOME=<本机 JDK17> ./gradlew :desktop:compileKotlin :desktop:verifyPackagedRuntime --offline --info`：成功（56s）；
  Kotlin 日志 `jdkHome` 是 `jdk-17.jdk/Contents/Home`，jpackage 可执行文件来自 `jdk-21.jdk/Contents/Home/bin/jpackage`。
- 使用同一 Java 17 Gradle 环境强制 `:desktop:createRuntimeImage --rerun-tasks --offline --info`：成功（7s），
  日志确认执行 `jdk-21.jdk/Contents/Home/bin/jlink`，不是复用旧 runtime 的推断。
- 随后重新执行 `verifyPackagedRuntime`：成功（15s），实际 `.app` 内 `JAVA_VERSION="21.0.8"`；
  此次同时设置环境变量指向 JDK17、`-PruntimeJavaHome` 指向 JDK21，确认属性优先级有效。
- `javap` 检查 `com.yunx.app.MainKt`：`major version: 61`（Java 17 字节码）。
- 显式 `-PruntimeJavaHome=<JDK17>`：按预期失败，提示 `macOS runtime must be JDK 21, found '17.0.16'`。
- `:desktop:packageDmg --dry-run`：校验任务在 `packageDmg` 之前；`git diff --check` 通过。
- 此任务只实际重建并验证 app image；未实际重建 DMG、安装替换应用、运行 GitHub CI 或进行真实输入法 UI 验收。
  构建日志临时保留于 `/tmp/yunx-jdk21-build.log`、`/tmp/yunx-jdk21-jlink.log`、`/tmp/yunx-jdk21-override.log`。

### 备选缓解（如果 JDK 21 升级有兼容问题）
- Compose 1.7.3 官方建议升级 Compose 到 1.8.x（对 AWT 输入法处理有改进），但 markdown-renderer 必须停在 0.33.0 的限制需要一并评估；
- 应用层无法 catch 该崩溃（native 层 NSException 在 AppKit 主线程抛出）——**没有纯 Kotlin 的规避手段**。

### 已完成的相关修复（同一代码域，已提交）
- 引擎启动超时 60s + Gatekeeper 容忍（`43fd961`）；
- 重启引擎失败 Snackbar 提醒（`07a0242`）；
- 引擎卡片改固定职责（`0686ca3`）；
- BT 设置显示条件修复（`7c0ee23`）。

---

## 附：环境与工具速查

| 项 | 值 |
|---|---|
| 构建命令 | `/Users/penn/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle :desktop:packageDmg` |
| DMG 输出 | `desktop/build/compose/binaries/main/dmg/YunX-Desktop-1.3.3.dmg` → 自动复制 `~/Downloads/` |
| 测试/冒烟前必须 | 隔离 `YUNX_DESKTOP_DATA_DIR`、`-Djava.util.prefs.userRoot`、`-Duser.home`、私有 tmp；**不要** `pkill` 用户正在使用的 YunX-Desktop，也不要删除真实数据/锁。单实例锁只会在隔离数据目录内生效 |
| 运行日志 | `~/.yunx-desktop/files/yunx-desktop.log`（诊断标签 YunX-ClickDiag / YunX-DL / GopeedEngine） |
| 崩溃报告 | `~/Library/Logs/DiagnosticReports/YunX-Desktop-*.ips` |
| 可用 JDK | 17.0.16 / 21.0.8 / 25（`/Library/Java/JavaVirtualMachines/`） |
| create-dmg | brew 已装 1.3.0（`/opt/homebrew/bin/create-dmg`） |
| GitHub 直连 | 上传/下载均慢（走 utun 隧道 ~500KB/s）；CI 构建可绕开 |

## 附：本次会话已完成的修复（均已提交推送）

| 提交 | 内容 |
|---|---|
| `da3234a` | 账号面板首次打开失败（GPT 协作，stateIn 初始值） |
| `f961c29` | 文件选择取消误弹第二对话框 |
| `2babbfb` | 检查更新指向 fork + 预发布开关核对 |
| `da73bd3` | 关于/支持/欢迎页品牌化 |
| `62d7185` | UC 实名报错友好化 |
| `4960c6d` | 菜单栏中文名 |
| `df4d411` | 线程数即时刷新 |
| `84047b2` / `43fd961` | JCEF 预热延迟 / 引擎超时容忍 |
| `924f5b0` | ffmpeg 多源兜底 |
| `f961c29`-`95d6cdf` | 文件选择器 osascript 原生面板 |
| `0686ca3` / `7c0ee23` | 引擎页固定职责卡片 + BT 设置恢复 |
| `36b6461` | P2P 任务自动归 Gopeed（引擎语义） |
| `a344a80` | 解析页自动识别磁力/ed2k |
| `33d5fd7` | DMG 布局根因（挂载点）修复 |
| `ee9b558` | create-dmg 标准样式方案 |
| `b8baa6e` 等 | 托盘通知 / DMG 更新 / 图标全尺寸 |
