"""Verify imported Termux sources, except the explicitly patched session boundary."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "third-party" / "termux"
manifest = json.loads((root / "upstream-files.json").read_text())
patched = {"terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java", "terminal-view/src/main/java/com/termux/view/TerminalView.java"}
for name, expected in manifest["files"].items():
    data = (root / name).read_bytes()
    if name == "terminal-view/src/main/java/com/termux/view/TerminalView.java":
        restored = data.replace(b"public class TerminalView extends View", b"public final class TerminalView extends View")
        if hashlib.sha256(restored).hexdigest() != expected:
            raise SystemExit("TerminalView may only remove the class final modifier")
    actual = hashlib.sha256(data).hexdigest()
    if name not in patched and actual != expected:
        raise SystemExit(f"Unexpected upstream modification: {name}")
print(f"Verified {len(manifest['files']) - len(patched)} unchanged upstream files; two documented boundary patches")
