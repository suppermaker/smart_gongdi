import sys
from pathlib import Path

RUNTIME_SRC = Path(__file__).resolve().parents[2] / "agent-runtime" / "src"
sys.path.insert(0, str(RUNTIME_SRC))
