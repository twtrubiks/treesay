"""Claude API 封裝：Messages API ＋ structured outputs ＋ 重試。

輸出由 structured outputs 把關：每個用途帶自己的 JSON schema
（`output_config.format`），模型只能產出符合 schema 的 JSON——沒有 CLI
envelope、沒有 code fence。例外只有拒答與 max_tokens 截斷，兩者都當失敗
重試 1 次；逾時不重試（5 分鐘 planting 逾期規則以單次 timeout 240s ＋
緩衝推算，所以 SDK 自己的重試關掉，次數只由這裡決定）。

沒有工具：照片直接以 image block 放進訊息，這個呼叫裡不存在任何工具。
使用者寫的日記原樣進 prompt、等同不可信輸入，但裡面就算夾帶指令，
能影響的也只有輸出的文字。
"""

from __future__ import annotations

import base64
import io
import json
import logging
import os
from pathlib import Path

import anthropic
from PIL import ExifTags, Image, ImageOps

import models
from models import DEFAULT_EMOTION, EMOTIONS

logger = logging.getLogger(__name__)

# 不用 SDK 預設的 ANTHROPIC_API_KEY：那個變數一旦 export 在 shell 設定裡，
# Claude Code 也會讀到它，從訂閱改走 API 計費。樹說用自己的名字，兩邊互不干擾。
API_KEY_ENV = "TREESAY_ANTHROPIC_API_KEY"

TIMEOUT_SECONDS = 240
# 思考與輸出共用這個上限。日記與週報都是幾百字的短文，16k 是留給 adaptive
# thinking 的餘裕，不是預期會用到這麼多。
MAX_TOKENS = 16000

# 依強度排序，錯誤訊息直接照這個順序列給使用者
EFFORTS = ("low", "medium", "high", "xhigh")


# 每個用途各自挑檔次。每一次呼叫都按 token 計費——日記不需要最貴的檔次。
DEFAULT_PROFILES: dict[str, tuple[str, str]] = {
    # 種樹：日記是使用者每天真的會讀的東西，值得好模型。但它是一段一百多字的
    # 溫暖短文，不是推理題，effort 開到頂只是多花錢、不會讓文字更暖。
    "plant": ("claude-opus-5-5", "medium"),
    # 週報：把已經寫好的七篇日記濃縮成四個欄位，模型小一階，但 effort 跟種樹
    # 齊平——一週只跑一次，省在這裡省不到什麼。
    "weekly": ("claude-sonnet-5-5", "medium"),
}

# API 限制：單張照片 base64 後 10 MB、整個 request 32 MB。照片總預算抓在
# request 上限之下，留餘裕給文字。
IMAGE_MAX_B64_BYTES = 10_000_000
IMAGES_BUDGET_B64_BYTES = 24_000_000

# 送給樹看的照片縮到 Claude 的標準解析度等級（長邊 1568px、約 115 萬像素，
# 一張約 1,500 token）。Opus 5.5 屬於高解析度等級，手機原圖會吃到單張 4,784
# token 的上限，比一次不附照片的種樹還貴——樹只要看懂你拍了什麼，不必數清
# 每一片葉子。縮的只是送出去的那一份，原圖照樣留在磁碟上。
IMAGE_MAX_EDGE = 1568
IMAGE_MAX_PIXELS = 1_150_000
JPEG_QUALITY = 85


# 每百萬 token 的美元價格（輸入, 輸出），抄自官方定價頁（2026-10）。只用來估算
# 「AI 費用」頁的數字，實際帳單以 Claude Console 為準。用環境變數換成表上沒有的
# 模型時，那幾次呼叫會記成未計價——在這裡補上那個模型就好。
# Haiku 5.5 的 prompt 超過 10 萬 token 會變貴，樹說的請求到不了那裡。
PRICES: dict[str, tuple[float, float]] = {
    "claude-fable-5-1": (10, 50),
    "claude-opus-5-5": (4, 20),
    "claude-sonnet-5-5": (2, 10),
    "claude-haiku-5-5": (0.10, 0.50),
}


