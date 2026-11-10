# macOS「首次点击需要点击两次」问题 — 排查交接文档

## 一、环境

- **硬件/系统**：Apple Silicon (arm64)，macOS 15 (Darwin 24.6)
- **技术栈**：Kotlin + Compose Multiplatform **1.7.3**（Desktop/JVM），Gradle 8.13，jvmToolchain 17（实际运行 JDK 21）
- **打包**：jpackage 打 `.app` → `.dmg`，**未签名**（ad-hoc）
- **关键依赖**：JCEF 132.3.1（内嵌 Chromium，mac 上为 **windowed** 模式，非 OSR）、JavaFX 17.0.13、SQLite JDBC、JNA 5.14
- **主窗口**：Compose 的 `ComposeWindow`（继承 JFrame），标题「云析 YunX-Desktop」
- **仓库**：`/Volumes/ZhiTai-iPlus7100/my-git/YunX-Desktop-For-Mac`（分支 `mac-port`，最新提交 `4450680`）
- **运行日志**：`~/.yunx-desktop/files/yunx-desktop.log`（诊断标签 `YunX-ClickDiag`、`YunX-Focus`）
- **上游**：Windows 版（tidain/YunX-Desktop），该问题为 mac 移植后新增

## 二、问题描述

冷启动应用后，**第一次点击 UI 控件无反应**，需要点击两次。最稳定的复现点：
「网盘」页 → 夸克网盘卡片右侧 **⋮ 三点按钮** → 第一次点击不弹「账号信息面板」，第二次点击才弹出。

用户感知：任何「窗口失去焦点后的第一次点击」都可能被吞。

## 三、已做过的尝试（按时间顺序）

### 尝试 1：修复窗口/应用焦点（多层组合，均无效）

| 动作 | 实现 | 结果 |
|---|---|---|
| AWT 层置前 | 窗口显示后 150ms 执行 `window.toFront() + requestFocus()` | ❌ 仍两次 |
| AWT 多次重试 | 150/400/900ms 三轮 toFront+requestFocus | ❌ |
| 原生应用激活 | JNA `objc_msgSend` 调 `[NSApp activateIgnoringOtherApps:YES]` | ❌ |
| LaunchServices 唤起 | `ProcessBuilder("open","-a",bundlePath)` 自我激活 | ❌ |
| 启动期焦点看护 | 窗口出现后每 500ms 检查 `window.isFocused`，未获得焦点就重复上述全部动作，持续 8 秒 | ❌ 仍复现；且日志实测出现过 **「启动 8 秒内窗口始终未获得焦点」**（`YunX-Focus` 警告） |

> 相关代码：`Main.kt` 启动 LaunchedEffect、`util/MacAppActivation.kt`（JNA objc：activateIgnoringOtherApps / hide / unhide）

### 尝试 2：消除「幽灵零尺寸窗口」

- **发现**：启动时 JCEF 的抖音预热会创建一个 `bounds=[0,0,0,0]` 的隐藏浏览器窗口（CGWindowList 可见，onscreen=true），怀疑 macOS 把它当作 app 主窗口、抢走 key 状态。
- **处理**：mac 上禁用预热（改惰性初始化）+ 启动 3 秒后清理所有 `width<=1 && height<=1` 的 Window。`Main.kt`
- **结果**：幽灵窗口确认消失（窗口列表验证），**但仍需两次点击**。

### 尝试 3：hover 重组吞掉点击（部分证据，非最终原因）

- **证据**：CUA 自动化实测「先把鼠标悬停在按钮上 → 再单击」= **一次生效**；「指针未悬停过的冷点」= 无效。
- **推测**：M3 `IconButton` 在 composition 中读取 hover 交互状态，首次悬停触发重组，Compose Desktop 把同一次点击的 press/release 拆到两个 `pointerInput` 实例 → 点击丢失。
- **处理**：把 ⋮ 从 `IconButton` 换成 `Box + clickable(indication=null, 独立 MutableInteractionSource)`；后为恢复悬停动画，改为 `hoverable + MutableState 只在 drawBehind 中读取`（绘制阶段读取不触发重组）。`DriveScreen.kt`
- **结果**：仍两次；hover 动画恢复（视觉验证通过）。**该假说未得到最终证实。**

### 尝试 4：AWT 全局事件监听（最终诊断，最接近结论）

在 `Main.kt` 用 `Toolkit.addAWTEventListener` 记录 `MOUSE_PRESSED` 和 `WINDOW_GAINED_FOCUS / LOST_FOCUS / ACTIVATED / DEACTIVATED`，并在 ⋮ 的 onClick 首行打日志。

**日志实锤（摘自 `YunX-ClickDiag`）**：

