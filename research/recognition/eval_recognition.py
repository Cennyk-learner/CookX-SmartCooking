"""Score the recognition engines and the merge rules on a labelled image set, at ingredient-class level.

Usage: python eval_recognition.py <best.pt> <image_dir> <manifest.csv> <out.json> <env.txt> [qwen_cache.json]

manifest.csv has the columns file and items (ingredient names separated by ';', one entry per object).
env.txt: line 1 DashScope API key, line 4 DashScope API base URL. Nothing from it is written to the output.
qwen_cache.json: an earlier output of this script; images found there are not sent to the vision-language model again.
The detector settings, the prompt and the merge rule are those of app/main.py, app/services/qwen_service.py and
app/services/recognition_fusion.py.
"""
import csv
import json
import re
import statistics
import sys
import time
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from app.services.recognition_fusion import canonical_name, merge_recognition  # noqa: E402

YOLO_ZH = {"tomato": "番茄", "cucumber": "黄瓜", "potato": "土豆", "carrot": "胡萝卜", "bell_pepper": "青椒", "broccoli": "西兰花", "mushroom": "蘑菇",
           "egg": "鸡蛋", "cabbage": "卷心菜", "pumpkin": "南瓜"}
QWEN_MODEL = "qwen3-vl-plus"
PROMPT = ("你是一个极其专业的生鲜食材识别专家。"
          "【任务】：识别图中所有食材。\n"
          "【要求】：\n"
          "1. name: 必须是该食材的【简体中文】常用名。\n"
          "2. category: 根据食材类型，必须从以下选项中选一个: ['meat', 'vegetable', 'fruit', 'other']。\n"
          "3. quantity: 识别数量。\n"
          "4. 忽略包装和容器，只返回 JSON 数组格式。\n"
          "示例：{\"detected\": [{\"name\": \"大白菜\", \"quantity\": 1, \"category\": \"vegetable\"}]}")


def union_rule(yolo_items, qwen_items):
    """The rule used before recognition_fusion: every name from either engine, larger quantity wins."""
    merged = {}
    for item in [*yolo_items, *qwen_items]:
        name = canonical_name(item.get("name"), YOLO_ZH)
        if name:
            merged[name] = max(merged.get(name, 0), max(1, int(item.get("quantity", 1) or 1)))
    return merged


def as_dict(items):
    out = {}
    for item in items:
        name = canonical_name(item.get("name"), YOLO_ZH)
        if name:
            out[name] = max(out.get(name, 0), max(1, int(item.get("quantity", 1) or 1)))
    return out


def summarise(records):
    trained = set(YOLO_ZH.values())

    def score(key, only_trained=False):
        tp = fp = fn = exact = counts = 0
        for r in records:
            truth = {n: q for n, q in r["truth"].items() if not only_trained or n in trained}
            got = {n: q for n, q in r[key].items() if not only_trained or n in trained}
            tp += len(truth.keys() & got.keys()); fp += len(got.keys() - truth.keys()); fn += len(truth.keys() - got.keys())
            exact += truth.keys() == got.keys(); counts += sum(got.get(n) == q for n, q in truth.items())
        return {"tp": tp, "fp": fp, "fn": fn, "precision": tp / (tp + fp) if tp + fp else None, "recall": tp / (tp + fn) if tp + fn else None,
                "exact_images": exact, "images": len(records), "count_correct": counts}

    def latency(values):
        values = sorted(values)
        return {"mean": statistics.mean(values), "p50": statistics.median(values), "p95": values[min(len(values) - 1, round(.95 * (len(values) - 1)))]}

    return {"images": len(records), "pairs": sum(len(r["truth"]) for r in records),
            "pairs_outside_detector_classes": sum(n not in trained for r in records for n in r["truth"]),
            "qwen_model": QWEN_MODEL,
            "methods": {k: score(k) for k in ("yolo", "qwen", "union", "fused", "fused_qwen_down")},
           "yolo_on_trained_classes": score("yolo", only_trained=True),
            "latency_seconds": {"yolo": latency([r["yolo_seconds"] for r in records]), "qwen": latency([r["qwen_seconds"] for r in records]),
                                "both": latency([r["yolo_seconds"] + r["qwen_seconds"] for r in records])}}


