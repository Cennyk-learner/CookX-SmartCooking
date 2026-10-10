import os
import re
import io
import json
import math
import shutil
import tempfile
import uuid
from datetime import datetime, timezone
from typing import Any, Dict, List, Literal, Optional
from collections import Counter
from functools import wraps
import inspect
from app.services.inventory_transactions import inventory_session, consume as consume_batches, receipt as consumption_receipt, read_json
from fastapi import FastAPI, UploadFile, File, HTTPException, Query, Request
from fastapi import Response, Depends
from app.storage import store as storage
from app.auth import require_auth, router as auth_router, issue_session, consume_invitation
from fastapi import Body
from fastapi.responses import StreamingResponse
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
try:
    from ultralytics import YOLO
except ImportError:
    YOLO=None
from PIL import Image
from pydantic import BaseModel, Field, field_validator, model_validator
from datetime import datetime, timedelta
# 导入你自己的服务模块
from app.services.deepseek_service import get_recipe_suggestion
from app.services.tts_service import generate_voice
from app.services.qwen_service import get_ingredients_from_qwen
from app.services.recognition_fusion import merge_recognition
from app.services.recommendation_service import (
    algorithm_version_for_recommendations,
    filter_eligible_recipes, load_recipes, recommend_recipes,
    safe_inventory_names, validate_weights,
)
from app.services.freshness_service import (
    calculate_freshfusion,
    normalize_storage_type,
    parse_datetime_safely,
)
from app.models.user import (
    create_user, authenticate_user, update_user, delete_user,
    find_user_by_username, find_user_by_phone, get_all_users
)
from app.services.sms_service import send_sms_code, verify_sms_code
app = FastAPI(title="Smart Cooking API", dependencies=[Depends(require_auth)])
app.include_router(auth_router)
from app.domain.households import router as households_router
app.include_router(households_router)
from app.domain.shopping import router as shopping_router
app.include_router(shopping_router)
from app.domain.recipes import router as recipes_router
app.include_router(recipes_router)
from app.domain.community import router as community_router
app.include_router(community_router)
from app.domain.comments import router as comments_router
app.include_router(comments_router)
from app.domain.likes import router as likes_router
app.include_router(likes_router)
from app.domain.ranking import router as ranking_router
app.include_router(ranking_router)
from app.domain.leftovers import router as leftovers_router
app.include_router(leftovers_router)
from app.domain.growth import router as growth_router
app.include_router(growth_router)
from app.domain.challenges import router as challenges_router
app.include_router(challenges_router)
from app.domain.badges import router as badges_router
app.include_router(badges_router)
from app.domain.planning import router as planning_router
app.include_router(planning_router)
from app.domain.learning import router as learning_router
app.include_router(learning_router)
from app.domain.family_consumption import router as family_consumption_router
app.include_router(family_consumption_router)
from app.domain.preferences import router as preferences_router
app.include_router(preferences_router)
from app.domain.private_media import router as private_media_router, PrivateStaticBoundary
app.include_router(private_media_router)
app.add_middleware(PrivateStaticBoundary)
USER_DATA_BASE = "app/data/users"
# --- 1. 配置与初始化 ---
UPLOAD_DIR = "app/static/uploads"
AUDIO_DIR = "app/static/audio"
INVENTORY_FILE = "app/inventory.json"
CHAT_HISTORY_FILE = "app/chat_history.json"
for path in [UPLOAD_DIR, AUDIO_DIR]:
    os.makedirs(path, exist_ok=True)


class LoginRequest(BaseModel):
    username: str
    password: str


class NutritionTarget(BaseModel):
    model_config = {"extra": "forbid"}

    max_calories_kcal: Optional[float] = Field(None, gt=0, allow_inf_nan=False, strict=True, description="每份热量上限，单位 kcal")
    min_protein_g: Optional[float] = Field(None, gt=0, allow_inf_nan=False, strict=True, description="每份蛋白质下限，单位 g")
    max_fat_g: Optional[float] = Field(None, gt=0, allow_inf_nan=False, strict=True, description="每份脂肪上限，单位 g")
    max_carbohydrates_g: Optional[float] = Field(None, gt=0, allow_inf_nan=False, strict=True, description="每份碳水化合物上限，单位 g")

    @field_validator("*", mode="before")
    @classmethod
    def reject_non_numeric_or_non_finite_target(cls, value: Any) -> Any:
        if value is None:
            return None
        if (
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(value)
        ):
            return "invalid nutrition target"
        return value

    @model_validator(mode="after")
    def require_at_least_one_target(self):
        if not any(value is not None for value in self.model_dump().values()):
            raise ValueError("nutrition_target至少需要一个每份数值目标")
        return self

class RecommendationRequest(BaseModel):
    model_config = {"extra": "forbid"}

    user_id: str = Field(..., min_length=1)
    top_k: int = Field(5, ge=1, le=20)
    weights: Optional[Dict[str, float]] = None
    preferences: Optional[Dict[str, Any]] = None
    budget: Optional[float] = Field(None, gt=0, allow_inf_nan=False, strict=True, description="整道菜预算，币种为 CNY")
    difficulty_target: Optional[Literal["easy", "medium", "hard"]] = Field(
        None, description="用户期望的烹饪难度：easy、medium 或 hard",
    )
    nutrition_target: Optional[NutritionTarget] = Field(
        None, description="用户按每份口径设置的明确营养数值目标",
    )

    @model_validator(mode="before")
    @classmethod
    def reject_explicit_null_nutrition_target(cls, value: Any) -> Any:
        if isinstance(value, dict) and "nutrition_target" in value and value["nutrition_target"] is None:
            raise ValueError("nutrition_target存在时必须是非空对象")
        return value

    @field_validator("weights", mode="before")
    @classmethod
    def reject_invalid_extended_weight(cls, value: Any) -> Any:
        if isinstance(value, dict):
            validated = dict(value)
            for key in ("D", "N"):
                if key not in value:
                    continue
                weight = value[key]
                if (
                    isinstance(weight, bool)
                    or not isinstance(weight, (int, float))
                    or not math.isfinite(weight)
                ):
                    validated[key] = f"invalid {key} weight"
            return validated
        return value

    @field_validator("budget", mode="before")
    @classmethod
    def reject_non_finite_budget(cls, value: Any) -> Any:
        # Keep FastAPI's 422 body JSON-serializable even when a non-standard
        # JSON parser accepts NaN or Infinity as a Python float.
        if isinstance(value, float) and not math.isfinite(value):
            return "non-finite budget"
        return value

class FreshnessEvaluationRequest(BaseModel):
    model_config = {"extra": "forbid"}

    ingredient_name: str = Field(..., min_length=1)
    purchase_time: Optional[str] = None
    add_time: Optional[str] = None
    shelf_life: Optional[float] = None
    expiry_date: Optional[str] = None
    storage_type: Optional[str] = None


PURCHASE_TIME_FUTURE_TOLERANCE = timedelta(minutes=5)


def _validated_inventory_quantity(value: Any) -> int:
    if isinstance(value, bool):
        raise ValueError("quantity必须为正整数")
    try:
        number = int(value)
        numeric = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError("quantity必须为正整数") from exc
    if number <= 0 or not math.isfinite(numeric) or numeric != number:
        raise ValueError("quantity必须为正整数")
    return number


