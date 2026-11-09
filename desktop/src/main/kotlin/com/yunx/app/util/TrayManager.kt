package com.yunx.app.util

import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘管理器。
 *
 * 右键菜单用 JNA 调 Win32 TrackPopupMenu（CreatePopupMenu + AppendMenuW），
 * 中文正常、暗色跟随系统。不用 Swing、不自绘窗口。
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null

    /** 是否已安装托盘图标 */
    val isInstalled: Boolean
        get() = trayIcon != null

    /**
     * 安装托盘图标。
     * @param image 图标
     * @param onLeftClick 左键单击回调
     * @param onRightClick 右键单击回调（屏幕坐标 x, y）
     */
    fun install(
        image: BufferedImage,
        onLeftClick: () -> Unit,
        onRightClick: (Int, Int) -> Unit
    ) {
        if (!SystemTray.isSupported()) return
        if (trayIcon != null) uninstall()

        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Desktop").apply {
            isImageAutoSize = true
            // 空 PopupMenu：Windows 必须设才能收到右键事件
            popupMenu = PopupMenu()
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) onLeftClick()
                }
                override fun mousePressed(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON3) {
                        e.consume()
                        onRightClick(e.xOnScreen, e.yOnScreen)
                    }
                }
            })
        }
        runCatching {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    /**
     * 安装带菜单的托盘图标。
     *
     * ★ Mac 移植：macOS 菜单栏的托盘图标本质是「点开菜单」，右键事件模型与 Windows 不同，
     * Win32 TrackPopupMenu 那套（[install] + Win32PopupMenu）在 mac 上不适用。
     * 这里用 AWT PopupMenu——点击图标直接弹出，菜单项回调在 AWT 事件线程触发。
     *
     * @param image 图标
     * @param items 菜单项：标签 to 回调；标签 "-" 表示分隔线
     * @param onLeftClick 预留（mac 上点击即弹菜单，不单独触发左键回调）
     * @return 是否安装成功
     */
    fun installWithMenu(
        image: BufferedImage,
        items: List<Pair<String, () -> Unit>>,
        onLeftClick: () -> Unit
    ): Boolean {
        if (!SystemTray.isSupported()) return false
        if (trayIcon != null) uninstall()

        val popup = PopupMenu()
        for ((label, action) in items) {
            if (label == "-") {
                popup.addSeparator()
                continue
            }
            val item = MenuItem(label)
            item.addActionListener { action() }
            popup.add(item)
        }

        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Desktop").apply {
            isImageAutoSize = true
            popupMenu = popup
        }
        return runCatching {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            true
        }.getOrDefault(false)
    }

    /** 移除托盘图标 */
    fun uninstall() {
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
            trayIcon = null
        }
    }

    /**
     * 显示系统通知。
     *
     * 走 AWT 托盘图标的原生气泡（Windows 10/11 会归入通知中心）。
     * 注意：托盘图标必须处于可见状态，否则 Windows 不会显示。
     * 这里不能用 Compose 的 TrayState —— 托盘图标已改为手写 AWT TrayIcon，没有 TrayState 实例。
     */
    fun showNotification(title: String, message: String) {
        val icon = trayIcon ?: return
        runCatching { icon.displayMessage(title, message, TrayIcon.MessageType.NONE) }
            .onFailure { Log.w("YunX-Tray", "showNotification failed: ${it.message}") }
    }

    /** 从 classpath 加载 icon.png */
    fun loadTrayIcon(): BufferedImage? = runCatching {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        ImageIO.read(stream)
    }.getOrNull()
}
