"""claude CLI 封裝：async subprocess ＋ 雙層 JSON 解析 ＋ 重試。

輸出是雙層 JSON：`--output-format json` 回的是 CLI envelope
（{"type": "result", "subtype": ..., "result": "..."}）。
先解析 envelope 檢查 subtype 是否 success，再取 result 字串
剝除 code fence 後 json.loads。解析失敗重試 1 次；逾時不重試
（5 分鐘 planting 逾期規則以單次 timeout 240s ＋ 緩衝推算）。

工具權限：使用者寫的日記會原樣進 prompt，對這個 subprocess 而言等同
不可信輸入，所以工具一律走排他白名單——`--tools` 決定 session 裡有哪些
工具存在，沒列到的等於不存在。預設一個都不給，只有要看照片時才開 Read。
再加 `--setting-sources ""` 不載入使用者的 settings.json，避免繼承那裡
既有的放行規則。

不靠 `--permission-mode` 收斂：實測 `-p` 無頭模式下權限模式不會攔工具
（要求跑 Bash 就真的跑了），只有 `--tools` 是 fail-closed 的。
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil

from models import DEFAULT_EMOTION, EMOTIONS

logger = logging.getLogger(__name__)

CLAUDE_CMD = "claude"
TIMEOUT_SECONDS = 240
AUTH_TIMEOUT_SECONDS = 15

# 依強度排序，錯誤訊息直接照這個順序列給使用者
EFFORTS = ("low", "medium", "high", "xhigh")


# 每個用途各自挑檔次。跑的是使用者自己的 Claude 訂閱額度，那份額度平常是拿來
# 工作的——日記不該跟工作搶最貴的檔次。
DEFAULT_PROFILES: dict[str, tuple[str, str]] = {
    # 種樹：日記是使用者每天真的會讀的東西，值得好模型。但它是一段一百多字的
    # 溫暖短文，不是推理題，effort 開到頂只是多燒額度、不會讓文字更暖。
    "plant": ("claude-opus-5", "medium"),
    # 週報：把已經寫好的七篇日記濃縮成四個欄位，模型小一階，但 effort 跟種樹
    # 齊平——一週只跑一次，省在這裡省不到什麼。
    "weekly": ("claude-sonnet-5", "medium"),
}


def resolve_profile(name: str) -> tuple[str, str]:
    """取某個用途的 (model, effort)，環境變數可覆寫預設。

    每次呼叫都重讀環境變數，不在 import 時凍結——測試不必操心 import 順序，
    設定何時生效也只跟程序有沒有重啟有關，跟模組什麼時候被載入無關。

    effort 打錯字（xhigh → hgih）若原樣傳下去，claude 會拒絕，而前端只會
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


class AIError(Exception):
    """AI 呼叫失敗（subprocess 錯誤或 JSON 解析失敗）。"""


class AITimeoutError(AIError):
    """AI 呼叫逾時（不重試）。"""


class AICliMissingError(AIError):
    """找不到 claude CLI（未安裝或不在 PATH）。"""


class AINotLoggedInError(AIError):
    """claude CLI 尚未登入。"""


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


def strip_code_fence(text: str) -> str:
    text = text.strip()
    if text.startswith("```"):
        first_newline = text.find("\n")
        if first_newline != -1 and text.endswith("```"):
            text = text[first_newline + 1 : -3].strip()
    return text


def parse_response(raw: str) -> dict:
    """解析 CLI envelope 與內層 JSON，任一層失敗丟 AIError。"""
    try:
        envelope = json.loads(raw)
    except json.JSONDecodeError as e:
        raise AIError(f"envelope 不是合法 JSON：{e}") from e
    if not isinstance(envelope, dict) or envelope.get("subtype") != "success":
        subtype = envelope.get("subtype") if isinstance(envelope, dict) else type(envelope).__name__
        raise AIError(f"claude 回傳非 success（subtype={subtype}）")
    result = envelope.get("result")
    if not isinstance(result, str):
        raise AIError("envelope 缺少 result 字串")
    try:
        inner = json.loads(strip_code_fence(result))
    except json.JSONDecodeError as e:
        raise AIError(f"result 不是合法 JSON：{e}") from e
    if not isinstance(inner, dict):
        raise AIError(f"result JSON 不是物件（得到 {type(inner).__name__}）")
    return inner