def _validated_inventory_shelf_life(value: Any) -> Optional[float]:
    if value is None:
        return None
    number = _finite_number(value)
    if number is None or number <= 0:
        raise ValueError("shelf_life必须为大于0的天数")
    return int(number) if number.is_integer() else number


def _validated_iso_datetime(field_name: str, value: Any) -> Optional[str]:
    if value is None:
        return None
    if not isinstance(value, str) or parse_datetime_safely(value) is None:
        raise ValueError(f"{field_name}必须为合法ISO 8601时间")
    return value


def _validate_inventory_temporal_fields(values: Dict[str, Any]) -> Dict[str, Any]:
    purchase = parse_datetime_safely(values.get("purchase_time"))
    added = parse_datetime_safely(values.get("add_time"))
    expiry = parse_datetime_safely(values.get("expiry_date"))
    now = datetime.now(timezone.utc)
    if purchase and purchase > now + PURCHASE_TIME_FUTURE_TOLERANCE:
        raise ValueError("purchase_time不能明显晚于服务端当前时间")
    if expiry and added and expiry <= added:
        raise ValueError("expiry_date必须晚于add_time")
    if expiry and purchase and expiry <= purchase:
        raise ValueError("expiry_date必须晚于purchase_time")
    return values


class InventoryCreateItem(BaseModel):
    model_config = {"extra": "allow"}

    name: str = Field(..., min_length=1)
    quantity: int = 1
    add_time: Optional[str] = None
    purchase_time: Optional[str] = None
    shelf_life: Optional[float] = None
    expiry_date: Optional[str] = None
    storage_type: Optional[str] = None

    @field_validator("quantity", mode="before")
    @classmethod
    def validate_quantity(cls, value):
        return _validated_inventory_quantity(value)

    @field_validator("shelf_life", mode="before")
    @classmethod
    def validate_shelf_life(cls, value):
        return _validated_inventory_shelf_life(value)

    @field_validator("add_time", "purchase_time", "expiry_date", mode="before")
    @classmethod
    def validate_time(cls, value, info):
        return _validated_iso_datetime(info.field_name, value)

    @model_validator(mode="before")
    @classmethod
    def validate_storage_alias(cls, values):
        if not isinstance(values, dict):
            return values
        storage = values.get("storage_type") or values.get("storage_method") or values.get("storage")
        if storage is not None:
            if normalize_storage_type(storage) is None:
                raise ValueError("storage_type不是项目支持的储存方式或兼容别名")
            values = dict(values)
            values["storage_type"] = storage
        return values

    @model_validator(mode="after")
    def validate_time_relationships(self):
        _validate_inventory_temporal_fields(self.model_dump())
        return self


class InventoryUpdateItem(BaseModel):
    model_config = {"extra": "forbid"}

    name: Optional[str] = Field(None, min_length=1)
    quantity: Optional[int] = None
    add_time: Optional[str] = None
    purchase_time: Optional[str] = None
    shelf_life: Optional[float] = None
    expiry_date: Optional[str] = None
    storage_type: Optional[str] = None

    @field_validator("quantity", mode="before")
    @classmethod
    def validate_quantity(cls, value):
        return None if value is None else _validated_inventory_quantity(value)

    @field_validator("shelf_life", mode="before")
    @classmethod
    def validate_shelf_life(cls, value):
        return _validated_inventory_shelf_life(value)

    @field_validator("add_time", "purchase_time", "expiry_date", mode="before")
    @classmethod
    def validate_time(cls, value, info):
        return _validated_iso_datetime(info.field_name, value)

    @field_validator("storage_type")
    @classmethod
    def validate_storage_type(cls, value):
        if value is not None and normalize_storage_type(value) is None:
            raise ValueError("storage_type不是项目支持的储存方式或兼容别名")
        return value

    @model_validator(mode="after")
    def validate_time_relationships(self):
        _validate_inventory_temporal_fields(self.model_dump(exclude_unset=True))
        return self


class ConfirmRecognitionRequest(BaseModel):
    model_config = {"extra": "forbid"}

    user_id: str = Field(..., min_length=1)
    confirmed: Literal[True]
    items: List[InventoryCreateItem] = Field(..., min_length=1)

# 开启跨域
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# 预加载模型 (建议放在全局避免重复加载)
model=None
if YOLO is not None:
    try:model=YOLO('app/models/best.pt')
    except Exception as error:print('本地视觉模型未加载：'+type(error).__name__+'；库存、菜单及其他本地功能仍可用')

# 挂载静态资源
app.mount("/static", StaticFiles(directory="app/static"), name="static")

NAME_MAP = {
    'beef': '牛肉',
    'carrot': '胡萝卜',
    'chicken': '鸡肉',
    'chili': '辣椒',
    'egg': '鸡蛋',
    'garlic': '大蒜',
    'kimchi': '泡菜',
    'leek': '大葱',
    'onion': '洋葱',
    'potato': '土豆',
    'tomato': '番茄',
    'cucumber': '黄瓜',
    'bell_pepper': '青椒',
    'broccoli': '西兰花',
    'mushroom': '蘑菇',
    'cabbage': '卷心菜',
    'pumpkin': '南瓜'
}
BROADCAST_PATH = "app/data/system_broadcast.json"

def update_expiration_notifications(user_id: str):
    inventory_path = get_user_path(user_id, "inventory.json")
    notify_path = get_user_path(user_id, "notifications.json")

    # 1. 读取该用户现有的通知
    user_notifications = []
    if storage.exists(notify_path):
        user_notifications = storage.read(notify_path, [])

    # 获取现有通知的 ID 集合，用于去重
    existing_ids = {n['id'] for n in user_notifications}
    new_found = False

    # 2. 【核心逻辑】：同步全局系统通知
    if os.path.exists(BROADCAST_PATH):
        with open(BROADCAST_PATH, "r", encoding="utf-8") as f:
            broadcasts = json.load(f)

        for b in broadcasts:
            # 如果这条全局公告的 ID 不在用户的个人消息里，就加进去
            if b['id'] not in existing_ids:
                user_notifications.insert(0, {
                    **b,  # 复制公告内容
                    "isRead": False  # 初始状态为未读
                })
                new_found = True

    # 3. 扫描食材过期逻辑 (保持你之前的代码)
    if storage.exists(inventory_path):
        inventory = storage.read(inventory_path, [])
        for item in inventory:
            # ... 计算过期天数逻辑 ...
            # title = f"{item['name']}即将过期"
            # if days_left <= 3 and title_not_in_existing:
            #     user_notifications.insert(0, {...})
            #     new_found = True
            pass

    # 4. 如果有新消息，保存回用户的文件夹
    if new_found:
        storage.write(notify_path, user_notifications[:50])

def get_user_path(user_id: str, filename: str):
    """
    根据用户ID和文件名获取专属存储路径。
    会自动创建用户专属文件夹：app/data/users/{user_id}/
    """
    # 转换为字符串防止 user_id 是数字类型导致报错
    user_id_str = str(user_id)

    # 拼接用户目录：app/data/users/12345
    user_dir = os.path.join(USER_DATA_BASE, user_id_str)

    # 如果该用户的文件夹不存在，则立即创建它
    if not storage.is_sqlite and not os.path.exists(user_dir):
        os.makedirs(user_dir, exist_ok=True)
        print(f"为用户 {user_id_str} 创建了专属文件夹")

    # 返回文件的完整路径：app/data/users/12345/inventory.json
    return os.path.join(user_dir, filename)

