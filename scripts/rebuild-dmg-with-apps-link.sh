#!/bin/bash
# ★ Mac 移植：jpackage 生成的 DMG 只含 .app，没有「Applications」快捷方式，
#   用户打开 DMG 后无处可拖（无法完成安装）。本脚本在 packageDmg 之后重建 DMG：
#   挂载 jpackage 产物 → 建 Applications 符号链接（标准 mac 安装盘布局）→ 重新压盘。
# 由 build.gradle.kts 的 copyDmgToDownloads 同批次调用（见 gradle 任务 RebuildDmgWithAppsLink）。

set -e
APP_DMG="$1"
if [ -z "$APP_DMG" ] || [ ! -f "$APP_DMG" ]; then
    echo "用法: $0 <dmg路径>"; exit 1
fi

MOUNT=/tmp/yunx-dmg-rebuild
VOLNAME="YunX-Desktop"

hdiutil detach "$MOUNT" -quiet 2>/dev/null || true
rm -rf "$MOUNT"

# 1. 挂载 jpackage 原始产物
hdiutil attach "$APP_DMG" -nobrowse -readonly -mountpoint "$MOUNT" > /dev/null

# 2. 建临时目录：拷出 .app + 建 Applications 链接
STAGE=$(mktemp -d)
cp -R "$MOUNT/$VOLNAME.app" "$STAGE/"
ln -s /Applications "$STAGE/Applications"
hdiutil detach "$MOUNT" -quiet

# 3. 重新压盘（替换原文件）
rm -f "$APP_DMG"
hdiutil create -volname "$VOLNAME" -srcfolder "$STAGE" -ov -format UDZO "$APP_DMG" > /dev/null
rm -rf "$STAGE"

# 4. 校验
hdiutil attach "$APP_DMG" -nobrowse -readonly -mountpoint "$MOUNT" > /dev/null
if [ -e "$MOUNT/Applications" ] && [ -d "$MOUNT/$VOLNAME.app" ]; then
    echo "DMG 重建完成：含 $VOLNAME.app + Applications 快捷方式"
else
    echo "警告：DMG 重建后缺少预期内容" >&2
fi
hdiutil detach "$MOUNT" -quiet
