package com.yunx.app.util

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ★ Mac 移植：macOS 原生文件/目录选择器（经 `osascript` 调用系统面板）。
 *
 * 为什么不用 AWT FileDialog：实测在 jpackage + Compose 应用里，AWT 包装的
 * NSOpenPanel 恒被定位到屏幕外（bounds=-250,880, onscreen=false），模态等待
 * 中整个应用像卡死；换父窗口/EDT/清理幽灵窗口均无效。
 * 为什么不用 JNA 直调 NSOpenPanel：AppKit 要求 NSWindow 必须在 OS 主线程创建，
 * 而 Java 进程的主线程并不是 OS 主线程（实测直接抛 NSInternalInconsistencyException）。
 * osascript 的 `choose folder/file` 由独立进程持有原生面板——位置、外观、交互与
 * 原生应用完全一致（对齐参考项目倒计时的「选择图片」对话框行为）。
 */
object MacNativeDialogs {

    private const val TAG = "YunX-NativePanel"

    /** 单个子进程等待上限：用户不点就一直等（面板是模态的） */
    private const val WAIT_HOURS = 1L

    /**
     * 弹出原生选择器。
     * @param directories true = 选文件夹（choose folder），false = 选文件（choose file）
     * @return 选中路径；用户取消或失败返回 null
     */
    fun pick(directories: Boolean, defaultDir: File? = null): String? {
        val loc = defaultDir?.takeIf { it.isDirectory }
            ?.let { " default location (POSIX file \"${it.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")}\")" }
            ?: ""
        val script = if (directories) {
            "POSIX path of (choose folder with prompt \"选择文件夹\"$loc)"
        } else {
            "POSIX path of (choose file with prompt \"选择文件\"$loc)"
        }
        // 取消 → null（不回退）；调用失败 → IOException 原样抛出（调用方决定回退）
        return exec(script)?.let { File(it).absolutePath }
    }

    /**
     * 弹出原生「另存为」选择器（choose file name：允许输入不存在的文件名）。
     * @param defaultName 预填文件名
     * @return 目标路径；取消返回 null
     */
    fun save(defaultName: String, defaultDir: File? = null): String? {
        val safeName = defaultName.replace("\\", "\\\\").replace("\"", "\\\"")
        val loc = defaultDir?.takeIf { it.isDirectory }
            ?.let { " default location (POSIX file \"${it.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"")}\")" }
            ?: ""
        val script = "POSIX path of (choose file name with prompt \"保存文件\" default name \"$safeName\"$loc)"
        // 取消 → null（不回退）；调用失败 → IOException 原样抛出（调用方决定回退）
        return exec(script)?.let { File(it).absolutePath }
    }

    /**
     * 跑 osascript。三种结果语义分明（调用方据此决定是否回退备用选择器）：
     * - 成功 → 返回 stdout（trim 后）
     * - 用户取消（退出码 1 + stderr -128/User canceled）→ 返回 null，**不抛异常**
     * - 调用失败（无法启动/其它错误）→ 抛 [IOException]，调用方可回退备用方案
     */
    private fun exec(script: String): String? {
        val p = try {
            ProcessBuilder("osascript", "-e", script)
                .redirectErrorStream(false)
                .start()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "osascript 无法启动: ${e.message}")
            throw java.io.IOException("osascript 无法启动：${e.message}", e)
        }
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        if (!p.waitFor(WAIT_HOURS, TimeUnit.HOURS)) {
            runCatching { p.destroyForcibly() }
            Log.w(TAG, "osascript 超时未完成")
            throw java.io.IOException("选择器超时未完成")
        }
        if (p.exitValue() != 0) {
            // 用户取消：退出码 1，stderr 含 "User canceled"/"-128" —— 正常取消，静默返回 null
            val cancelled = err.contains("-128") || err.contains("User canceled")
            if (cancelled) return null
            Log.w(TAG, "osascript 退出码 ${p.exitValue()}: ${err.take(120)}")
            throw java.io.IOException("选择器异常退出（${p.exitValue()}）：${err.take(120)}")
        }
        return out.trim().takeIf { it.isNotBlank() }
    }
}
