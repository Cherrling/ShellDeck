"""Verify imported Termux sources, except the explicitly patched session boundary."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "third-party" / "termux"
manifest = json.loads((root / "upstream-files.json").read_text())
patched = {"terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java"}
for name, expected in manifest["files"].items():
    actual = hashlib.sha256((root / name).read_bytes()).hexdigest()
    if name not in patched and actual != expected:
        raise SystemExit(f"Unexpected upstream modification: {name}")
print(f"Verified {len(manifest['files']) - len(patched)} unchanged upstream files; one documented session patch")