def get_current_inventory_names(user_id: str):
    return sorted(safe_inventory_names(storage.read(get_user_path(user_id, "inventory.json"), []),datetime.now()))

def get_user_file_path(user_id: str, file_name: str):
    """为每个用户创建独立文件夹：app/data/users/{user_id}/inventory.json"""
    user_dir = os.path.join(USER_DATA_BASE, user_id)
    if not storage.is_sqlite: os.makedirs(user_dir, exist_ok=True)
    return os.path.join(user_dir, file_name)

@app.get("/")
async def root():
    return {"message": "SmartCooking API 运行中", "status": "online"}

@app.get("/favicon.ico", include_in_schema=False)
async def favicon():
    # 返回一个空的响应，避免 404 刷屏
    return Response(status_code=204)


MERGEABLE_INVENTORY_MEASUREMENTS = ("weight_g", "grams", "volume_ml", "ml", "amount")
INVENTORY_NAME_ALIASES = {
    "tomato": "西红柿",
    "番茄": "西红柿",
    "西红柿": "西红柿",
    "小番茄": "西红柿",
    "圣女果": "西红柿",
    "beef": "牛肉",
    "牛肉": "牛肉",
    "milk": "牛奶",
    "牛奶": "牛奶",
    "tofu": "豆腐",
    "豆腐": "豆腐",
    "potato": "土豆",
    "土豆": "土豆",
    "carrot": "胡萝卜",
    "胡萝卜": "胡萝卜",
    "chicken": "鸡肉",
    "鸡肉": "鸡肉",
    "egg": "鸡蛋",
    "鸡蛋": "鸡蛋",
    "onion": "洋葱",
    "洋葱": "洋葱",
    "garlic": "大蒜",
    "大蒜": "大蒜",
    "ginger": "生姜",
    "姜": "生姜",
    "生姜": "生姜",
    "broccoli": "西兰花",
    "西兰花": "西兰花",
    "kimchi": "泡菜",
    "韩式泡菜": "泡菜",
    "泡菜": "泡菜",
    "chili": "红辣椒",
    "红辣椒": "红辣椒",
    "青辣椒": "青辣椒",
}


def normalize_inventory_name(name):
    normalized = str(name or "").strip().lower()
    return INVENTORY_NAME_ALIASES.get(normalized, normalized)


def _inventory_datetime(value):
    try:
        return datetime.fromisoformat(str(value).replace("Z", "+00:00"))
    except (TypeError, ValueError):
        return None


def _inventory_date(value):
    """只取库存时间的自然日；旧数据时间无效时不参与合并。"""
    parsed = _inventory_datetime(value)
    return parsed.date() if parsed else None


def _inventory_storage_method(item):
    return str(
        item.get("storage_method")
        or item.get("storage_type")
        or item.get("storage")
        or ""
    ).strip().lower()


def get_inventory_batch_key(item):
    """Merge only known compatible batches, retaining fractional days and date evidence."""
    name = normalize_inventory_name(item.get("name"))
    added_date = _inventory_date(item.get("add_time"))
    shelf_life = _finite_number(item.get("shelf_life"))
    if not name or added_date is None or shelf_life is None or shelf_life <= 0:
        return None
    return (name, added_date.isoformat(), _inventory_storage_method(item), shelf_life,
            item.get("purchase_time") or item.get("purchase_date"), item.get("expiry_date"))


def _finite_number(value):
    if isinstance(value, bool):
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _merge_measurements(existing, incoming):
    """只累加同一实际数值字段；amount 还必须具有相同单位。"""
    for field in MERGEABLE_INVENTORY_MEASUREMENTS:
        existing_value = _finite_number(existing.get(field))
        incoming_value = _finite_number(incoming.get(field))
        if incoming_value is None:
            continue
        if field == "amount":
            existing_unit = str(existing.get("amount_unit") or existing.get("unit") or "").strip().lower()
            incoming_unit = str(incoming.get("amount_unit") or incoming.get("unit") or "").strip().lower()
            if existing_value is None and incoming_unit:
                existing[field] = int(incoming_value) if incoming_value.is_integer() else incoming_value
                if incoming.get("amount_unit") is not None:
                    existing["amount_unit"] = incoming["amount_unit"]
                elif incoming.get("unit") is not None:
                    existing["unit"] = incoming["unit"]
                continue
            if existing_value is None or not existing_unit or existing_unit != incoming_unit:
                continue
        elif existing_value is None:
            existing[field] = int(incoming_value) if incoming_value.is_integer() else incoming_value
            continue
        total = existing_value + incoming_value
        existing[field] = int(total) if total.is_integer() else total


def _keep_earliest_add_time(existing, incoming):
    existing_time = _inventory_datetime(existing.get("add_time"))
    incoming_time = _inventory_datetime(incoming.get("add_time"))
    if not existing_time or not incoming_time:
        return
    existing_clock = (existing_time.hour, existing_time.minute, existing_time.second, existing_time.microsecond)
    incoming_clock = (incoming_time.hour, incoming_time.minute, incoming_time.second, incoming_time.microsecond)
    if incoming_clock < existing_clock:
        existing["add_time"] = incoming["add_time"]


def merge_duplicate_inventory_batches(items):
    """纯数据归并：不读写文件，并保留首条记录的稳定 ID 和未知字段。"""
    merged = []
    batches = {}
    protected_fields = {
        "id", "name", "quantity", "add_time", "shelf_life",
        "storage_method", "storage_type", "storage",
        "purchase_time", "purchase_date", "expiry_date",
        *MERGEABLE_INVENTORY_MEASUREMENTS,
    }

    for source_item in items:
        item = dict(source_item)
        item["name"] = normalize_inventory_name(item.get("name"))
        batch_key = get_inventory_batch_key(item)
        if batch_key is None or batch_key not in batches:
            merged.append(item)
            if batch_key is not None:
                batches[batch_key] = item
            continue

        existing = batches[batch_key]
        existing["quantity"] = int(existing.get("quantity", 0)) + int(item.get("quantity", 0))
        _merge_measurements(existing, item)
        _keep_earliest_add_time(existing, item)
        for field, value in item.items():
            if field not in protected_fields and field not in existing:
                existing[field] = value

    return merged


