"""FastAPI 路由。僅供本機使用（uvicorn 綁 127.0.0.1）。"""

from __future__ import annotations

import contextlib
import datetime
import json
import sys
import uuid
from pathlib import Path

from fastapi import Depends, FastAPI, File, Form, HTTPException, Response, UploadFile
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.orm import selectinload
from starlette.exceptions import HTTPException as StarletteHTTPException

import ai
import models
import prompts
import questions
from models import Day, Memory, Message, WeeklyReport

FRONTEND_DIST = Path(__file__).resolve().parent.parent / "frontend" / "dist"

TREE_ASLEEP = "樹睡著了，等等再來 🌙"
CLI_MISSING = "樹還沒連上森林 🌱 這台電腦找不到 claude 指令，請先安裝 Claude Code。"
NOT_LOGGED_IN = "樹在等你開門 🌿 請在終端機執行 claude auth login，再回來試一次。"
PLANTING_STALE_SECONDS = 300

# 今天之外還能回頭補種的天數。窗口刻意留短：如果任何一天都補得回來，森林就從
# 「發生過的紀錄」變成可以填滿的成果，空格會變成待辦事項——那正是這個 App 最
# 想避開的東西。而且它必須短於一週，否則會出現「週報早就生成快取、之後才補種
# 那天」的不一致（週報只生成已結束的週且永久快取）。
BACKFILL_WINDOW_DAYS = 2
BACKFILL_EXPIRED = "這一天已經走遠了，就讓它安靜地留著吧 🍂"

# 樹的記憶：種樹一次最多記下幾件事／注入 prompt 的條數上限／週報整理後的保留上限。
# 上限存在的理由不是省 token，是防走樣——記憶清單一旦長到讀不完，
# 「樹記得你」就退化成「樹存了一堆你的資料」。
PLANT_MEMORY_LIMIT = 3
MEMORY_PROMPT_LIMIT = 20
MEMORY_KEEP_LIMIT = 30

# 種一次樹最多留幾個關鍵詞。它是回望的線索，不是分類系統。
PLANT_KEYWORD_LIMIT = 4

# 近期日記注入：只取目標日往前這幾天內、最多這幾篇。窗口刻意小——
# 「近期」要對得起這兩個字，一個月前的日記突然被翻出來不是連續性，是驚嚇。
RECENT_DIARY_DAYS = 7
RECENT_DIARY_LIMIT = 3
RECENT_DIARY_CHARS = 300


def ai_http_error(exc: ai.AIError) -> HTTPException:
    """把 AI 失敗轉成使用者能行動的訊息。

    環境沒設好時重按幾次也不會好，要講清楚該修什麼；其餘失敗維持
    療癒文案，不裸露技術錯誤（真正的原因記在後端 log）。
    """
    if isinstance(exc, ai.AICliMissingError):
        return HTTPException(503, CLI_MISSING)
    if isinstance(exc, ai.AINotLoggedInError):
        return HTTPException(503, NOT_LOGGED_IN)
    return HTTPException(502, TREE_ASLEEP)

models.init_db()


async def check_environment() -> None:
    """啟動時檢查 claude CLI，有問題就在終端機講清楚。

    不中止啟動——森林、日記詳情這些讀取功能不需要 AI，環境沒設好
    也還能回來看已經種下的樹。
    """
    try:
        await ai.check_cli()
    except ai.AICliMissingError:
        hint = "找不到 claude 指令，請先安裝 Claude Code：https://claude.com/claude-code"
    except ai.AINotLoggedInError:
        hint = "claude CLI 尚未登入，請執行：claude auth login"
    else:
        return
    print(f"\n⚠️  {hint}\n   種樹與週報會失敗，其餘功能正常。\n", file=sys.stderr)


def show_ai_profiles() -> None:
    """印出實際生效的模型檔次——調了環境變數卻沒吃到，是查不出來的那種問題。"""
    print(f"🌳 AI 檔次：{ai.profile_summary()}", file=sys.stderr)


