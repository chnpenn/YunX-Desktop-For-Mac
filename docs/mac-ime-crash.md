# macOS 输入法闪退：运行时修复与诊断

## 1.3.5 正式打包状态

用户反馈当前版本未再闪退，并于 2026-11-13 授权生成正式 DMG、提交仓库。
1.3.5（Beta=0）沿用已测试的 JBR 与 Metal 设置，同时包含光鸭登录结果传播修复、
Pixiv 地址修正及 Pixiv／光鸭界面入口隐藏；已有账号数据保留。
此反馈不替代长期稳定性证明，下面保留原实验方法和验证边界。

## 2026-11-13 结论与证据边界

当前用户报告为 1.3.4、macOS 15.8.2 ARM64、SCIM，主线程
`AWTView selectedRange +788 → SIGTRAP`。与 Java2D Metal 销毁崩溃不同。
本次新附件自身时间为 20:16:32，不能当成晚于此前 20:19:45 的新时间戳。
光鸭登录是 Compose 表单，不使用 JCEF。

本机针对无焦点选区查询的实测：

| 对象 | 结果 |
| --- | --- |
| 当前安装包内 Temurin 21.0.13+9-LTS | `NullPointerException: Null component provided to invokeAndWait` |
| Oracle 21.0.8 | 同一 NPE |
| 官方 JBR SDK 21.0.11+10-b1163.125 | 返回 `[0, 0]`，退出码 0 |
| 候选 app 内实际 jlink 运行时 | 返回 `[0, 0]`，退出码 0 |

真实光鸭表单的隔离诊断进一步确认：正常窗口失焦后，保留的 CInputMethod
对象的 `fAwtFocussedComponent` 可以为 null。此时主动补发 `selectedRange`
查询，原安装运行时抛同一 NPE，候选运行时返回空选区。
**查询是诊断程序主动触发的；没有自然复现用户的 native SIGTRAP，也没有取得
用户崩溃现场的原始 Java 栈。** 因此这是针对已证实运行时缺陷的修复候选，
不能宣称所有间歇性崩溃均已解决。

依据：
- [OpenJDK 21.0.13 CInputMethod](https://github.com/openjdk/jdk21u/blob/jdk-21.0.13-ga/src/java.desktop/macosx/classes/sun/lwawt/macosx/CInputMethod.java)
- [JBR 21 源码](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr-release-21.0.11b1163.125/src/java.desktop/macosx/classes/sun/lwawt/macosx/CInputMethod.java)：`selectedRange` 检查输入上下文和焦点；其他输入法回调亦有保护。
- [所选官方发布](https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr-release-21.0.11b1163.125)

## 实施范围

- 使用包含上述上游保护的 JBR 21 候选；不更改光鸭按钮、强制清焦点或禁用中文输入法。
- 保留 `-Dsun.java2d.metal=false`，不启用 `JNU_NO_COCOA_EXCEPTION`。
- `verifyMacImeRuntime` 在创建运行时/开发启动前执行行为探针，拒绝存在该缺陷的运行时。
- 修复 Compose 1.7.3 切换 javaHome 后仍重用旧镜像的问题：路径及 release 为构建输入。
  本次实际观察到指定 JBR 后旧镜像仍为 21.0.8；修正后重新生成 21.0.11。
- `verifyPackagedRuntime` 校验包内版本并通过临时启动器使用**包内实际运行时**执行探针。
  jlink 的 release 会删除厂商等字段，不能依赖这些字段判断修复是否落地。
- 发布工作流使用相同的固定下载、SHA-512 和行为检查。此次未执行 CI、提交、推送或发布。

注意：JBR 21.0.11 与 Temurin 21.0.13 是不同发行构建，**不是补丁号升级**。
本次选择依据是具体输入法保护及对照实验，不是“版本更高”或安全补丁等价声明。
长期正式采用前仍需评估运行时维护、安全补丁与完整应用兼容性；后续更新须保留行为检查。

## 构建与回归

```bash
export YUNX_RUNTIME_JAVA_HOME="$(bash scripts/setup-macos-runtime.sh)"
# 本次验证也以该 JBR 为 Gradle/测试 JVM；Java 目标主版本仍为 21。
export JAVA_HOME="$YUNX_RUNTIME_JAVA_HOME"
./gradlew -I scripts/diagnostics/isolated-tests.gradle \
  :desktop:test :desktop:verifyPackagedRuntime --offline --console=plain
```

setup 脚本只写项目 `.gradle/yunx-runtime`，不改系统 Java 或已安装应用。
首次下载需网络，普通 Gradle 打包不会自动下载。显式 `-PruntimeJavaHome` 的优先级高于环境变量。

本次隔离测试：91 项通过，0 失败。读取真实浏览器 Cookie 的手动测试明确排除；
内存 PreferencesFactory 隔离 macOS 偏好，独立 home/data/tmp 隔离测试数据。
曾尝试 FileSystemPreferencesFactory，但其 chmod native 在 macOS 不适用；改用内存工厂后通过。

包内探针可单独执行，旧版本退出非零是预期故障证据：

```bash
python3 scripts/diagnostics/verify-packaged-ime.py \
  /Applications/YunX-Desktop.app "$YUNX_RUNTIME_JAVA_HOME"
```

它仅创建临时启动器和配置，以符号链接只读使用目标 runtime，不加载应用主入口或账号数据。

## 真实光鸭表单离线诊断

```bash
./gradlew :desktop:runGuangYaImeDiagnostic --offline --console=plain \
  > /tmp/yunx-ime-form.log 2>&1
# 需要人工补发无焦点查询时，另加 -PprobeInactiveSelection=true
```

诊断入口位于 test 源集，不进入发布包。使用真实 GuangYaLoginScreen / ViewModel / API，
内存 DAO 和偏好，无真实账号。OkHttp 拦截器直接返回固定响应，从不调用 proceed，
不经过 DNS/连接、不发真实短信。未知请求也在内存终止，不允许登录持久化。
JNU_APPKIT_TRACE 保存 native 回调中的 Java 异常；不要输入真实凭据。

已核对候选：英文按键输入、窗口失焦、密码显示切换、验证码模式切换、
模拟发码提示与倒计时、最小化/恢复。mock 日志确认两条请求均由内存响应。
自动化 Unicode type-text 没有证明中文文本进入输入框，不计为中文输入法通过。
待人工验收：SCIM 候选词未提交时切换输入框、候选词提交、选区与复制粘贴、
输入法菜单、长期最小化后台使用，以及完整应用 JCEF/视频/绘制回归。

本机本轮原始日志：`/tmp/yunx-ime-investigation/`，包括 installed-regression.log、
original-lifecycle.log、candidate-lifecycle.log、final-verification.log。
这些临时路径不是仓库交付物，关键结果已在本文保留。
