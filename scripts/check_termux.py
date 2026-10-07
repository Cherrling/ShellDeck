"""Verify imported Termux sources, except the explicitly patched session boundary."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "third-party" / "termux"
manifest = json.loads((root / "upstream-files.json").read_text())
patched = {"terminal-emulator/src/main/java/com/termux/terminal/TerminalSession.java", "terminal-view/src/main/java/com/termux/view/TerminalView.java", "terminal-view/src/main/java/com/termux/view/TerminalRenderer.java"}
osc8 = json.loads((root / "patches/osc8-patched-files.json").read_text())
patched.update(osc8)
for name, expected in manifest["files"].items():
    data = (root / name).read_bytes()
    if name == "terminal-view/src/main/java/com/termux/view/TerminalView.java":
        restored = data.replace(b"public class TerminalView extends View", b"public final class TerminalView extends View")
        if hashlib.sha256(restored).hexdigest() != expected:
            raise SystemExit("TerminalView may only remove the class final modifier")
    actual = hashlib.sha256(data).hexdigest()
    if name.endswith("/TerminalRenderer.java") and actual != "22c98c0b7bdef054eb7a8ddcbf7c7f56ae060d1e4211b120cc7ae4965e47dd0e":
        raise SystemExit("Renderer differs from documented selection-color patch")
    if name in osc8 and actual != osc8[name]:
        raise SystemExit(f"Source differs from documented OSC 8 patch: {name}")
    if name not in patched and actual != expected:
        raise SystemExit(f"Unexpected upstream modification: {name}")
print(f"Verified {len(manifest['files']) - len(patched)} unchanged upstream files; four documented patches")