class SPAStaticFiles(StaticFiles):
    """前端走 history mode，/forest 這類路徑沒有對應檔案，一律回 index.html。"""

    async def get_response(self, path: str, scope):
        try:
            return await super().get_response(path, scope)
        except StarletteHTTPException as exc:
            if exc.status_code == 404:
                return await super().get_response("index.html", scope)
            raise


def check_frontend() -> None:
    """沒有 build 產物時提醒，但不影響 vite dev server 的開發流程。"""
    if not FRONTEND_DIST.is_dir():
        print(
            "\nℹ️  找不到 frontend/dist，此位址只提供 API。\n"
            "   要單一位址啟動請先在 frontend/ 執行：npm install && npm run build\n"
            "   （開發時用 vite dev server 則可忽略）\n",
            file=sys.stderr,
        )


@contextlib.asynccontextmanager
async def lifespan(app: FastAPI):
    await check_environment()
    show_ai_profiles()
    check_frontend()
    yield


app = FastAPI(title="樹說 TreeSay", lifespan=lifespan)
app.mount("/photos", StaticFiles(directory=models.PHOTOS_DIR), name="photos")


def get_db():
    db = models.SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_or_create_day(db, date: datetime.date) -> Day:
    day = db.scalar(select(Day).where(Day.date == date))
    if day is None:
        # question 在這一列誕生時就蓋章存下（見 models.Day.question 的說明）
        day = Day(
            date=date, status="collecting", question=questions.question_for(date)
        )
        db.add(day)
        db.flush()
    return day


def message_dict(m: Message) -> dict:
    return {
        "id": m.id,
        "content": m.content,
        "photo_url": f"/photos/{m.photo_path}" if m.photo_path else None,
        "created_at": m.created_at.isoformat(),
    }


def can_plant_day(day: Day | None, date: datetime.date, today: datetime.date) -> bool:
    """那一天現在還能不能種樹——今天隨時可以，過去的只在補種窗口內可以。"""
    if day is None or day.status != "collecting" or not day.messages:
        return False
    return 0 <= (today - date).days <= BACKFILL_WINDOW_DAYS


def backfill_candidate(db, today: datetime.date) -> str | None:
    """窗口內留了話卻沒收尾、最早的那一天——首頁遞補種邀請用。

    一次只遞一天：多天並列就成了待辦清單。挑最早的，因為它最先過期，
    種下之後下一天自然浮上來。沒有這樣的天就回 None，首頁那行字整個不出現。
    """
    days = db.scalars(
        select(Day)
        .where(
            Day.date >= today - datetime.timedelta(days=BACKFILL_WINDOW_DAYS),
            Day.date < today,
            Day.status == "collecting",
        )
        .options(selectinload(Day.messages))
        .order_by(Day.date)
    ).all()
    for d in days:
        if d.messages:
            return d.date.isoformat()
    return None


def day_keywords(day: Day) -> list[str]:
    # 落盤之前種的樹沒有關鍵詞（NULL），當空清單——不回頭重跑 AI
    return json.loads(day.keywords_json) if day.keywords_json else []


def day_dict(day: Day | None, date: datetime.date) -> dict:
    today = models.effective_date(models.now_local())
    if day is None:
        return {
            "date": date.isoformat(),
            "status": "collecting",
            # 這一天還沒有任何記錄（連 row 都沒有），問題現算現看即可；
            # 第一則訊息進來、row 誕生時才蓋章成事實
            "question": questions.question_for(date),
            "diary": None,
            "emotion": None,
            "tree_reply": None,
            "keywords": [],
            "planted_at": None,
            "messages": [],
            "can_plant": False,
        }
    return {
        "date": day.date.isoformat(),
        "status": day.status,
        "question": day.question or questions.question_for(date),
        "diary": day.diary_text,
        "emotion": day.emotion,
        "tree_reply": day.tree_reply,
        "keywords": day_keywords(day),
        "planted_at": day.planted_at.isoformat() if day.planted_at else None,
        "messages": [message_dict(m) for m in day.messages],
        "can_plant": can_plant_day(day, date, today),
    }


