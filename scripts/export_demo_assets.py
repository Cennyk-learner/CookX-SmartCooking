"""Export the checked-in recipe catalog and nutrition provenance for the Android demo."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from app.domain.planning_catalog import catalog, nutrition, template, ingredient

rows = catalog()
rows.append(template("plan-millet", "小米粥", [ingredient("小米", 50)],
                     ["称量小米，淘洗后加入适量水。", "煮开后转小火，煮至米粒软烂，按需要调整稀稠。"], 30, "staple"))
rows[-1]["tags"] += ["素食", "纯素"]
for row in rows:
    row["dish_name"] = row["name"]
    row["schemaVersion"] = 1
    row["ingredients_list"] = [dict(item=i["name"], amount=i["amount"], unit=i["unit"]) for i in row["ingredients"]]
    row["steps"] = [dict(id=f"step-{n + 1}", text=s) if isinstance(s, str) else dict(s, id=f"step-{n + 1}") for n, s in enumerate(row["steps"])]
    row["used_ingredients"] = [i["name"] for i in row["ingredients"]]
    row["missing"] = []
    row["planning_nutrition"] = nutrition(row)
    row["content_source"] = "built_in"
target = ROOT / "android-native/app/src/main/assets/offline/recipes.json"
target.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Exported {len(rows)} built-in recipes with source evidence")
