package com.yunx.app.util

import com.sun.jna.platform.win32.Crypt32Util
import com.yunx.app.AppContext
import java.io.File
import java.sql.DriverManager
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 一键从本机浏览器导入网盘登录 Cookie（方案 A）。
 *
 * Windows：
 * - Chromium 系（Chrome / Edge / 360 / QQ / 搜狗 / Opera / Brave 等）：
 *   Network\Cookies（SQLite）中的 encrypted_value 为 "v10" 前缀 + Windows DPAPI 加密载荷，
 *   用 CryptUnprotectData 解密（同一 Windows 用户下可解）。
 *   "v20" 前缀为「应用绑定加密」（Chrome 127+ / 较新 Edge），本应用无法解密 → 跳过该 Cookie。
 * - Firefox：cookies.sqlite 中 value 为明文，直接读取。
 *
 * ★ Mac 移植（macOS 分支）：
 * - Chromium 系：加密方案为 "v10" 前缀 + AES-128-CBC，密钥是 Keychain 里
 *   "<浏览器名> Safe Storage" 条目（security 命令读取；首次会弹 Keychain 授权框，允许即可），
 *   PBKDF2(1003 次, 16字节) 派生密钥，载荷前 3 字节为 "v10" + 12 字节 nonce + 密文(前16字节为IV)。
 * - Firefox：cookies.sqlite 明文，路径为 ~/Library/Application Support/Firefox/Profiles。
 */
object BrowserCookieImporter {

    private const val TAG = "YunX-Cookie"

    private val isMacOS: Boolean
        get() = System.getProperty("os.name", "").lowercase().let { it.contains("mac") || it.contains("darwin") }

    enum class Kind { CHROMIUM, FIREFOX }

    data class BrowserInfo(
        val id: String,
        val displayName: String,
        val kind: Kind,
        val profileDirs: List<File>
    )

    data class FoundCookies(
        val browserName: String,
        val cookie: String,
        val count: Int
    )

    /** 平台 → 需要匹配的 Cookie 域名后缀 */
    fun domainSuffixes(platform: String): List<String> = when (platform) {
        "QUARK" -> listOf(".quark.cn")
        "UC" -> listOf(".uc.cn")
        "BAIDU" -> listOf(".baidu.com")
        "C139" -> listOf("mail.10086.cn", "yun.139.com", ".10086.cn")
        "PAN115" -> listOf(".115.com", ".115cdn.com")
        else -> emptyList()
    }

    /** 发现本机已安装浏览器的 Cookie 库目录（存在 cookies 文件才算） */
    fun discoverBrowsers(): List<BrowserInfo> {
        // ★ Mac 移植：macOS 下 Chromium 系在 ~/Library/Application Support/<浏览器>，
        //   Firefox 在 ~/Library/Application Support/Firefox/Profiles
        if (isMacOS) return discoverBrowsersMac()
        val local = System.getenv("LOCALAPPDATA")?.let { File(it) } ?: return emptyList()
        val roaming = System.getenv("APPDATA")?.let { File(it) } ?: return emptyList()
        val out = mutableListOf<BrowserInfo>()

        // Chromium 系：<root>/Network/Cookies
        val chromiumCandidates = listOf(
            "Chrome" to listOf(File(local, "Google\\Chrome\\User Data")),
            "Edge" to listOf(File(local, "Microsoft\\Edge\\User Data")),
            "360极速浏览器" to listOf(File(local, "360Chrome\\Chrome\\User Data")),
            "360安全浏览器" to listOf(
                File(roaming, "360se6\\User Data"),
                File(roaming, "360se\\User Data"),
                File(local, "360se6\\User Data")
            ),
            "QQ浏览器" to listOf(File(local, "Tencent\\QQBrowser\\User Data")),
            "搜狗浏览器" to listOf(
                File(roaming, "SogouExplorer\\User Data"),
                File(local, "SogouExplorer\\User Data")
            ),
            "Opera" to listOf(File(roaming, "Opera Software\\Opera Stable")),
            "Brave" to listOf(File(local, "BraveSoftware\\Brave-Browser\\User Data")),
            "Vivaldi" to listOf(File(local, "Vivaldi\\User Data"))
        )
        for ((name, roots) in chromiumCandidates) {
            val existing = roots.filter { root ->
                root.isDirectory && File(root, "Network\\Cookies").exists()
            }
            if (existing.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, existing)
            }
        }

