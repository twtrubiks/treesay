#!/usr/bin/env python3
"""用網頁版真的後端，驗證手機與電腦版的匯出檔互相讀得懂。

匯出 JSON 是同步做出來前唯一的手動同步：手機匯出、電腦匯入，反過來也一樣。
兩邊各自的單元測試只看得到自己的格式，這支把兩邊接起來。
需要網頁版的虛擬環境（pip install -r backend/requirements.txt），在 repo 根目錄執行：

  python android/scripts/web-roundtrip.py fixture
      網頁版真的走一輪（丟話、附照片、改字、收回、種樹、補種、跨過凌晨 4:00、週報、放下記憶），
      把 /api/export 寫成 android/app/src/test/resources/web-export/export.json，
      ExportImportTest 拿它匯進手機。網頁版的匯出格式改了就重跑
  python android/scripts/web-roundtrip.py check <手機匯出的 JSON>
      匯進一個空的網頁版：每一天、每一則話、週報、記憶都要進來，網頁版的每一頁都讀得出來，
      網頁版再匯出一次，內容要跟手機的一樣。手機的檔案由 ExportImportTest 寫在
      android/app/build/web-check/android-export.json，CI 跑完單元測試後檢查
"""

import datetime
import io
import json
import os
import sys
import tempfile
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "android/app/src/test/resources/web-export/export.json"

# 資料落點在 import models 時就決定，先指到暫存目錄，不能碰到本機真的日記
os.environ["TREESAY_DATA_DIR"] = tempfile.mkdtemp(prefix="treesay-roundtrip-")
os.environ["TREESAY_ANTHROPIC_API_KEY"] = "sk-ant-roundtrip-not-used"
sys.path.insert(0, str(ROOT / "backend"))

from fastapi.testclient import TestClient  # noqa: E402
from PIL import Image  # noqa: E402

import ai  # noqa: E402
import main  # noqa: E402
import models  # noqa: E402


class Clock:
    def __init__(self):
        self.now = datetime.datetime(2026, 7, 13, 8, 0)

    def __call__(self):
        return self.now

    def at(self, text: str):
        self.now = datetime.datetime.fromisoformat(text)


clock = Clock()
models.now_local = clock
replies: list[dict] = []


async def fake_create(prompt, schema, images, profile):
    """不打 API：照順序回預錄的 JSON。"""
    if not replies:
        raise AssertionError("這一步不該呼叫 Claude")
    text = json.dumps(replies.pop(0), ensure_ascii=False)
    return SimpleNamespace(stop_reason="end_turn", content=[SimpleNamespace(type="text", text=text)])


ai._create = fake_create
client = TestClient(main.app)


def ok(response, status=200):
    if response.status_code != status:
        raise SystemExit(f"{response.request.method} {response.request.url} → {response.status_code} {response.text}")
    return response.json()


def say(text: str, photo: bytes | None = None) -> dict:
    files = {"photo": ("sky.jpg", photo, "image/jpeg")} if photo else None
    return ok(client.post("/api/messages", data={"content": text}, files=files), 201)


def jpeg() -> bytes:
    buf = io.BytesIO()
    Image.new("RGB", (64, 48), (140, 178, 217)).save(buf, "JPEG")
    return buf.getvalue()


def fixture():
    # 週一：附一張照片、改一句、收回一句，晚上種下
    clock.at("2026-07-13T08:10:00")
    say("早上去河堤跑了一圈", jpeg())
    typo = say("中午吃了牛肉麵，有點鹹")
    ok(client.patch(f"/api/messages/{typo['id']}", json={"content": "中午吃了牛肉麵，湯有點鹹"}))
    clock.at("2026-07-13T19:30:00")
    gone = say("這句說錯了")
    ok(client.delete(f"/api/messages/{gone['id']}"))
    replies.append({
        "diary": "早上在河堤跑了一圈，中午的牛肉麵湯有點鹹。",
        "emotion": "calm",
        "tree_reply": "跑完步的早晨和一碗麵，平平穩穩的一天。",
        "memory": ["週末喜歡去河堤跑步"],
        "keywords": ["河堤", "牛肉麵"],
    })
    clock.at("2026-07-13T22:00:00")
    ok(client.post("/api/today/plant"))

    # 週二：熬夜到凌晨，凌晨 1:30 說的話還算週二；忘了種
    clock.at("2026-07-14T21:00:00")
    say("在改簡報")
    clock.at("2026-07-15T01:30:00")
    say("終於改完了，好睏")

    # 週三說了話，週四才補種（days_ago = 1）
    clock.at("2026-07-15T12:00:00")
    say("下午去看了牙醫，有點緊張")
    clock.at("2026-07-16T20:00:00")
    replies.append({
        "diary": "去看了牙醫，坐在候診室時有點緊張。",
        "emotion": "anxious",
        "tree_reply": "隔了一天才回來也沒關係，昨天緊張著去看牙醫的你很勇敢。",
        "memory": ["最近在準備搬家"],
        "keywords": ["牙醫"],
    })
    ok(client.post("/api/days/2026-07-15/plant"))

    # 下週一整理上週的週報，記憶也在這時候瘦身；之後放下一條
    clock.at("2026-07-20T09:00:00")
    replies.append({
        "good_things": ["在河堤跑了一圈", "簡報改完了"],
        "bad_things": ["看牙醫有點緊張"],
        "keywords": ["河堤", "牙醫"],
        "advice": "下週也照自己的步調來。",
        "memories": ["週末喜歡去河堤跑步", "最近在準備搬家", "簡報常常改到很晚"],
    })
    assert ok(client.get("/api/reports/2026-07-13"))["status"] == "ready"
    memories = ok(client.get("/api/memories"))
    late = next(m for m in memories if m["content"] == "簡報常常改到很晚")
    ok(client.delete(f"/api/memories/{late['id']}"))

    clock.at("2026-07-20T21:00:00")
    say("今天還沒種的一天")

    data = ok(client.get("/api/export"))
    data["data_dir"] = "~/.treesay"
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    FIXTURE.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"寫好了 {FIXTURE.relative_to(ROOT)}：{len(data['days'])} 天、"
          f"{len(data['weekly_reports'])} 份週報、{len(data['memories'])} 條記憶")