# ---------- 今日 ----------


@app.get("/api/today")
def get_today(db=Depends(get_db)):
    date = models.effective_date(models.now_local())
    day = db.scalar(select(Day).where(Day.date == date))
    payload = day_dict(day, date)
    payload["backfill_candidate"] = backfill_candidate(db, date)
    return payload


@app.post("/api/messages", status_code=201)
def create_message(
    content: str = Form(""),
    photo: UploadFile | None = File(None),
    db=Depends(get_db),
):
    content = content.strip()
    if not content and photo is None:
        raise HTTPException(400, "想說什麼都可以，但訊息是空的喔")
    now = models.now_local()
    date = models.effective_date(now)
    day = get_or_create_day(db, date)
    if day.status == "planted":
        raise HTTPException(409, "今天的樹已經種下了，新的想法留到明天吧 🌱")
    if day.status == "planting":
        raise HTTPException(409, "樹正在生長中，等它一下 🌱")

    photo_path = None
    if photo is not None and photo.filename:
        day_dir = models.PHOTOS_DIR / date.isoformat()
        day_dir.mkdir(parents=True, exist_ok=True)
        suffix = Path(photo.filename).suffix.lower() or ".jpg"
        filename = f"{uuid.uuid4().hex}{suffix}"
        (day_dir / filename).write_bytes(photo.file.read())
        photo_path = f"{date.isoformat()}/{filename}"

    msg = Message(day_id=day.id, content=content, photo_path=photo_path, created_at=now)
    db.add(msg)
    db.commit()
    return message_dict(msg)


def editable_message(db, message_id: int, action: str) -> Message:
    """取出一則還能改動的訊息：樹還沒收下的話才可以再整理。

    收回與修改共用同一條界線——種下之後日記就是從這些話長出來的，
    事後改素材會讓日記對不上，錯字也是那天的一部分。
    """
    msg = db.get(Message, message_id)
    if msg is None:
        raise HTTPException(404, "找不到這則訊息")
    today = models.effective_date(models.now_local())
    if msg.day.date != today or msg.day.status != "collecting":
        raise HTTPException(409, f"只有今天還沒種樹前的訊息可以{action}")
    return msg


class MessageUpdate(BaseModel):
    content: str


@app.patch("/api/messages/{message_id}")
def update_message(message_id: int, payload: MessageUpdate, db=Depends(get_db)):
    """改一則說錯的話。只動文字，照片要換請收回重傳。

    不記錄改過的痕跡（沒有 edited_at、沒有「已編輯」標記）：這是自己的
    日記，沒有另一個人需要知道你改了什麼。時間留在說出口的那一刻。
    """
    msg = editable_message(db, message_id, "修改")
    content = payload.content.strip()
    if not content and msg.photo_path is None:
        raise HTTPException(400, "整句不想留的話，用收回就好")
    msg.content = content
    db.commit()
    return message_dict(msg)


@app.delete("/api/messages/{message_id}")
def delete_message(message_id: int, db=Depends(get_db)):
    msg = editable_message(db, message_id, "收回")
    if msg.photo_path:
        (models.PHOTOS_DIR / msg.photo_path).unlink(missing_ok=True)
    db.delete(msg)
    db.commit()
    return {"ok": True}


