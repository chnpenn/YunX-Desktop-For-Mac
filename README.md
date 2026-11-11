<div align="center">

<img src="desktop/src/main/resources/icon.png" width="120" alt="云析 Mac 版" />

# 云析 Mac 版 · YunX-Desktop-For-Mac

**粘贴分享链接，直接高速下载 —— 云析 YunX 的 macOS 移植版（非官方社区维护）**

网盘：夸克 / UC / 迅雷 / 百度 / 139 / 123 / 115 / 光鸭 / 蓝奏云 / 蓝奏云优享版；
视频：哔哩哔哩 / 小红书 / 抖音 / 微博 / 快手 / AcFun / Pixiv；另支持磁力 / BT 种子 / ed2k 等 P2P 链接。
Range 分片并发 + 断点续传，B 站高清自动合轨。

[![Release](https://img.shields.io/github/v/release/chnpenn/YunX-Desktop-For-Mac?style=flat-square\&label=release\&color=4C8BF5)](https://github.com/chnpenn/YunX-Desktop-For-Mac/releases/latest)
[![License](https://img.shields.io/badge/license-AGPL--3.0-blue?style=flat-square)](./LICENSE)
[![Platform](https://img.shields.io/badge/platform-macOS%20arm64-111111?style=flat-square\&logo=apple\&logoColor=white)](#安装)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square\&logo=kotlin\&logoColor=white)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose%20Multiplatform-Desktop-4285F4?style=flat-square\&logo=jetpackcompose\&logoColor=white)](https://www.jetbrains.com/lp/compose-multiplatform/)

[下载最新版](https://github.com/chnpenn/YunX-Desktop-For-Mac/releases/latest) · [上游项目](https://github.com/tidain/YunX-Desktop) · [已知不足](#已知不足与待完善) · [构建](docs/BUILD.md)

</div>

***

> [!IMPORTANT]
> **云析永远免费开源。** 如果你是在任何地方「花钱买到」的，说明你被骗了，请立即退款。

> **本项目基于上游 [tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop)（云析 Windows 桌面版，
> 源于安卓版 [CYQawa/YunX](https://github.com/CYQawa/YunX)）移植，沿用 AGPL-3.0 协议。
> 本移植版的全部修改同样以 AGPL-3.0 开放；使用本仓库代码请同样以 AGPL-3.0 开放源代码。**

## 简介

**云析 Mac 版**：粘贴**网盘分享链接**或**视频作品链接**，即可浏览内容、在线预览并直接高速下载；
也支持**磁力 / BT 种子 / ed2k** 等 P2P 链接。网盘与视频平台支持多账号，登录凭证加密存本机。

macOS 移植版基于上游 Windows 版代码：核心逻辑（解析协议、Range 分片下载、断点续传、
多账号、加密备份、JCEF 内嵌登录、浏览器插件桥）全部复用；平台相关层（对话框、托盘、
通知、图标、打包）针对 macOS 重写或适配。

## 安装（Apple Silicon 原生）

1. 从 [Releases](https://github.com/chnpenn/YunX-Desktop-For-Mac/releases/latest) 下载 `YunX-Desktop-x.x.x.dmg`
2. 打开 DMG → 拖入「应用程序」
3. **首次打开**若提示「无法验证开发者」（当前未签名公证）：**右键 → 打开**
4. 首次一键导入浏览器 Cookie 会弹 Keychain 授权框 → 点「**始终允许**」

应用内「设置 → 检查更新」可检测新版本（DMG 直接下载）。

## mac 适配亮点

- **原生对话框**：目录/文件选择走系统面板（`osascript` + `choose folder/file`），默认定位下载目录
- **JCEF 内嵌登录**：Chromium 直接加载网盘登录页，Cookie 自动检测（含 Chrome 130+ 32 字节信封头解密适配）
- **Gopeed 引擎**：自动下载 `macos-arm64` 内核并补 ad-hoc 签名，磁力/BT/ed2k 开箱即用
- **ffmpeg/ffplay**：应用内自动安装（osxexperts 社区构建 + 版本回退备源），brew 安装的也能自动探测
- **托盘通知**：下载完成气泡通知；更新包（.dmg）完成后自动在访达显示
- **中文名**：Dock 与菜单栏显示「云析」，应用图标为云析定制图标（含 16~1024 全尺寸 icns 层）
- **单实例锁**：重复启动自动激活已有窗口（文件锁实现）

## 已知不足与待完善

> 透明说明当前移植版的差距，按优先级排列。欢迎 PR 补齐。

| # | 项目 | 说明 |
|---|---|---|
| 1 | **未签名 / 未公证** | 首次打开需右键绕过 Gatekeeper；正式分发需 Apple Developer 账号 + 签名公证（流程见 [docs/mac-signing-notarization.md](docs/mac-signing-notarization.md)） |
| 2 | **视频预览未嵌入窗口** | mac 上 ffplay 为独立窗口；默认已改用内置 JavaFX 播放器（应用内预览）。HEVC / 加密 HLS 暂不支持（JavaFX 格式限制），Live 图等场景可在设置显式切换 FFplay。嵌入方案调研见 [docs/mac-preview-embed-research.md](docs/mac-preview-embed-research.md) |
| 3 | **下载进度无系统通知** | 仅有完成时托盘气泡；进度条通知需 UserNotifications 框架（依赖签名） |
| 4 | **「自动导入浏览器 Cookie」仅验证夸克 / UC** | 其余平台（百度 / 115 / 123 等）凭证键与校验未逐一验证，入口已暂时隐藏；验证后按平台放开 |
| 5 | **UC 需实名** | UC 服务端要求实名认证，未实名账号无法列表/下载（应用已做友好提示，需去 UC 官网实名） |
| 6 | **窗口位置无「自定义」遮罩选位** | 遮罩选位器为 Windows 专属 C# 进程；mac 保留九宫格 / 上次位置 / 居中 |
| 7 | **无启动闪屏** | Windows 由 C# Launcher 注入闪屏；mac 直接出窗口 |
| 8 | **应用内更新的预发布通道** | 「接受预发布版更新」开关已生效（/releases 列表 + prerelease 过滤），但发布节奏取决于本仓库维护者 |
| 9 | **部分平台解析待实测** | 网盘协议与平台账号状态强相关；已实测夸克 / UC / 迅雷，其余平台理论可用，遇问题请提 Issue |
| 10 | **JCEF 内嵌浏览器部分站点兼容性** | Chromium 132 内核；个别站点风控可能要求辅助验证，建议使用浏览器插件桥或 Cookie 导入 |

## 与上游的差异（技术摘要）

| 领域 | 上游（Windows） | 本仓库（macOS） |
|---|---|---|
| SQLite | 裁剪包（仅 Windows 原生库） | 官方全平台 jar |
| JavaFX | `:win` classifier | `:mac-aarch64` |
| JCEF | `jcef-natives-windows-amd64` | `jcef-natives-macosx-arm64`（同版本） |
| 文件对话框 | COM IFileOpenDialog | `osascript` 原生面板（AWT FileDialog 在 jpackage 下定位异常） |
| Cookie 解密 | DPAPI | Keychain「Safe Storage」+ PBKDF2 + AES-CBC + Chrome 130+ 32B 信封头剥离 |
| 下载引擎 | gopeed.exe | gopeed（macos-arm64，自动 codesign） |
| ffmpeg | GyanD essentials zip | osxexperts 构建多源 + codesign |
| 托盘菜单 | Win32 TrackPopupMenu | AWT PopupMenu |
| 完成通知 | Toast 通知中心 | AWT 托盘气泡 |
| 打包 | Inno Setup exe + 便携 zip | jpackage DMG |
| 图标 | 定制 ico | 云析 icns（16~1024 全尺寸） |
| 关机 | `shutdown /s /t`（可倒计时） | osascript 立即关机（无倒计时） |
| 机器指纹 | 注册表 MachineGuid | IOPlatformUUID（ioreg） |

完整适配细节见 `docs/` 目录与提交历史；Windows 端差异排查记录见 [docs/mac-two-click-issue.md](docs/mac-two-click-issue.md)。

## 支持开发

- **Star**：给 [本仓库](https://github.com/chnpenn/YunX-Desktop-For-Mac) 与 [上游](https://github.com/tidain/YunX-Desktop) 各点个 Star ⭐
- **Issues**：macOS 移植 Bug 或建议欢迎反馈

## 反馈与交流

- **Mac 版交流群**：1121753821（应用内「关于 / 欢迎页」点击胶囊复制群号）
- **macOS 移植问题**：本仓库 [Issues](https://github.com/chnpenn/YunX-Desktop-For-Mac/issues)
- **上游（Windows 版）**：[tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop) · 安卓版 [CYQawa/YunX](https://github.com/CYQawa/YunX)

## 文档

| 文档 | 内容 |
| --- | --- |
| [mac 签名与公证](docs/mac-signing-notarization.md) | 正式分发前置流程（需 Apple Developer 账号） |
| [mac 预览嵌入调研](docs/mac-preview-embed-research.md) | 视频预览嵌入方案分析 |
| [mac 两次点击排查](docs/mac-two-click-issue.md) | 一项平台级 bug 的完整排查记录 |
| [功能清单](docs/FEATURES.md) | 全部功能与细节（上游） |
| [使用说明](docs/USAGE.md) | 快速上手、登录方式、设置项、数据目录、托盘操作 |
| [构建与打包](docs/BUILD.md) | 环境要求、命令、版本号、打包产物、目录结构 |
| [与安卓版的差异](docs/UPSTREAM-DIFF.md) | 桌面版相对安卓版的新增 / 重做 / 移除 |
| [免责声明](docs/DISCLAIMER.md) | 免责声明、协议逆向说明、反倒卖、开源协议 |

## 开源协议

本项目基于上游 tidain/YunX-Desktop 移植，与安卓版 [CYQawa/YunX](https://github.com/CYQawa/YunX) 同属云析项目，
同样以 [GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 协议开源，详见 [LICENSE](LICENSE)。
完整的免责与合规说明见 **[免责声明](docs/DISCLAIMER.md)**。

## 更多

- **Mac 版仓库**：<https://github.com/chnpenn/YunX-Desktop-For-Mac>
- 上游 PC 版仓库：<https://github.com/tidain/YunX-Desktop>
- 安卓版仓库：<https://github.com/CYQawa/YunX>
- 上游问题反馈：<https://github.com/tidain/YunX-Desktop/issues>

## 贡献者

<sub><sup>上游 Contributors</sup></sub>

<a href="https://github.com/tidain/YunX-Desktop/graphs/contributors">
  <img alt="contributors" src="https://contrib.rocks/image?repo=tidain/YunX-Desktop" />
</a>

---

<div align="center">

**云析 v1.3.3 · Made with ❤ and GPT&Claude · 基于 tidain/YunX-Desktop 移植**

</div>

## Star History

<a href="https://www.star-history.com/?repos=chnpenn%2Fyunx-desktop-for-mac&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=chnpenn/yunx-desktop-for-mac&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=chnpenn/yunx-desktop-for-mac&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=chnpenn/yunx-desktop-for-mac&type=date&legend=top-left" />
 </picture>
</a>
