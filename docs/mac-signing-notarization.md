# macOS 签名与公证指南（YunX-Desktop-For-Mac 分发前置条件）

> 当前状态：`.app` 未签名（ad-hoc）。用户首次打开需「右键 → 打开」绕过 Gatekeeper；
> 从别人处拿到 DMG 会直接被拦（"无法验证开发者"）。正式分发给非技术用户前**必须**完成签名+公证。

## 一、前置条件

1. **Apple Developer Program** 账号（$99/年）：https://developer.apple.com
2. Xcode 或仅 Command Line Tools（`xcode-select --install`）
3. 在 https://developer.apple.com/account/resources/certificates 创建两个证书：
   - **Developer ID Application**（分发 .app 用）
   - （如分发 .pkg 安装器才需要 Developer ID Installer，DMG 不用）
4. 证书下载导入钥匙串后，确认：
   ```bash
   security find-identity -v -p codesigning
   # 应看到 "Developer ID Application: <你的名字> (<TEAM_ID>)"
   ```
   `TEAM_ID` 在开发者账号页的 Membership Details 里。

## 二、签名（codesign）

### 关键：JCEF 的所有 Helper 也要签

应用包内有多层二进制，**必须全部签名**，且顺序从内到外：

```bash
APP=/Applications/YunX-Desktop.app
IDENTITY="Developer ID Application: Your Name (TEAM_ID)"

# 1. JCEF 各 Helper 子应用（最内层）
find "$APP/Contents/app" -name "*.app" -o -path "*jcef-bundle*" -name "*.app" | while read h; do
  codesign --force --options runtime --sign "$IDENTITY" "$h"
done

# 2. 主可执行文件
codesign --force --options runtime --sign "$IDENTITY" "$APP/Contents/MacOS/YunX-Desktop"

# 3. 外层（--deep 兜底覆盖剩余嵌套，Apple 已不推荐 deep 但作为收尾校验有用）
codesign --force --options runtime --deep --sign "$IDENTITY" "$APP"
```

`--options runtime` = 启用 Hardened Runtime，公证的必要条件。

### 签名后注意

- **Keychain 权限重置**：签名后应用的身份变了，之前「始终允许」的 Chrome Safe Storage
  Keychain 授权会失效，用户需重新授权一次（Cookie 导入功能）。
- **单实例锁**：与本问题无关，但重新分发时提醒用户完全退出旧版本。

## 三、公证（notarization）

```bash
# 1. 打 zip 交公证（不是直接交 dmg）
ditto -c -k --keepParent "$APP" YunX-Desktop-mac.zip

# 2. 提交公证（App Store Connect API 密钥或 Apple ID 均可）
xcrun notarytool submit YunX-Desktop-mac.zip \
  --apple-id "your@email" --team-id "TEAM_ID" --password "app-specific-password" \
  --wait

# 3. 通过后 staple（把票据钉到 app 上，离线验证）
xcrun stapler staple "$APP"

# 4. 重新打 DMG（staple 后再打包，票据随包分发）
hdiutil create -volname "YunX-Desktop" -srcfolder "$APP" -ov -format UDZO YunX-Desktop-1.3.3.dmg

# 5. 校验
spctl -a -t exec -vv "$APP"
# 期望: accepted source=Notarized Developer ID
```

## 四、自动化建议

在 `desktop/build.gradle.kts` 的 packageDmg 后追加签名任务（示例，实际接入需环境变量存身份）：

```kotlin
// 仅在提供 MAC_CODESIGN_IDENTITY 环境变量时执行
tasks.register("signMacApp") {
    doLast {
        val identity = System.getenv("MAC_CODESIGN_IDENTITY") ?: return@doLast
        val app = layout.buildDirectory.file("compose/binaries/main/app/YunX-Desktop.app").get().asFile
        exec { commandLine("find", app.absolutePath, "-name", "*.app") }  // 逐层签
        exec { commandLine("codesign", "--force", "--options", "runtime", "--deep", "--sign", identity, app.absolutePath) }
    }
}
```

公证因需要 App 专用密码，建议放 CI（GitHub Actions + secrets）手动触发，不放本地。

## 五、常见坑

| 坑 | 表现 | 解法 |
|---|---|---|
| JCEF Helper 未签 | 公证失败 / 运行闪退 | 逐个签 helper 子应用 |
| Hardened Runtime 阻止 JIT | Chromium 渲染进程崩溃 | 加 entitlements: `com.apple.security.cs.allow-jit` + `allow-unsigned-executable-memory` |
| Keychain 授权失效 | Cookie 导入要求重新授权 | 预期行为，告知用户 |
| ad-hoc 签名残留 | `codesign -dv` 显示 Signature=adhoc | 用 --force 重签 |
| DMG 在 staple 前分发 | 用户仍被拦 | 严格按顺序：签 → 公证 zip → staple → 打 DMG |