def merge_inventory_items(inventory_data, items, current_time=None):
    """新增库存与历史数据共用同一批次 key 和归并函数。"""
    batch_time = current_time or datetime.now(timezone.utc)

    for incoming in items:
        name = normalize_inventory_name(incoming.get("name", "未知"))
        if not name:
            continue

        incoming_quantity = int(incoming.get("quantity", 1))
        generated_add_time = batch_time.isoformat().replace("+00:00", "Z")
        new_item = {
            "id": str(uuid.uuid4())[:8],
            "name": name,
            "quantity": incoming_quantity,
            "add_time": incoming.get("add_time") or generated_add_time,
        }
        storage_type = incoming.get("storage_method") or incoming.get("storage_type") or incoming.get("storage")
        if storage_type is not None and str(storage_type).strip():
            new_item["storage_type"] = storage_type
        if incoming.get("shelf_life") is not None:
            shelf_life = _finite_number(incoming.get("shelf_life"))
            if shelf_life is not None and shelf_life > 0:
                new_item["shelf_life"] = int(shelf_life) if shelf_life.is_integer() else shelf_life
        for traceable_field in ("purchase_time", "purchase_date", "expiry_date", "visual_freshness"):
            if incoming.get(traceable_field) is not None:
                new_item[traceable_field] = incoming[traceable_field]
        _validate_inventory_temporal_fields(new_item)
        for field in MERGEABLE_INVENTORY_MEASUREMENTS:
            value = _finite_number(incoming.get(field))
            if value is not None:
                new_item[field] = int(value) if value.is_integer() else value
        for unit_field in ("amount_unit", "unit"):
            if incoming.get(unit_field) is not None:
                new_item[unit_field] = incoming[unit_field]
        inventory_data.append(new_item)

    return merge_duplicate_inventory_batches(inventory_data)


def _write_inventory_atomic(path, data):
    storage.write(path, data)


def _require_existing_user(user_id: str) -> None:
    if not any(str(user.get("id")) == str(user_id) for user in get_all_users()):
        raise HTTPException(status_code=404, detail="用户不存在")


def inventory_guard(function):
    signature = inspect.signature(function)
    @wraps(function)
    async def wrapped(*args, **kwargs):
        values = signature.bind(*args, **kwargs).arguments
        user_id = values.get("user_id") or values["request"].user_id
        if function.__name__ == "evaluate_inventory_freshness":
            for parameter in ("reference_time", "visual_freshness"):
                if parameter in values["request"].query_params:
                    raise HTTPException(422, f"不允许客户端提供{parameter}")
        _require_existing_user(user_id)
        path = os.path.join(USER_DATA_BASE, str(user_id), "inventory.json")
        with storage.session(path):
            context=values.get('request_context')
            key=context.headers.get('Idempotency-Key') if context is not None and storage.is_sqlite else None
            fingerprint=None
            if key:
                from app.storage import encode
                from app.services.inventory_transactions import digest
                if not re.fullmatch(r'[A-Za-z0-9_-]{8,128}',key):raise HTTPException(422,'操作凭证格式无效')
                def serial(value):
                    if isinstance(value,list):return [serial(v) for v in value]
                    return value.model_dump(mode='json') if hasattr(value,'model_dump') else value
                fingerprint=digest({'operation':function.__name__,'values':{k:serial(v) for k,v in values.items() if k!='request_context'},'if_match':context.headers.get('If-Match')})
                with storage.transaction() as db:
                    prior=db.execute('SELECT fingerprint,payload FROM receipts WHERE scope=? AND key=?',(user_id,key)).fetchone()
                    if prior:
                        if prior['fingerprint']!=fingerprint:raise HTTPException(409,'原操作凭证不能用于不同内容')
                        return {**json.loads(prior['payload']),'replayed':True}
            if storage.is_sqlite and function.__name__ in ('delete_item','update_item'):
                from app.services.inventory_transactions import digest
                expected=context.headers.get('If-Match') if context is not None else None
                if not expected:raise HTTPException(428,'请刷新库存并使用支持版本核对的客户端')
                existing=next((r for r in storage.read(path,[]) if str(r['id'])==str(values['item_id'])),None)
                if existing is None:raise HTTPException(404,'库存项目不存在')
                if digest(existing)!=expected.strip('"'):raise HTTPException(409,'批次已被修改，请刷新后重新核对')
            result=await function(*args, **kwargs)
            if key:
                with storage.transaction() as db:db.execute('INSERT INTO receipts VALUES(?,?,?,?)',(user_id,key,fingerprint,encode(result)))
            return result
    return wrapped


def _public_freshfusion_result(item: Dict[str, Any], evaluated_at: datetime) -> Dict[str, Any]:
    public_item = dict(item)
    public_item.pop("visual_freshness", None)
    detail = calculate_freshfusion(public_item, reference_time=evaluated_at)
    public_visual_note = "当前尚无服务端可信视觉鲜度结果，V未参与融合。"
    detail["data_quality_notes"] = list(dict.fromkeys([
        *detail["data_quality_notes"], public_visual_note,
    ]))
    return detail


def _confirmed_inventory_records(
    inventory: List[Dict[str, Any]],
    incoming_items: List[Dict[str, Any]],
    evaluated_at: datetime,
) -> List[Dict[str, Any]]:
    generated_add_time = evaluated_at.isoformat().replace("+00:00", "Z")
    confirmed = []
    seen_ids = set()
    for incoming in incoming_items:
        probe = {
            **incoming,
            "name": normalize_inventory_name(incoming.get("name")),
            "add_time": incoming.get("add_time") or generated_add_time,
        }
        batch_key = get_inventory_batch_key(probe)
        matches = [
            item for item in inventory
            if (
                get_inventory_batch_key(item) == batch_key
                if batch_key is not None
                else normalize_inventory_name(item.get("name")) == probe["name"]
                and item.get("add_time") == probe["add_time"]
            )
        ]
        if not matches:
            raise ValueError("无法定位确认入库后的库存记录")
        matched = matches[0]
        item_id = str(matched.get("id"))
        if item_id not in seen_ids:
            confirmed.append(matched)
            seen_ids.add(item_id)
    return confirmed


@app.post("/api/add-to-inventory")
@inventory_guard
async def add_to_inventory(items: List[InventoryCreateItem], user_id: str = Query(...), request_context: Request = None):
    """正式入库：按用户隔离，并强制补全日期"""
    _require_existing_user(user_id)
    try:
        # 1. 获取该用户的专属路径
        path = get_user_path(user_id, "inventory.json")

        inventory_data = storage.read(path, [])
        if not isinstance(inventory_data, list):
            raise HTTPException(status_code=500, detail="库存格式损坏，未写入")

        # 3. 同一用户的同日、同名食材在写入时合并
        inventory_data = merge_inventory_items(
            inventory_data,
            [item.model_dump(exclude_none=True) for item in items],
        )

        # 4. 原子写入当前用户的库存文件
        _write_inventory_atomic(path, inventory_data)

        print(f"用户 {user_id} 成功存入 {len(items)} 件食材到 {path}")
        return {"status": "success", "message": "已存入冰箱"}

    except HTTPException:
        raise
    except (OSError, json.JSONDecodeError, ValueError, TypeError) as e:
        print(f"存入失败报错: {e}")
        raise HTTPException(status_code=422, detail=str(e)) from e


