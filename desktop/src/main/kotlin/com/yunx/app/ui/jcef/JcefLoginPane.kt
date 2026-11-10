package com.yunx.app.ui.jcef

import com.yunx.app.ui.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.SnackbarController
import com.yunx.app.util.BrowserCookieImporter
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import javax.swing.JPanel

private const val TAG = "YunX-JCEF-UI"

/**
 * 内嵌浏览器登录面板（方案 B）：
 * - SwingPanel 嵌入真实 Chromium（JCEF），加载网盘登录页；
 * - 每 1.5 秒轮询全局 Cookie，检测到必需键后提示「已检测到登录态」，一键保存；
 * - 底部提供「自动导入浏览器 Cookie」（方案 A）与「手动粘贴」备选。
 *
 * @param requiredKeys 必须全部出现的 Cookie 键名（与键名集合取交集，全命中才算登录态）
 * @param anyOfKeys 候选键名（任一出现即视为登录态）；用于网页版仅下发 authorization、
 *                  客户端版仅下发 Os_SSo_Sid 的双形态平台（如 139 网盘）兜底
 */
@Composable
fun JcefLoginPane(
    loginUrl: String,
    domains: List<String>,
    requiredKeys: List<String>,
    platform: String,
    onSave: suspend (String) -> Boolean,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSwitchToPaste: () -> Unit,
    anyOfKeys: List<String> = emptyList()
) {
    val scope = rememberCoroutineScope()
    val app = JcefHolder.app()
    var detectedCookie by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var browserFailure by remember { mutableStateOf<String?>(null) }

    // 创建浏览器（Compose 桌面 UI 线程即 EDT，JCEF 要求在 EDT 上创建）
    val browserHolder = remember {
        if (app == null) {
            null
        } else {
            try {
                val client: CefClient = app.createClient()
                val browser: CefBrowser = client.createBrowser(loginUrl, false, false)
                // 不调用 createImmediately()：它会在画布尚未挂到窗口（尺寸为 0）时
                // 强行创建原生浏览器窗口，首次绘制丢失 → 白屏直到手动缩放窗口。
                // 交给 java-cef 在画布 addNotify（拿到真实尺寸）时自动创建，
                // 登录页一显示画布即挂载，页面加载与 Cookie 检测仅延迟几毫秒。
                BrowserHolder(client, browser)
            } catch (t: Throwable) {
                browserFailure = "内嵌浏览器启动失败：${t.message}"
                Log.e(TAG, "create browser failed", t)
                null
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { browserHolder?.browser?.close(true) }
            runCatching { browserHolder?.client?.dispose() }
        }
    }

    BackHandler(enabled = !isSaving) { onBack() }

    // 轮询 Cookie：检测必需键齐全后置为 detectedCookie
    // （visitAllCookies 回调在 CEF IO 线程异步执行，需等待其完成再判断，见 collectCookies）
    LaunchedEffect(browserHolder) {
        if (browserHolder == null) return@LaunchedEffect
        while (true) {
            val cookie = withContext(Dispatchers.IO) { collectCookies(domains, requiredKeys, anyOfKeys) }
            if (cookie != null) detectedCookie = cookie
            delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部导航栏：左上角返回（标准 IconButton 悬停高亮，与手动 Cookie 页一致）。
        // 登录页是提前 return 的覆盖层，不在 MainScreen 的 LocalContentColor 提供者内，
        // 默认黑色会让 IconButton 的悬停状态层在深色主题下变成"变暗"而非亮色高亮——
        // 这里显式提供 onSurface，高亮层与 TopAppBar 内的按钮观感一致。
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { if (!isSaving) onBack() },
                    enabled = !isSaving
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
                Text(
                    text = "${platform}登录",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // 状态卡
        Surface(
            color = if (detectedCookie != null) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (detectedCookie != null) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "已检测到登录态",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val cookie = detectedCookie ?: return@Button
                            scope.launch {
                                isSaving = true
                                val ok = onSave(cookie)
                                isSaving = false
                                if (ok) {
                                    SnackbarController.show("登录成功")
                                    onSaved()
                                } else {
                                    SnackbarController.show("Cookie 校验失败，请确认已登录")
                                }
                            }
                        },
                        enabled = !isSaving
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("保存登录")
                        }
                    }
                } else {
                    Icon(
                        Icons.Outlined.Wifi,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = browserFailure ?: "请在下方网页中登录，登录成功后自动检测",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 内嵌浏览器区域
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (browserHolder != null) {
                SwingPanel(
                    factory = {
                        // 强制画布尺寸与容器同步：Compose 侧尺寸变化时（窗口缩放/页面切换动画）
                        // BorderLayout 不一定立即重新布局 JCEF 画布，偶发"浏览器不铺满/留白"。
                        // 每次布局后校正画布边界，其内置 ComponentListener 会联动 CEF 原生窗口缩放。
                        object : JPanel(BorderLayout()) {
                            override fun doLayout() {
                                super.doLayout()
                                val canvas = browserHolder.browser.uiComponent
                                if (canvas.width != width || canvas.height != height ||
                                    canvas.x != 0 || canvas.y != 0
                                ) {
                                    canvas.setBounds(0, 0, width, height)
                                }
                            }
                        }.apply {
                            background = java.awt.Color.WHITE
                            add(browserHolder.browser.uiComponent, BorderLayout.CENTER)
                            addComponentListener(object : java.awt.event.ComponentAdapter() {
                                override fun componentResized(e: java.awt.event.ComponentEvent) {
                                    browserHolder.browser.uiComponent.setBounds(0, 0, width, height)
                                }
                            })
                            javax.swing.SwingUtilities.invokeLater { validate() }
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    background = Color.White
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = browserFailure ?: "内嵌浏览器不可用",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // 底部备选入口
        // ★ Mac 移植：「自动导入浏览器 Cookie」整体屏蔽（见 MAC_BROWSER_COOKIE_IMPORT_ENABLED），
        //   「手动粘贴」也一并隐藏——网页登录 + 自动检测已覆盖主路径，粘贴模式仅在浏览器不可用时
        //   有意义（不可用时本页会直接报错引导）。恢复时把整段 if 的条件去掉即可。
        if (MAC_BROWSER_COOKIE_IMPORT_ENABLED || !System.getProperty("os.name", "").lowercase().contains("mac")) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AutoImportCookieButton(
                    platform = platform,
                    onImported = onSave,
                    onSaved = onSaved,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onSwitchToPaste,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("手动粘贴")
                }
            }
        }
    }
}

private class BrowserHolder(val client: CefClient, val browser: CefBrowser)

/**
 * ★ Mac 移植：一键导入浏览器 Cookie 的 mac 开关（当前屏蔽）。
 * 夸克 / UC 两个平台已实测打通（发现 Chrome → Keychain 解密 → 32B 信封剥离 → 校验入库），
 * 其余平台（百度 / 139 / 115 / 123 / 蓝奏等）的凭证键与校验链路未逐一验证，统一先不放开。
 * 后续放开：改回 true，或按 platform 白名单（QUARK / UC 先行）精细控制。
 */
private const val MAC_BROWSER_COOKIE_IMPORT_ENABLED = false

/**
 * 从全局 Cookie 管理器收集目标域 Cookie；必需键齐全时返回 "k=v; ..." 串。
 *
 * JCEF 的 visitAllCookies 是**异步**的：CefCookieVisitor.visit 回调在 CEF IO 线程执行，
 * visitAllCookies 返回时回调可能尚未开始/完成。因此调用后必须等待一小段窗口
 * 让回调把结果写入（线程安全收集容器），再判断必需键。
 *
 * 登录态判定：[requiredKeys] 全命中 OR [anyOfKeys] 任一命中即视为有效。
 * - requiredKeys 用于严格平台（如夸克：必须有 buser-u。
 * - anyOfKeys 用于双形态平台（如 139：客户端下发 Os_SSo_Sid，网页版仅下发 authorization）。
 */
private fun collectCookies(
    domains: List<String>,
    requiredKeys: List<String>,
    anyOfKeys: List<String> = emptyList()
): String? {
    return runCatching {
        val manager = CefCookieManager.getGlobalManager()
        val pairs = java.util.concurrent.ConcurrentLinkedQueue<Pair<String, String>>()
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                val domain = cookie.domain ?: ""
                if (domains.any { domain.contains(it) }) {
                    pairs.add((cookie.name ?: "") to (cookie.value ?: ""))
                }
                return true
            }
        })
        // 等待 IO 线程回调完成（CEF 遍历 Cookie 库很快，几百毫秒足够）
        Thread.sleep(400)
        val all = pairs.toList()
        if (all.isNotEmpty()) {
            Log.d(TAG, "cookies for $domains: ${all.size} (keys=${all.map { it.first }.distinct()})")
        }
        val names = all.map { it.first }.toSet()
        // 必需键全命中 OR anyOfKeys 任一命中；anyOfKeys 为空时仅看 requiredKeys
        val requiredOk = requiredKeys.all { names.contains(it) }
        val anyOk = anyOfKeys.isNotEmpty() && anyOfKeys.any { names.contains(it) }
        if (!requiredOk && !anyOk) return null
        all.joinToString("; ") { "${it.first}=${it.second}" }
    }.getOrNull()
}

/**
 * 方案 A 入口按钮 + 对话框：扫描本机浏览器并列出可导入的 Cookie。
 * 可用于内嵌浏览器模式与纯粘贴模式。
 */
@Composable
fun AutoImportCookieButton(
    platform: String,
    onImported: suspend (String) -> Boolean,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier
) {
    // ★ Mac 移植：一键导入浏览器 Cookie 在 mac 上整体屏蔽（仅夸克/UC 实测可用，
    //   其余平台的凭证格式与校验未验证通过，点导入只会得到「没有凭证」的困扰）。
    //   ★ 放开方式：把下面的开关改回 true 即可；想只放开部分平台，可改为按 platform 白名单判断。
    if (System.getProperty("os.name", "").lowercase().contains("mac") &&
        !MAC_BROWSER_COOKIE_IMPORT_ENABLED
    ) {
        return
    }

    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<BrowserCookieImporter.FoundCookies>>(emptyList()) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scope.launch {
            scanning = true
            errorMsg = null
            com.yunx.app.util.Log.i("YunX-Cookie-UI", "scan() 开始 platform=$platform")
            try {
                val found = withContext(Dispatchers.IO) {
                    val domains = BrowserCookieImporter.domainSuffixes(platform)
                    BrowserCookieImporter.discoverBrowsers()
                        .also { com.yunx.app.util.Log.i("YunX-Cookie-UI", "发现 ${it.size} 个浏览器: ${it.joinToString { b -> b.id }}") }
                        .mapNotNull { browser ->
                            runCatching { BrowserCookieImporter.importFrom(browser, domains) }
                                .onFailure { com.yunx.app.util.Log.w("YunX-Cookie-UI", "导入 ${browser.id} 异常: ${it.message}") }
                                .getOrNull()
                        }
                }
                results = found
                scanning = false
                if (found.isEmpty()) {
                    errorMsg = "未在本机浏览器中找到该平台的登录 Cookie。\n" +
                        "提示：需先在浏览器中登录过对应网盘；Chrome/Edge 较新版本因安全限制可能无法读取，可尝试 Firefox 或 360 浏览器。"
                }
            } catch (t: Throwable) {
                com.yunx.app.util.Log.e("YunX-Cookie-UI", "scan() 整体异常", t)
                scanning = false
                errorMsg = "导入过程异常：${t.message ?: t.javaClass.simpleName}"
            }
        }
    }

    OutlinedButton(onClick = { show = true; scan() }, modifier = modifier) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
        Spacer(modifier = Modifier.size(6.dp))
        Text("自动导入浏览器 Cookie")
    }

    if (show) {
        // ★ Mac 移植：登录页的 JCEF 画布是重量级 SwingPanel（原生子窗口），mac 上会浮在
        //   Compose 内容之上，普通 AlertDialog 会被浏览器整个盖住（点击导入后「看起来没反应」，
        //   实际对话框已弹出但埋在浏览器底下）。改用独立置顶窗口（参照 BrowserHookDialogWindow），
        //   与主窗口平级、alwaysOnTop，任何平台都能弹到最前。
        androidx.compose.ui.window.Window(
            onCloseRequest = { if (!scanning) show = false },
            title = "自动导入浏览器 Cookie",
            undecorated = true,
            resizable = false,
            alwaysOnTop = true,
            state = androidx.compose.ui.window.rememberWindowState(
                size = androidx.compose.ui.unit.DpSize(460.dp, 320.dp),
                position = androidx.compose.ui.window.WindowPosition(Alignment.Center)
            )
        ) {
            val win = this.window
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) {
                    if (win.width > 0 && win.height > 0) {
                        runCatching {
                            win.shape = java.awt.geom.RoundRectangle2D.Float(
                                0f, 0f, win.width.toFloat(), win.height.toFloat(), 24f, 24f
                            )
                        }
                        break
                    }
                    kotlinx.coroutines.delay(50)
                }
            }
            com.yunx.app.ui.theme.ComposeEmptyActivityTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "自动导入浏览器 Cookie",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (scanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.size(10.dp))
                            Text("正在扫描本机浏览器…")
                        }
                    } else if (results.isNotEmpty()) {
                        results.forEach { found ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = found.browserName,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            text = "找到 ${found.count} 个 Cookie",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            com.yunx.app.util.Log.i("YunX-Cookie-UI", "点「导入」 browser=${found.browserName} count=${found.count}")
                                            scope.launch {
                                                scanning = true
                                                com.yunx.app.util.Log.i("YunX-Cookie-UI", "开始校验导入 Cookie(调 onSave)…")
                                                // ★ onSave 里可能有网络/解密层抛出的异常（如 OkHttp 拒绝非法
                                                //   Cookie 头），不接住会把协程打死、scanning 卡 true、按钮全灰
                                                val ok = try {
                                                    onImported(found.cookie)
                                                } catch (t: Throwable) {
                                                    com.yunx.app.util.Log.e("YunX-Cookie-UI", "onSave 异常", t)
                                                    false
                                                }
                                                com.yunx.app.util.Log.i("YunX-Cookie-UI", "onSave 返回 ok=$ok")
                                                scanning = false
                                                if (ok) {
                                                    show = false
                                                    SnackbarController.show("登录成功")
                                                    onSaved()
                                                } else {
                                                    errorMsg = "导入的 Cookie 中没有该网盘的登录凭证——" +
                                                        "请先在系统浏览器里登录 $platform 官网，再回来点「导入」"
                                                }
                                            }
                                        },
                                        enabled = !scanning
                                    ) { Text("导入") }
                                }
                            }
                        }
                    }
                    // ★ 校验失败的提示必须独立于「结果列表」显示：
                    //   扫描有结果但点导入后校验不过（如浏览器里没登录该网盘）时，
                    //  errorMsg 只在 else-if 尾部显示的旧写法会把它整个吞掉（表现为「点了没反应」）
                    if (errorMsg != null) {
                        Text(
                            text = errorMsg ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(
                        onClick = { if (!scanning) show = false },
                        enabled = !scanning,
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("关闭") }
                }
            }
        }
    }
}
