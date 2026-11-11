# YunX-Desktop-For-Mac 三个遗留问题 — 完整交接文档（供 GPT 处理）

> 生成时间：2026-11-11
> 仓库：/Volumes/ZhiTai-iPlus7100/my-git/YunX-Desktop-For-Mac（分支 `mac-port`，最新提交见 git log）
> 构建命令：`/Users/penn/.gradle/wrapper/dists/gradle-8.13-bin/5xuhj0ry160q40clulazy9h7d/gradle-8.13/bin/gradle :desktop:packageDmg`
> DMG 自动产出到 `desktop/build/compose/binaries/main/dmg/` 并复制到 `~/Downloads/`
> 运行日志：`~/.yunx-desktop/files/yunx-desktop.log`
> 技术栈：Kotlin + Compose Multiplatform 1.7.3（Desktop/JVM）+ JCEF 132.3.1 + JNA 5.14，jpackage 打包（未签名），运行时 JDK 17（本地构建）/ CI macos-14

---

## 问题 1：DMG 安装盘样式难看（裸 Finder 视图，无美化布局）

### 现象
打开 DMG 后是 Finder 默认视图：两个小图标挤在左上角，白底无布局。用户期望的是标准 mac 安装盘样式：灰底/带背景图、app 与 Applications 并排居中、128px 图标。

### 已确认的事实链（重要——避免重复走弯路）

1. **jpackage 生成的 DMG 只含 `.app`，没有 `/Applications` 符号链接**——这已修复（`95d6cdf`），脚本会补链接。
2. **样式脚本（AppleScript 设置窗口尺寸/图标位置）从未生效过**。根因已定位：
   - 脚本用 `hdiutil attach ... -mountpoint /tmp/yunx-dmg-rebuild` 挂载 tmp 盘；
   - `-mountpoint` 挂载时 Finder 看到的 disk 名是**挂载点名**（"yunx-dmg-rebuild"），不是卷名；
   - AppleScript `tell disk "YunX-Desktop"` 永远抛 -1728（找不到 disk）→ 布局设置静默失败（输出重定向到 /dev/null 无人看见）。
3. **修复已提交**（`33d5fd7`）：tmp 盘去掉 `-mountpoint`（自动挂载到 `/Volumes/YunX-Desktop`，AppleScript 能命中）；`hdiutil create -o` 注意会**自动追加 `.dmg` 后缀**（`X.dmg.tmp` 实际生成 `X.dmg.tmp.dmg`，文件名要对齐）。
4. **实测确认**：修复后 `.DS_Store` 已写入 DMG（位置 160,90 持久化）。
5. **但用户仍认为丑**。用户认可的样式特征（截图图一）：
   - 灰底圆角矩形背景（或背景图）
   - **大图标（约 128px）**：app 在左，Applications 在右
   - **白色箭头**指向 Applications
   - 两图标并排居中、窗口有留白

### 当前实现（HEAD `scripts/rebuild-dmg-with-apps-link.sh`）

- 使用 `create-dmg`（brew 已装 1.3.0）：`--icon "YunX-Desktop.app" 160 200 --app-drop-link 420 200 --window-size 640 420 --volname YunX-Desktop`
- 失败时降级为默认布局 DMG（仍含 Applications 链接）
- **实测 create-dmg 产物本身样式正确**（曾截图验证：灰底、大图标、箭头都出现了）

### 疑点：为什么最终用户拿到的 DMG 还是裸的