@app.post("/api/inventory/confirm-recognition")
@inventory_guard
async def confirm_recognized_inventory(request: ConfirmRecognitionRequest, request_context: Request = None):
    """Persist user-confirmed candidates, then return real-time FreshFusion results."""
    _require_existing_user(request.user_id)
    path = os.path.join(USER_DATA_BASE, request.user_id, "inventory.json")
    try:
        inventory: List[Dict[str, Any]] = []
        if storage.exists(path):
            loaded = storage.read(path, [])
            if not isinstance(loaded, list):
                raise ValueError("inventory must be an array")
            inventory = loaded

        evaluated_at = datetime.now(timezone.utc)
        evaluated_at_iso = evaluated_at.isoformat().replace("+00:00", "Z")
        incoming_items = [item.model_dump(exclude_none=True) for item in request.items]
        updated_inventory = merge_inventory_items(
            inventory,
            incoming_items,
            current_time=evaluated_at,
        )
        confirmed_records = _confirmed_inventory_records(
            updated_inventory,
            incoming_items,
            evaluated_at,
        )
        confirmed_items = []
        for record in confirmed_records:
            detail = _public_freshfusion_result(record, evaluated_at)
            confirmed_items.append({
                "item_id": record.get("id"),
                "ingredient_name": record.get("name"),
                "inventory_item": dict(record),
                "evaluated_at": evaluated_at_iso,
                **detail,
            })

        # Evaluation completes before the single atomic write. If it fails, no
        # inventory mutation is committed and no false success is returned.
        os.makedirs(os.path.dirname(path), exist_ok=True)
        _write_inventory_atomic(path, updated_inventory)
        return {
            "status": "success",
            "user_id": request.user_id,
            "evaluated_at": evaluated_at_iso,
            "confirmed_count": len(confirmed_items),
            "confirmed_items": confirmed_items,
        }
    except HTTPException:
        raise
    except (json.JSONDecodeError, OSError) as exc:
        raise HTTPException(status_code=500, detail="用户库存数据无法读取或写入") from exc
    except (ValueError, TypeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:
        raise HTTPException(status_code=500, detail="鲜度评估失败，库存未写入") from exc


@app.post("/api/analyze-fridge")
async def analyze_fridge(file: UploadFile = File(...)):
    try:
        # 1. 第一步：保存上传的图片
        temp_filename = f"{uuid.uuid4()}.jpg"
        temp_path = os.path.join(UPLOAD_DIR, temp_filename)
        with open(temp_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)
        upload_size = os.path.getsize(temp_path)
        with Image.open(temp_path) as uploaded_image:
            image_width, image_height = uploaded_image.size
            image_mode = uploaded_image.mode
            image_channels = len(uploaded_image.getbands())
        print(
            f"[RECOGNITION] upload filename={file.filename!r} "
            f"content_type={file.content_type!r} saved_file={temp_filename} "
            f"bytes={upload_size} width={image_width} height={image_height} "
            f"mode={image_mode} channels={image_channels}"
        )

        # 2. 第二步：运行本地 YOLO 模型扫描
        print(
            "[YOLO] model=app/models/best.pt conf=0.5 iou=0.3 imgsz=640 "
            "agnostic_nms=True classes=None max_det=300(default)"
        )
        yolo_error = None
        yolo_items = []
        try:
            results = model(
                temp_path,
                conf=0.5,  # 设定置信度
                iou=0.3,
                imgsz=640,
                agnostic_nms=True,
            )
            yolo_names = [model.names[int(c)] for r in results for c in r.boxes.cls]
            raw_detections = [
                {"name": model.names[int(class_id)], "confidence": round(float(confidence), 4)}
                for result in results
                for class_id, confidence in zip(result.boxes.cls, result.boxes.conf)
            ]
            box_count = sum(len(r.boxes) for r in results)
            best_confidence = {}
            for detection in raw_detections:
                best_confidence[detection["name"]] = max(best_confidence.get(detection["name"], 0.0), detection["confidence"])
            yolo_items = [
                {"name": NAME_MAP.get(name, name), "quantity": quantity, "confidence": best_confidence.get(name, 0.0)}
                for name, quantity in Counter(yolo_names).items()
            ]
            print(f"[YOLO] boxes={box_count}")
            print(f"[YOLO] raw detections={raw_detections}")
            print(f"[YOLO] detected foods={yolo_items}")
        except Exception as error:
            yolo_error = f"{type(error).__name__}: {error}"
            print(f"[YOLO] failed: {yolo_error}")

        # Qwen 是复杂场景的整图识别来源，因此每张图均调用；不再依赖 YOLO 的检测数量。
        print("[QWEN] called: true")
        qwen_res = await get_ingredients_from_qwen(temp_path)
        qwen_error = qwen_res.get("_qwen_error")
        qwen_items = qwen_res.get("detected", []) if not qwen_error else []
        if qwen_error:
            print(f"[QWEN] failed: {qwen_error}")
        else:
            print(f"[QWEN] detected foods={qwen_items}")

        # Qwen 成功时以它的类别和数量为准；Qwen 不可用或没有结果时由本地 YOLO 接管。
        detected_items, fusion_source = merge_recognition(yolo_items, qwen_items, NAME_MAP, qwen_failed=bool(qwen_error))
        print(f"[FUSION] source={fusion_source}")
        print(f"[FUSION] merged foods={detected_items}")

        if not detected_items and qwen_error:
            print("[RECOGNITION] final detected=0")
            return {
                "status": "error",
                "message": "AI 食材识别服务暂时不可用，请稍后重试",
                "detected": [],
            }
        # -------------------------------------------------------

        # Recognition has no traceable purchase/storage/visual freshness data yet.
        # Keep the legacy string field, but derive it from FreshFusion's explicit
        # unknown result instead of inventing a value from the ingredient name.
        for item in detected_items:
            detail = calculate_freshfusion({"ingredient_name": item["name"]})
            item["freshness"] = detail["freshness_label"]
            item["freshness_detail"] = detail

        # 4. 第四步：清理临时文件（可选）
        # os.remove(temp_path)

        print(f"[RECOGNITION] final food count={len(detected_items)} items={detected_items}")
        return {"status": "success", "detected": detected_items}

    except Exception as e:
        print(f"识别接口异常: {e}")
        return {"status": "error", "message": str(e)}


@app.post("/api/recommendations")
@inventory_guard
async def multi_objective_recommendations(request: RecommendationRequest):
    """Return deterministic recommendations from local recipes and user inventory."""
    if not any(user.get("id") == request.user_id for user in get_all_users()):
        raise HTTPException(status_code=404, detail="用户不存在")

    saved_preferences={}
    if storage.is_sqlite:
        from app.domain.preferences import scoring_preferences as read_scoring_preferences
        saved_preferences=read_scoring_preferences(request.user_id)
    scoring_preferences = {**saved_preferences,**dict(request.preferences or {})}
    if saved_preferences.get('disliked_ingredients'):
        incoming=scoring_preferences.get('disliked_ingredients',[])
        if isinstance(incoming,str):incoming=re.split('[,，、\n]',incoming)
        scoring_preferences['disliked_ingredients']=list(dict.fromkeys(saved_preferences['disliked_ingredients']+(incoming if isinstance(incoming,list) else [])))
    scoring_preferences.pop("budget", None)
    scoring_preferences.pop("difficulty_target", None)
    scoring_preferences.pop("nutrition_target", None)
    if request.budget is not None:
        scoring_preferences["budget"] = request.budget
    if request.difficulty_target is not None:
        scoring_preferences["difficulty_target"] = request.difficulty_target
    if request.nutrition_target is not None:
        scoring_preferences["nutrition_target"] = request.nutrition_target.model_dump(exclude_none=True)

    try:
        validate_weights(request.weights)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    inventory_path = os.path.join(USER_DATA_BASE, request.user_id, "inventory.json")
    inventory = []
    if storage.exists(inventory_path):
        try:
            loaded = storage.read(inventory_path, [])
            inventory = loaded if isinstance(loaded, list) else []
        except (OSError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=500, detail="用户库存数据无法读取") from exc

    recipes = load_recipes()
    generated_at = datetime.now().astimezone().isoformat()
    scoring_time = datetime.now()
    safe_names = safe_inventory_names(inventory, scoring_time)
    if not safe_names:
        return {
            "algorithm_version": "multi_objective_v1",
            "user_id": request.user_id,
            "generated_at": generated_at,
            "status": "inventory_required",
            "message": "当前没有可用库存食材，请先添加或识别食材后再获取推荐。",
            "eligible_recipe_count": 0,
            "filtered_recipe_count": len(recipes),
            "recommendations": [],
        }

    eligible_recipes, filtered_count = filter_eligible_recipes(
        inventory=inventory,
        recipes=recipes,
        preferences=scoring_preferences,
        now=scoring_time,
    )
    if not eligible_recipes:
        return {
            "algorithm_version": "multi_objective_v1",
            "user_id": request.user_id,
            "generated_at": generated_at,
            "status": "no_eligible_recipes",
            "message": "当前库存与候选菜谱没有有效的关键食材匹配。",
            "eligible_recipe_count": 0,
            "filtered_recipe_count": filtered_count,
            "recommendations": [],
        }

    try:
        recommendations = recommend_recipes(
            inventory=inventory,
            top_k=len(eligible_recipes) if storage.is_sqlite else request.top_k,
            preferences=scoring_preferences,
            weights=request.weights,
            recipes=eligible_recipes,
            now=scoring_time,
        )
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    personalization=None
    if storage.is_sqlite:
        from app.domain.learning import rerank
        recommendations,personalization=rerank(request.user_id,recommendations)
        recommendations=recommendations[:request.top_k]
    return {
        "personalization":personalization,
        "algorithm_version": algorithm_version_for_recommendations(recommendations),
        "user_id": request.user_id,
        "generated_at": generated_at,
        "status": "success",
        "message": "已根据当前安全库存生成推荐。",
        "eligible_recipe_count": len(eligible_recipes),
        "filtered_recipe_count": filtered_count,
        "recommendations": recommendations,
    }

@app.post("/api/freshness/evaluate")
async def evaluate_freshness(request: FreshnessEvaluationRequest):
    """Evaluate one item without persisting or enriching it with fabricated data."""
    evaluated_at = datetime.now(timezone.utc)
    payload = request.model_dump(exclude_none=True)
    payload["name"] = payload.pop("ingredient_name")
    result = _public_freshfusion_result(payload, evaluated_at)
    return {"evaluated_at": evaluated_at.isoformat().replace("+00:00", "Z"), **result}


@app.get("/api/users/{user_id}/inventory/freshness")
@inventory_guard
async def evaluate_inventory_freshness(user_id: str, request: Request):
    """Evaluate inventory in stable file order; never writes the inventory file."""
    for forbidden_parameter in ("reference_time", "visual_freshness"):
        if forbidden_parameter in request.query_params:
            raise HTTPException(status_code=422, detail=f"不允许客户端提供{forbidden_parameter}")
    if not any(user.get("id") == user_id for user in get_all_users()):
        raise HTTPException(status_code=404, detail="用户不存在")
    inventory_path = os.path.join(USER_DATA_BASE, user_id, "inventory.json")
    inventory: List[Dict[str, Any]] = []
    if storage.exists(inventory_path):
        try:
            loaded = storage.read(inventory_path, [])
            if not isinstance(loaded, list):
                raise ValueError("inventory must be an array")
            inventory = loaded
        except (OSError, json.JSONDecodeError, ValueError) as exc:
            raise HTTPException(status_code=500, detail="用户库存数据无法读取") from exc

    evaluated_at = datetime.now(timezone.utc)
    evaluated_at_iso = evaluated_at.isoformat().replace("+00:00", "Z")
    evaluated = []
    for item in inventory:
        detail = _public_freshfusion_result(item, evaluated_at)
        evaluated.append({"item_id": item.get("id"), "name": item.get("name"), **detail})
    return {
        "algorithm_version": "freshfusion_v1",
        "user_id": user_id,
        "evaluated_at": evaluated_at_iso,
        "generated_at": evaluated_at_iso,
        "sort_order": "inventory_file_order",
        "total_count": len(evaluated),
        "evaluable_count": sum(item["fresh_score"] is not None for item in evaluated),
        "unknown_count": sum(item["fresh_score"] is None for item in evaluated),
        "expired_count": sum(item["expired"] for item in evaluated),
        "expiring_soon_count": sum(item["expiring_soon"] for item in evaluated),
        "items": evaluated,
    }


@app.get("/api/recommend-recipe")
async def recommend_recipe(user_prompt: str, user_id: str, save_history: bool = True):
    # --- 1. 变量初始化防御 (防止 referenced before assignment) ---
    full_response = ""
    recipe_data = {}

    try:
        # --- 2. 强制中文指令注入 (解决英文回复问题) ---
        safe_prompt = user_prompt
        if "中文" not in safe_prompt:
            safe_prompt += " (请务必用简体中文详细回复，步骤要极其详细，像教小朋友一样)"

        # --- 3. 记录用户提问 ---
        if save_history:
            save_chat_message(user_id, "user", safe_prompt)

        # --- 4. 获取库存并调用 AI ---
        inventory = get_current_inventory_names(user_id)

        # 逐块接住生成器内容 (后端接完，不流向前端)
        async for chunk in get_recipe_suggestion(inventory, safe_prompt):
            full_response += chunk

        if not full_response:
            raise ValueError("AI 未返回任何内容")

        # --- 5. 强力 JSON 解析 ---
        import re
        # 匹配最外层的 {}
        json_match = re.search(r'(\{.*\})', full_response, re.DOTALL)
        clean_json = json_match.group(1) if json_match else full_response
        recipe_data = json.loads(clean_json)

        # --- 6. 核心补全：确保前端渲染不白屏 ---
        # 补全菜名
        if "dish_name" not in recipe_data:
            recipe_data["dish_name"] = "精选创意菜"

        # 补全营养成分
        if "nutrition" not in recipe_data:
            recipe_data["nutrition"] = None

        # 补全食材清单
        if "ingredients_list" not in recipe_data:
            recipe_data["ingredients_list"] = []

        # 补全缺失和消耗追踪
        if "missing" not in recipe_data:
            recipe_data["missing"] = []
        if "used_ingredients" not in recipe_data:
            recipe_data["used_ingredients"] = []

        # --- 7. 持久化：保存 AI 回答到历史记录 ---
        if save_history:
            ai_display_content = f"为您准备好了：{recipe_data['dish_name']}"
            save_chat_message(
                user_id,
                "assistant",
                ai_display_content,
                recipe=recipe_data,
                missing=recipe_data.get('missing', [])
            )
            print(f"✅ [DEBUG] 用户 {user_id} 的完整对话已持久化到文件")

        # --- 8. 返回给前端 ---
        return {"status": "success", "recipe": recipe_data}

    except Exception as e:
        print(f"❌ [ERROR] 推荐接口崩溃: {str(e)}")
        # 报错时也尝试存一条记录，防止对话流断开
        if save_history:
            save_chat_message(user_id, "assistant", "厨师长刚才走神了，没听清您的要求，能再说一遍吗？")
        return {"status": "error", "message": f"处理失败: {str(e)}"}

class ConsumeBatchItem(BaseModel):
    model_config = {"extra": "forbid"}
    item_id: str = Field(min_length=1, max_length=128)
    quantity: int = Field(strict=True, gt=0)
    expected_quantity: int = Field(strict=True, gt=0)
    expected_revision: Optional[str] = Field(None,min_length=64,max_length=64)


class ConsumeBatchRequest(BaseModel):
    model_config = {"extra": "forbid"}
    user_id: str = Field(min_length=1)
    idempotency_key: str = Field(pattern=r"^[A-Za-z0-9_-]{8,128}$")
    items: List[ConsumeBatchItem] = Field(min_length=1, max_length=100)

    @model_validator(mode="after")
    def unique_items(self):
        if len({row.item_id for row in self.items}) != len(self.items):
            raise ValueError("同一批次只能选择一次")
        return self


@app.post("/api/inventory/consume")
@inventory_guard
async def consume_inventory_batches(request: ConsumeBatchRequest):
    path = os.path.join(USER_DATA_BASE, request.user_id, "inventory.json")
    try:
        return (storage.consume if storage.is_sqlite else consume_batches)(path, request.idempotency_key,
                               [row.model_dump(exclude_none=True) for row in request.items], _write_inventory_atomic)
    except OSError as exc:
        raise HTTPException(500, "扣减响应未确认，请使用原幂等键查询结果") from exc


@app.get("/api/inventory/consumption/{idempotency_key}")
@inventory_guard
async def get_consumption_receipt(idempotency_key: str, user_id: str):
    return (storage.receipt if storage.is_sqlite else consumption_receipt)(os.path.join(USER_DATA_BASE, user_id, "inventory.json"), idempotency_key)


@app.post("/api/consume-ingredients")
async def consume_ingredients(used_items: List[str], user_id: str):
    if not any(str(user.get("id")) == str(user_id) for user in get_all_users()):
        return {"status": "error", "message": "用户不存在"}
    with storage.session(os.path.join(USER_DATA_BASE, user_id, "inventory.json")):
        path = get_user_path(user_id, "inventory.json")
    
        if not storage.exists(path):
            return {"status": "error", "message": "库存文件不存在"}
    
        inventory_data = storage.read(path, [])
    
        used_names = {normalize_inventory_name(name) for name in used_items}
        for name in used_names:
            if sum(normalize_inventory_name(row.get("name")) == name for row in inventory_data) > 1:
                return {"status": "error", "message": "同名多批次，请升级客户端按批次扣减"}
        # 执行扣减逻辑
        new_inventory = []
        used_names = {normalize_inventory_name(name) for name in used_items}
        for item in inventory_data:
            # 匹配名称（统一转小写去空格）
            name_key = normalize_inventory_name(item["name"])
    
            if name_key in used_names:
                # 如果在消耗名单里，数量减 1
                item["quantity"] = int(item.get("quantity", 1)) - 1
                # 如果减完后数量大于 0，保留；否则不加入 new_inventory (即删除)
                if item["quantity"] > 0:
                    new_inventory.append(item)
            else:
                # 不在消耗名单里的，原样保留
                new_inventory.append(item)
    
        # 保存更新后的库存
        _write_inventory_atomic(path, new_inventory)
    
        return {"status": "success", "remaining_count": len(new_inventory)}


@app.get("/api/inventory")
@inventory_guard
async def get_inventory(user_id: str):  # ✅ 必须有这个参数
    _require_existing_user(user_id)
    path = os.path.join(USER_DATA_BASE, str(user_id), "inventory.json")

    if not storage.exists(path):
        return []

    try:
        data = storage.read(path, [])

        if storage.is_sqlite and isinstance(data,list):
            from app.services.inventory_transactions import digest
            return [{**row,"_revision":digest(row)} for row in data]
        return data if isinstance(data, list) else []
    except (OSError, json.JSONDecodeError) as e:
        print(f"读取失败: {e}")
        raise HTTPException(status_code=500, detail="用户库存数据无法读取") from e


@app.delete("/api/inventory/{item_id}")
@inventory_guard
async def delete_item(item_id: str, user_id: str, request_context: Request = None):  # ✅ 确保接收这两个参数
    _require_existing_user(user_id)
    try:
        path = os.path.join(USER_DATA_BASE, str(user_id), "inventory.json")

        if not storage.exists(path):
            raise HTTPException(status_code=404, detail="库存项目不存在")

        # 2. 读取数据
        data = storage.read(path, [])

        # 3. 执行过滤（删除匹配 ID 的项）
        # 注意：确保 item["id"] 和传入的 item_id 类型一致（都是字符串）
        original_count = len(data)
        new_data = [item for item in data if str(item["id"]) != str(item_id)]

        # 4. 只有确实删除了才写文件（优化性能）
        if len(new_data) < original_count:
            _write_inventory_atomic(path, new_data)
            print(f"用户 {user_id} 删除了食材 {item_id}")
            return {"status": "success"}
        else:
            raise HTTPException(status_code=404, detail="库存项目不存在")

    except HTTPException:
        raise
    except (OSError, json.JSONDecodeError) as e:
        print(f"删除失败: {e}")
        raise HTTPException(status_code=500, detail="用户库存数据无法读取") from e


@app.put("/api/inventory/{item_id}")
@inventory_guard
async def update_item(item_id: str, item_data: InventoryUpdateItem, user_id: str, request_context: Request = None):
    _require_existing_user(user_id)
    path = os.path.join(USER_DATA_BASE, str(user_id), "inventory.json")
    if not storage.exists(path):
        raise HTTPException(status_code=404, detail="库存项目不存在")
    try:
        data = storage.read(path, [])

        updates = item_data.model_dump(exclude_unset=True)
        found = False
        for item in data:
            if str(item["id"]) == str(item_id):
                if "purchase_time" in updates:
                    item.pop("purchase_date", None)
                if "storage_type" in updates:
                    item.pop("storage_method", None)
                    item.pop("storage", None)
                candidate = {**item, **updates}
                _validate_inventory_temporal_fields(candidate)
                if "name" in updates:
                    updates["name"] = normalize_inventory_name(updates["name"])
                item.update(updates)
                found = True
                break

        if not found:
            raise HTTPException(status_code=404, detail="库存项目不存在")

        _write_inventory_atomic(path, data)
        return {"status": "success"}
    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e)) from e
    except (OSError, json.JSONDecodeError) as e:
        raise HTTPException(status_code=500, detail="用户库存数据无法读取") from e


