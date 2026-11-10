package com.yunx.app.util

import com.sun.jna.NativeLibrary

/**
 * ★ Mac 移植：macOS 应用激活辅助。
 *
 * [activateIgnoringOtherApps] 调 AppKit 的 `[NSApp activateIgnoringOtherApps:YES]`——
 * 强制把本应用带到前台并获得键盘焦点（连「其他应用正持有焦点」的场景也一并覆盖）。
 *
 * 背景：Java 侧的 toFront()/requestFocus() 只在 AWT 层面生效；mac 上窗口所属的
 * NSApplication 若不是 active 状态（启动期被阻塞的 JCEF 初始化拖过了激活时机），
 * 用户的第一次点击会被系统拿去「激活应用」（click-through 保护），表现为
 * 「任何按钮都要点两次才响应」。这条原生调用是消除该现象的标准手段。
 */
object MacAppActivation {

    private const val TAG = "YunX-MacActivate"

    private val objcLib: NativeLibrary? by lazy {
        if (!System.getProperty("os.name", "").lowercase().contains("mac")) null
        else runCatching { NativeLibrary.getInstance("objc") }.getOrNull()
    }

    fun activateIgnoringOtherApps(): Boolean {
        val l = objcLib ?: return false
        return runCatching {
            val getClassFn = l.getFunction("objc_getClass")
            val selFn = l.getFunction("sel_registerName")
            val msgSendFn = l.getFunction("objc_msgSend")

            val cls = getClassFn.invokePointer(arrayOf<Any>("NSApplication"))
                ?: return false
            val shared = msgSendFn.invokePointer(
                arrayOf<Any>(cls, selFn.invokePointer(arrayOf<Any>("sharedApplication")))
            ) ?: return false
            val selActivate = selFn.invokePointer(arrayOf<Any>("activateIgnoringOtherApps:"))

            // BOOL 参数按机器字长整型传 1（arm64/x86_64 上 objc_msgSend 的 BOOL 位宽足够容纳）
            msgSendFn.invoke(Void.TYPE, arrayOf<Any>(shared, selActivate, 1))
            true
        }.onFailure {
            Log.w(TAG, "activateIgnoringOtherApps 失败: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * 取消「隐藏应用」并激活（与 [hideApp] 配对）。
     * 托盘菜单「显示主窗口」、剪贴板弹窗「打开」等唤回路径先调它，再置前窗口。
     */
    fun unhideAndActivate(): Boolean {
        val l = objcLib ?: return false
        return runCatching {
            val getClassFn = l.getFunction("objc_getClass")
            val selFn = l.getFunction("sel_registerName")
            val msgSendFn = l.getFunction("objc_msgSend")

            val cls = getClassFn.invokePointer(arrayOf<Any>("NSApplication"))
                ?: return false
            val shared = msgSendFn.invokePointer(
                arrayOf<Any>(cls, selFn.invokePointer(arrayOf<Any>("sharedApplication")))
            ) ?: return false
            msgSendFn.invoke(
                Void.TYPE,
                arrayOf<Any>(shared, selFn.invokePointer(arrayOf<Any>("unhide:")), com.sun.jna.Pointer.NULL)
            )
            msgSendFn.invoke(
                Void.TYPE,
                arrayOf<Any>(shared, selFn.invokePointer(arrayOf<Any>("activateIgnoringOtherApps:")), 1)
            )
            true
        }.onFailure {
            Log.w(TAG, "unhideAndActivate 失败: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * 隐藏整个应用（等价 Cmd+H）。macOS 的系统级行为保证：点击 Dock 图标即可恢复显示。
     *
     * 用它替代「隐藏主窗口」实现最小化到托盘——AWT 的 isVisible=false 会被 Compose
     * 状态同步回滚，而且点 Dock 图标不会恢复；NSApp hide 由系统托管，恢复路径可靠。
     */
    fun hideApp(): Boolean {
        val l = objcLib ?: return false
        return runCatching {
            val getClassFn = l.getFunction("objc_getClass")
            val selFn = l.getFunction("sel_registerName")
            val msgSendFn = l.getFunction("objc_msgSend")

            val cls = getClassFn.invokePointer(arrayOf<Any>("NSApplication"))
                ?: return false
            val shared = msgSendFn.invokePointer(
                arrayOf<Any>(cls, selFn.invokePointer(arrayOf<Any>("sharedApplication")))
            ) ?: return false
            val selHide = selFn.invokePointer(arrayOf<Any>("hide:"))
            msgSendFn.invoke(Void.TYPE, arrayOf<Any>(shared, selHide, com.sun.jna.Pointer.NULL))
            true
        }.onFailure {
            Log.w(TAG, "hideApp 失败: ${it.message}")
        }.getOrDefault(false)
    }
}
