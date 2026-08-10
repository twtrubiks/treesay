"""API 整合測試：pytest ＋ mock subprocess（ai._run_claude），不真呼叫 claude。"""

import datetime
import json

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

import ai
import models


class FakeClock:
    def __init__(self):
        self.now = datetime.datetime(2026, 7, 24, 12, 0)

    def __call__(self):
        return self.now


@pytest.fixture()
def clock(monkeypatch):
    c = FakeClock()
    monkeypatch.setattr(models, "now_local", c)
    return c


@pytest.fixture()
def db_factory(tmp_path, monkeypatch):
    engine = create_engine(
        f"sqlite:///{tmp_path / 'test.db'}", connect_args={"check_same_thread": False}
    )
    models.Base.metadata.create_all(engine)
    factory = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)
    monkeypatch.setattr(models, "SessionLocal", factory)
    photos = tmp_path / "photos"
    photos.mkdir()
    monkeypatch.setattr(models, "PHOTOS_DIR", photos)
    return factory


@pytest.fixture()
def client(db_factory, clock):
    import main

    return TestClient(main.app)


@pytest.fixture()
def mock_ai(monkeypatch):
    """把 ai._run_claude 換成回傳預錄 envelope 的假 subprocess。"""
    state = {"payloads": [], "calls": []}

    def install(*payloads):
        state["payloads"] = list(payloads)

    async def fake_run(prompt, allowed_tools=None, profile="plant"):
        state["calls"].append({"prompt": prompt, "allowed_tools": allowed_tools})
        idx = min(len(state["calls"]) - 1, len(state["payloads"]) - 1)
        payload = state["payloads"][idx]
        return json.dumps(
            {
                "type": "result",
                "subtype": "success",
                "result": json.dumps(payload, ensure_ascii=False),
            }
        )

    monkeypatch.setattr(ai, "_run_claude", fake_run)
    install.calls = state["calls"]
    return install


PLANT_RESULT = {
    "diary": "今天有點累，但撐過來了。",
    "emotion": "tired",
    "tree_reply": "今天真的辛苦了，慢慢來就好。",
}


