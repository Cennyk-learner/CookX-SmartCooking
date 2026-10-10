"""Build a 10-class ingredient detection set from the Open Images V7 validation and test splits (boxes are human-verified there).

Usage: python prepare_oid.py <work_dir>
Writes <work_dir>/dataset/{images,labels}/{train,val,test} in YOLO format, data.yaml and dataset_stats.json.
"""
import csv
import hashlib
import json
import sys
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

WORK = Path(sys.argv[1])
OID, DS = WORK / "oid", WORK / "dataset"
CLASSES = [("/m/07j87", "tomato", "番茄"), ("/m/015x4r", "cucumber", "黄瓜"), ("/m/05vtc", "potato", "土豆"), ("/m/0fj52s", "carrot", "胡萝卜"),
           ("/m/0jg57", "bell_pepper", "青椒"), ("/m/0hkxq", "broccoli", "西兰花"), ("/m/052sf", "mushroom", "蘑菇"), ("/m/033cnk", "egg", "鸡蛋"),
           ("/m/0fbw6", "cabbage", "卷心菜"), ("/m/05zsy", "pumpkin", "南瓜")]
INDEX = {mid: i for i, (mid, _, _) in enumerate(CLASSES)}
SOURCES = {"validation": "https://storage.googleapis.com/openimages/v5/validation-annotations-bbox.csv",
           "test": "https://storage.googleapis.com/openimages/v5/test-annotations-bbox.csv"}


def fetch(url, dst):
    if not dst.exists() or dst.stat().st_size == 0:
        tmp = dst.with_suffix(dst.suffix + ".part")
        urllib.request.urlretrieve(url, tmp)
        tmp.replace(dst)
    return dst


boxes = defaultdict(list)  # (split, image id) -> [(class, cx, cy, w, h)]
for split, url in SOURCES.items():
    with open(fetch(url, OID / f"{split}-bbox.csv"), newline="", encoding="utf8") as f:
        for row in csv.DictReader(f):
            if row["LabelName"] in INDEX and row["IsGroupOf"] != "1" and row["IsDepiction"] != "1":
                x0, x1, y0, y1 = (float(row[k]) for k in ("XMin", "XMax", "YMin", "YMax"))
                if x1 - x0 > .01 and y1 - y0 > .01:
                    boxes[(split, row["ImageID"])].append((INDEX[row["LabelName"]], (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0))
print("images with boxes:", len(boxes), flush=True)


def part(image_id):
    bucket = int(hashlib.md5(image_id.encode()).hexdigest(), 16) % 10
    return "val" if bucket == 8 else "test" if bucket == 9 else "train"


for p in ("train", "val", "test"):
    (DS / "images" / p).mkdir(parents=True, exist_ok=True)
    (DS / "labels" / p).mkdir(parents=True, exist_ok=True)


def download(item):
    (split, image_id), rows = item
    p = part(image_id)
    dst = DS / "images" / p / f"{image_id}.jpg"
    try:
        fetch(f"https://open-images-dataset.s3.amazonaws.com/{split}/{image_id}.jpg", dst)
    except Exception as error:  # an image that cannot be fetched is left out of the set
        return None, str(error)
    (DS / "labels" / p / f"{image_id}.txt").write_text("".join(f"{c} {x:.6f} {y:.6f} {w:.6f} {h:.6f}\n" for c, x, y, w, h in rows))
    return (p, rows), None


stats = {p: {"images": 0, "boxes": Counter()} for p in ("train", "val", "test")}
failed = 0
with ThreadPoolExecutor(32) as pool:
    for n, (ok, error) in enumerate(pool.map(download, boxes.items()), 1):
        if ok is None:
            failed += 1
        else:
            stats[ok[0]]["images"] += 1
            stats[ok[0]]["boxes"].update(CLASSES[c][1] for c, *_ in ok[1])
        if n % 500 == 0:
            print(n, "done", flush=True)

(DS / "data.yaml").write_text(f"path: {DS.as_posix()}\ntrain: images/train\nval: images/val\ntest: images/test\nnames:\n" +
                              "".join(f"  {i}: {name}\n" for i, (_, name, _) in enumerate(CLASSES)), encoding="utf8")
summary = {"source": "Open Images V7, validation and test splits", "classes": [{"id": i, "name": n, "zh": z} for i, (_, n, z) in enumerate(CLASSES)],
           "failed_downloads": failed, "splits": {p: {"images": s["images"], "boxes": dict(s["boxes"]), "total_boxes": sum(s["boxes"].values())} for p, s in stats.items()}}
(WORK / "dataset_stats.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf8")
print(json.dumps(summary["splits"], ensure_ascii=False))
