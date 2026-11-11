#!/bin/bash
# ★ Mac 移植：jpackage 生成的 DMG 只含 .app，没有「Applications」快捷方式，
#   用户打开 DMG 后无处可拖（无法完成安装）。本脚本在 packageDmg 之后重建 DMG：
#   1) 用 create-dmg 生成标准样式安装盘（app 与 Applications 并排居中、箭头引导）
#   2) 失败时降级为默认布局 DMG（仍含 Applications 链接，保证可安装）
# 由 build.gradle.kts 的 copyDmgToDownloads 任务调用。

set -e
APP_DMG="$1"
if [ -z "$APP_DMG" ] || [ ! -f "$APP_DMG" ]; then
    echo "用法: $0 <dmg路径>"; exit 1
fi

STAGE=$(mktemp -d)
VOLNAME="YunX-Desktop"
TMP_DMG="${APP_DMG%.dmg}-styled.dmg"

hdiutil detach "/Volumes/$VOLNAME" -force -quiet 2>/dev/null || true

# 1. 建 stage：.app + Applications 符号链接
cp -R "$APP_DMG" "$STAGE/.app-source" 2>/dev/null || true
hdiutil attach "$APP_DMG" -nobrowse -readonly > /dev/null
SRC_APP=$(ls -d /Volumes/*/YunX-Desktop.app 2>/dev/null | head -1)
if [ -z "$SRC_APP" ]; then
    # 原盘没有嵌套结构,直接挂载卷根找 .app
    SRC_APP=$(ls -d /Volumes/YunX-Desktop/*.app 2>/dev/null | head -1)
fi
hdiutil detach "/Volumes/YunX-Desktop" -quiet 2>/dev/null || true

if [ -z "$SRC_APP" ]; then
    echo "警告:找不到 YunX-Desktop.app,降级为默认布局"
    hdiutil convert "$APP_DMG" -format UDZO -o "$TMP_DMG" > /dev/null
    mv "$TMP_DMG" "$APP_DMG"
    rm -rf "$STAGE"
    exit 0
fi

cp -R "$SRC_APP" "$STAGE/YunX-Desktop.app"
ln -s /Applications "$STAGE/Applications"

# 2. create-dmg 生成标准样式（app 左 160,200 / Applications 右 420,200 / 窗口 640x420）
#    失败（工具未装等）时降级为默认布局
if command -v create-dmg > /dev/null 2>&1; then
    create-dmg \
        --volname "$VOLNAME" \
        --icon "YunX-Desktop.app" 160 200 \
        --app-drop-link 420 200 \
        --window-size 640 420 \
        --hide-extension "YunX-Desktop.app" \
        "$TMP_DMG" \
        "$STAGE" > /dev/null 2>&1 || {
        echo "create-dmg 失败,降级为默认布局"
        hdiutil create -volname "$VOLNAME" -srcfolder "$STAGE" -ov -format UDZO "$TMP_DMG" > /dev/null
    }
else
    hdiutil create -volname "$VOLNAME" -srcfolder "$STAGE" -ov -format UDZO "$TMP_DMG" > /dev/null
fi

# 3. 替换原 DMG
mv "$TMP_DMG" "$APP_DMG"
rm -rf "$STAGE"

# 4. 校验
hdiutil attach "$APP_DMG" -nobrowse -readonly > /dev/null
if [ -e "/Volumes/$VOLNAME/Applications" ] && [ -d "/Volumes/$VOLNAME/YunX-Desktop.app" ]; then
    echo "DMG 重建完成：create-dmg 标准样式（app + Applications 并排居中）"
else
    echo "警告：DMG 重建后缺少预期内容" >&2
fi
hdiutil detach "/Volumes/$VOLNAME" -quiet 2>/dev/null || true
