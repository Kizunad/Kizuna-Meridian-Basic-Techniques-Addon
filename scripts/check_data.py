"""检查基础功法模组清单与纯数据边界；协议语义由 Java 验证负责。"""

from pathlib import Path
import json
import sys


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    resources = root / "fabric/src/main/resources"
    metadata = json.loads((resources / "fabric.mod.json").read_text(encoding="utf-8"))
    catalog = "data/meridian_basic_techniques/technique_catalog.txtpb"
    assert metadata["environment"] == "*", "客户端也须加载图标资源"
    assert not metadata.get("entrypoints") and not metadata.get("mixins"), "正式包必须是纯数据"
    assert metadata["custom"]["meridian_technique:catalogs"] == catalog
    for dependency in ("meridian_technique", "meridian_human_host"):
        assert dependency in metadata["depends"], f"缺少硬依赖 {dependency}"
    assert list((root / "shared/src/main/resources").rglob("*.txtpb")) == [
        root / "shared/src/main/resources" / catalog
    ], "目录只能有一份维护源"
    for path in resources.rglob("*.json"):
        json.loads(path.read_text(encoding="utf-8"))
    assert not list((root / "fabric/src").rglob("*.java")), "正式包不得有 Java"
    print("数据检查通过：纯数据清单、双端资源和唯一目录源。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