async def _auth_status() -> dict | None:
    """讀 `claude auth status --json`；無法判定時回 None（不當成未登入）。"""
    try:
        proc = await asyncio.create_subprocess_exec(
            CLAUDE_CMD,
            "auth",
            "status",
            "--json",
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        stdout, _ = await asyncio.wait_for(proc.communicate(), AUTH_TIMEOUT_SECONDS)
        status = json.loads(stdout)
    except (OSError, TimeoutError, json.JSONDecodeError):
        return None
    return status if isinstance(status, dict) else None


async def check_cli() -> None:
    """檢查 claude CLI 可用且已登入，否則丟對應的 AIError 子類。

    只採信兩個確定訊號——CLI 在不在 PATH、`auth status` 的 loggedIn，
    不對錯誤訊息做字串猜測，寧可漏判也不誤殺（額度用盡等情境一律
    走通用失敗路徑）。
    """
    if shutil.which(CLAUDE_CMD) is None:
        raise AICliMissingError("PATH 中找不到 claude CLI")
    status = await _auth_status()
    if status is not None and status.get("loggedIn") is False:
        raise AINotLoggedInError("claude CLI 尚未登入")


async def _run_claude(
    prompt: str,
    allowed_tools: list[str] | None = None,
    profile: str = "plant",
) -> str:
    model, effort = resolve_profile(profile)
    tools = ",".join(allowed_tools) if allowed_tools else ""
    cmd = [
        CLAUDE_CMD,
        "-p",
        "--output-format",
        "json",
        "--model",
        model,
        "--effort",
        effort,
        # 不繼承使用者 settings.json 的放行規則
        "--setting-sources",
        "",
        # 排他白名單：沒列到的工具在這個 session 中不存在（空字串＝全關）
        "--tools",
        tools,
    ]
    if allowed_tools:
        # 存在之外還要預先核准，才不必倚賴無頭模式的預設放行行為
        cmd += ["--allowedTools", tools]
    try:
        proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError as e:
        raise AICliMissingError(f"找不到 claude CLI：{e}") from e
    except OSError as e:
        raise AIError(f"無法啟動 claude CLI：{e}") from e
    try:
        stdout, stderr = await asyncio.wait_for(
            proc.communicate(prompt.encode()), TIMEOUT_SECONDS
        )
    except TimeoutError:
        proc.kill()
        await proc.wait()
        raise AITimeoutError(f"claude 呼叫逾時（{TIMEOUT_SECONDS}s）") from None
    if proc.returncode != 0:
        detail = stderr.decode(errors="replace").strip()
        # 前端只會看到療癒文案，真正的原因留在後端 log 供自行診斷
        logger.warning("claude 結束碼 %s：%s", proc.returncode, detail[:500])
        raise AIError(f"claude 結束碼 {proc.returncode}：{detail[:200]}")
    return stdout.decode()


async def ask(
    prompt: str,
    allowed_tools: list[str] | None = None,
    profile: str = "plant",
) -> dict:
    """呼叫 claude 並回傳內層 JSON dict；解析失敗重試 1 次，逾時不重試。

    profile 決定模型與 effort（見 DEFAULT_PROFILES 與 resolve_profile）。

    兩次都失敗才回頭診斷環境——沒登入之類的問題重按幾次也不會好，
    要讓使用者知道該去修什麼，而不是一直看到「樹睡著了」。
    """
    last_error = AIError("AI 呼叫失敗")
    for _ in range(2):
        try:
            raw = await _run_claude(prompt, allowed_tools, profile)
            return parse_response(raw)
        except (AITimeoutError, AICliMissingError):
            raise
        except AIError as e:
            last_error = e
    try:
        await check_cli()
    except AIError as diagnosis:
        raise diagnosis from last_error
    raise last_error
