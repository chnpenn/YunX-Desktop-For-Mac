#!/usr/bin/env python3
"""Run the IME regression probe using the app's actual jlinked runtime.

The installed/built app is read-only. Only a temporary launcher/config is created.
No account data, main application code or network clients are loaded.
"""
import pathlib
import shutil
import subprocess
import sys
import tempfile

app = pathlib.Path(sys.argv[1]).resolve()
jdk = pathlib.Path(sys.argv[2]).resolve()
probe = pathlib.Path(__file__).with_name("InactiveInputMethodProbe.java")
with tempfile.TemporaryDirectory(prefix="yunx-ime-check-") as temp:
    root = pathlib.Path(temp)
    classes = root / "classes"
    classes.mkdir()
    subprocess.run([str(jdk / "bin/javac"), "-d", str(classes), str(probe)], check=True)
    contents = root / "Probe.app/Contents"
    (contents / "MacOS").mkdir(parents=True)
    (contents / "app").mkdir()
    shutil.copy2(app / "Contents/MacOS/YunX-Desktop", contents / "MacOS/YunX-Desktop")
    shutil.copy2(app / "Contents/Info.plist", contents / "Info.plist")
    for library in (app / "Contents/app").glob("*.dylib"):
        shutil.copy2(library, contents / "app" / library.name)
    (contents / "runtime").symlink_to(app / "Contents/runtime", target_is_directory=True)
    (contents / "app/YunX-Desktop.cfg").write_text(
        "[Application]\n"
        f"app.classpath={classes}\n"
        "app.mainclass=InactiveInputMethodProbe\n"
        "[JavaOptions]\n"
        "java-options=--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED\n"
    )
    subprocess.run([str(contents / "MacOS/YunX-Desktop")], check=True, timeout=30)