async def plant_day(db, date: datetime.date, now: datetime.datetime) -> dict:
    """種下某一天的樹。date 是今天就是正常結算，是過去某天就是補種。"""
    days_ago = (models.effective_date(now) - date).days
    if days_ago < 0:
        raise HTTPException(400, "那一天還沒到呢")
    if days_ago > BACKFILL_WINDOW_DAYS:
        raise HTTPException(409, BACKFILL_EXPIRED)
    day = db.scalar(select(Day).where(Day.date == date))
    if day is None or not day.messages:
        raise HTTPException(
            400, "今天還沒有想法丟給樹" if days_ago == 0 else "這一天沒有留下什麼話"
        )
    if day.status == "planted":
        raise HTTPException(
            409, "今天的樹已經種下了 🌳" if days_ago == 0 else "這一天的樹已經種下了 🌳"
        )
    if day.status == "planting":
        started = day.planting_started_at
        if started and (now - started).total_seconds() < PLANTING_STALE_SECONDS:
            raise HTTPException(409, "樹正在生長中 🌱")

    day.status = "planting"
    day.planting_started_at = now
    db.commit()

    msgs = [
        {
            "time": m.created_at.strftime("%H:%M"),
            "content": m.content,
            "photo_path": str(models.PHOTOS_DIR / m.photo_path) if m.photo_path else None,
        }
        for m in day.messages
    ]
    has_photo = any(m["photo_path"] for m in msgs)

    # 樹帶著記憶傾聽：長期記得的事＋目標日往前幾天的日記。
    # 補種時近期日記以「那一天」為基準往回看，不然樹會引用未來。
    all_memories = db.scalars(select(Memory).order_by(Memory.id)).all()
    recent_days = db.scalars(
        select(Day)
        .where(
            Day.status == "planted",
            Day.date < date,
            Day.date >= date - datetime.timedelta(days=RECENT_DIARY_DAYS),
        )
        .order_by(Day.date.desc())
        .limit(RECENT_DIARY_LIMIT)
    ).all()
    recent_diaries = [
        {
            "date": d.date.isoformat(),
            "emotion": d.emotion or models.DEFAULT_EMOTION,
            "diary": (d.diary_text or "")[:RECENT_DIARY_CHARS],
        }
        for d in reversed(recent_days)
    ]

    prompt = prompts.plant_prompt(
        msgs,
        # 用蓋章存下的那題，不重算——題庫變動後重算會對不上當天真正問過的
        question=day.question or questions.question_for(date),
        days_ago=days_ago,
        memories=[m.content for m in all_memories[-MEMORY_PROMPT_LIMIT:]],
        recent_diaries=recent_diaries,
    )
    try:
        data = await ai.ask(
            prompt, allowed_tools=["Read"] if has_photo else None, profile="plant"
        )
    except ai.AIError as e:
        day.status = "collecting"
        day.planting_started_at = None
        db.commit()
        raise ai_http_error(e)

    diary = str(data.get("diary") or "").strip()
    tree_reply = str(data.get("tree_reply") or "").strip()
    if not diary or not tree_reply:
        day.status = "collecting"
        day.planting_started_at = None
        db.commit()
        raise HTTPException(502, TREE_ASLEEP)

    day.diary_text = diary
    day.emotion = ai.normalize_emotion(data.get("emotion"))
    day.tree_reply = tree_reply
    # 順手留下的回望線索：欄位壞掉就當沒有，比照 memory 不拖垮種樹
    day.keywords_json = json.dumps(
        ai.normalize_keywords(data.get("keywords"), PLANT_KEYWORD_LIMIT),
        ensure_ascii=False,
    )
    day.status = "planted"
    day.planted_at = models.now_local()

    # 順手記下值得長期記住的事（搭種樹的便車，不另外呼叫 AI）。
    # 記憶欄位壞掉就當沒有——不能因為記憶失敗讓種樹跟著失敗。
    existing = {m.content for m in all_memories}
    for text in ai.normalize_memories(data.get("memory"), PLANT_MEMORY_LIMIT):
        if text not in existing:
            db.add(Memory(content=text, source_date=date))

    db.commit()
    return day_dict(day, date)


@app.post("/api/today/plant")
async def plant_today(db=Depends(get_db)):
    now = models.now_local()
    return await plant_day(db, models.effective_date(now), now)