def resolve_profile(name: str) -> tuple[str, str]:
    """取某個用途的 (model, effort)，環境變數可覆寫預設。

    每次呼叫都重讀環境變數，不在 import 時凍結——測試不必操心 import 順序，
    設定何時生效也只跟程序有沒有重啟有關，跟模組什麼時候被載入無關。

    effort 打錯字（xhigh → hgih）若原樣傳下去，API 會拒絕，而前端只會
    顯示「樹睡著了」——使用者永遠查不到是自己少打一個字母。寧可回退預設，
    也不要讓一個 typo 變成看不懂的失敗。模型名稱沒辦法離線驗證，改由啟動
    時印出實際生效的檔次讓人自己確認。
    """
    model, effort = DEFAULT_PROFILES[name]
    prefix = f"TREESAY_{name.upper()}_"
    chosen_effort = os.environ.get(f"{prefix}EFFORT", effort)
    if chosen_effort not in EFFORTS:
        logger.warning(
            "%sEFFORT=%r 不是合法檔次（%s），改用預設 %s",
            prefix,
            chosen_effort,
            " / ".join(EFFORTS),
            effort,
        )
        chosen_effort = effort
    return os.environ.get(f"{prefix}MODEL", model), chosen_effort


def profile_summary() -> str:
    """啟動時列印用：環境變數覆寫有沒有吃到，看這一行就知道。

    用途名沿用 profile key 而非中文標籤，好直接對上 TREESAY_PLANT_MODEL
    這類變數名。
    """
    parts = []
    for name in DEFAULT_PROFILES:
        model, effort = resolve_profile(name)
        parts.append(f"{name}={model} / {effort}")
    return "，".join(parts)


def unpriced_models() -> list[str]:
    """目前生效、但不在 PRICES 裡的模型，啟動時提醒用。

    這些模型的呼叫會記成未計價，「AI 費用」頁的數字就會少算。價目表是手抄的，
    模型改名或換了環境變數都可能讓它過期——在啟動時講，比等到月底看帳才發現好。
    """
    seen: list[str] = []
    for name in DEFAULT_PROFILES:
        model, _ = resolve_profile(name)
        if model not in PRICES and model not in seen:
            seen.append(model)
    return seen


class AIError(Exception):
    """AI 呼叫失敗（API 錯誤或輸出不可用）。"""


class AITimeoutError(AIError):
    """AI 呼叫逾時（不重試）。"""


class AIKeyError(AIError):
    """API key 有問題——重按幾次也不會好，要讓使用者知道該去修什麼。"""


class AIKeyMissingError(AIKeyError):
    """沒有設定 API key。"""


class AIKeyInvalidError(AIKeyError):
    """API key 被 API 拒絕（401）。"""


def api_key() -> str | None:
    return os.environ.get(API_KEY_ENV, "").strip() or None


def check_key() -> None:
    """啟動檢查：只看 key 有沒有設，不打網路——有沒有效等真的呼叫時才知道。"""
    if api_key() is None:
        raise AIKeyMissingError(f"沒有設定 {API_KEY_ENV}")


def estimate_cost(model: str, input_tokens: int, output_tokens: int) -> float | None:
    """依價目表估算一次呼叫的美元費用；模型不在表上回 None。"""
    price = PRICES.get(model)
    if price is None:
        return None
    return (input_tokens * price[0] + output_tokens * price[1]) / 1_000_000


def record_usage(profile: str, model: str, usage) -> None:
    """把一次呼叫的用量記進資料庫，給「AI 費用」頁看。

    拒答、被截斷而要重試的回應一樣計費，所以每拿到一個回應就記一筆，
    不管之後解不解析得出來。費用在記下的當下算好存起來，日後定價調整，
    舊帳不會跟著被改寫。記帳失敗只留 log：錢已經花了、回覆也拿到了，
    不能因為記帳出錯讓這次種樹作廢。
    """
    try:
        with models.SessionLocal() as db:
            db.add(
                models.ApiUsage(
                    created_at=models.now_local(),
                    purpose=profile,
                    model=model,
                    input_tokens=usage.input_tokens,
                    output_tokens=usage.output_tokens,
                    cost_usd=estimate_cost(model, usage.input_tokens, usage.output_tokens),
                )
            )
            db.commit()
    except Exception:
        logger.warning("記錄 AI 用量失敗", exc_info=True)


