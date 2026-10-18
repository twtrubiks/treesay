"""ai.py 單元測試：輸出解析、錯誤分類、請求內容，不真呼叫 Claude API。"""

import asyncio
import base64
import io
import json
from types import SimpleNamespace

import anthropic
import httpx2
import pytest
from PIL import ExifTags, Image

import ai
import models
import prompts

SCHEMA = prompts.PLANT_SCHEMA
REQUEST = httpx2.Request("POST", "https://api.anthropic.com/v1/messages")



def image_bytes(fmt: str = "JPEG", size=(8, 6), mode: str = "RGB", **save_kwargs) -> bytes:
    """Pillow 解得開的真圖片——送出前會先在本機解一次，只有檔頭的假檔過不了關。"""
    buf = io.BytesIO()
    Image.new(mode, size).save(buf, fmt, **save_kwargs)
    return buf.getvalue()


JPEG = image_bytes("JPEG")
PNG = image_bytes("PNG")


def make_response(text: str, stop_reason: str = "end_turn"):
    """模擬 SDK 的 Message：adaptive thinking 會在文字前多一個 thinking block。"""
    return SimpleNamespace(
        stop_reason=stop_reason,
        content=[
            SimpleNamespace(type="thinking", thinking="想一想"),
            SimpleNamespace(type="text", text=text),
        ],
        usage=SimpleNamespace(input_tokens=1000, output_tokens=500),
    )


def status_error(cls, code: int):
    return cls("boom", response=httpx2.Response(code, request=REQUEST), body=None)


def install_client(monkeypatch, *results) -> dict:
    """換上假 SDK client，記下建構參數與每次請求。

    results 依序作為每次請求的結果（回應或例外），用完就重複最後一個。
    """
    captured: dict = {"requests": []}
    outcomes = list(results) or [make_response('{"ok": true}')]

    class FakeMessages:
        async def create(self, **kwargs):
            captured["requests"].append(kwargs)
            outcome = outcomes[min(len(captured["requests"]), len(outcomes)) - 1]
            if isinstance(outcome, Exception):
                raise outcome
            return outcome

    class FakeClient:
        def __init__(self, **kwargs):
            captured["client"] = kwargs
            self.messages = FakeMessages()

        async def __aenter__(self):
            return self

        async def __aexit__(self, *exc):
            return False

    monkeypatch.setattr(ai.anthropic, "AsyncAnthropic", FakeClient)
    return captured


class TestParseResponse:
    def test_valid_output(self):
        response = make_response('{"diary": "今天很平靜", "emotion": "calm"}')
        assert ai.parse_response(response) == {"diary": "今天很平靜", "emotion": "calm"}

    def test_refusal(self):
        """拒答時輸出不保證符合 schema，不能當成功。"""
        with pytest.raises(ai.AIError, match="refusal"):
            ai.parse_response(make_response("我不能幫忙", stop_reason="refusal"))

    def test_truncated_by_max_tokens(self):
        with pytest.raises(ai.AIError, match="max_tokens"):
            ai.parse_response(make_response('{"diary": "今天', stop_reason="max_tokens"))

    def test_bad_json(self):
        with pytest.raises(ai.AIError, match="JSON"):
            ai.parse_response(make_response("這不是 JSON"))

    def test_not_dict(self):
        with pytest.raises(ai.AIError, match="不是物件"):
            ai.parse_response(make_response('["a", "b"]'))


class TestNormalizeEmotion:
    @pytest.mark.parametrize(
        "emotion", ["happy", "calm", "excited", "tired", "sad", "anxious", "angry"]
    )
    def test_valid_emotions_kept(self, emotion):
        assert ai.normalize_emotion(emotion) == emotion

    @pytest.mark.parametrize("bad", ["joyful", "", None, 123, "HAPPY"])
    def test_out_of_enum_falls_back_to_calm(self, bad):
        assert ai.normalize_emotion(bad) == "calm"


class TestNormalizeMemories:
    """記憶會被永久保存並注入之後每次種樹，寬進嚴出。"""

    @pytest.mark.parametrize("bad", [None, "一句話", {"a": 1}, 123])
    def test_non_list_returns_empty(self, bad):
        assert ai.normalize_memories(bad, 3) == []

    def test_strips_and_drops_empty(self):
        assert ai.normalize_memories(["  在做樹說  ", "", "   "], 3) == ["在做樹說"]

    def test_non_string_items_skipped(self):
        assert ai.normalize_memories([1, None, "養了一隻貓", ["巢狀"]], 3) == [
            "養了一隻貓"
        ]

    def test_too_long_item_dropped(self):
        long = "很長" * 100
        assert ai.normalize_memories([long, "短短的"], 3) == ["短短的"]

    def test_dedupes_and_caps_at_limit(self):
        items = ["一樣的", "一樣的", "第二件", "第三件", "第四件"]
        assert ai.normalize_memories(items, 3) == ["一樣的", "第二件", "第三件"]