        // Firefox：<Profiles>/xxx/cookies.sqlite
        val ffRoot = File(roaming, "Mozilla\\Firefox\\Profiles")
        if (ffRoot.isDirectory) {
            val profiles = ffRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").exists() }
                ?.toList() ?: emptyList()
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
            }
        }
        return out
    }

    /** 从指定浏览器导入指定平台的 Cookie；未找到返回 null */
    fun importFrom(browser: BrowserInfo, domains: List<String>): FoundCookies? =
        when (browser.kind) {
            Kind.CHROMIUM -> importChromium(browser, domains)
            Kind.FIREFOX -> importFirefox(browser, domains)
        }

    // ---------- Mac 浏览器发现 ----------

    /**
     * ★ Mac 移植：macOS 浏览器发现。
     * Chromium 系的 User Data 在 ~/Library/Application Support/<目录名>（Chrome 还有嵌套的
     * Google/Chrome），Cookie 库在 <profile>/Cookies（旧布局）或 <profile>/Network/Cookies（新布局）。
     */
    private fun discoverBrowsersMac(): List<BrowserInfo> {
        val appSupport = File(System.getProperty("user.home"), "Library/Application Support")
        val out = mutableListOf<BrowserInfo>()

        val chromiumCandidates = listOf(
            "Chrome" to listOf(File(appSupport, "Google/Chrome")),
            "Edge" to listOf(File(appSupport, "Microsoft Edge")),
            "Brave" to listOf(File(appSupport, "BraveSoftware/Brave-Browser")),
            "Vivaldi" to listOf(File(appSupport, "Vivaldi")),
            "Opera" to listOf(File(appSupport, "com.operasoftware.Opera")),
            "Arc" to listOf(File(appSupport, "Arc/User Data"))
        )
        for ((name, roots) in chromiumCandidates) {
            val existing = roots.filter { root ->
                root.isDirectory && (File(root, "Default/Cookies").exists() ||
                    File(root, "Default/Network/Cookies").exists())
            }
            if (existing.isNotEmpty()) {
                out += BrowserInfo("chromium-$name", name, Kind.CHROMIUM, existing)
            }
        }

        val ffRoot = File(appSupport, "Firefox/Profiles")
        if (ffRoot.isDirectory) {
            val profiles = ffRoot.listFiles { f -> f.isDirectory && File(f, "cookies.sqlite").exists() }
                ?.toList() ?: emptyList()
            if (profiles.isNotEmpty()) {
                out += BrowserInfo("firefox", "Firefox", Kind.FIREFOX, profiles)
            }
        }
        return out
    }

    // ---------- Chromium ----------

    private fun importChromium(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (root in browser.profileDirs) {
            // Windows：Network\Cookies；★ Mac：Default/Cookies 或 Default/Network/Cookies
            val cookiesFile = if (isMacOS) {
                listOf(File(root, "Default/Network/Cookies"), File(root, "Default/Cookies"))
                    .firstOrNull { it.exists() }
            } else {
                File(root, "Network\\Cookies").takeIf { it.exists() }
            } ?: continue
            val copy = File(AppContext.cacheDir, "browser-cookies-${System.nanoTime()}.db")
            try {
                cookiesFile.copyTo(copy, overwrite = true)
                val cookie = extractChromium(browser, copy, domains) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 ${browser.displayName} Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractChromium(browser: BrowserInfo, dbFile: File, domains: List<String>): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host_key LIKE ?" }
            conn.prepareStatement(
                "SELECT host_key, name, encrypted_value FROM cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = decryptChromiumValue(browser, rs.getBytes(3)) ?: continue
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }

    /**
     * Chromium encrypted_value：
     * - Windows："v10" 前缀 + DPAPI 载荷 → CryptUnprotectData 解密；
     *   "v20" 前缀（应用绑定加密，Chrome 127+）→ 返回 null（无法解密，跳过后整体提示）；
     *   无前缀（个别旧版）→ 直接按 DPAPI 尝试。
     * - ★ Mac："v10" 前缀 + AES-128-CBC，密钥从 Keychain 的 "<浏览器> Safe Storage" 读取，
     *   PBKDF2（迭代 1003 次）派生；"v20" 同样无法解密，返回 null。
     */
    private fun decryptChromiumValue(browser: BrowserInfo, bytes: ByteArray): String? {
        if (bytes.size < 3) return null
        var offset = 0
        if (bytes[0] == 'v'.code.toByte() &&
            bytes[1] in '0'.code.toByte()..'9'.code.toByte() &&
            bytes[2] in '0'.code.toByte()..'9'.code.toByte()
        ) {
            // v10 / v20 等版本前缀
            val version = (bytes[1] - '0'.code.toByte()) * 10 + (bytes[2] - '0'.code.toByte())
            if (version >= 20) return null // 应用绑定加密，无法解密
            offset = 3
        }
        val payload = if (offset > 0) bytes.copyOfRange(offset, bytes.size) else bytes
        if (isMacOS) return decryptChromiumValueMac(payload, browser.id)
        return runCatching {
            val plain = Crypt32Util.cryptUnprotectData(payload)
            String(plain, Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * ★ Mac 移植：Chromium mac 加密 Cookie 解密。
     * 流程：security find-generic-password 取 "<浏览器> Safe Storage" 口令
     * → PBKDF2-HMAC-SHA1(迭代 1003, 盐="saltysalt", 16字节密钥)
     * → AES-128-CBC，IV 固定为 16 个空格(0x20)，密文 PKCS#5 去填充。
     * （这是 Chromium os_crypt 在 mac 上的既定方案，v10 载荷去掉 3 字节前缀后即是密文）
     * 首次调用 macOS 会弹 Keychain 授权框，用户允许后同一应用不再重复询问。
     */
    private fun decryptChromiumValueMac(payload: ByteArray, browserId: String): String? {
        val passphrase = keychainPassphrase(browserId) ?: return null
        if (payload.size <= 16) return null
        return runCatching {
            val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            val spec = javax.crypto.spec.PBEKeySpec(passphrase.toCharArray(), SALT, 1003, 128)
            val key = factory.generateSecret(spec).encoded
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16) { ' '.code.toByte() }))
            val plain = String(cipher.doFinal(payload), Charsets.UTF_8)
            // ★ 只接受可打印 ASCII 的 Cookie 值。个别埋点 Cookie（_UP_* 等）解出来是二进制，
            //   String(UTF-8) 会产生 U+FFFD 替换符，拼进 Cookie 头时 OkHttp 直接抛
            //   「Unexpected char 0xfffd」→ 整个导入在校验昵称一步炸掉。
            //   登录凭证（__pus/__puus 等）都是纯 ASCII，不受影响；乱码 Cookie 主动丢弃。
            plain.takeIf { s -> s.none { c -> c < ' ' || c > '~' } }
        }.getOrNull()
    }

    /** Chromium mac AES 密钥派生固定盐："saltysalt"（os_crypt_mac.mm 里写死的 9 字节） */
    private val SALT = "saltysalt".toByteArray(Charsets.US_ASCII)

    /** 各浏览器 Keychain 的服务名/账户名映射（Chromium 按浏览器品牌写入不同条目） */
    private val MAC_KEYCHAIN_NAMES: Map<String, Pair<String, String>> = mapOf(
        "chromium-Chrome" to ("Chrome" to "Chrome Safe Storage"),
        "chromium-Edge" to ("Microsoft Edge" to "Microsoft Edge Safe Storage"),
        "chromium-Brave" to ("Brave" to "Brave Safe Storage"),
        "chromium-Vivaldi" to ("Vivaldi" to "Vivaldi Safe Storage"),
        "chromium-Opera" to ("Opera" to "Opera Safe Storage"),
        "chromium-Arc" to ("Arc" to "Arc Safe Storage")
    )

    /** 从 Keychain 读取 Safe Storage 口令（按浏览器分缓存；读取失败返回 null，该浏览器的解密全部跳过） */
    private val passphraseCache = HashMap<String, String?>()

    private fun keychainPassphrase(browserId: String): String? {
        synchronized(passphraseCache) {
            if (passphraseCache.containsKey(browserId)) return passphraseCache[browserId]
        }
        val names = MAC_KEYCHAIN_NAMES[browserId]
            ?: ("Chrome" to "Chrome Safe Storage") // 未知品牌退回 Chrome（不影响发现，只影响解密）

        val value: String? = runCatching {
            val p = ProcessBuilder(
                "security", "find-generic-password",
                "-w", "-a", names.first, "-s", names.second
            ).redirectErrorStream(false).start()
            // ★ 先带超时等待进程退出，再读输出（输出仅几十字节，不会撑满管道缓冲）。
            //   顺序反过来（先 readText 再 waitFor）时，security 等用户授权就永远不退出，
            //   readText 也永远读不到 EOF → 导入线程挂死、按钮永远转圈。
            //   8 秒未完成即视为「用户未授权」，强杀并明确失败（下次点击重试会再弹一次授权）。
            val finished = p.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                runCatching { p.destroyForcibly() }
                Log.w(TAG, "Keychain 读取超时（授权框可能被遮挡）：${names.second}")
                null
            } else {
                val out = p.inputStream.bufferedReader().readText().trim()
                out.takeIf { p.exitValue() == 0 && it.isNotBlank() }
            }
        }.getOrNull()

        synchronized(passphraseCache) { passphraseCache[browserId] = value }
        if (value == null) {
            Log.w(TAG, "Keychain 读取失败: ${names.second}（用户拒绝/超时/条目不存在），该浏览器 Cookie 将无法解密")
        }
        return value
    }

    // ---------- Firefox ----------

    private fun importFirefox(browser: BrowserInfo, domains: List<String>): FoundCookies? {
        for (profile in browser.profileDirs) {
            val dbFile = File(profile, "cookies.sqlite")
            if (!dbFile.exists()) continue
            val copy = File(AppContext.cacheDir, "ff-cookies-${System.nanoTime()}.db")
            try {
                dbFile.copyTo(copy, overwrite = true)
                val cookie = extractFirefox(copy, domains) ?: continue
                return FoundCookies(browser.displayName, cookie, cookie.split(';').size)
            } catch (e: Exception) {
                Log.w(TAG, "读取 Firefox Cookie 失败: ${e.message}")
            } finally {
                runCatching { copy.delete() }
            }
        }
        return null
    }

    private fun extractFirefox(dbFile: File, domains: List<String>): String? {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { conn ->
            val likeClause = domains.joinToString(" OR ") { "host LIKE ?" }
            conn.prepareStatement(
                "SELECT host, name, value FROM moz_cookies WHERE $likeClause"
            ).use { st ->
                domains.forEachIndexed { i, d -> st.setString(i + 1, "%$d%") }
                st.executeQuery().use { rs ->
                    val pairs = mutableListOf<String>()
                    while (rs.next()) {
                        val name = rs.getString(2)
                        val value = rs.getString(3)
                        if (name.isNotBlank()) pairs += "$name=$value"
                    }
                    if (pairs.isEmpty()) return null
                    return pairs.joinToString("; ")
                }
            }
        }
    }
}
