#!/bin/bash
# Rebuild jpackage's DMG in place, then let Gradle copy that exact artifact.
# Requires create-dmg (brew install create-dmg) and a GUI session with Finder
# automation allowed. Styling failures are fatal; the original stays untouched.
set -euo pipefail

if [[ $# -ne 1 || ! -f "$1" ]]; then
    echo "Usage: $0 <dmg-path>" >&2
    exit 1
fi
command -v create-dmg >/dev/null || {
    echo "create-dmg is required: brew install create-dmg" >&2
    exit 1
}
APP_DMG="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BACKGROUND="$SCRIPT_DIR/dmg-background.png"
[[ -f "$BACKGROUND" ]] || { echo "Missing background: $BACKGROUND" >&2; exit 1; }

# Mount points and staging must live on the root filesystem: external volumes
# are frequently mounted `nodev`, where hdiutil attach -mountpoint is denied.
# Only the finished DMG stays next to the target, so the final swap is atomic.
WORK=$(mktemp -d "${TMPDIR:-/tmp}/yunx-dmg.XXXXXX")
MOUNT="$WORK/mount"
STAGE="$WORK/stage"
TMP_DMG="$(dirname "$APP_DMG")/.yunx-styled.$$.dmg"
MOUNTED=false
cleanup() {
    local status=$?
    trap - EXIT
    if [[ "$MOUNTED" == true ]]; then
        if ! hdiutil detach "$MOUNT" -quiet; then
            echo "Could not detach $MOUNT; retained temporary files: $WORK" >&2
            exit 1
        fi
    fi
    rm -rf "$WORK"
    # TMP_DMG lives beside the target (outside WORK); drop it on any non-success exit.
    if [[ "$status" -ne 0 && -f "$TMP_DMG" ]]; then
        rm -f "$TMP_DMG"
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$MOUNT" "$STAGE"
hdiutil attach "$APP_DMG" -nobrowse -readonly -mountpoint "$MOUNT" >/dev/null
MOUNTED=true
[[ -d "$MOUNT/YunX-Desktop.app" ]] || { echo "DMG is missing YunX-Desktop.app" >&2; exit 1; }
ditto "$MOUNT/YunX-Desktop.app" "$STAGE/YunX-Desktop.app"
hdiutil detach "$MOUNT" -quiet
MOUNTED=false

# create-dmg creates Applications itself. Pre-creating that link makes its ln
# fail before Finder can save the layout. Preserve diagnostics on any failure.
create-dmg \
    --volname "YunX-Desktop" \
    --background "$BACKGROUND" \
    --window-pos 200 120 \
    --window-size 640 420 \
    --icon-size 128 \
    --text-size 14 \
    --icon "YunX-Desktop.app" 160 200 \
    --app-drop-link 480 200 \
    --hide-extension "YunX-Desktop.app" \
    "$TMP_DMG" "$STAGE"

# Validate the compressed result before replacing the source. A missing Finder
# store means styling was not persisted; never report a plain image as styled.
hdiutil verify "$TMP_DMG" >/dev/null
hdiutil attach "$TMP_DMG" -nobrowse -readonly -mountpoint "$MOUNT" >/dev/null
MOUNTED=true
[[ -d "$MOUNT/YunX-Desktop.app" && -L "$MOUNT/Applications" &&
   "$(readlink "$MOUNT/Applications")" == /Applications &&
   -s "$MOUNT/.DS_Store" && -s "$MOUNT/.background/dmg-background.png" ]] || {
    echo "DMG validation failed: app, Applications link, Finder layout or background missing" >&2
    exit 1
}
MAGIC=$(dd if="$MOUNT/.DS_Store" bs=1 skip=4 count=4 2>/dev/null)
[[ "$MAGIC" == Bud1 ]] || { echo "Invalid Finder layout store" >&2; exit 1; }
hdiutil detach "$MOUNT" -quiet
MOUNTED=false
mv -f "$TMP_DMG" "$APP_DMG"
echo "DMG rebuilt and verified: $APP_DMG (128px icons, background and Applications link)"