@app.post("/api/days/{date_str}/plant")
async def plant_past_day(date_str: str, db=Depends(get_db)):
    """補種那天忘了按的樹。窗口見 BACKFILL_WINDOW_DAYS。"""
    try:
        date = datetime.date.fromisoformat(date_str)
    except ValueError:
        raise HTTPException(400, "日期格式應為 YYYY-MM-DD")
    return await plant_day(db, date, models.now_local())


# ---------- 森林 ----------


@app.get("/api/days")
def list_days(month: str, db=Depends(get_db)):
    try:
        year, mon = month.split("-")
        start = datetime.date(int(year), int(mon), 1)
    except ValueError:
        raise HTTPException(400, "month 格式應為 YYYY-MM")
    end = (start.replace(day=28) + datetime.timedelta(days=4)).replace(day=1)
    days = db.scalars(
        select(Day)
        .where(Day.date >= start, Day.date < end)
        .options(selectinload(Day.messages))
        .order_by(Day.date)
    ).all()
    # 種下的樹，加上留了話卻沒收尾的日子。後者只是給一個回得去的入口——
    # 讓人看得到自己說過什麼，不是在月曆上標記「未完成」。
    return [
        {"date": d.date.isoformat(), "emotion": d.emotion, "status": d.status}
        for d in days
        if d.status == "planted" or d.messages
    ]


@app.get("/api/days/{date_str}")
def get_day(date_str: str, db=Depends(get_db)):
    try:
        date = datetime.date.fromisoformat(date_str)
    except ValueError:
        raise HTTPException(400, "日期格式應為 YYYY-MM-DD")
    day = db.scalar(select(Day).where(Day.date == date))
    if day is None:
        raise HTTPException(404, "這一天還是安靜的土壤")
    return day_dict(day, date)


# ---------- 樹記得的事 ----------


def memory_dict(m: Memory) -> dict:
    return {
        "id": m.id,
        "content": m.content,
        "source_date": m.source_date.isoformat(),
        "created_at": m.created_at.isoformat(),
    }


@app.get("/api/memories")
def list_memories(db=Depends(get_db)):
    """樹記得的事，最新記下的在前。看不見的記憶比沒有記憶更可怕。"""
    rows = db.scalars(select(Memory).order_by(Memory.id.desc())).all()
    return [memory_dict(m) for m in rows]


@app.delete("/api/memories/{memory_id}")
def delete_memory(memory_id: int, db=Depends(get_db)):
    """讓樹放下一件事。刪了就是刪了——之後的種樹與週報都不會再看到它。"""
    m = db.get(Memory, memory_id)
    if m is None:
        raise HTTPException(404, "樹已經放下這件事了")
    db.delete(m)
    db.commit()
    return {"ok": True}


# ---------- 週報 ----------


