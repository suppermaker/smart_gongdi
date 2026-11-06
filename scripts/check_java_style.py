#!/usr/bin/env python3
"""Small dependency-free Java hygiene gate for this compact repository."""
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "services" / "control-plane" / "src"
errors = []
for path in sorted(root.rglob("*.java")):
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if "\t" in line:
            errors.append(f"{path}:{number}: tab indentation")
        if line.rstrip() != line:
            errors.append(f"{path}:{number}: trailing whitespace")
        if len(line) > 1000:
            errors.append(f"{path}:{number}: line exceeds 1000 columns")
if errors:
    print("\n".join(errors))
    sys.exit(1)
print("JAVA_STYLE_OK")
