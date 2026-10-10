"""Combine the local detector and the vision-language model into one draft for the confirmation page.

The vision-language model reads the whole picture; when it answers, its ingredient names and quantities are used.
The local detector is the fallback: when the vision-language model fails or returns nothing, the detector result is
used, so recognition keeps working without the cloud service. On the two evaluation sets in research/recognition the
vision-language model missed no ingredient class, and taking the larger of the two quantities added counting errors,
which is why the detector no longer adds entries or raises quantities while the vision-language model is available.
"""

# Different everyday names of one ingredient are stored under a single name.
INGREDIENT_ALIASES = {
    "西红柿": "番茄", "圣女果": "番茄", "马铃薯": "土豆", "洋芋": "土豆", "红萝卜": "胡萝卜",
    "甜椒": "青椒", "彩椒": "青椒", "柿子椒": "青椒", "灯笼椒": "青椒", "青甜椒": "青椒", "菜椒": "青椒", "绿甜椒": "青椒",
    "西蓝花": "西兰花", "青花菜": "西兰花", "香菇": "蘑菇", "冬菇": "蘑菇", "花菇": "蘑菇", "鲜香菇": "蘑菇",
    "圆白菜": "卷心菜", "包菜": "卷心菜", "甘蓝": "卷心菜", "结球甘蓝": "卷心菜", "洋白菜": "卷心菜", "包心菜": "卷心菜", "cabbage": "卷心菜",
    "小南瓜": "南瓜", "贝贝南瓜": "南瓜", "eggplant": "茄子", "长茄子": "茄子", "紫茄子": "茄子",
    "紫洋葱": "洋葱", "紫皮洋葱": "洋葱", "红洋葱": "洋葱", "圆葱": "洋葱", "生鸡蛋": "鸡蛋",
}


def canonical_name(name, name_map=None):
    text = str(name or "").strip()
    if not text:
        return ""
    text = (name_map or {}).get(text.lower(), text)
    return INGREDIENT_ALIASES.get(text.lower(), INGREDIENT_ALIASES.get(text, text))


def _quantity(value):
    try:
        return max(1, int(value))
    except (TypeError, ValueError):
        return 1


def _collect(items, name_map):
    merged = {}
    for item in items or []:
        name = canonical_name(item.get("name"), name_map)
        if name:
            merged[name] = max(merged.get(name, 0), _quantity(item.get("quantity", 1)))
    return merged


def merge_recognition(yolo_items, qwen_items, name_map=None, qwen_failed=False):
    """Return ([{"name", "quantity"}], source) for the confirmation page; source is "qwen", "yolo" or "none"."""
    from_qwen = {} if qwen_failed else _collect(qwen_items, name_map)
    if from_qwen:
        chosen, source = from_qwen, "qwen"
    else:
        chosen = _collect(yolo_items, name_map)
        source = "yolo" if chosen else "none"
    return [{"name": name, "quantity": quantity} for name, quantity in chosen.items()], source