class TestNormalizeKeywords:
    """關鍵詞欄位壞掉就當沒有，不能拖垮種樹。"""

    @pytest.mark.parametrize("bad", [None, "一個詞", {"a": 1}, 123])
    def test_non_list_returns_empty(self, bad):
        assert ai.normalize_keywords(bad, 4) == []

    def test_cleans_dedupes_and_caps(self):
        items = ["  種樹  ", "", 7, "種樹", "散步", "晚餐", "貓", "多的"]
        assert ai.normalize_keywords(items, 4) == ["種樹", "散步", "晚餐", "貓"]

    def test_sentence_length_item_dropped(self):
        long = "這不是一個詞而是一整句話所以應該被丟掉才對吧"
        assert ai.normalize_keywords([long, "散步"], 4) == ["散步"]


class TestApiKey:
    """key 的問題重按也不會好，要分得出來、而且不重試。"""

    def test_missing_key_never_reaches_network(self, monkeypatch):
        # conftest 的替身 client 一被建構就會失敗，能走到這裡代表沒打網路
        monkeypatch.delenv(ai.API_KEY_ENV)
        with pytest.raises(ai.AIKeyMissingError):
            asyncio.run(ai.ask("hi", SCHEMA))

    def test_blank_key_counts_as_missing(self, monkeypatch):
        monkeypatch.setenv(ai.API_KEY_ENV, "   ")
        with pytest.raises(ai.AIKeyMissingError):
            ai.check_key()

    def test_check_key_passes_when_set(self):
        ai.check_key()

    def test_anthropic_api_key_is_not_borrowed(self, monkeypatch):
        """export ANTHROPIC_API_KEY 會把 Claude Code 也改成 API 計費，樹說不讀它。"""
        monkeypatch.delenv(ai.API_KEY_ENV)
        monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-ant-claude-code")
        with pytest.raises(ai.AIKeyMissingError):
            ai.check_key()

    def test_key_passed_explicitly(self, monkeypatch):
        """明確傳入 key，SDK 才不會自己去翻 ANTHROPIC_API_KEY 或設定檔。"""
        captured = install_client(monkeypatch)
        asyncio.run(ai.ask("hi", SCHEMA))
        assert captured["client"]["api_key"] == "sk-ant-test"

    def test_invalid_key_not_retried(self, monkeypatch):
        captured = install_client(
            monkeypatch, status_error(anthropic.AuthenticationError, 401)
        )
        with pytest.raises(ai.AIKeyInvalidError):
            asyncio.run(ai.ask("hi", SCHEMA))
        assert len(captured["requests"]) == 1


class TestAskRetry:
    def test_retry_once_then_success(self, monkeypatch):
        captured = install_client(
            monkeypatch,
            make_response("拒答", stop_reason="refusal"),
            make_response('{"ok": true}'),
        )
        assert asyncio.run(ai.ask("hi", SCHEMA)) == {"ok": True}
        assert len(captured["requests"]) == 2

    def test_fail_twice_raises(self, monkeypatch):
        captured = install_client(monkeypatch, make_response("壞掉"))
        with pytest.raises(ai.AIError):
            asyncio.run(ai.ask("hi", SCHEMA))
        assert len(captured["requests"]) == 2

    def test_api_error_retried_once(self, monkeypatch):
        captured = install_client(
            monkeypatch,
            status_error(anthropic.InternalServerError, 500),
            make_response('{"ok": true}'),
        )
        assert asyncio.run(ai.ask("hi", SCHEMA)) == {"ok": True}
        assert len(captured["requests"]) == 2

    def test_timeout_not_retried(self, monkeypatch):
        captured = install_client(monkeypatch, anthropic.APITimeoutError(REQUEST))
        with pytest.raises(ai.AITimeoutError):
            asyncio.run(ai.ask("hi", SCHEMA))
        assert len(captured["requests"]) == 1

    def test_sdk_retries_disabled(self, monkeypatch):
        """SDK 自己也會重試（含逾時），疊上這裡的重試會把 planting 拖過逾期時間。"""
        captured = install_client(monkeypatch)
        asyncio.run(ai.ask("hi", SCHEMA))
        assert captured["client"]["max_retries"] == 0
        assert captured["client"]["timeout"] == ai.TIMEOUT_SECONDS