if __name__ == "__main__":
    weights, image_dir, manifest, out, env = (Path(p) for p in sys.argv[1:6])
    cache = {}
    if len(sys.argv) > 6:
        cache = {r["file"]: r for r in json.loads(Path(sys.argv[6]).read_text(encoding="utf8"))["records"]}
    import dashscope
    from dashscope import MultiModalConversation
    from ultralytics import YOLO
    lines = [x.strip() for x in env.read_text(encoding="utf8").splitlines() if x.strip()]
    dashscope.api_key, dashscope.base_http_api_url = lines[0], lines[3]
    detector = YOLO(str(weights))
    rows = list(csv.DictReader(open(manifest, encoding="utf8")))
    detector(str(image_dir / rows[0]["file"]), conf=0.5, iou=0.3, imgsz=640, agnostic_nms=True, verbose=False)  # warm-up, not timed
    records = []
    for row in rows:
        path = image_dir / row["file"]
        truth = dict(Counter(canonical_name(x, YOLO_ZH) for x in row["items"].split(";")))
        start = time.perf_counter()
        result = detector(str(path), conf=0.5, iou=0.3, imgsz=640, agnostic_nms=True, verbose=False)[0]
        yolo_seconds = time.perf_counter() - start
        per_class = {}
        for c, conf in zip(result.boxes.cls.tolist(), result.boxes.conf.tolist()):
            item = per_class.setdefault(detector.names[int(c)], {"name": detector.names[int(c)], "quantity": 0, "confidence": 0.0})
            item["quantity"] += 1; item["confidence"] = max(item["confidence"], round(conf, 4))
        yolo_items = list(per_class.values())
        old = cache.get(row["file"])
        if old and old.get("qwen_items") is not None:
            qwen_items, qwen_seconds = old["qwen_items"], old["qwen_seconds"]
        elif old and old.get("qwen") is not None:  # output of the first version of this script
            qwen_items, qwen_seconds = [{"name": n, "quantity": q} for n, q in old["qwen"].items()], old["qwen_seconds"]
        else:
            start = time.perf_counter()
            reply = MultiModalConversation.call(model=QWEN_MODEL, enable_thinking=False,
                                                messages=[{"role": "user", "content": [{"image": f"file://{path.resolve()}"}, {"text": PROMPT}]}])
            qwen_seconds = time.perf_counter() - start
            assert reply.status_code == 200, (reply.status_code, reply.code)
            data = json.loads(re.search(r"(\[.*\]|\{.*\})", reply.output.choices[0].message.content[0]["text"], re.DOTALL).group(1))
            qwen_items = [{"name": i.get("name"), "quantity": i.get("quantity", 1)} for i in (data if isinstance(data, list) else data.get("detected", []))]
        record = {"file": row["file"], "scene": row.get("scene") or f"{row.get('background', '')}，{row.get('arrangement', '')}", "truth": truth,
                  "yolo_items": yolo_items, "qwen_items": qwen_items, "yolo_seconds": yolo_seconds, "qwen_seconds": qwen_seconds,
                  "yolo": as_dict(yolo_items), "qwen": as_dict(qwen_items), "union": union_rule(yolo_items, qwen_items),
                  "fused": as_dict(merge_recognition(yolo_items, qwen_items, YOLO_ZH)[0]),
                  "fused_qwen_down": as_dict(merge_recognition(yolo_items, qwen_items, YOLO_ZH, qwen_failed=True)[0])}
        records.append(record)
        print(row["file"], "| yolo", sorted(record["yolo"]), "| qwen", sorted(record["qwen"]), "| fused", sorted(record["fused"]), flush=True)
    summary = summarise(records)
    summary["records"] = records
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf8")
    print(json.dumps({k: v for k, v in summary.items() if k != "records"}, ensure_ascii=False, indent=1))
