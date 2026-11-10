package com.yunx.app.util

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.Shell32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import javax.swing.JFileChooser
import javax.swing.UIManager
import javax.swing.filechooser.FileSystemView

/**
 * 桌面端系统能力统一入口：剪贴板 / 打开文件与链接 / 目录选择器。
 * 替代 Android 的 ClipboardManager、Intent.ACTION_VIEW、SAF 选择器。
 */
object DesktopActions {

    /** FOLDERID_Downloads 的 GUID（注册表兜底读取时使用） */
    private const val DOWNLOADS_FOLDER_GUID = "{374DE290-123F-4565-9164-39C4925E467B}"

    /** ★ Mac 移植：macOS 环境（Finder 相关操作走 `open` 命令） */
    private val isMacOS: Boolean
        get() = System.getProperty("os.name", "").lowercase().let { it.contains("mac") || it.contains("darwin") }

    /** 复制文本到系统剪贴板 */
    fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(text), null)
        }
    }

    /** 读取系统剪贴板文本（无文本内容时返回 null） */
    fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard
            .getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }.getOrNull()

    /** 用系统默认应用打开文件（下载完成后的「打开」） */
    fun openFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return runCatching {
            java.awt.Desktop.getDesktop().open(file)
            true
        }.getOrDefault(false)
    }

    /**
     * 在资源管理器中显示文件（选中）。
     *
     * Windows 走 ShellExecute，把 `/select,"<绝对路径>"` 作为**原始参数串**交给系统；
     * 不能用 ProcessBuilder —— 它按自己的规则给参数加引号/转义，两种写法都会失效：
     *   1. `/select,` 与路径拆成两个参数：路径里的**半角逗号**会被 Explorer 当成参数分隔符，
     *      截断后的路径不存在，Explorer 就落到「主文件夹」等默认位置（本次修的 bug）；
     *   2. 合成一个参数并自带引号：Java 又把引号转义成 `\"`，Explorer 解析不出路径，同样落回默认位置。
     * ShellExecute 不做任何转义，逗号/空格/中文路径都能正确定位。
     */
    fun revealFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        // ★ Mac 移植：`open -R` 在 Finder 中定位并选中文件（路径含空格/中文也安全，ProcessBuilder 原样传参）
        if (isMacOS) {
            return runCatching {
                ProcessBuilder("open", "-R", file.absolutePath).start()
                true
            }.getOrDefault(false)
        }
        if (WindowsTitleBar.isWindows) {
            // 返回 HINSTANCE：> 32 表示成功（<= 32 是错误码），失败再退回命令行方式
            val ret = runCatching {
                Shell32.INSTANCE.ShellExecute(
                    null, "open", "explorer.exe",
                    "/select,\"${file.absolutePath}\"", null, WinUser.SW_SHOWNORMAL
                )
            }.getOrNull()
            if (ret != null && com.sun.jna.Pointer.nativeValue(ret.toPointer()) > 32L) return true
        }
        return runCatching {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            true
        }.getOrDefault(false)
    }

    /** 在资源管理器中打开目录（不存在时先创建） */
    fun openDirectory(path: String): Boolean {
        val dir = File(path)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        // ★ Mac 移植：`open` 在 Finder 中打开目录
        if (isMacOS) {
            return runCatching {
                ProcessBuilder("open", dir.absolutePath).start()
                true
            }.getOrDefault(false)
        }
        return runCatching {
            ProcessBuilder("explorer.exe", dir.absolutePath).start()
            true
        }.getOrDefault(false)
    }

    /**
     * 把主窗口拉到前台（托盘「显示主窗口」、剪贴板弹窗点「打开」共用）。
     *
     * ★ Windows 上必须做两件事，少一件就会出现「点了打开但窗口没到前台」：
     *   1. **先还原**：窗口可能已**最小化到任务栏**（extendedState 含 ICONIFIED 位），
     *      此时 toFront() 对它是无效的 —— 必须先把状态改回 NORMAL；
     *   2. **再置顶置前**：toFront() 单独调用不一定能夺取前台焦点，用 alwaysOnTop 瞬时切换强制置顶。
     */
    fun bringWindowToFront(window: Frame?) {
        val w = window ?: return
        runCatching {
            // ★ Mac 移植：应用可能处于「隐藏」(NSApp hide) 状态，先取消隐藏并激活
            if (isMacOS) MacAppActivation.unhideAndActivate()
            w.isVisible = true
            if (w.extendedState and Frame.ICONIFIED != 0) {
                w.extendedState = Frame.NORMAL
            }
            val wasOnTop = w.isAlwaysOnTop
            w.isAlwaysOnTop = true
            w.toFront()
            w.requestFocus()
            w.isAlwaysOnTop = wasOnTop
        }
    }

    /**
     * 重启应用：延迟 2 秒后拉起新的实例（给本进程留出退出的时间，避免两个实例同时抢数据库/缓存），
     * 返回是否成功发起。调用方应在返回 true 后立即退出本进程。
     *
     * 拿不到启动器 exe（开发运行 `gradle run`）时返回 false，由调用方提示手动重启。
     */
    fun restartApp(): Boolean {
        // ★ Mac 移植：jpackage 的 .app 用 `open -n` 拉起新副本。
        //   jpackage.app-path 指向 <包>/Contents/MacOS/<名>，向上两级即 .app 包；
        //   开发运行（gradle run）拿不到 .app 时返回 false，由调用方提示手动重启。
        //   延迟 2 秒再启动，等本进程把单实例文件锁/数据库句柄放掉。
        if (isMacOS) {
            val appBundle = System.getProperty("jpackage.app-path")?.let { p ->
                File(p).parentFile?.parentFile?.takeIf { it.isDirectory && it.name.endsWith(".app") }
            } ?: return false
            return runCatching {
                // 路径经 $0 传参，避免 sh -c 字符串里的引号/转义问题
                ProcessBuilder("/bin/sh", "-c", "sleep 2; open -n \"$0\"", appBundle.absolutePath).start()
                true
            }.getOrDefault(false)
        }
        val exe = launcherExe() ?: return false
        return runCatching {
            // 用 cmd 延迟启动：本进程马上就会退出，必须等它把文件句柄都放掉。
            // 延迟用 ping 而不是 timeout —— timeout 在 stdin 被重定向时会直接报错退出（ProcessBuilder 默认就是管道）
            ProcessBuilder(
                "cmd.exe", "/c",
                "ping -n 3 127.0.0.1 >nul & start \"\" \"${exe.absolutePath}\""
            ).directory(exe.parentFile).start()
            true
        }.getOrDefault(false)
    }

    /**
     * 关闭计算机（「下载完成后关机」用）。
     *
     * 走系统 shutdown 命令：普通用户本地关机不需要管理员权限（SeShutdownPrivilege），
     * 调用方无需提权。非 Windows 直接返回 false（本功能目前仅在桌面端 Windows 提供）。
     *
     * @param seconds 关机前延迟秒数（0 = 立即关机）；调用方负责在倒计时期间决定是否取消
     * @return 是否成功发起
     */
    fun shutdownComputer(seconds: Int = 0): Boolean {
        // ★ Mac 移植：osascript 触发系统关机（无需 root；首次调用 macOS 会弹自动化授权确认）。
        //   mac 版暂不支持延迟秒数（AppleScript 无法可靠倒计时，shutdown 命令需要 root），参数忽略。
        if (isMacOS) {
            return runCatching {
                ProcessBuilder(
                    "osascript", "-e", "tell application \"System Events\" to shut down"
                ).start()
                true
            }.getOrDefault(false)
        }
        if (!WindowsTitleBar.isWindows) return false
        return runCatching {
            ProcessBuilder("shutdown", "/s", "/t", seconds.coerceAtLeast(0).toString()).start()
            true
        }.getOrDefault(false)
    }

    /** 便携版/安装版的启动器 exe：优先 jpackage 注入的路径，否则取工作目录下的 YunX-Desktop.exe */
    private fun launcherExe(): File? {
        System.getProperty("jpackage.app-path")?.let { p ->
            File(p).takeIf { it.isFile }?.let { return it }
        }
        return File(File(System.getProperty("user.dir") ?: "."), "YunX-Desktop.exe").takeIf { it.isFile }
    }

    /** 用系统默认浏览器打开链接 */
    fun openUrl(url: String) {
        runCatching {
            java.awt.Desktop.getDesktop().browse(URI(url))
        }
    }

    /**
     * 弹出目录选择对话框。
     *
     * Windows：使用原生 COM IFileOpenDialog（FOS_PICKFOLDERS），
     *   即资源管理器同款「选择文件夹」对话框（WindowsFolderPicker），
     *   COM 不可用时回退 Swing JFileChooser。
     * Linux：使用 Swing JFileChooser（DIRECTORIES_ONLY 模式）。
     * macOS：继续使用 AWT FileDialog（原生体验更好）。
     *
     * @return 选中目录绝对路径；取消返回 null
     */
    fun pickDirectory(): String? {
        val osName = System.getProperty("os.name", "").lowercase()
        val isMac = osName.contains("mac") || osName.contains("darwin")

        if (isMac) {
            // ★ mac：用 osascript 的系统原生面板（位置/外观与原生应用一致）。
            //   AWT FileDialog 在 jpackage+Compose 下恒定位到屏幕外；JNA 直调 NSOpenPanel
            //   受 OS 主线程限制不可行。
            //   回退语义：仅 osascript **无法启动/异常退出**（IOException）才回退 Swing 选择器；
            //   用户取消返回 null（不回退，否则取消后还会弹第二个对话框）。
            return try {
                MacNativeDialogs.pick(directories = true, defaultDir = defaultDownloadDir)
            } catch (e: java.io.IOException) {
                swingDirectoryChooser()
            }
        }

        if (WindowsTitleBar.isWindows) {
            return when (val r = WindowsFolderPicker.pick(defaultDownloadDir.takeIf { it.isDirectory }, "选择文件夹")) {
                is WindowsFolderPicker.Result.Success -> r.path
                WindowsFolderPicker.Result.Cancelled -> null
                WindowsFolderPicker.Result.Unavailable -> swingDirectoryChooser()
            }
        }

        return swingDirectoryChooser()
    }

    /** ★ Mac 移植：隐藏所有零尺寸窗口（幽灵 Frame 会让原生面板定位到屏幕外） */
    private fun hideZeroSizeWindows() {
        runCatching {
            java.awt.Window.getWindows().forEach { w ->
                if (w.width <= 1 && w.height <= 1) runCatching { w.isVisible = false }
            }
        }
    }

    /** Swing 目录选择器（Windows COM 不可用回退 / mac 主路径）。
     *  ★ Mac 移植：mac 用它替代 AWT FileDialog（原生面板在 jpackage+Compose 下
     *    会定位到屏幕外）。必须在 EDT 显示，否则 Swing 也会定位异常。 */
    private fun swingDirectoryChooser(): String? = onEdt {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
        val chooser = JFileChooser(FileSystemView.getFileSystemView())
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        chooser.dialogTitle = "选择文件夹"
        chooser.isAcceptAllFileFilterUsed = false
        chooser.approveButtonText = "选择文件夹"
        chooser.currentDirectory = defaultDownloadDir
        val result = chooser.showOpenDialog(null)
        if (result == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }.getOrNull()

    /**
     * 弹出文件选择对话框（导入备份等）。
     *
     * Windows：使用原生 COM IFileOpenDialog（资源管理器同款样式，WindowsFilePicker），
     *   初始定位到下载目录，COM 不可用时回退 AWT FileDialog。
     * macOS / Linux：AWT FileDialog。
     *
     * @param filters 文件类型过滤器（显示名 to 通配符），空则不过滤
     * @return 选中文件绝对路径；取消返回 null
     */
    fun pickFile(filters: List<Pair<String, String>> = emptyList()): String? {
        val osName = System.getProperty("os.name", "").lowercase()
        val isMac = osName.contains("mac") || osName.contains("darwin")

        if (isMac) return awtPickFile("选择文件")

        if (WindowsTitleBar.isWindows) {
            return when (val r = WindowsFilePicker.pickFile(
                defaultDownloadDir.takeIf { it.isDirectory }, "选择文件", filters
            )) {
                is WindowsFilePicker.Result.Success -> r.path
                WindowsFilePicker.Result.Cancelled -> null
                WindowsFilePicker.Result.Unavailable -> awtPickFile("选择文件")
            }
        }

        return awtPickFile("选择文件")
    }

    /**
     * 弹出「另存为」对话框（导出备份等）。
     *
     * Windows：使用原生 COM IFileSaveDialog（资源管理器同款样式，WindowsFilePicker），
     *   初始定位到系统下载目录，COM 不可用时回退 AWT FileDialog(SAVE)。
     * macOS / Linux：AWT FileDialog(SAVE)。
     *
     * @param defaultName 预填文件名
     * @param filters 文件类型过滤器（显示名 to 通配符）
     * @param defaultExtension 用户省略扩展名时自动补全（null 不补）
     * @return 目标文件绝对路径；取消返回 null
     */
    fun saveFile(
        defaultName: String,
        title: String,
        filters: List<Pair<String, String>> = emptyList(),
        defaultExtension: String? = null
    ): String? {
        val osName = System.getProperty("os.name", "").lowercase()
        val isMac = osName.contains("mac") || osName.contains("darwin")

        if (isMac) return awtSaveFile(defaultName, title)

        if (WindowsTitleBar.isWindows) {
            return when (val r = WindowsFilePicker.saveFile(
                defaultDownloadDir.takeIf { it.isDirectory }, defaultName, title, filters, defaultExtension
            )) {
                is WindowsFilePicker.Result.Success -> r.path
                WindowsFilePicker.Result.Cancelled -> null
                WindowsFilePicker.Result.Unavailable -> awtSaveFile(defaultName, title)
            }
        }

        return awtSaveFile(defaultName, title)
    }

    /**
     * ★ Mac 移植：主窗口引用（Main.kt 启动时注册）。
     * 文件对话框必须以**主窗口**为父；之前用「第一个可见 Frame」可能选到 JCEF 的内部宿主窗口，
     * 实测会导致对话框被定位到屏幕外（bounds=-250,880, onscreen=false），模态等待中应用像卡死。
     */
    @Volatile
    var mainWindow: Frame? = null

    /**
     * ★ Mac 移植：给 AWT 文件对话框找父窗口（优先主窗口，其次面积最大的可见 Frame）。
     */
    private fun dialogParent(): Frame? {
        mainWindow?.takeIf { it.isVisible }?.let { return it }
        return java.awt.Window.getWindows().filterIsInstance<Frame>()
            .filter { it.isVisible }
            .maxByOrNull { it.width.toLong() * it.height.toLong() }
    }

    /**
     * ★ Mac 移植：在 EDT 上执行并同步等结果（AWT 窗口必须在 EDT 创建/显示）。
     * 调用方通常在 Dispatchers.IO，直接 show 原生面板会定位错乱。
     * 已在 EDT 时直接执行，避免 invokeAndWait 死锁。
     */
    private fun <T> onEdt(block: () -> T): Result<T> = runCatching {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            var out: Any? = null
            var err: Throwable? = null
            java.awt.EventQueue.invokeAndWait {
                try {
                    out = block()
                } catch (t: Throwable) {
                    err = t
                }
            }
            err?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            out as T
        }
    }

    /** AWT 旧式文件选择对话框（Windows COM 不可用 / 非 Windows 回退） */
    private fun awtPickFile(title: String): String? = onEdt {
        // ★ Mac 移植：mac 走 osascript 系统原生面板，失败回退 JFileChooser
        if (isMacOS) {
            val native = try {
                MacNativeDialogs.pick(directories = false, defaultDir = defaultDownloadDir)
            } catch (e: java.io.IOException) {
                null
            }
            if (native != null) return@onEdt native
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
            val chooser = JFileChooser(FileSystemView.getFileSystemView())
            chooser.fileSelectionMode = JFileChooser.FILES_ONLY
            chooser.dialogTitle = title
            chooser.currentDirectory = defaultDownloadDir
            return@onEdt if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else null
        }
        val dialog = FileDialog(dialogParent(), title, FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        dialog.dispose()
        if (dir != null && file != null) File(dir, file).absolutePath else null
    }.getOrNull()

    /** AWT 旧式另存为对话框（Windows COM 不可用 / 非 Windows 回退） */
    private fun awtSaveFile(defaultName: String, title: String): String? = onEdt {
        // ★ Mac 移植：mac 走 osascript 原生「另存为」，失败回退 JFileChooser
        if (isMacOS) {
            val native = try {
                MacNativeDialogs.save(defaultName, defaultDir = defaultDownloadDir)
            } catch (e: java.io.IOException) {
                null
            }
            if (native != null) return@onEdt native
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) } catch (_: Exception) {}
            val chooser = JFileChooser(FileSystemView.getFileSystemView())
            chooser.fileSelectionMode = JFileChooser.FILES_ONLY
            chooser.dialogTitle = title
            chooser.currentDirectory = defaultDownloadDir
            chooser.selectedFile = File(defaultDownloadDir, defaultName)
            return@onEdt if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.absolutePath
            } else null
        }
        val dialog = FileDialog(dialogParent(), title, FileDialog.SAVE)
        dialog.file = defaultName
        dialog.directory = defaultDownloadDir.absolutePath
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        dialog.dispose()
        if (dir != null && file != null) File(dir, file).absolutePath else null
    }.getOrNull()

    /**
     * 系统默认下载目录。
     *
     * 不再硬编码 <用户目录>/Downloads：Windows 用户可在资源管理器属性里把「下载」
     * 重定向到任意盘/目录，这里通过 SHGetKnownFolderPath(FOLDERID_Downloads) 取真实值，
     * 失败时逐级回退到注册表、最后才是 <用户目录>/Downloads。
     */
    val defaultDownloadDir: File by lazy { resolveDefaultDownloadDir() }

    private fun resolveDefaultDownloadDir(): File {
        val os = System.getProperty("os.name", "").lowercase()
        val home = File(System.getProperty("user.home"))
        val fallback = File(home, "Downloads")

        return when {
            // Windows：优先 Known Folder API，其次注册表，最后传统路径
            os.contains("win") -> windowsDownloadDir() ?: fallback
            // Linux：读 XDG 用户目录配置
            os.contains("linux") || os.contains("nix") -> xdgDownloadDir(home) ?: fallback
            // macOS：~/Downloads 即系统下载目录
            else -> fallback
        }
    }

    /** Windows：SHGetKnownFolderPath → 注册表 User/Shell Folders 兜底 */
    private fun windowsDownloadDir(): File? {
        // 1. 官方 Known Folder API（Vista+，最准确）
        runCatching {
            Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Downloads)
        }.getOrNull()?.let { path ->
            if (path.isNotBlank()) return File(path)
        }
        // 2. 注册表兜底：{374DE290-...} 即 FOLDERID_Downloads
        return runCatching {
            val key = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\"
            // Shell Folders 里的值已被系统展开，优先；User Shell Folders 需手动展开环境变量
            val expanded = Advapi32Util.registryGetStringValue(
                WinReg.HKEY_CURRENT_USER, key + "Shell Folders",
                DOWNLOADS_FOLDER_GUID
            )
            File(expandWindowsEnv(expanded))
        }.recoverCatching {
            val raw = Advapi32Util.registryGetStringValue(
                WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders",
                DOWNLOADS_FOLDER_GUID
            )
            File(expandWindowsEnv(raw))
        }.getOrNull()?.takeIf { it.absolutePath.isNotBlank() }
    }

    /** Linux：解析 ~/.config/user-dirs.dirs 的 XDG_DOWNLOAD_DIR（如 XDG_DOWNLOAD_DIR="$HOME/Downloads"） */
    private fun xdgDownloadDir(home: File): File? = runCatching {
        val config = File(home, ".config/user-dirs.dirs")
        if (!config.isFile) return@runCatching null
        val line = config.readLines().firstOrNull {
            it.trimStart().startsWith("XDG_DOWNLOAD_DIR=")
        } ?: return@runCatching null
        val raw = line.substringAfter('=').trim().trim('"', '\'')
        if (raw.isBlank()) return@runCatching null
        File(raw.replace("\$HOME", home.absolutePath).replace("^~".toRegex(), home.absolutePath))
    }.getOrNull()?.takeIf { it.absolutePath.isNotBlank() }

    /** 展开 Windows 路径里的 %ENV% 环境变量（注册表 User Shell Folders 用） */
    private fun expandWindowsEnv(path: String): String =
        Regex("%([A-Za-z_][A-Za-z0-9_]*)%").replace(path) { m ->
            System.getenv(m.groupValues[1]) ?: m.value
        }
}