@app.get("/api/reports/{week_start}")
async def get_report(week_start: str, db=Depends(get_db)):
    try:
        ws = datetime.date.fromisoformat(week_start)
    except ValueError:
        raise HTTPException(400, "日期格式應為 YYYY-MM-DD")
    if ws.weekday() != 0:
        raise HTTPException(400, "week_start 應為週一")

    week_end = ws + datetime.timedelta(days=6)
    today = models.effective_date(models.now_local())
    if week_end >= today:
        return {"status": "growing", "message": "這週還在生長中 🌱"}

    cached = db.scalar(select(WeeklyReport).where(WeeklyReport.week_start == ws))
    if cached:
        return {
            "status": "ready",
            "report": json.loads(cached.content_json),
            "generated_at": cached.created_at.isoformat(),
        }

    days = db.scalars(
        select(Day)
        .where(Day.date >= ws, Day.date <= week_end, Day.status == "planted")
        .order_by(Day.date)
    ).all()
    if not days:
        return {"status": "empty", "message": "這一週是安靜的土壤"}

    diaries = [
        {
            "date": d.date.isoformat(),
            "emotion": d.emotion or models.DEFAULT_EMOTION,
            "diary": d.diary_text or "",
        }
        for d in days
    ]
    mems = db.scalars(select(Memory).order_by(Memory.id)).all()
    try:
        data = await ai.ask(
            prompts.weekly_prompt(diaries, memories=[m.content for m in mems]),
            profile="weekly",
        )
    except ai.AIError as e:
        raise ai_http_error(e)

    report = {
        "good_things": [str(x) for x in (data.get("good_things") or [])],
        "bad_things": [str(x) for x in (data.get("bad_things") or [])],
        "keywords": [str(x) for x in (data.get("keywords") or [])],
        "advice": str(data.get("advice") or ""),
    }

    # 記憶整理：AI 只拿得到現有清單，所以使用者刪掉的事不會復活。
    # 回空清單視為可疑——樹不該在一次週報裡忘掉所有事——不套用。
    if mems:
        curated = ai.normalize_memories(data.get("memories"), MEMORY_KEEP_LIMIT)
        if curated:
            today_date = models.effective_date(models.now_local())
            existing = {m.content for m in mems}
            for text in curated:
                if text not in existing:
                    db.add(Memory(content=text, source_date=today_date))
            keep = set(curated)
            for m in mems:
                if m.content not in keep:
                    db.delete(m)
    row = WeeklyReport(
        week_start=ws,
        content_json=json.dumps(report, ensure_ascii=False),
        created_at=models.now_local(),
    )
    db.add(row)
    db.commit()
    return {
        "status": "ready",
        "report": report,
        "generated_at": row.created_at.isoformat(),
    }


# ---------- 匯出 ----------


@app.get("/api/export")
def export_all(db=Depends(get_db)):
    """完整匯出日記資料，供備份與遷移。

    日記是不可再生的資料，不該只存在一個 SQLite 檔裡。相片本身不含
    在這份 JSON（只記檔名），完整備份請連同資料目錄的 photos/ 一起。
    """
    days = db.scalars(select(Day).order_by(Day.date)).all()
    reports = db.scalars(select(WeeklyReport).order_by(WeeklyReport.week_start)).all()
    payload = {
        "exported_at": models.now_local().isoformat(),
        "data_dir": str(models.DATA_DIR),
        "days": [
            {
                "date": d.date.isoformat(),
                "status": d.status,
                "question": d.question,
                "diary": d.diary_text,
                "emotion": d.emotion,
                "tree_reply": d.tree_reply,
                "keywords": day_keywords(d),
                "planted_at": d.planted_at.isoformat() if d.planted_at else None,
                "messages": [
                    {
                        "content": m.content,
                        "photo": m.photo_path,
                        "created_at": m.created_at.isoformat(),
                    }
                    for m in d.messages
                ],
            }
            for d in days
        ],
        "weekly_reports": [
            {
                "week_start": w.week_start.isoformat(),
                "report": json.loads(w.content_json),
                "created_at": w.created_at.isoformat(),
            }
            for w in reports
        ],
        "memories": [
            memory_dict(m)
            for m in db.scalars(select(Memory).order_by(Memory.id)).all()
        ],
    }
    stamp = models.now_local().strftime("%Y%m%d")
    return Response(
        json.dumps(payload, ensure_ascii=False, indent=2),
        media_type="application/json",
        headers={
            "Content-Disposition": f'attachment; filename="treesay-{stamp}.json"'
        },
    )


@app.api_route("/api/{path:path}", methods=["GET", "POST", "PUT", "PATCH", "DELETE"])
def api_not_found(path: str):
    """擋在 SPA fallback 前面，避免打錯的 API 路徑拿到 200 HTML。"""
    raise HTTPException(404, f"沒有這個 API：/api/{path}")


# 掛在 "/" 會攔截所有路徑，必須放在全部 API 路由之後
if FRONTEND_DIST.is_dir():
    app.mount("/", SPAStaticFiles(directory=FRONTEND_DIST, html=True), name="frontend")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
