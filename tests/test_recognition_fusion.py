from app.services.recognition_fusion import canonical_name, merge_recognition

NAME_MAP = {"egg": "鸡蛋", "tomato": "番茄", "pumpkin": "南瓜", "mushroom": "蘑菇"}


def merged(yolo, qwen, **kwargs):
    items, source = merge_recognition(yolo, qwen, NAME_MAP, **kwargs)
    return {item["name"]: item["quantity"] for item in items}, source


def test_vision_model_result_is_used_when_it_answers():
    qwen = [{"name": "洋葱", "quantity": 1}, {"name": "鸡蛋", "quantity": 3}]
    yolo = [{"name": "pumpkin", "quantity": 1, "confidence": 0.93}, {"name": "egg", "quantity": 4, "confidence": 0.9}]
    assert merged(yolo, qwen) == ({"洋葱": 1, "鸡蛋": 3}, "qwen")


def test_detector_takes_over_when_vision_model_fails():
    yolo = [{"name": "tomato", "quantity": 2, "confidence": 0.55}, {"name": "egg", "quantity": 1, "confidence": 0.93}]
    assert merged(yolo, [{"name": "洋葱", "quantity": 1}], qwen_failed=True) == ({"番茄": 2, "鸡蛋": 1}, "yolo")


def test_detector_takes_over_when_vision_model_returns_nothing():
    yolo = [{"name": "mushroom", "quantity": 3, "confidence": 0.6}]
    assert merged(yolo, []) == ({"蘑菇": 3}, "yolo")
    assert merged([], []) == ({}, "none")


def test_aliases_of_one_ingredient_are_merged():
    qwen = [{"name": "西红柿", "quantity": 2}, {"name": "番茄", "quantity": 1}, {"name": "香菇", "quantity": 1}, {"name": "紫皮洋葱", "quantity": 1}]
    assert merged([], qwen) == ({"番茄": 2, "蘑菇": 1, "洋葱": 1}, "qwen")
    assert canonical_name("  Eggplant ") == "茄子"


def test_blank_names_and_invalid_quantities_are_tolerated():
    qwen = [{"name": " ", "quantity": 1}, {"name": "土豆", "quantity": "many"}, {"name": "黄瓜", "quantity": 0}]
    assert merged([{"name": "", "confidence": 0.99}], qwen) == ({"土豆": 1, "黄瓜": 1}, "qwen")