class TestMessageToPlant:
    def test_full_flow(self, client, clock, mock_ai):
        r = client.post("/api/messages", data={"content": "今天好累"})
        assert r.status_code == 201
        r = client.post("/api/messages", data={"content": "但晚餐很好吃"})
        assert r.status_code == 201

        mock_ai(PLANT_RESULT)
        r = client.post("/api/today/plant")
        assert r.status_code == 200
        body = r.json()
        assert body["status"] == "planted"
        assert body["diary"] == PLANT_RESULT["diary"]
        assert body["emotion"] == "tired"

        # 日記入庫：從單日詳情讀得到
        r = client.get("/api/days/2026-07-24")
        assert r.json()["diary"] == PLANT_RESULT["diary"]

        # prompt 內含兩則訊息
        assert "今天好累" in mock_ai.calls[0]["prompt"]
        assert "晚餐很好吃" in mock_ai.calls[0]["prompt"]

        # 種完轉唯讀
        r = client.post("/api/messages", data={"content": "補一句"})
        assert r.status_code == 409

    def test_plant_without_messages(self, client, mock_ai):
        assert client.post("/api/today/plant").status_code == 400

    def test_out_of_enum_emotion_falls_back(self, client, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai({**PLANT_RESULT, "emotion": "joyful"})
        r = client.post("/api/today/plant")
        assert r.json()["emotion"] == "calm"

    def test_ai_failure_returns_502_and_unlocks(self, client, clock, monkeypatch):
        client.post("/api/messages", data={"content": "嗨"})

        async def broken(prompt, allowed_tools=None, profile="plant"):
            return "not json"

        monkeypatch.setattr(ai, "_run_claude", broken)
        r = client.post("/api/today/plant")
        assert r.status_code == 502
        assert "樹睡著了" in r.json()["detail"]
        # 失敗後回到 collecting，可以再丟訊息
        assert client.post("/api/messages", data={"content": "再試"}).status_code == 201

    def test_not_logged_in_returns_503_with_actionable_hint(
        self, client, clock, monkeypatch
    ):
        """環境沒設好時重按也不會好，要講明該修什麼而非「樹睡著了」。"""
        client.post("/api/messages", data={"content": "嗨"})

        async def broken(prompt, allowed_tools=None, profile="plant"):
            return "not json"

        async def logged_out():
            return {"loggedIn": False}

        monkeypatch.setattr(ai, "_run_claude", broken)
        monkeypatch.setattr(ai, "_auth_status", logged_out)
        r = client.post("/api/today/plant")
        assert r.status_code == 503
        assert "claude auth login" in r.json()["detail"]
        assert client.post("/api/messages", data={"content": "再試"}).status_code == 201

    def test_cli_missing_returns_503(self, client, clock, monkeypatch):
        client.post("/api/messages", data={"content": "嗨"})

        async def no_such_binary(*args, **kwargs):
            raise FileNotFoundError("claude")

        monkeypatch.setattr(ai.asyncio, "create_subprocess_exec", no_such_binary)
        r = client.post("/api/today/plant")
        assert r.status_code == 503
        assert "claude" in r.json()["detail"]


class TestDayCutover:
    def test_before_4am_belongs_to_previous_day(self, client, clock):
        clock.now = datetime.datetime(2026, 7, 25, 3, 30)
        r = client.post("/api/messages", data={"content": "睡不著"})
        assert r.status_code == 201
        assert client.get("/api/today").json()["date"] == "2026-07-24"
        day = client.get("/api/days/2026-07-24").json()
        assert day["messages"][0]["content"] == "睡不著"

    def test_at_4am_belongs_to_same_day(self, client, clock):
        clock.now = datetime.datetime(2026, 7, 25, 4, 0)
        client.post("/api/messages", data={"content": "早"})
        assert client.get("/api/today").json()["date"] == "2026-07-25"


class TestPlantLock:
    def test_duplicate_plant_blocked_while_planting(self, client, clock, db_factory, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        with db_factory() as db:
            day = db.query(models.Day).one()
            day.status = "planting"
            day.planting_started_at = clock.now
            db.commit()
        assert client.post("/api/today/plant").status_code == 409

    def test_stale_planting_can_replant(self, client, clock, db_factory, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        with db_factory() as db:
            day = db.query(models.Day).one()
            day.status = "planting"
            day.planting_started_at = clock.now - datetime.timedelta(seconds=320)
            db.commit()
        mock_ai(PLANT_RESULT)
        assert client.post("/api/today/plant").status_code == 200


class TestBackfill:
    """當天忘了按種樹：那些話不該就這樣沉掉，但也不能永遠補得回來。"""

    def _forget_to_plant(self, client, clock, days: int):
        client.post("/api/messages", data={"content": "今天什麼都沒做完"})
        clock.now += datetime.timedelta(days=days)

    def test_yesterday_can_be_planted(self, client, clock, mock_ai):
        self._forget_to_plant(client, clock, 1)
        mock_ai(PLANT_RESULT)
        r = client.post("/api/days/2026-07-24/plant")
        assert r.status_code == 200
        assert r.json()["status"] == "planted"
        # 日記歸在原本那一天，不是想起來的那天
        assert r.json()["date"] == "2026-07-24"
        assert client.get("/api/days/2026-07-25").status_code == 404

    def test_backfill_prompt_knows_it_is_late(self, client, clock, mock_ai):
        """隔了一天還說「今天辛苦了」會很假，AI 得知道時間差。"""
        self._forget_to_plant(client, clock, 1)
        mock_ai(PLANT_RESULT)
        client.post("/api/days/2026-07-24/plant")
        prompt = mock_ai.calls[0]["prompt"]
        assert "以下是使用者昨天丟給你的訊息" in prompt
        assert "現在才回來種下這棵樹" in prompt

    def test_same_day_prompt_unchanged(self, client, clock, mock_ai):
        """當天種樹不該被補種的措辭污染。"""
        client.post("/api/messages", data={"content": "今天好累"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        prompt = mock_ai.calls[0]["prompt"]
        assert "以下是使用者今天丟給你的訊息" in prompt
        assert "現在才回來" not in prompt

    def test_expired_day_is_left_alone(self, client, clock, mock_ai):
        self._forget_to_plant(client, clock, 3)
        mock_ai(PLANT_RESULT)
        r = client.post("/api/days/2026-07-24/plant")
        assert r.status_code == 409
        assert "走遠" in r.json()["detail"]
        assert not mock_ai.calls, "過期的天不該還去燒額度"

    def test_can_plant_marks_the_window(self, client, clock):
        self._forget_to_plant(client, clock, 0)
        assert client.get("/api/days/2026-07-24").json()["can_plant"] is True
        clock.now += datetime.timedelta(days=2)  # 前天，還在窗口內
        assert client.get("/api/days/2026-07-24").json()["can_plant"] is True
        clock.now += datetime.timedelta(days=1)  # 走遠了
        assert client.get("/api/days/2026-07-24").json()["can_plant"] is False

    def test_day_without_messages_is_not_plantable(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "留一句"})
        clock.now += datetime.timedelta(days=1)
        # 昨天有訊息、前天沒有
        assert client.post("/api/days/2026-07-23/plant").status_code == 400
        assert not mock_ai.calls

    def test_future_day_rejected(self, client, clock):
        assert client.post("/api/days/2026-07-25/plant").status_code == 400

    def test_already_planted_day_rejected(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        clock.now += datetime.timedelta(days=1)
        assert client.post("/api/days/2026-07-24/plant").status_code == 409

    def test_window_stays_shorter_than_a_week(self):
        """週報只生成已結束的週且永久快取。窗口一旦跨週，就會出現
        「森林上有那棵樹、週報裡沒有那一天」的不一致。"""
        import main

        assert main.BACKFILL_WINDOW_DAYS < 7


class TestBackfillInvite:
    """首頁的補種邀請：前一兩天留了話沒收尾，遞一個安靜的入口，不自動種。"""

    def test_no_candidate_on_a_quiet_start(self, client, clock):
        assert client.get("/api/today").json()["backfill_candidate"] is None

    def test_today_itself_is_not_a_candidate(self, client, clock):
        client.post("/api/messages", data={"content": "今天的話"})
        assert client.get("/api/today").json()["backfill_candidate"] is None

    def test_yesterday_with_messages_is_offered(self, client, clock):
        client.post("/api/messages", data={"content": "留了話就睡了"})
        clock.now += datetime.timedelta(days=1)
        assert client.get("/api/today").json()["backfill_candidate"] == "2026-07-24"

    def test_oldest_first_so_nothing_expires_unseen(self, client, clock):
        """前天、昨天都沒收尾：一次只遞一天（多天並列就成了待辦清單），
        先遞快過期的那天，種完之後下一天自然浮上來。"""
        client.post("/api/messages", data={"content": "前天的話"})
        clock.now += datetime.timedelta(days=1)
        client.post("/api/messages", data={"content": "昨天的話"})
        clock.now += datetime.timedelta(days=1)
        assert client.get("/api/today").json()["backfill_candidate"] == "2026-07-24"

    def test_expired_day_is_not_offered(self, client, clock):
        """走遠的日子連邀請都不遞——邀請一個補不回來的天只是折磨。"""
        client.post("/api/messages", data={"content": "走遠了"})
        clock.now += datetime.timedelta(days=3)
        assert client.get("/api/today").json()["backfill_candidate"] is None

    def test_planted_day_is_not_offered(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "有收尾"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        clock.now += datetime.timedelta(days=1)
        assert client.get("/api/today").json()["backfill_candidate"] is None

    def test_day_with_only_retracted_messages_is_not_offered(self, client, clock):
        mid = client.post("/api/messages", data={"content": "算了"}).json()["id"]
        client.delete(f"/api/messages/{mid}")
        clock.now += datetime.timedelta(days=1)
        assert client.get("/api/today").json()["backfill_candidate"] is None


class TestForest:
    def test_planted_and_unfinished_days_both_listed(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "留一句就睡了"})  # 07-24 沒收尾
        clock.now += datetime.timedelta(days=1)
        client.post("/api/messages", data={"content": "今天有種樹"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")  # 07-25 種了

        by_date = {d["date"]: d for d in client.get("/api/days?month=2026-07").json()}
        assert by_date["2026-07-24"]["status"] == "collecting"
        assert by_date["2026-07-24"]["emotion"] is None
        assert by_date["2026-07-25"]["status"] == "planted"
        assert by_date["2026-07-25"]["emotion"] == "tired"

    def test_untouched_days_stay_out(self, client, clock):
        """沒留下任何話的日子不該進森林——那才是真正安靜的土壤。"""
        # 送一句再收回：Day 存在但沒有訊息
        mid = client.post("/api/messages", data={"content": "算了"}).json()["id"]
        client.delete(f"/api/messages/{mid}")
        assert client.get("/api/days?month=2026-07").json() == []

    def test_bad_month_format_rejected(self, client):
        assert client.get("/api/days?month=2026").status_code == 400


class TestMessages:
    def test_delete_only_when_collecting(self, client, clock, mock_ai):
        mid = client.post("/api/messages", data={"content": "刪我"}).json()["id"]
        assert client.delete(f"/api/messages/{mid}").status_code == 200

        mid = client.post("/api/messages", data={"content": "留著"}).json()["id"]
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        assert client.delete(f"/api/messages/{mid}").status_code == 409

    def test_edit_keeps_the_original_time(self, client, clock):
        """打錯字改回來——時間留在說出口的那一刻，不因為改過就跳到現在。"""
        msg = client.post("/api/messages", data={"content": "今天好類"}).json()
        clock.now += datetime.timedelta(hours=1)
        edited = client.patch(f"/api/messages/{msg['id']}", json={"content": "今天好累"})
        assert edited.status_code == 200
        assert edited.json()["content"] == "今天好累"
        assert edited.json()["created_at"] == msg["created_at"]
        assert client.get("/api/today").json()["messages"][0]["content"] == "今天好累"

    def test_edit_rejected_after_planting(self, client, clock, mock_ai):
        """種下之後日記就是從這些話長出來的，改素材會讓日記對不上。"""
        mid = client.post("/api/messages", data={"content": "留著"}).json()["id"]
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        assert client.patch(f"/api/messages/{mid}", json={"content": "改"}).status_code == 409

    def test_edit_rejected_on_past_day(self, client, clock):
        """昨天的話只能補種，不能再改——跟收回同一條界線。"""
        mid = client.post("/api/messages", data={"content": "昨天說的"}).json()["id"]
        clock.now += datetime.timedelta(days=1)
        assert client.patch(f"/api/messages/{mid}", json={"content": "改"}).status_code == 409

    def test_edit_to_empty_rejected(self, client, clock):
        """整句不想留的話用收回，不要留一則空泡泡在那裡。"""
        mid = client.post("/api/messages", data={"content": "說了什麼"}).json()["id"]
        assert client.patch(f"/api/messages/{mid}", json={"content": "  "}).status_code == 400

    def test_edit_missing_message(self, client):
        assert client.patch("/api/messages/999", json={"content": "改"}).status_code == 404

    def test_photo_upload_and_plant_uses_read_tool(self, client, clock, mock_ai):
        r = client.post(
            "/api/messages",
            data={"content": "看看這張"},
            files={"photo": ("cat.jpg", b"fake-jpg-bytes", "image/jpeg")},
        )
        assert r.status_code == 201
        photo_url = r.json()["photo_url"]
        assert photo_url.startswith("/photos/2026-07-24/")
        saved = models.PHOTOS_DIR / photo_url.removeprefix("/photos/")
        assert saved.read_bytes() == b"fake-jpg-bytes"

        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        assert mock_ai.calls[0]["allowed_tools"] == ["Read"]
        assert str(saved) in mock_ai.calls[0]["prompt"]


class TestMemory:
    """樹的記憶：搭種樹便車抽取、注入之後的種樹、使用者看得到也刪得掉。"""

    def test_plant_extracts_and_next_plant_remembers(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "開始做樹說這個專案"})
        mock_ai({**PLANT_RESULT, "memory": ["最近在做樹說這個專案"]})
        client.post("/api/today/plant")

        mems = client.get("/api/memories").json()
        assert [m["content"] for m in mems] == ["最近在做樹說這個專案"]
        assert mems[0]["source_date"] == "2026-07-24"

        clock.now += datetime.timedelta(days=1)
        client.post("/api/messages", data={"content": "又是一天"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")

        prompt = mock_ai.calls[1]["prompt"]
        assert "最近在做樹說這個專案" in prompt
        # 沒有這條界線，記憶就會從「更懂你」變成「查你的帳」
        assert "不追蹤進度、不對帳" in prompt
        # 昨天的日記也一起進來（近期日記）
        assert PLANT_RESULT["diary"] in prompt
        assert "2026-07-24" in prompt

    def test_first_plant_has_no_memory_block(self, client, clock, mock_ai):
        """還沒認識就不該裝熟——沒有記憶時不出現記憶區塊，但抽取照常要求。"""
        client.post("/api/messages", data={"content": "第一天"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        prompt = mock_ai.calls[0]["prompt"]
        assert "不追蹤進度" not in prompt
        assert "最近種下的日記" not in prompt
        assert '"memory"' in prompt

    def test_same_fact_not_duplicated(self, client, clock, mock_ai):
        for i in range(2):
            client.post("/api/messages", data={"content": f"第 {i} 天"})
            mock_ai({**PLANT_RESULT, "memory": ["養了一隻貓"]})
            client.post("/api/today/plant")
            clock.now += datetime.timedelta(days=1)
        assert len(client.get("/api/memories").json()) == 1

    def test_garbage_memory_field_does_not_break_plant(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai({**PLANT_RESULT, "memory": "不是清單"})
        assert client.post("/api/today/plant").status_code == 200
        assert client.get("/api/memories").json() == []

    def test_delete_memory_and_it_stays_forgotten(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai({**PLANT_RESULT, "memory": ["會被放下的事"]})
        client.post("/api/today/plant")

        mid = client.get("/api/memories").json()[0]["id"]
        assert client.delete(f"/api/memories/{mid}").status_code == 200
        assert client.get("/api/memories").json() == []
        assert client.delete(f"/api/memories/{mid}").status_code == 404

        # 放下之後，之後的種樹不再看到它
        clock.now += datetime.timedelta(days=1)
        client.post("/api/messages", data={"content": "新的一天"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        assert "會被放下的事" not in mock_ai.calls[-1]["prompt"]

    def test_old_diary_stays_out_of_recent(self, client, clock, mock_ai):
        """「近期」要對得起這兩個字——超過窗口的日記不注入。"""
        client.post("/api/messages", data={"content": "很久以前"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")

        clock.now += datetime.timedelta(days=8)
        client.post("/api/messages", data={"content": "八天後"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        assert "最近種下的日記" not in mock_ai.calls[1]["prompt"]

    def test_backfill_does_not_see_future_diaries(self, client, clock, mock_ai):
        """補種以「那一天」為基準往回看，樹不能引用未來的日記。"""
        client.post("/api/messages", data={"content": "忘了收尾的一天"})  # 07-24
        clock.now += datetime.timedelta(days=1)
        client.post("/api/messages", data={"content": "第二天"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")  # 07-25 種了

        clock.now += datetime.timedelta(days=1)
        mock_ai(PLANT_RESULT)
        assert client.post("/api/days/2026-07-24/plant").status_code == 200
        # 7/24 之前沒有任何種下的日子，區塊不該出現
        assert "最近種下的日記" not in mock_ai.calls[-1]["prompt"]


WEEKLY_RESULT = {
    "good_things": ["晚餐很好吃"],
    "bad_things": ["有點累"],
    "keywords": ["疲憊", "晚餐"],
    "advice": "記得留一點時間給自己。",
}


class TestWeeklyReport:
    def _seed_week(self, db_factory):
        with db_factory() as db:
            for d in [datetime.date(2026, 7, 14), datetime.date(2026, 7, 15)]:
                db.add(
                    models.Day(
                        date=d,
                        status="planted",
                        diary_text=f"{d} 的日記",
                        emotion="calm",
                        tree_reply="辛苦了",
                    )
                )
            db.commit()

    def test_generate_then_cache_hit(self, client, clock, db_factory, mock_ai):
        self._seed_week(db_factory)
        mock_ai(WEEKLY_RESULT)

        r = client.get("/api/reports/2026-07-13")
        assert r.status_code == 200
        assert r.json()["status"] == "ready"
        assert r.json()["report"] == WEEKLY_RESULT
        assert len(mock_ai.calls) == 1

        # 快取命中：不再呼叫 AI
        r = client.get("/api/reports/2026-07-13")
        assert r.json()["status"] == "ready"
        assert len(mock_ai.calls) == 1

    def test_current_week_is_growing(self, client, clock, mock_ai):
        r = client.get("/api/reports/2026-07-20")
        assert r.json()["status"] == "growing"
        assert len(mock_ai.calls) == 0

    def test_empty_past_week(self, client, clock, mock_ai):
        r = client.get("/api/reports/2026-07-06")
        assert r.json()["status"] == "empty"
        assert len(mock_ai.calls) == 0

    def test_week_start_must_be_monday(self, client):
        assert client.get("/api/reports/2026-07-14").status_code == 400

    def _seed_memories(self, db_factory, *contents):
        with db_factory() as db:
            for c in contents:
                db.add(
                    models.Memory(content=c, source_date=datetime.date(2026, 7, 14))
                )
            db.commit()

    def test_weekly_curates_memories(self, client, clock, db_factory, mock_ai):
        """週報是記憶唯一的瘦身時機：合併重複、放下過時，保留的不動聲色。"""
        self._seed_memories(db_factory, "在做樹說", "在做 treesay", "一次性的事")
        self._seed_week(db_factory)
        mock_ai({**WEEKLY_RESULT, "memories": ["在做樹說", "新合併的事"]})

        assert client.get("/api/reports/2026-07-13").json()["status"] == "ready"
        assert "在做 treesay" in mock_ai.calls[0]["prompt"]  # 現有記憶有進 prompt

        mems = {m["content"]: m for m in client.get("/api/memories").json()}
        assert set(mems) == {"在做樹說", "新合併的事"}
        # 保留的條目原封不動（記下的日期不變），合併出的新條目記在整理當天
        assert mems["在做樹說"]["source_date"] == "2026-07-14"
        assert mems["新合併的事"]["source_date"] == "2026-07-24"

    def test_weekly_empty_curation_keeps_memories(
        self, client, clock, db_factory, mock_ai
    ):
        """樹不該在一次週報裡忘掉所有事——回空清單視為可疑，不套用。"""
        self._seed_memories(db_factory, "重要的事")
        self._seed_week(db_factory)
        mock_ai({**WEEKLY_RESULT, "memories": []})

        assert client.get("/api/reports/2026-07-13").json()["status"] == "ready"
        assert [m["content"] for m in client.get("/api/memories").json()] == [
            "重要的事"
        ]

    def test_weekly_without_memories_skips_curation(
        self, client, clock, db_factory, mock_ai
    ):
        self._seed_week(db_factory)
        mock_ai(WEEKLY_RESULT)
        client.get("/api/reports/2026-07-13")
        assert "樹一路記著" not in mock_ai.calls[0]["prompt"]


class TestExport:
    def test_empty_export_is_valid(self, client):
        data = client.get("/api/export").json()
        assert data["days"] == []
        assert data["weekly_reports"] == []
        assert data["memories"] == []
        assert data["exported_at"]

    def test_export_contains_memories(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "開始養貓"})
        mock_ai({**PLANT_RESULT, "memory": ["養了一隻貓"]})
        client.post("/api/today/plant")

        mems = client.get("/api/export").json()["memories"]
        assert [m["content"] for m in mems] == ["養了一隻貓"]
        assert mems[0]["source_date"] == "2026-07-24"

    def test_export_contains_planted_day(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "今天還行"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")

        r = client.get("/api/export")
        assert r.status_code == 200
        assert "attachment" in r.headers["content-disposition"]
        day = r.json()["days"][0]
        assert day["date"] == "2026-07-24"
        assert day["diary"] == PLANT_RESULT["diary"]
        assert day["emotion"] == "tired"
        assert day["tree_reply"] == PLANT_RESULT["tree_reply"]
        assert [m["content"] for m in day["messages"]] == ["今天還行"]


class TestFrontendServing:
    def test_unknown_api_path_returns_404_json(self, client):
        """打錯的 API 不能被 SPA fallback 接走、回成 200 HTML。"""
        r = client.get("/api/nope")
        assert r.status_code == 404
        assert "沒有這個 API" in r.json()["detail"]

    def test_spa_route_falls_back_to_index(self, client):
        import main

        if not main.FRONTEND_DIST.is_dir():
            pytest.skip("需要先在 frontend/ 執行 npm run build")
        r = client.get("/forest")
        assert r.status_code == 200
        assert "text/html" in r.headers["content-type"]