排查方向（按可能性排序）：
1. **`packageDmg` 与 rebuild 脚本的时序**：gradle 任务 `copyDmgToDownloads` 在 `packageDmg` 之后跑 rebuild 脚本，确认没有旧产物被意外复制覆盖（检查 `copyDmgToDownloads` 复制的源路径与 rebuild 输出路径是否一致）。
2. **create-dmg 在 CI/后台执行时 AppleScript/Finder 交互失败**：create-dmg 内部用 AppleScript 设置图标位置，**无头/后台环境可能弹不出 Finder 交互**，失败时它可能静默产出未布局的 DMG（create-dmg 对失败的处理是 `|| true` 降级——降级产物就是裸的）。本地手动跑是好的（截图验证过），但 gradle 子进程环境可能差异。
3. **`set -e` 与降级逻辑的交互**：脚本顶部 `set -e`，create-dmg 失败会中断脚本而不是走降级分支（检查 `|| true` 是否覆盖所有失败路径）。

### 建议（给 GPT）
- 优先方案：**放弃运行时 AppleScript**，改用**背景图方案**——生成一张 660×400 的背景 PNG（灰底/渐变+左侧画 app 图标示意+箭头+右侧 Applications 图标示意），DMG 挂载后把 `.background` 目录写入卷根 + AppleScript 仅设置背景图与图标位置（AppleScript 失败时至少背景图还在，.DS_Store 位置也预置）。
- 或者：**预生成 .DS_Store**——先手动排好一次 DMG，把生成的 `.DS_Store` 存为仓库资源，每次打包时直接写入卷根（图标位置/窗口尺寸全部固化，不依赖运行时 AppleScript）。这个方案**100% 可靠**，唯一限制是窗口尺寸固定。
- 校验：重建后挂载检查 `/Volumes/YunX-Desktop/.DS_Store` 是否存在且包含 `Bud1` 魔数。

---

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

## 问题 3：应用意外闪退（崩溃报告 4 份，同一根因）

### 崩溃签名（~/Library/Logs/DiagnosticReports/YunX-Desktop-2026-11-11-{163338,185815,190454,193355}.ips）

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

**JDK 17 AWT 与 macOS 15.x 输入法（中文输入法/SCIM）的已知兼容性 bug**：
- Compose Desktop 的 ComposeWindow 内嵌 AWTView 作为文本输入目标；
- 中文输入法（SCIM/搜狗等）通过 TSM/XPC 请求 `selectedRange`；
- JDK 17 的 AWTView 实现在某些状态下（无编辑上下文/焦点切换瞬间）抛 `NSException`；
- NSApplication 未捕获 → `-[NSApplication _crashOnException:]` → **主线程 EXC_BREAKPOINT 崩溃**；
- 对应 OpenJDK bug 族：JDK-8281544 / JDK-8271456 等（macOS IME 兼容），**JDK 21.0.5+ / 17.0.13+ 包含修复**。

### 触发场景（用户日志时间线）
- 粘贴磁力到输入框（输入法激活）→ 切换输入法/焦点 → 崩溃；
- 三次崩溃（16:33 / 18:58 / 19:33）栈完全一致。

### 修复方案（推荐，已验证环境可行）

**将运行时 JDK 升级到 21.0.8**（本机 `/Library/Java/JavaVirtualMachines/jdk-21.jdk` 已存在，`java -version` 验证 21.0.8）：

1. **CI 侧**（`.github/workflows/release.yml`，已确认是 '21'）：`setup-java` 的 `java-version` 为 '21'——jpackage 用运行 gradle 的 JDK 做 jlink 运行时，DMG 里的运行时即 JDK 21；
2. **本地侧**：构建命令前加 `JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home`（toolchain 编译目标仍 17，仅运行时变化）；
3. **注意**：`jvmToolchain(17)` 保持不变（Compose 1.7.3 编译目标兼容）；jpackage 的 `--runtime-image` 用 JDK 21 的 jlink 输出，应用体积会略增；
4. **验证**：装新包后用中文输入法在解析框输入/切换输入法/粘贴文本，观察是否再出同栈崩溃（检查 DiagnosticReports 新增文件）。

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
| 测试前必须 | `pkill -9 -f "YunX-Desktop.app"` + `rm -f ~/.yunx-desktop/yunx-instance.lock`（单实例锁会让新启动复用旧进程） |
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
