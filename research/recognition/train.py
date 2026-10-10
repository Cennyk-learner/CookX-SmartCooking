"""Fine-tune YOLOv8s on the ingredient set and evaluate it on the held-out test split.

Usage: python train.py <work_dir> [epochs]
Writes runs/ingredients (Ultralytics run directory with results.csv and weights) and train_summary.json.
"""
import json
import sys
import time
from pathlib import Path

from ultralytics import YOLO

WORK = Path(sys.argv[1])
EPOCHS = int(sys.argv[2]) if len(sys.argv) > 2 else 30
CONFIG = dict(data=str(WORK / "dataset" / "data.yaml"), epochs=EPOCHS, imgsz=640, batch=16, seed=42, optimizer="AdamW", lr0=0.001, lrf=0.01,
              cos_lr=True, weight_decay=0.0005, warmup_epochs=3, close_mosaic=10, workers=4, project=str(WORK / "runs"), name="ingredients",
              exist_ok=True, plots=True, verbose=False)

if __name__ == "__main__":
    start = time.time()
    model = YOLO("yolov8s.pt")
    model.train(**CONFIG)
    minutes = (time.time() - start) / 60
    best = YOLO(str(WORK / "runs" / "ingredients" / "weights" / "best.pt"))
    summary = {"config": {k: v for k, v in CONFIG.items() if k not in ("project", "name", "exist_ok", "plots", "verbose", "data")},
               "base_weights": "yolov8s.pt (COCO)", "train_minutes": round(minutes, 1), "splits": {}}
    for split in ("val", "test"):
        m = best.val(data=CONFIG["data"], split=split, imgsz=640, batch=16, plots=False, verbose=False)
        summary["splits"][split] = {"precision": float(m.box.mp), "recall": float(m.box.mr), "map50": float(m.box.map50), "map50_95": float(m.box.map),
                                    "per_class_map50": {best.names[int(c)]: float(v) for c, v in zip(m.box.ap_class_index, m.box.ap50)}}
    (WORK / "train_summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf8")
    print(json.dumps(summary["splits"], indent=1))