@app.get("/api/tts")
async def get_tts(request: Request, text: str = Query(..., min_length=1, max_length=3000)):
    """
    语音合成接口
    """
    try:
        # 传入文本生成语音，建议在 tts_service 中处理文件名唯一性
        filename = await generate_voice(text)
        if storage.is_sqlite:
            from pathlib import Path
            from app.domain.private_media import save_media
            root=Path(AUDIO_DIR).resolve();path=(root/filename).resolve()
            if path.parent!=root or not path.is_file() or path.stat().st_size>10*1024*1024:raise HTTPException(500,'语音文件不可用')
            url=save_media(request.state.user_id,path.read_bytes(),'audio/mpeg','audio')
            path.unlink()
            return {'audio_url':url}
        return {"audio_url": f"/static/audio/{filename}"}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/api/chat-history")
async def get_chat_history_api(user_id: str):
    return storage.read(get_user_path(user_id, "chat_history.json"), [])


# 确保 save_chat_message 接收 user_id 参数
def save_chat_message(user_id: str, role: str, content: str, recipe: dict = None, missing: list = None):
    # ✅ 核心修改：通过 get_user_path 获取该用户的专属 chat_history.json 路径
    path = get_user_path(user_id, "chat_history.json")

    history = []
    if storage.exists(path):
        try:
            history = storage.read(path, [])
        except:
            history = []

    # 加入新消息
    history.append({
        "role": role,
        "content": content,
        "recipe": recipe,
        "missing": missing,
        "time": datetime.now().strftime("%H:%M")
    })

    # 仅保留最近 20 条，写入该用户文件夹
    storage.write(path, history[-20:])