class TestRequest:
    """日記內容會原樣進 prompt，所以這個請求裡不能有任何工具。"""

    def test_no_tools(self, monkeypatch):
        captured = install_client(monkeypatch)
        asyncio.run(ai.ask("hi", SCHEMA, images=[{"type": "image"}]))
        request = captured["requests"][0]
        assert "tools" not in request
        assert "tool_choice" not in request

    def test_schema_constrains_output(self, monkeypatch):
        captured = install_client(monkeypatch)
        asyncio.run(ai.ask("hi", SCHEMA))
        fmt = captured["requests"][0]["output_config"]["format"]
        assert fmt == {"type": "json_schema", "schema": SCHEMA}

    def test_text_only_prompt(self, monkeypatch):
        captured = install_client(monkeypatch)
        asyncio.run(ai.ask("說說今天", SCHEMA))
        (message,) = captured["requests"][0]["messages"]
        assert message == {
            "role": "user",
            "content": [{"type": "text", "text": "說說今天"}],
        }

    def test_images_come_first_with_numbered_labels(self, monkeypatch):
        """照片在前、各帶「照片 N」標籤，prompt 裡的「附照片 N」才對得上。"""
        captured = install_client(monkeypatch)
        img1, img2 = {"type": "image", "n": 1}, {"type": "image", "n": 2}
        asyncio.run(ai.ask("說說今天", SCHEMA, images=[img1, img2]))
        content = captured["requests"][0]["messages"][0]["content"]
        assert content == [
            {"type": "text", "text": "照片 1："},
            img1,
            {"type": "text", "text": "照片 2："},
            img2,
            {"type": "text", "text": "說說今天"},
        ]


class TestImages:
    @pytest.mark.parametrize(
        "data, media_type",
        [
            (JPEG, "image/jpeg"),
            (PNG, "image/png"),
            (b"GIF89a-body", "image/gif"),
            (b"RIFF\x00\x00\x00\x00WEBPVP8 ", "image/webp"),
            (b"\x00\x00\x00\x18ftypheic", None),
            (b"", None),
        ],
    )
    def test_sniff_by_header_not_suffix(self, data, media_type):
        assert ai.sniff_media_type(data) == media_type

    def test_blocks_align_with_paths(self, tmp_path):
        """送不出去的照片留 None 佔位，呼叫端才知道哪一則的照片樹看不到。"""
        jpg = tmp_path / "a.jpg"
        jpg.write_bytes(JPEG)
        heic = tmp_path / "b.heic"
        heic.write_bytes(b"\x00\x00\x00\x18ftypheic")
        png = tmp_path / "c.png"
        png.write_bytes(PNG)

        blocks = ai.image_blocks([jpg, heic, tmp_path / "gone.jpg", png])

        assert blocks[1] is None and blocks[2] is None
        assert blocks[0] == {
            "type": "image",
            "source": {
                "type": "base64",
                "media_type": "image/jpeg",
                "data": base64.b64encode(JPEG).decode(),
            },
        }
        assert blocks[3]["source"]["media_type"] == "image/png"

    def test_oversized_image_skipped(self, tmp_path, monkeypatch):
        monkeypatch.setattr(ai, "IMAGE_MAX_B64_BYTES", 8)
        jpg = tmp_path / "big.jpg"
        jpg.write_bytes(JPEG)
        assert ai.image_blocks([jpg]) == [None]

    def test_total_budget_keeps_request_under_limit(self, tmp_path, monkeypatch):
        """整天的照片加起來超過 request 上限時，多出來的不送，種樹照常。"""
        one = len(base64.b64encode(JPEG))
        monkeypatch.setattr(ai, "IMAGES_BUDGET_B64_BYTES", one * 2)
        paths = []
        for i in range(3):
            path = tmp_path / f"{i}.jpg"
            path.write_bytes(JPEG)
            paths.append(path)
        blocks = ai.image_blocks(paths)
        assert [b is not None for b in blocks] == [True, True, False]

    def test_corrupt_file_skipped(self, tmp_path):
        """檔頭對、內容壞的檔案送出去會讓整次請求 400，在本機就先擋下。"""
        broken = tmp_path / "broken.jpg"
        broken.write_bytes(b"\xff\xd8\xff\xe0" + b"not-really-a-jpeg")
        assert ai.image_blocks([broken]) == [None]

    def test_phone_photo_downscaled_original_untouched(self, tmp_path):
        """手機原圖縮到標準解析度再送，磁碟上的原圖一個 byte 都不動。"""
        photo = tmp_path / "phone.jpg"
        original = image_bytes("JPEG", size=(4032, 3024))
        photo.write_bytes(original)

        (block,) = ai.image_blocks([photo])

        assert block["source"]["media_type"] == "image/jpeg"
        sent = Image.open(io.BytesIO(base64.b64decode(block["source"]["data"])))
        w, h = sent.size
        assert max(w, h) <= ai.IMAGE_MAX_EDGE
        assert w * h <= ai.IMAGE_MAX_PIXELS
        assert abs(w / h - 4032 / 3024) < 0.01
        assert photo.read_bytes() == original

    def test_exif_orientation_applied(self):
        """直拍的照片方向記在 EXIF，模型不讀 metadata，送出前要真的轉正。"""
        exif = Image.Exif()
        exif[ExifTags.Base.Orientation] = 6  # 順時針轉 90 度才是正的
        data = image_bytes("JPEG", size=(40, 20), exif=exif.tobytes())

        sent, media_type = ai.prepare_image(data)

        assert media_type == "image/jpeg"
        assert Image.open(io.BytesIO(sent)).size == (20, 40)

    def test_transparency_kept_as_png(self):
        """有透明的圖縮圖後仍是 PNG，轉 JPEG 透明處會變成一片黑。"""
        data = image_bytes("PNG", size=(3000, 2000), mode="RGBA")
        _, media_type = ai.prepare_image(data)
        assert media_type == "image/png"


