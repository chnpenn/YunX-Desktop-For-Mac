package com.yunx.app.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.awt.ComposeWindow
import com.yunx.app.ui.login.GuangYaLoginScreen
import com.yunx.app.ui.viewmodel.GuangYaAccountViewModel
import com.yunx.app.data.repository.GuangYaAccountRepository
import com.yunx.app.data.network.GuangYaApi
import com.yunx.app.data.db.*
import kotlinx.coroutines.flow.flowOf
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import java.awt.*
import java.awt.event.*
import javax.swing.SwingUtilities

fun main() {
    println("RUNTIME ${System.getProperty("java.runtime.version")} / ${System.getProperty("java.vendor")}")
    val dao = object: GuangYaAccountDao {
        override fun observeAccount() = flowOf<GuangYaAccountEntity?>(null)
        override fun observeAccounts() = flowOf(emptyList<GuangYaAccountEntity>())
        override suspend fun getAccount(): GuangYaAccountEntity? = null
        override suspend fun getAccounts() = emptyList<GuangYaAccountEntity>()
        override suspend fun upsert(account: GuangYaAccountEntity) { error("Probe disallows login/persistence") }
        override suspend fun setPrimary(id: String) = error("unsupported")
        override suspend fun updateRemark(id: String, remark: String) = error("unsupported")
        override suspend fun deleteById(id: String) = error("unsupported")
        override suspend fun clear() = error("unsupported")
    }
    // No proceed(): all requests terminate in memory before DNS/socket creation.
    val client = OkHttpClient.Builder().addInterceptor { chain ->
        val path = chain.request().url.encodedPath
        val body = when (path) {
            "/v1/shield/captcha/init" -> "{}"
            "/v1/auth/verification" -> """{"verification_id":"offline-probe","is_user":true,"expires_in":600}"""
            else -> """{"error":"offline_probe","error_description":"Probe blocks this operation"}"""
        }
        println("MOCK $path")
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Offline probe")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val model = GuangYaAccountViewModel(GuangYaAccountRepository(dao, GuangYaApi { client }))
    Toolkit.getDefaultToolkit().addAWTEventListener({ e ->
        if (java.lang.Boolean.getBoolean("yunx.ime.probeInactiveSelection") && e is FocusEvent && e.id == FocusEvent.FOCUS_LOST) {
            val ctx = e.component.inputContext
            javax.swing.Timer(200) {
                try {
                    val f = Class.forName("sun.awt.im.InputContext").getDeclaredField("inputMethod").apply { isAccessible = true }
                    val im = f.get(ctx)
                    if (im != null && im.javaClass.name == "sun.lwawt.macosx.CInputMethod") {
                        val focus = im.javaClass.getDeclaredField("fAwtFocussedComponent").apply { isAccessible = true }.get(im)
                        println("LIFECYCLE after real focus loss: component=${focus?.javaClass?.name}")
                        if (focus == null) {
                            val method = im.javaClass.getDeclaredMethod("selectedRange").apply { isAccessible = true }
                            try { println("LIFECYCLE delayed selection=" + (method.invoke(im) as IntArray).contentToString()) }
                            catch (ex: java.lang.reflect.InvocationTargetException) { ex.cause!!.printStackTrace() }
                        }
                    }
                } catch (ex: Exception) { ex.printStackTrace() }
            }.apply { isRepeats = false; start() }
        }
        if (e is FocusEvent || e is WindowEvent) println("EVENT ${System.currentTimeMillis()} $e")
    }, AWTEvent.FOCUS_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK or AWTEvent.WINDOW_FOCUS_EVENT_MASK)
    SwingUtilities.invokeLater {
        ComposeWindow().apply {
            title = "YunX 光鸭离线输入法诊断（不会发送短信）"
            setSize(700, 760);setLocation(100,100)
            defaultCloseOperation = javax.swing.WindowConstants.DISPOSE_ON_CLOSE
            addWindowListener(object: WindowAdapter() {override fun windowClosed(e: WindowEvent) {System.exit(0)}})
            setContent { MaterialTheme { GuangYaLoginScreen(model, {}, {}) } }
            isVisible = true
        }
    }
}