def normalize_emotion(value: object) -> str:
    """枚舉外的情緒值 fallback 為 calm。"""
    if isinstance(value, str) and value in EMOTIONS:
        return value
    return DEFAULT_EMOTION


# 單條記憶的長度上限。超過的多半是整段日記而非「一件事」，
# 存進去會讓之後每次種樹的 prompt 被灌爆，直接丟棄。
MEMORY_ITEM_MAX_CHARS = 120

# 單個關鍵詞的長度上限。超過的是句子不是詞。
KEYWORD_ITEM_MAX_CHARS = 20


def _clean_str_list(value: object, limit: int, max_chars: int) -> list[str]:
    """把 AI 回的字串清單欄位整理乾淨，寬進嚴出：

    不是清單就當沒有，非字串、空白、過長、重複的條目一律略過。
    """
    if not isinstance(value, list):
        return []
    out: list[str] = []
    for item in value:
        if not isinstance(item, str):
            continue
        text = item.strip()
        if not text or len(text) > max_chars or text in out:
            continue
        out.append(text)
        if len(out) >= limit:
            break
    return out


def normalize_memories(value: object, limit: int) -> list[str]:
    """記憶會被永久保存並注入之後每次種樹，清洗從嚴。"""
    return _clean_str_list(value, limit, MEMORY_ITEM_MAX_CHARS)


def normalize_keywords(value: object, limit: int) -> list[str]:
    """關鍵詞欄位壞掉就當沒有——比照記憶，不能因為它讓種樹跟著失敗。"""
    return _clean_str_list(value, limit, KEYWORD_ITEM_MAX_CHARS)


def sniff_media_type(data: bytes) -> str | None:
    """看檔頭認圖片格式，只認 API 支援的四種。

    不信副檔名：API 會檢查 media_type 與內容是否相符，標錯一張整次就失敗。
    """
    if data.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def prepare_image(data: bytes) -> tuple[bytes, str] | None:
    """把一張照片整理成要送出的 (bytes, media_type)；送不出去回 None。

    夠小、方向正確、格式 API 認得的原檔照送；其餘縮圖、轉正後重新編碼。
    先在本機解一次：壞掉的檔案若送出去，API 會讓整次請求 400，連帶那天
    種不下去。
    """
    try:
        img = Image.open(io.BytesIO(data))
        w, h = img.size
        scale = min(1.0, IMAGE_MAX_EDGE / max(w, h), (IMAGE_MAX_PIXELS / (w * h)) ** 0.5)
        # 手機常把方向記在 EXIF 而不是真的轉像素，而模型不讀 metadata——
        # 原檔照送的話，直拍的照片樹會看成躺著的
        rotated = img.getexif().get(ExifTags.Base.Orientation, 1) != 1
        media_type = sniff_media_type(data)
        if media_type and scale == 1 and not rotated:
            img.load()
            return data, media_type
        img.thumbnail((max(1, int(w * scale)), max(1, int(h * scale))))
        img = ImageOps.exif_transpose(img)
        buf = io.BytesIO()
        if img.mode in ("RGBA", "LA") or "transparency" in img.info:
            img.convert("RGBA").save(buf, "PNG")
            return buf.getvalue(), "image/png"
        img.convert("RGB").save(buf, "JPEG", quality=JPEG_QUALITY)
        return buf.getvalue(), "image/jpeg"
    except Exception:
        # Pillow 解不開時丟的例外五花八門（UnidentifiedImageError、截斷檔的
        # OSError、DecompressionBombError……），對這裡都是同一件事：這張送不出去
        return None