class TestSchemas:
    """structured outputs 對 schema 有硬性要求，違反的話每次請求都會 400。"""

    @pytest.mark.parametrize(
        "schema",
        [prompts.PLANT_SCHEMA, prompts.weekly_schema(True), prompts.weekly_schema(False)],
    )
    def test_objects_are_closed_and_fully_required(self, schema):
        assert schema["additionalProperties"] is False
        assert schema["required"] == list(schema["properties"])

    def test_emotion_enum_matches_models(self):
        enum = prompts.PLANT_SCHEMA["properties"]["emotion"]["enum"]
        assert set(enum) == models.EMOTIONS
        assert len(enum) == len(models.EMOTIONS)

    def test_weekly_memories_only_when_there_are_memories(self):
        assert "memories" in prompts.weekly_schema(True)["properties"]
        assert "memories" not in prompts.weekly_schema(False)["properties"]

    def test_schemas_are_json_serializable(self):
        json.dumps([prompts.PLANT_SCHEMA, prompts.weekly_schema(True)])


class TestProfiles:
    """每次呼叫都按 token 計費，不同用途不該一律走最貴的檔次。"""

    @staticmethod
    def pair(request) -> tuple[str, str]:
        return request["model"], request["output_config"]["effort"]

    def test_plant_and_weekly_use_different_profiles(self, monkeypatch):
        captured = install_client(monkeypatch)
        asyncio.run(ai.ask("hi", SCHEMA, profile="plant"))
        asyncio.run(ai.ask("hi", SCHEMA, profile="weekly"))
        plant, weekly = (self.pair(r) for r in captured["requests"])

        assert plant != weekly
        assert plant == ai.DEFAULT_PROFILES["plant"]
        assert weekly == ai.DEFAULT_PROFILES["weekly"]

    def test_no_profile_pinned_to_top_effort(self):
        """xhigh／max 寫死在日記生成上只是燒錢。"""
        assert all(
            effort not in ("xhigh", "max") for _, effort in ai.DEFAULT_PROFILES.values()
        )

    def test_env_var_overrides(self, monkeypatch):
        monkeypatch.setenv("TREESAY_PLANT_MODEL", "claude-haiku-5-5")
        monkeypatch.setenv("TREESAY_PLANT_EFFORT", "low")
        assert ai.resolve_profile("plant") == ("claude-haiku-5-5", "low")

    def test_env_override_reaches_request_after_import(self, monkeypatch):
        """設定不在 import 時凍結：模組載入後才設的變數也要吃到。"""
        captured = install_client(monkeypatch)
        monkeypatch.setenv("TREESAY_PLANT_MODEL", "claude-haiku-5-5")
        monkeypatch.setenv("TREESAY_PLANT_EFFORT", "low")
        asyncio.run(ai.ask("hi", SCHEMA, profile="plant"))
        assert self.pair(captured["requests"][0]) == ("claude-haiku-5-5", "low")

    def test_invalid_effort_falls_back_to_default(self, monkeypatch):
        """typo 原樣傳下去只會變成使用者查不出原因的「樹睡著了」。"""
        monkeypatch.setenv("TREESAY_PLANT_EFFORT", "hgih")
        assert ai.resolve_profile("plant") == ai.DEFAULT_PROFILES["plant"]

    def test_summary_shows_effective_values(self, monkeypatch):
        """啟動時要能看出覆寫有沒有吃到。"""
        monkeypatch.setenv("TREESAY_WEEKLY_MODEL", "claude-haiku-5-5")
        summary = ai.profile_summary()
        weekly_effort = ai.DEFAULT_PROFILES["weekly"][1]
        assert f"weekly=claude-haiku-5-5 / {weekly_effort}" in summary
        assert "plant={} / {}".format(*ai.DEFAULT_PROFILES["plant"]) in summary

    def test_default_models_are_all_priced(self):
        """預設檔次一定要在價目表上，否則出廠就記不到錢。"""
        assert ai.unpriced_models() == []

    def test_unpriced_models_lists_overrides_missing_from_prices(self, monkeypatch):
        """換成表上沒有的模型，啟動時要講出來，不然費用頁默默少算。"""
        monkeypatch.setenv("TREESAY_PLANT_MODEL", "claude-opus-9")
        monkeypatch.setenv("TREESAY_WEEKLY_MODEL", "claude-opus-9")
        assert ai.unpriced_models() == ["claude-opus-9"]


