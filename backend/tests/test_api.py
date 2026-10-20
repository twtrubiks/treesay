"""API 整合測試：pytest ＋ mock Claude API（ai._create），不真呼叫 Claude。"""

import base64
import datetime
import io
import json
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from PIL import Image

import ai
import models
import questions


def image_bytes(fmt: str) -> bytes:
    """Pillow 解得開的小圖——送出前會先在本機解一次，只有檔頭的假檔會被略過。"""
    buf = io.BytesIO()
    Image.new("RGB", (8, 6)).save(buf, fmt)
    return buf.getvalue()


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
def client(db_factory, clock):
    import main

    return TestClient(main.app)


def api_response(text: str, stop_reason: str = "end_turn"):
    return SimpleNamespace(
        stop_reason=stop_reason, content=[SimpleNamespace(type="text", text=text)]
    )


@pytest.fixture()
def mock_ai(monkeypatch):
    """把 ai._create 換成回傳預錄 JSON 的假 API。"""
    state = {"payloads": [], "calls": []}

    def install(*payloads):
        state["payloads"] = list(payloads)

    async def fake_create(prompt, schema, images, profile):
        state["calls"].append(
            {"prompt": prompt, "schema": schema, "images": images, "profile": profile}
        )
        idx = min(len(state["calls"]) - 1, len(state["payloads"]) - 1)
        return api_response(json.dumps(state["payloads"][idx], ensure_ascii=False))

    monkeypatch.setattr(ai, "_create", fake_create)
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
        assert mock_ai.calls[0]["profile"] == "plant"

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

        async def broken(prompt, schema, images, profile):
            return api_response("拒答", stop_reason="refusal")

        monkeypatch.setattr(ai, "_create", broken)
        r = client.post("/api/today/plant")
        assert r.status_code == 502
        assert "樹睡著了" in r.json()["detail"]
        # 失敗後回到 collecting，可以再丟訊息
        assert client.post("/api/messages", data={"content": "再試"}).status_code == 201

    @pytest.mark.parametrize("field", ["diary", "tree_reply"])
    def test_blank_diary_or_reply_returns_502_and_unlocks(
        self, client, clock, mock_ai, field
    ):
        """schema 擋得住型別、擋不住空字串——種下一棵空白的樹比種不下去更糟。"""
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai({**PLANT_RESULT, field: "  "})
        r = client.post("/api/today/plant")
        assert r.status_code == 502
        assert client.get("/api/today").json()["status"] == "collecting"

    def test_invalid_key_returns_503_with_actionable_hint(
        self, client, clock, monkeypatch
    ):
        """環境沒設好時重按也不會好，要講明該修什麼而非「樹睡著了」。"""
        client.post("/api/messages", data={"content": "嗨"})

        async def rejected(prompt, schema, images, profile):
            raise ai.AIKeyInvalidError("401")

        monkeypatch.setattr(ai, "_create", rejected)
        r = client.post("/api/today/plant")
        assert r.status_code == 503
        assert ai.API_KEY_ENV in r.json()["detail"]
        assert client.post("/api/messages", data={"content": "再試"}).status_code == 201

    def test_missing_key_returns_503(self, client, clock, monkeypatch):
        client.post("/api/messages", data={"content": "嗨"})
        monkeypatch.delenv(ai.API_KEY_ENV)
        r = client.post("/api/today/plant")
        assert r.status_code == 503
        assert ai.API_KEY_ENV in r.json()["detail"]


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
    def test_lock_taken_before_calling_ai(self, client, clock, db_factory, monkeypatch):
        """AI 要回好一陣子，鎖得在呼叫之前蓋好，連按第二下才擋得住。"""
        client.post("/api/messages", data={"content": "嗨"})
        seen = {}

        async def watching(prompt, schema, images, profile):
            with db_factory() as db:
                day = db.query(models.Day).one()
                seen.update(status=day.status, started=day.planting_started_at)
            return api_response(json.dumps(PLANT_RESULT, ensure_ascii=False))

        monkeypatch.setattr(ai, "_create", watching)
        assert client.post("/api/today/plant").status_code == 200
        assert seen == {"status": "planting", "started": clock.now}

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

    def test_planted_day_is_not_offered_again(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai(PLANT_RESULT)
        assert client.post("/api/today/plant").json()["can_plant"] is False

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


class TestQuestionIsAFact:
    """那天問過什麼是那一天的事實：Day 誕生時蓋章，題庫變動不得位移歷史。"""

    def _grow_bank(self, monkeypatch):
        """模擬往題庫加一題——mod 定題的所有日期會因此位移。"""
        monkeypatch.setattr(questions, "QUESTIONS", ["新加的一題"] + questions.QUESTIONS)

    def test_stamped_question_survives_bank_growth(self, client, clock, monkeypatch):
        before = client.get("/api/today").json()["question"]
        client.post("/api/messages", data={"content": "嗨"})  # row 誕生，蓋章
        self._grow_bank(monkeypatch)
        assert client.get("/api/today").json()["question"] == before

    def test_unstamped_day_follows_current_bank(self, client, clock, monkeypatch):
        """還沒開口的日子沒有事實可言，問題現算現看。"""
        self._grow_bank(monkeypatch)
        expected = questions.question_for(datetime.date(2026, 7, 24))
        assert client.get("/api/today").json()["question"] == expected

    def test_plant_prompt_uses_stamped_question(
        self, client, clock, mock_ai, monkeypatch
    ):
        client.post("/api/messages", data={"content": "嗨"})
        stamped = client.get("/api/today").json()["question"]
        self._grow_bank(monkeypatch)
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        assert stamped in mock_ai.calls[0]["prompt"]

    def test_day_detail_and_export_carry_question(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        stamped = questions.question_for(datetime.date(2026, 7, 24))
        assert client.get("/api/days/2026-07-24").json()["question"] == stamped
        assert client.get("/api/export").json()["days"][0]["question"] == stamped


class TestKeywords:
    """種樹順手留下的回望線索：存起來備用，壞掉不拖垮種樹。"""

    def test_plant_stores_keywords(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "傍晚去散步"})
        mock_ai({**PLANT_RESULT, "keywords": ["散步", "傍晚"]})
        r = client.post("/api/today/plant")
        assert r.json()["keywords"] == ["散步", "傍晚"]
        assert client.get("/api/days/2026-07-24").json()["keywords"] == ["散步", "傍晚"]
        assert client.get("/api/export").json()["days"][0]["keywords"] == [
            "散步",
            "傍晚",
        ]

    def test_garbage_keywords_do_not_break_plant(self, client, clock, mock_ai):
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai({**PLANT_RESULT, "keywords": "不是清單"})
        r = client.post("/api/today/plant")
        assert r.status_code == 200
        assert r.json()["status"] == "planted"
        assert r.json()["keywords"] == []

    def test_missing_keywords_field_means_empty(self, client, clock, mock_ai):
        """落盤前的舊模型輸出沒有這個欄位，一樣正常種樹。"""
        client.post("/api/messages", data={"content": "嗨"})
        mock_ai(PLANT_RESULT)
        assert client.post("/api/today/plant").json()["keywords"] == []


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

    def test_month_lists_only_its_own_days(self, client, clock):
        for now in [
            datetime.datetime(2026, 6, 30, 12, 0),
            datetime.datetime(2026, 7, 1, 12, 0),
            datetime.datetime(2026, 8, 1, 12, 0),
        ]:
            clock.now = now
            client.post("/api/messages", data={"content": "留一句"})
        days = client.get("/api/days?month=2026-07").json()
        assert [d["date"] for d in days] == ["2026-07-01"]

    def test_bad_month_format_rejected(self, client):
        assert client.get("/api/days?month=2026").status_code == 400


class TestMessages:
    def test_empty_message_rejected(self, client, clock):
        assert client.post("/api/messages", data={"content": "  "}).status_code == 400
        assert client.get("/api/today").json()["messages"] == []

    def test_photo_only_message_accepted(self, client, clock):
        """拍了照不想打字也是一則訊息。"""
        r = client.post(
            "/api/messages", files={"photo": ("sky.jpg", image_bytes("JPEG"), "image/jpeg")}
        )
        assert r.status_code == 201
        assert r.json()["content"] == ""
        assert r.json()["photo_url"]

    def test_retract_removes_the_photo_file(self, client, clock):
        """收回就是不留了，照片不該還躺在磁碟上。"""
        msg = client.post(
            "/api/messages",
            data={"content": "拍錯了"},
            files={"photo": ("oops.jpg", image_bytes("JPEG"), "image/jpeg")},
        ).json()
        saved = models.PHOTOS_DIR / msg["photo_url"].removeprefix("/photos/")
        assert saved.is_file()
        assert client.delete(f"/api/messages/{msg['id']}").status_code == 200
        assert not saved.exists()

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

    def test_photo_upload_and_plant_sends_image(self, client, clock, mock_ai):
        jpeg = image_bytes("JPEG")
        r = client.post(
            "/api/messages",
            data={"content": "看看這張"},
            files={"photo": ("cat.jpg", jpeg, "image/jpeg")},
        )
        assert r.status_code == 201
        photo_url = r.json()["photo_url"]
        assert photo_url.startswith("/photos/2026-07-24/")
        saved = models.PHOTOS_DIR / photo_url.removeprefix("/photos/")
        assert saved.read_bytes() == jpeg

        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        (image,) = mock_ai.calls[0]["images"]
        assert image["source"]["data"] == base64.b64encode(jpeg).decode()
        prompt = mock_ai.calls[0]["prompt"]
        assert "看看這張（附照片 1）" in prompt
        # 照片已經直接附上，本機路徑沒有理由送出去
        assert str(models.PHOTOS_DIR) not in prompt

    def test_unsupported_photo_does_not_block_planting(self, client, clock, mock_ai):
        """樹看不懂的照片（例如 HEIC）只是略過，那天的樹照樣種得下去。"""
        client.post(
            "/api/messages",
            data={"content": "手機拍的"},
            files={"photo": ("live.heic", b"\x00\x00\x00\x18ftypheic", "image/heic")},
        )
        client.post(
            "/api/messages",
            data={"content": "這張可以"},
            files={"photo": ("ok.png", image_bytes("PNG"), "image/png")},
        )
        mock_ai(PLANT_RESULT)
        r = client.post("/api/today/plant")
        assert r.json()["status"] == "planted"

        call = mock_ai.calls[0]
        assert [i["source"]["media_type"] for i in call["images"]] == ["image/png"]
        assert "手機拍的（附了一張照片，但這次沒能帶給你看）" in call["prompt"]
        # 編號跟著實際送出的照片走，不是跟著訊息順序
        assert "這張可以（附照片 1）" in call["prompt"]


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
        assert "memory：" in prompt
        assert "memory" in mock_ai.calls[0]["schema"]["properties"]

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

    def test_recent_diaries_are_the_latest_three_planted(self, client, clock, mock_ai):
        """取最近的三篇、由舊到新排；留了話沒種的那天沒有日記可帶。"""
        for i in range(6):  # 07-24 到 07-29，07-27 那天沒收尾
            if i:
                clock.now += datetime.timedelta(days=1)
            client.post("/api/messages", data={"content": f"第 {i} 天"})
            if i != 3:
                mock_ai({**PLANT_RESULT, "diary": f"第 {i} 天的日記"})
                client.post("/api/today/plant")

        prompt = mock_ai.calls[-1]["prompt"]
        positions = [prompt.index(f"第 {i} 天的日記") for i in (1, 2, 4)]
        assert positions == sorted(positions)
        assert "第 0 天的日記" not in prompt
        assert "2026-07-27" not in prompt

    def test_memories_stay_when_recent_diaries_run_out(self, client, clock, mock_ai):
        """隔了一陣子才回來，近期日記空了，樹也不能因此忘了你。"""
        client.post("/api/messages", data={"content": "開始養貓"})
        mock_ai({**PLANT_RESULT, "memory": ["養了一隻貓"]})
        client.post("/api/today/plant")

        clock.now += datetime.timedelta(days=10)
        client.post("/api/messages", data={"content": "好久不見"})
        mock_ai(PLANT_RESULT)
        client.post("/api/today/plant")
        prompt = mock_ai.calls[-1]["prompt"]
        assert "最近種下的日記" not in prompt
        assert "養了一隻貓" in prompt
        assert "不追蹤進度、不對帳" in prompt

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
        assert mock_ai.calls[0]["profile"] == "weekly"

        # 快取命中：不再呼叫 AI，拿到的是同一份週報
        r = client.get("/api/reports/2026-07-13")
        assert r.json()["status"] == "ready"
        assert r.json()["report"] == WEEKLY_RESULT
        assert len(mock_ai.calls) == 1

    def test_prompt_carries_this_weeks_planted_diaries(
        self, client, clock, db_factory, mock_ai
    ):
        """週一到週日都算這週；隔壁週的日記、沒種下的天不混進來。"""
        with db_factory() as db:
            for d in [
                datetime.date(2026, 7, 12),  # 上週日
                datetime.date(2026, 7, 13),
                datetime.date(2026, 7, 19),
                datetime.date(2026, 7, 20),  # 下週一
            ]:
                db.add(
                    models.Day(
                        date=d,
                        status="planted",
                        diary_text=f"{d} 的日記",
                        emotion="calm",
                        tree_reply="辛苦了",
                    )
                )
            db.add(models.Day(date=datetime.date(2026, 7, 16), status="collecting"))
            db.commit()
        mock_ai(WEEKLY_RESULT)

        client.get("/api/reports/2026-07-13")
        prompt = mock_ai.calls[0]["prompt"]
        assert "2026-07-13 的日記" in prompt
        assert "2026-07-19 的日記" in prompt
        for outside in ["2026-07-12", "2026-07-16", "2026-07-20"]:
            assert outside not in prompt

    def test_current_week_is_growing(self, client, clock, mock_ai):
        r = client.get("/api/reports/2026-07-20")
        assert r.json()["status"] == "growing"
        assert len(mock_ai.calls) == 0

    def test_growing_until_its_sunday_is_over(self, client, clock, mock_ai):
        """週日還沒過完（凌晨 4:00 前也還算週日）。週報永久快取，
        早一步生成，那天就永遠不在週報裡。"""
        for now in [datetime.datetime(2026, 7, 26, 23, 0), datetime.datetime(2026, 7, 27, 3, 59)]:
            clock.now = now
            assert client.get("/api/reports/2026-07-20").json()["status"] == "growing"
        clock.now = datetime.datetime(2026, 7, 27, 4, 0)
        assert client.get("/api/reports/2026-07-20").json()["status"] == "empty"
        assert not mock_ai.calls

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
        assert "memories" in mock_ai.calls[0]["schema"]["properties"]

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
        # 沒東西可整理就不要求這個欄位——schema 必填，留著等於逼 AI 生記憶出來
        assert "memories" not in mock_ai.calls[0]["schema"]["properties"]


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

    def test_export_contains_unplanted_day(self, client, clock):
        """留了話還沒種是最常見的狀態，備份不能偏偏在這裡斷掉。"""
        client.post("/api/messages", data={"content": "還沒種"})

        r = client.get("/api/export")
        assert r.status_code == 200
        day = r.json()["days"][0]
        assert (day["status"], day["planted_at"]) == ("collecting", None)
        assert [m["content"] for m in day["messages"]] == ["還沒種"]


class TestImport:
    def _upload(self, client, payload):
        body = payload if isinstance(payload, bytes) else json.dumps(payload).encode()
        return client.post("/api/import", files={"file": ("treesay.json", body, "application/json")})

    def _planted_export(self, client, mock_ai):
        client.post("/api/messages", data={"content": "今天還行"})
        mock_ai({**PLANT_RESULT, "memory": ["養了一隻貓"], "keywords": ["貓"]})
        client.post("/api/today/plant")
        return client.get("/api/export").json()

    def test_roundtrip_into_empty_database(self, client, clock, mock_ai, tmp_path, monkeypatch):
        exported = self._planted_export(client, mock_ai)

        # 換一個全新的資料庫（像換了電腦），把剛才的匯出匯進去
        from sqlalchemy import create_engine
        from sqlalchemy.orm import sessionmaker

        engine = create_engine(f"sqlite:///{tmp_path / 'other.db'}")
        models.Base.metadata.create_all(engine)
        monkeypatch.setattr(models, "SessionLocal", sessionmaker(bind=engine, expire_on_commit=False))

        assert client.get("/api/export").json()["days"] == []
        r = self._upload(client, exported)
        assert r.status_code == 200
        assert r.json()["days_added"] == 1
        assert r.json()["memories_added"] == 1

        again = client.get("/api/export").json()
        assert again["days"] == exported["days"]
        assert [m["content"] for m in again["memories"]] == ["養了一隻貓"]
        day = client.get("/api/days/2026-07-24").json()
        assert day["status"] == "planted"
        assert day["keywords"] == ["貓"]
        assert day["question"] == exported["days"][0]["question"]

    def test_existing_day_is_kept_not_overwritten(self, client, clock, mock_ai):
        exported = self._planted_export(client, mock_ai)
        exported["days"][0]["diary"] = "別人家的版本"

        r = self._upload(client, exported)
        assert r.json()["days_added"] == 0
        assert r.json()["days_skipped"] == 1
        assert r.json()["memories_skipped"] == 1
        assert client.get("/api/days/2026-07-24").json()["diary"] == PLANT_RESULT["diary"]

    def test_skipping_existing_keeps_importing_the_rest(self, client, clock):
        """跳過本機已有的那筆之後，排在後面的照樣要加進來。"""

        def day(date):
            return {"date": date, "messages": [{"content": date, "created_at": f"{date}T10:00:00"}]}

        def report(week_start):
            return {"week_start": week_start, "report": {"advice": week_start}}

        def memory(content):
            return {"content": content, "source_date": "2026-07-20"}

        self._upload(
            client,
            {"days": [day("2026-07-20")], "weekly_reports": [report("2026-07-06")], "memories": [memory("養了一隻貓")]},
        )
        r = self._upload(
            client,
            {
                "days": [day("2026-07-20"), day("2026-07-21")],
                "weekly_reports": [report("2026-07-06"), report("2026-07-13")],
                "memories": [memory("養了一隻貓"), memory("在做樹說")],
            },
        ).json()

        assert (r["days_added"], r["days_skipped"]) == (1, 1)
        assert (r["reports_added"], r["reports_skipped"]) == (1, 1)
        assert (r["memories_added"], r["memories_skipped"]) == (1, 1)
        data = client.get("/api/export").json()
        assert [d["date"] for d in data["days"]] == ["2026-07-20", "2026-07-21"]
        assert [w["week_start"] for w in data["weekly_reports"]] == ["2026-07-06", "2026-07-13"]
        assert [m["content"] for m in data["memories"]] == ["養了一隻貓", "在做樹說"]

    def test_imported_question_kept_not_recomputed(self, client, clock):
        """那天問過的題目跟著匯進來——兩台電腦的題庫不同，重算就會對不上。"""
        payload = {
            "days": [
                {
                    "date": "2026-07-20",
                    "question": "那天真正問過的題目",
                    "messages": [{"content": "嗨", "created_at": "2026-07-20T10:00:00"}],
                }
            ]
        }
        self._upload(client, payload)
        assert client.get("/api/days/2026-07-20").json()["question"] == "那天真正問過的題目"

    def test_backup_written_before_import(self, client, clock, tmp_path):
        client.post("/api/messages", data={"content": "備份前就在的話"})
        r = self._upload(client, {"days": []})
        backup = r.json()["backup"]
        assert backup and backup.startswith(str(tmp_path / "test.db.bak-"))
        import sqlite3

        with sqlite3.connect(backup) as conn:
            rows = conn.execute("SELECT content FROM messages").fetchall()
        assert rows == [("備份前就在的話",)]

    def test_garbage_file_rejected(self, client):
        r = self._upload(client, b"not json at all")
        assert r.status_code == 400
        assert "讀不懂" in r.json()["detail"]
        r = self._upload(client, {"days": [{"date": "not-a-date"}]})
        assert r.status_code == 400

    def test_planted_day_with_bad_emotion_falls_back(self, client, clock):
        payload = {
            "days": [
                {
                    "date": "2026-07-20",
                    "status": "planted",
                    "diary": "那天的日記",
                    "emotion": "ecstatic",
                    "tree_reply": "樹的回覆",
                    "planted_at": "2026-07-20T22:00:00",
                    "messages": [{"content": "嗨", "photo": None, "created_at": "2026-07-20T10:00:00"}],
                }
            ]
        }
        self._upload(client, payload)
        day = client.get("/api/days/2026-07-20").json()
        assert day["emotion"] == "calm"
        assert day["question"]  # 匯出沒帶問題也會蓋章
        assert [m["content"] for m in day["messages"]] == ["嗨"]

    def test_half_planted_day_comes_back_as_collecting(self, client, clock):
        payload = {
            "days": [
                {
                    "date": "2026-07-23",
                    "status": "planting",
                    "messages": [{"content": "卡住那天", "created_at": "2026-07-23T10:00:00"}],
                }
            ]
        }
        self._upload(client, payload)
        day = client.get("/api/days/2026-07-23").json()
        assert day["status"] == "collecting"
        assert day["can_plant"]  # 昨天，還在補種窗口內

    def test_missing_photo_is_counted_but_kept(self, client, clock):
        payload = {
            "days": [
                {
                    "date": "2026-07-23",
                    "messages": [
                        {"content": "", "photo": "2026-07-23/x.jpg", "created_at": "2026-07-23T10:00:00"}
                    ],
                }
            ]
        }
        r = self._upload(client, payload)
        assert r.json()["photos_missing"] == 1
        msg = client.get("/api/days/2026-07-23").json()["messages"][0]
        assert msg["photo_url"] == "/photos/2026-07-23/x.jpg"

    def test_weekly_report_imported_once(self, client, clock):
        payload = {
            "weekly_reports": [
                {"week_start": "2026-07-13", "report": {"summary": "一週"}, "created_at": "2026-07-20T09:00:00"}
            ]
        }
        assert self._upload(client, payload).json()["reports_added"] == 1
        assert self._upload(client, payload).json()["reports_skipped"] == 1
        assert client.get("/api/export").json()["weekly_reports"][0]["report"] == {"summary": "一週"}


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


class TestUsage:
    """AI 費用頁：只按月加總，不拆到天。"""

    def add(self, db_factory, when, purpose, cost, model="claude-opus-5-5"):
        with db_factory() as db:
            db.add(
                models.ApiUsage(
                    created_at=when,
                    purpose=purpose,
                    model=model,
                    input_tokens=1000,
                    output_tokens=500,
                    cost_usd=cost,
                )
            )
            db.commit()

    def test_empty(self, client):
        assert client.get("/api/usage").json() == {"months": [], "total_usd": 0}

    def test_grouped_by_month_newest_first(self, client, db_factory):
        dt = datetime.datetime
        self.add(db_factory, dt(2026, 6, 30, 23, 0), "plant", 0.02)
        self.add(db_factory, dt(2026, 7, 1, 9, 0), "plant", 0.03)
        self.add(db_factory, dt(2026, 7, 2, 9, 0), "plant", 0.01)
        self.add(db_factory, dt(2026, 7, 6, 9, 0), "weekly", 0.004)
        self.add(db_factory, dt(2026, 7, 7, 9, 0), "plant", None, model="claude-someday-9")

        body = client.get("/api/usage").json()

        july, june = body["months"]
        assert july["month"] == "2026-07"
        assert july["plant_usd"] == pytest.approx(0.04)
        assert july["weekly_usd"] == pytest.approx(0.004)
        assert july["total_usd"] == pytest.approx(0.044)
        # 算不出錢的呼叫不偷偷當成 0 元，要讓人知道有幾次沒算進去
        assert july["unpriced_calls"] == 1
        assert (june["month"], june["unpriced_calls"]) == ("2026-06", 0)
        assert june["total_usd"] == pytest.approx(0.02)
        assert body["total_usd"] == pytest.approx(0.064)
        # 只到月，沒有任何一天的數字
        assert set(july) == {"month", "total_usd", "plant_usd", "weekly_usd", "unpriced_calls"}
