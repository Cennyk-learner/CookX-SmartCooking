"""Add training images from the leading part of the Open Images V7 train-split annotations (stops after a fixed number of images).

Usage: python extend_train.py <work_dir> [max_images]
Validation and test images stay those taken from the human-verified validation/test splits by prepare_oid.py.
"""
import csv
import io
import json
import sys
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
WORK = Path(sys.argv[1])
LIMIT = int(sys.argv[2]) if len(sys.argv) > 2 else 3600
DS = WORK / "dataset"
CLASSES = ["/m/07j87", "/m/015x4r", "/m/05vtc", "/m/0fj52s", "/m/0jg57", "/m/0hkxq", "/m/052sf", "/m/033cnk", "/m/0fbw6", "/m/05zsy"]
NAMES = ["tomato", "cucumber", "potato", "carrot", "bell_pepper", "broccoli", "mushroom", "egg", "cabbage", "pumpkin"]
INDEX = {m: i for i, m in enumerate(CLASSES)}
URL = "https://storage.googleapis.com/openimages/v6/oidv6-train-annotations-bbox.csv"

boxes, read = defaultdict(list), 0
with open(WORK / "oid" / "train-bbox.head.csv", encoding="utf8", newline="") as local:  # leading part of URL, fetched with curl
    for row in csv.DictReader(local):
        read += 1
        if row["IsInside"] is None:  # the file is cut at a byte offset, so its last line is incomplete
            break
        if row["LabelName"] in INDEX and row["IsGroupOf"] != "1" and row["IsDepiction"] != "1":
            x0, x1, y0, y1 = (float(row[k]) for k in ("XMin", "XMax", "YMin", "YMax"))
            if x1 - x0 > .01 and y1 - y0 > .01:
                boxes[row["ImageID"]].append((INDEX[row["LabelName"]], (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0))
        if len(boxes) > LIMIT:  # rows are grouped by image, so the last image may be incomplete: drop it
            boxes.pop(row["ImageID"], None)
            break
        if read % 1000000 == 0:
            print(read, "rows,", len(boxes), "images", flush=True)
print("annotation rows read:", read, "images:", len(boxes), flush=True)


def download(item):
    image_id, rows = item
    dst = DS / "images" / "train" / f"{image_id}.jpg"
    try:
        if not dst.exists():
            urllib.request.urlretrieve(f"https://open-images-dataset.s3.amazonaws.com/train/{image_id}.jpg", dst)
    except Exception:
        dst.unlink(missing_ok=True)
        return None
    (DS / "labels" / "train" / f"{image_id}.txt").write_text("".join(f"{c} {x:.6f} {y:.6f} {w:.6f} {h:.6f}\n" for c, x, y, w, h in rows))
    return rows


added, count = 0, Counter()
with ThreadPoolExecutor(32) as pool:
    for rows in pool.map(download, boxes.items()):
        if rows:
            added += 1
            count.update(NAMES[c] for c, *_ in rows)

stats = json.loads((WORK / "dataset_stats.json").read_text(encoding="utf8"))
train = stats["splits"]["train"]
if "added_from_train_split" not in stats:
    for name, n in count.items():
        train["boxes"][name] = train["boxes"].get(name, 0) + n
    train["images"] += added
    train["total_boxes"] = sum(train["boxes"].values())
    stats["added_from_train_split"] = {"images": added, "annotation_rows_read": read}
    stats["source"] = "Open Images V7: validation and test splits, plus the first part of the train split for training"
    (WORK / "dataset_stats.json").write_text(json.dumps(stats, ensure_ascii=False, indent=1), encoding="utf8")
print("added", added, json.dumps(train, ensure_ascii=False))