class TestUsage:
    """每個回應記一筆用量，給「AI 費用」頁加總。"""

    def rows(self, db_factory):
        with db_factory() as db:
            return db.query(models.ApiUsage).order_by(models.ApiUsage.id).all()

    def test_each_response_recorded_with_cost(self, monkeypatch, db_factory):
        install_client(monkeypatch, make_response('{"ok": true}'))
        asyncio.run(ai.ask("hi", SCHEMA, profile="plant"))

        (row,) = self.rows(db_factory)
        model = ai.DEFAULT_PROFILES["plant"][0]
        assert (row.purpose, row.model) == ("plant", model)
        assert (row.input_tokens, row.output_tokens) == (1000, 500)
        assert row.cost_usd == pytest.approx(ai.estimate_cost(model, 1000, 500))

    def test_cost_follows_price_table(self):
        # Opus 5.5：輸入 $4、輸出 $20／百萬 token
        assert ai.estimate_cost("claude-opus-5-5", 1_000_000, 100_000) == pytest.approx(6)

    def test_retried_responses_are_billed_too(self, monkeypatch, db_factory):
        """拒答的那次一樣花了錢，不能只記成功的那次。"""
        install_client(
            monkeypatch,
            make_response("", stop_reason="refusal"),
            make_response('{"ok": true}'),
        )
        asyncio.run(ai.ask("hi", SCHEMA))
        assert len(self.rows(db_factory)) == 2

    def test_failed_request_not_recorded(self, monkeypatch, db_factory):
        """API 直接回錯誤就沒有用量可記。"""
        install_client(monkeypatch, status_error(anthropic.InternalServerError, 500))
        with pytest.raises(ai.AIError):
            asyncio.run(ai.ask("hi", SCHEMA))
        assert self.rows(db_factory) == []

    def test_unknown_model_recorded_without_cost(self, monkeypatch, db_factory):
        """換成價目表上沒有的模型，還是要記下來，只是算不出錢。"""
        monkeypatch.setenv("TREESAY_PLANT_MODEL", "claude-someday-9")
        install_client(monkeypatch, make_response('{"ok": true}'))
        asyncio.run(ai.ask("hi", SCHEMA, profile="plant"))
        (row,) = self.rows(db_factory)
        assert row.model == "claude-someday-9" and row.cost_usd is None

    def test_recording_failure_does_not_lose_reply(self, monkeypatch):
        """錢已經花了、回覆也拿到了，記帳出錯不能讓這次種樹作廢。"""

        def broken_session():
            raise RuntimeError("disk full")

        monkeypatch.setattr(models, "SessionLocal", broken_session)
        install_client(monkeypatch, make_response('{"ok": true}'))
        assert asyncio.run(ai.ask("hi", SCHEMA)) == {"ok": True}