```
14:11:21.770  WINDOW_ACTIVATED 云析 YunX-Desktop     ← open -a 激活成功
14:11:21.771  WINDOW_GAINED_FOCUS 云析 YunX-Desktop
14:11:34.391  WINDOW_LOST_FOCUS 云析 YunX-Desktop     ← 焦点被别的应用抢走
14:11:34.393  WINDOW_DEACTIVATED 云析 YunX-Desktop
14:11:43.044  AWT press@1256,687  winFocused=false    ← 合成点击落点在窗口外（自动化坐标误差）
14:11:43.076  ⋮ onClick 触发 platform=夸克网盘        ← 但 onClick 仍然执行了
14:13:03.792  AWT press@1089,360  winFocused=false    ← 落点正确的一次点击
14:13:03.800  ⋮ onClick 触发 platform=夸克网盘        ← 面板立即弹出（一次成功）
```

**结论/推断**：
1. 只要点击命中正确位置，**第一次点击就会触发 onClick 并弹面板**——Compose 的事件链本身是通的。
2. 所有点击发生时 `winFocused=false`，且点击**没有触发** `WINDOW_GAINED_FOCUS/ACTIVATED`——窗口在点击后仍未成为 key window。
3. 因此最可能的机制是 **macOS 标准行为**：点击一个**非 key window** 时，第一次点击被系统「消耗」用于激活窗口（Cocoa 的 `acceptsFirstMouse` 默认为 NO 的 click-through 保护），第二次点击才投递给控件。
4. 为什么窗口经常不是 key window：应用启动时 JCEF 是**阻塞式初始化**（`JcefHolder.initBlocking()`，数秒），期间窗口未出现；窗口出现后 key 状态不稳定，且期间有其它窗口/应用抢焦点（实测 8 秒内从未获得焦点的案例）。

## 四、当前代码状态

- `util/MacAppActivation.kt`：JNA + objc_msgSend，封装 `activateIgnoringOtherApps` / `hideApp` / `unhideAndActivate`
- `Main.kt`：启动期 8 秒焦点看护；`Toolkit.addAWTEventListener` 诊断日志（**临时，可删除**）
- `DriveScreen.kt`：⋮ 三点按钮 = `Box(40dp) + hoverable + drawBehind(hover 圆底) + clickable(indication=null)`，onClick 内有诊断日志
- 未签名 jpackage bundle；`Info.plist` 无 `LSUIElement`（即正常前台应用）

## 五、剩余可疑点 / 建议下一步

1. **直接验证「非 key window 首击被吞」**：让用户在实际使用（真鼠标、非自动化）时点一次 ⋮，读 `YunX-ClickDiag` 日志中的 `winFocused` 值与是否出现 `WINDOW_GAINED_FOCUS`。若点击后出现 GAINED_FOCUS 而 onClick 未触发 → 假说成立。
2. **接受首击（acceptsFirstMouse）**：Cocoa 层给窗口的 contentView 覆写 `acceptsFirstMouse:` 返回 YES。难点：AWT 的 `ComposeWindow` 的 NSView 无法从 Java 侧直接获取；JNA 直调 AppKit 又不能脱离**主线程**（macOS 上 Java 进程的主线程 ≠ AWT EDT；实测在非主线程创建 NSWindow 会抛 `NSInternalInconsistencyException` 崩溃）。可行方向：
   - 用 `[NSApp windows]` 枚举出主窗口 → `makeKeyAndOrderFront:`（同样受主线程限制，需验证从 EDT 调用是否安全）
   - 用 `sun.lwawt.macosx.CAccessibility`/内部 API 拿 NSWindow（反射，脆弱）
   - 写一个小 native dylib（编译期 JNI），在 dylib 里用 `dispatch_async(dispatch_get_main_queue(), ^{...})` 调度 AppKit 调用——最可靠但要引入原生代码
3. **换事件层**：把 ⋮ 的点击改为 `pointerInput(awaitPointerEventScope)` + `awaitFirstDown(requireUnconsumed = false)` 自行处理，绕过 Compose `clickable` 的 press/release 配对（若是事件被消费/配对失败，这条路能救）。
4. **升级 Compose Multiplatform**：1.7.3 → 1.8.x/1.9.x 观察该问题是否被上游修复。**注意**：`multiplatform-markdown-renderer-m3` 必须停留在 0.33.0（0.34+ 依赖 Compose 1.8 的 BasicText 新签名，会导致运行时 `NoSuchMethodError`），升级时需一起处理。
5. **减少 key 状态丢失**：JCEF 初始化完成后显式把窗口重新置为 key（结合第 2 条的 makeKeyAndOrderFront）；或把 JCEF 初始化从阻塞改为窗口显示后再初始化（mac 上曾因 AppKit 主线程约束改成阻塞，需重新评估）。
6. **对照实验**：在同一台机器写一个最小 Compose Desktop 1.7.3 示例（一个按钮 + 点击计数），冷启动后第一次点击是否也丢 → 可确定是 Compose 框架级问题还是本应用特有（JCEF/多窗口干扰）。

## 六、可复现的最小关键信息

- 触发路径：冷启动 → 网盘页 → 点夸克卡片右侧 ⋮
- 观察点：点击后账号信息面板未出现；第二次点击出现
- 日志：`~/.yunx-desktop/files/yunx-desktop.log`，检索 `YunX-ClickDiag`
- 关键特征：点击瞬间 `winFocused=false`，点击后无 `WINDOW_GAINED_FOCUS`
