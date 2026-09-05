#!/usr/bin/env python3
import re
import subprocess
import sys
from pathlib import Path

src = Path(sys.argv[1]).resolve()
out = sys.argv[2]

checks = {
    "is_desktop_android": "true",
    "enable_desktop_android_extensions": "true",
    "enable_extensions_core": "true",
}

for name, expected in checks.items():
    proc = subprocess.run(
        ["gn", "args", out, "--list=" + name, "--short"],
        cwd=src,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=True,
    )
    text = proc.stdout.strip()
    print(f"[{name}] {text}")
    m = re.search(rf"\b{re.escape(name)}\s*=\s*(true|false)\b", text)
    if not m or m.group(1) != expected:
        raise SystemExit(f"{name} must resolve to {expected}; got: {text}")

# Source guards: fail early if this Chromium tag does not contain the official route.
buildflags = src / "extensions/buildflags/buildflags.gni"
chrome_build = src / "build/config/chrome_build.gni"
for path in (buildflags, chrome_build):
    if not path.is_file():
        raise SystemExit(f"missing Chromium source file: {path}")

bf = buildflags.read_text(errors="replace")
cb = chrome_build.read_text(errors="replace")
required = [
    "enable_desktop_android_extensions",
    "enable_extensions_core",
]
for token in required:
    if token not in bf:
        raise SystemExit(f"Chromium tag lacks {token} in {buildflags}")
if "is_desktop_android" not in cb:
    raise SystemExit(f"Chromium tag lacks is_desktop_android in {chrome_build}")

print("Desktop Android extension GN gates verified.")
