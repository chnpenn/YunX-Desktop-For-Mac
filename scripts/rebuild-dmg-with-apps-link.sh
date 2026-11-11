#!/bin/bash
# ★ Mac 移植：jpackage 生成的 DMG 只含 .app，没有「Applications」快捷方式，
#   用户打开 DMG 后无处可拖（无法完成安装）。本脚本在 packageDmg 之后重建 DMG：
#   1) 挂载 jpackage 产物，拷出 .app
#   2) 建标准布局：.app 与 Applications 符号链接并排、位置对齐（Apple 官方风格）
#   3) 卷名改为「云析 Mac 版」，重新压盘
# 由 build.gradle.kts 的 copyDmgToDownloads 任务调用。

set -e
APP_DMG="$1"
if [ -z "$APP_DMG" ] || [ ! -f "$APP_DMG" ]; then
    echo "用法: $0 <dmg路径>"; exit 1
fi

MOUNT=/tmp/yunx-dmg-rebuild
STAGE=$(mktemp -d)
VOLNAME="YunX-Desktop"

hdiutil detach "$MOUNT" -quiet 2>/dev/null || true
rm -rf "$MOUNT"

# 1. 挂载 jpackage 原始产物，拷出 .app
hdiutil attach "$APP_DMG" -nobrowse -readonly -mountpoint "$MOUNT" > /dev/null
cp -R "$MOUNT/YunX-Desktop.app" "$STAGE/"
hdiutil detach "$MOUNT" -quiet

# 2. 建 Applications 符号链接（指向系统 Applications）
ln -s /Applications "$STAGE/Applications"

# 3. AppleScript 设置 Finder 视图：图标大小、网格对齐、两个图标并排靠上居中
#    （create volume 的临时盘先挂上，用 Finder 设置视图参数后再压盘）
hdiutil create -volname "$VOLNAME" -srcfolder "$STAGE" -ov -format UDRW -size 512m "${APP_DMG%.dmg}-tmp.dmg" > /dev/null
hdiutil attach "${APP_DMG%.dmg}-tmp.dmg" -nobrowse -mountpoint "$MOUNT" > /dev/null

osascript <<APPLESCRIPT > /dev/null 2>&1
tell application "Finder"
    tell disk "YunX-Desktop"
        open
        set current view of container window to icon view
        set toolbar visible of container window to false
        set statusbar visible of container window to false
        set bounds of container window to {260, 130, 860, 540}
        set icon size of container window to 128
        set arrangement of icon view of container window to not arranged
        set position of item "YunX-Desktop.app" of container window to {150, 80}
        set position of item "Applications" of container window to {360, 80}
        close
    end tell
end tell
APPLESCRIPT

sync
sleep 2
hdiutil detach "$MOUNT" -force -quiet 2>/dev/null || hdiutil detach "$MOUNT" -quiet

# 4. 转成只读压缩盘（发布用）
rm -f "$APP_DMG"
hdiutil convert "${APP_DMG%.dmg}-tmp.dmg" -format UDZO -o "$APP_DMG" > /dev/null
rm -f "${APP_DMG%.dmg}-tmp.dmg"
rm -rf "$STAGE"

# 5. 校验
hdiutil attach "$APP_DMG" -nobrowse -readonly -mountpoint "$MOUNT" > /dev/null
if [ -e "$MOUNT/Applications" ] && [ -d "$MOUNT/YunX-Desktop.app" ]; then
    echo "DMG 重建完成：$VOLNAME 卷名，.app + Applications 并排（Apple 官方风格布局）"
else
    echo "警告：DMG 重建后缺少预期内容" >&2
fi
hdiutil detach "$MOUNT" -quiet
