"""Run two unchanged Minecraft scenarios on the existing Compose environment."""
import sys
from pathlib import Path

project_root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(project_root / "e2e"))
import suite

if __name__ == "__main__":
    suite.PROFILES["pg00-baseline"] = ["E2E-CONN-001", "E2E-LEGACY-001"]
    sys.argv[1:] = ["--profile", "pg00-baseline", "--topology", "compose",
                    "--root", str(project_root / "build/e2e/pg00")]
    suite.main()