def image_blocks(paths: list[Path]) -> list[dict | None]:
    """把照片轉成 image block，順序與 paths 對齊；送不出去的位置放 None。

    格式不支援（例如 HEIC）、檔案損毀、單張過大、超出整體預算、檔案不見，
    都只是這張樹看不到——一張照片不能拖垮整次種樹，否則那天會永遠種不下去。
    """
    blocks: list[dict | None] = []
    budget = IMAGES_BUDGET_B64_BYTES
    for path in paths:
        try:
            prepared = prepare_image(path.read_bytes())
        except OSError:
            prepared = None
        encoded = base64.b64encode(prepared[0]).decode() if prepared else ""
        if prepared and len(encoded) <= min(IMAGE_MAX_B64_BYTES, budget):
            budget -= len(encoded)
            blocks.append(
                {
                    "type": "image",
                    "source": {"type": "base64", "media_type": prepared[1], "data": encoded},
                }
            )
        else:
            logger.warning(
                "照片 %s 無法送給樹看（格式不支援、檔案損毀、太大或不見了），略過", path.name
            )
            blocks.append(None)
    return blocks


def parse_response(response) -> dict:
    """取出 structured output 的 JSON 物件，失敗丟 AIError。

    schema 保證正常結束的輸出能解析；拒答（refusal）與 max_tokens 截斷時
    文字不保證符合 schema，一律當失敗交給重試。
    """
    if response.stop_reason != "end_turn":
        raise AIError(f"Claude 沒有正常結束（stop_reason={response.stop_reason}）")
    text = "".join(block.text for block in response.content if block.type == "text")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise AIError(f"輸出不是合法 JSON：{e}") from e
    if not isinstance(data, dict):
        raise AIError(f"輸出 JSON 不是物件（得到 {type(data).__name__}）")
    return data


def _content(prompt: str, images: list[dict]) -> list[dict]:
    """照片放前面、各自帶編號標籤，prompt 裡用「附照片 N」對上。"""
    blocks: list[dict] = []
    for i, image in enumerate(images, 1):
        blocks += [{"type": "text", "text": f"照片 {i}："}, image]
    blocks.append({"type": "text", "text": prompt})
    return blocks


async def _create(prompt: str, schema: dict, images: list[dict], profile: str):
    key = api_key()
    if key is None:
        raise AIKeyMissingError(f"沒有設定 {API_KEY_ENV}")
    model, effort = resolve_profile(profile)
    # key 明確傳入：SDK 就不會再去翻 ANTHROPIC_API_KEY 或磁碟上的設定檔
    async with anthropic.AsyncAnthropic(
        api_key=key, timeout=TIMEOUT_SECONDS, max_retries=0
    ) as client:
        try:
            response = await client.messages.create(
                model=model,
                max_tokens=MAX_TOKENS,
                messages=[{"role": "user", "content": _content(prompt, images)}],
                output_config={
                    "effort": effort,
                    "format": {"type": "json_schema", "schema": schema},
                },
            )
        except anthropic.APITimeoutError:
            raise AITimeoutError(f"Claude API 逾時（{TIMEOUT_SECONDS}s）") from None
        except anthropic.AuthenticationError as e:
            raise AIKeyInvalidError(f"{API_KEY_ENV} 被拒絕") from e
        except anthropic.APIError as e:
            # 前端只會看到療癒文案，真正的原因（額度用完、模型名打錯……）
            # 留在後端 log 供自行診斷
            logger.warning("Claude API 失敗：%s", e)
            raise AIError(f"Claude API 失敗：{e}") from e
    record_usage(profile, model, response.usage)
    return response


async def ask(
    prompt: str,
    schema: dict,
    images: list[dict] | None = None,
    profile: str = "plant",
) -> dict:
    """呼叫 Claude 並回傳符合 schema 的 dict；失敗重試 1 次。

    逾時與 key 的問題不重試：前者會把 planting 拖過逾期時間，後者重按也不會好。
    profile 決定模型與 effort（見 DEFAULT_PROFILES 與 resolve_profile）。
    """
    last_error = AIError("AI 呼叫失敗")
    for _ in range(2):
        try:
            response = await _create(prompt, schema, images or [], profile)
            return parse_response(response)
        except (AITimeoutError, AIKeyError):
            raise
        except AIError as e:
            last_error = e
    raise last_error
