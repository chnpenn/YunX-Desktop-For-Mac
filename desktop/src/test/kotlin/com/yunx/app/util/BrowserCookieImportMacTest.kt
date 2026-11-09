package com.yunx.app.util

import com.yunx.app.AppContext
import org.junit.Test

/** mac Cookie 导入链路本机复现测试(手动跑,不进 CI) */
class BrowserCookieImportMacTest {
    @Test
    fun importFromChromeQuark() {
        AppContext.init()
        val browsers = BrowserCookieImporter.discoverBrowsers()
        println("TEST: 发现浏览器 = ${browsers.map { it.id }}")
        check(browsers.isNotEmpty()) { "未发现浏览器" }
        val domains = BrowserCookieImporter.domainSuffixes("QUARK")
        println("TEST: 域名 = $domains, 开始 importFrom(带 30s 看门狗)...")
        val worker = Thread {
            try {
                val found = BrowserCookieImporter.importFrom(browsers.first(), domains)
                println("TEST: importFrom 返回 = ${found?.browserName} count=${found?.count}")
            } catch (t: Throwable) {
                println("TEST: importFrom 抛异常 = ${t::class.simpleName}: ${t.message}")
            }
        }
        worker.isDaemon = true
        worker.start()
        worker.join(30_000)
        if (worker.isAlive) {
            println("TEST: importFrom 挂死!存活线程栈:")
            Thread.getAllStackTraces().forEach { (t, st) ->
                if (t === worker) st.forEach { println("    at $it") }
            }
            error("importFrom 30 秒未返回")
        }
        println("TEST: 完成")
    }
}