@app.post("/api/clear-chat")
async def clear_chat(user_id: str):
    storage.write(get_user_path(user_id, "chat_history.json"), [])
    return {"status": "success"}

# ==================== 用户认证相关接口 ====================

@app.post("/api/upload-avatar")
async def api_upload_avatar(request: Request, file: UploadFile = File(...)):
    """上传头像"""
    if storage.is_sqlite:
        from app.domain.private_media import upload_avatar
        return await upload_avatar(request,file)
    try:
        # 验证文件类型
        if not file.content_type.startswith('image/'):
            return {"status": "error", "message": "只能上传图片文件"}
        
        # 验证文件大小（不超过 5MB）
        file_size = 0
        content = await file.read()
        file_size = len(content)
        if file_size > 5 * 1024 * 1024:
            return {"status": "error", "message": "图片大小不能超过 5MB"}
        
        # 生成唯一文件名
        file_extension = file.filename.split('.')[-1] if '.' in file.filename else 'jpg'
        filename = f"{uuid.uuid4()}.{file_extension}"
        file_path = f"{UPLOAD_DIR}/avatars/{filename}"
        
        # 确保头像目录存在
        avatar_dir = f"{UPLOAD_DIR}/avatars"
        os.makedirs(avatar_dir, exist_ok=True)
        
        # 保存文件
        with open(file_path, "wb") as f:
            f.write(content)
        
        # 返回可访问的 URL
        avatar_url = f"/static/uploads/avatars/{filename}"
        
        return {
            "status": "success",
            "message": "上传成功",
            "avatar_url": avatar_url
        }
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.post("/api/send-sms-code")
async def api_send_sms_code(phone: str):
    """发送短信验证码"""
    try:
        # 检查手机号是否已被注册
        if find_user_by_phone(phone):
            return {"status": "error", "message": "该手机号已被注册"}
        
        success = send_sms_code(phone)
        if success:
            return {"status": "success", "message": "验证码已发送"}
        else:
            return {"status": "error", "message": "发送失败"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

class RegisterRequest(BaseModel):
    invitation_code: str = ""
    nickname: str
    phone: str = ""
    password: str = ""
    sms_code: str = ""

@app.post("/api/register")
async def api_register(data: RegisterRequest):
    try:
        with storage.transaction():
            if storage.is_sqlite: consume_invitation(data.invitation_code)
            user = create_user(data.nickname, data.phone, data.nickname, data.password)
        return {"status": "success", "user": user}
    except Exception as e:
        return {"status": "error", "message": str(e)}


@app.post("/api/login")
async def api_login(data: LoginRequest):  # ✅ 使用模型接收整个 Body
    try:
        # 从 data 对象中获取字段
        user = authenticate_user(data.username, data.password)

        if user:
            return {
                "status": "success",
                "message": "登录成功",
                "user": user,
                **(issue_session(user["id"]) if storage.is_sqlite else {})
            }
        else:
            return {"status": "error", "message": "用户名或密码错误"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.get("/api/user/{user_id}")
async def api_get_user(user_id: str):
    """获取用户信息"""
    try:
        from app.models.user import get_all_users
        users = get_all_users()
        
        for user in users:
            if user["id"] == user_id:
                return {
                    "status": "success",
                    "user": {
                        "id": user["id"],
                        "username": user["username"],
                        "phone": user["phone"],
                        "nickname": user["nickname"],
                        "avatar": user["avatar"]
                    }
                }
        
        return {"status": "error", "message": "用户不存在"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.put("/api/user/{user_id}")
async def api_update_user(user_id: str, nickname: str = None, phone: str = None, avatar: str = None):
    """更新用户信息"""
    if storage.is_sqlite and avatar is not None:
        from app.domain.private_media import validate_avatar
        current=next((u for u in get_all_users() if u['id']==user_id),{})
        validate_avatar(user_id,avatar,current.get('avatar',''))
    try:
        update_data = {}
        if nickname is not None:
            update_data["nickname"] = nickname
        if phone is not None:
            # 检查手机号是否被其他用户使用
            existing_user = find_user_by_phone(phone)
            if existing_user and existing_user["id"] != user_id:
                return {"status": "error", "message": "该手机号已被其他用户使用"}
            update_data["phone"] = phone
        if avatar is not None:
            update_data["avatar"] = avatar
        
        updated_user = update_user(user_id, **update_data)
        
        if updated_user:
            return {
                "status": "success",
                "message": "更新成功",
                "user": {
                    "id": updated_user["id"],
                    "username": updated_user["username"],
                    "phone": updated_user["phone"],
                    "nickname": updated_user["nickname"],
                    "avatar": updated_user["avatar"]
                }
            }
        else:
            return {"status": "error", "message": "用户不存在"}
    except Exception as e:
        return {"status": "error", "message": str(e)}

@app.delete("/api/user/{user_id}")
async def api_delete_user(user_id: str):
    """注销用户"""
    try:
        success = delete_user(user_id)
        
        if success:
            return {"status": "success", "message": "账户已注销"}
        else:
            return {"status": "error", "message": "用户不存在"}
    except Exception as e:
        return {"status": "error", "message": str(e)}


@app.get("/api/notifications")
async def get_notifications(user_id: str):
    # 每次获取前先扫描一遍库存
    update_expiration_notifications(user_id)

    path = get_user_path(user_id, "notifications.json")
    if storage.exists(path):
        return storage.read(path, [])
    return []


@app.post("/api/notifications/read")
async def mark_as_read(user_id: str, msg_id: str = None):
    path = get_user_path(user_id, "notifications.json")
    if not storage.exists(path): return {"status": "error"}

    data = storage.read(path, [])

    for m in data:
        if msg_id is None or m['id'] == msg_id:
            m['is_read'] = True

    storage.write(path, data)
    return {"status": "success"}

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