def wall_clock(text: str | None) -> str | None:
    """網頁版存當地時間、不帶時差；手機帶時差。比的是說話當地牆上的時鐘。"""
    if text is None:
        return None
    return datetime.datetime.fromisoformat(text).replace(tzinfo=None).isoformat()


def check(path: str):
    phone = json.loads(Path(path).read_text(encoding="utf-8"))
    problems: list[str] = []
    # 網頁版的時鐘撥到手機匯出的那一刻：週報頁才會把那幾週當成已經結束
    clock.at(wall_clock(phone["exported_at"]))

    def expect(what: str, got, want):
        if got != want:
            problems.append(f"{what}：網頁版是 {got!r}，手機是 {want!r}")

    with open(path, "rb") as f:
        result = ok(client.post("/api/import", files={"file": ("treesay.json", f, "application/json")}))
    expect("加進的天數", result["days_added"], len(phone["days"]))
    expect("加進的週報", result["reports_added"], len(phone["weekly_reports"]))
    expect("加進的記憶", result["memories_added"], len(phone["memories"]))
    photos = sum(1 for d in phone["days"] for m in d["messages"] if m["photo"])
    expect("少掉的照片", result["photos_missing"], photos)

    web = ok(client.get("/api/export"))
    by_date = {d["date"]: d for d in web["days"]}
    for day in phone["days"]:
        got = by_date.get(day["date"])
        if got is None:
            problems.append(f"{day['date']} 沒有進來")
            continue
        for key in ("status", "question", "diary", "emotion", "tree_reply", "keywords"):
            expect(f"{day['date']} 的 {key}", got[key], day[key])
        expect(f"{day['date']} 的 planted_at", wall_clock(got["planted_at"]), wall_clock(day["planted_at"]))
        expect(
            f"{day['date']} 的話",
            [(m["content"], m["photo"], wall_clock(m["created_at"])) for m in got["messages"]],
            [(m["content"], m["photo"], wall_clock(m["created_at"])) for m in day["messages"]],
        )
        # 網頁版的詳情頁讀得出來（時間帶著時差進資料庫，讀的時候不能出錯）
        detail = ok(client.get(f"/api/days/{day['date']}"))
        expect(f"{day['date']} 詳情頁的話", len(detail["messages"]), len(day["messages"]))

    for month in sorted({d["date"][:7] for d in phone["days"]}):
        forest = ok(client.get("/api/days", params={"month": month}))
        expect(f"{month} 森林裡的天數", len(forest), sum(1 for d in phone["days"] if d["date"].startswith(month)))

    expect(
        "週報",
        [(w["week_start"], w["report"]) for w in web["weekly_reports"]],
        [(w["week_start"], w["report"]) for w in phone["weekly_reports"]],
    )
    for w in phone["weekly_reports"]:
        # 匯進來的週報是快取：不該再呼叫 Claude
        report = ok(client.get(f"/api/reports/{w['week_start']}"))
        expect(f"{w['week_start']} 的週報", report.get("report"), w["report"])

    expect(
        "記憶",
        [(m["content"], m["source_date"]) for m in web["memories"]],
        [(m["content"], m["source_date"]) for m in phone["memories"]],
    )
    ok(client.get("/api/memories"))

    if problems:
        print("手機的匯出檔匯進網頁版之後對不上：", *problems, sep="\n  ")
        raise SystemExit(1)
    print(f"手機的匯出檔匯進網頁版沒問題：{len(phone['days'])} 天、"
          f"{len(phone['weekly_reports'])} 份週報、{len(phone['memories'])} 條記憶")


if __name__ == "__main__":
    if sys.argv[1:] == ["fixture"]:
        fixture()
    elif len(sys.argv) == 3 and sys.argv[1] == "check":
        check(sys.argv[2])
    else:
        raise SystemExit(__doc__)
