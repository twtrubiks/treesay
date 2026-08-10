"""ai.py 單元測試：JSON 解析各情境，不真呼叫 claude。"""

import asyncio
import json

import pytest

import ai


def make_envelope(result: str, subtype: str = "success") -> str:
    return json.dumps({"type": "result", "subtype": subtype, "result": result})


def fake_auth(status):
    async def _status():
        return status

    return _status


class TestParseResponse:
    def test_valid_envelope(self):
        raw = make_envelope('{"diary": "今天很平靜", "emotion": "calm"}')
        assert ai.parse_response(raw) == {"diary": "今天很平靜", "emotion": "calm"}

    def test_error_subtype(self):
        raw = make_envelope("whatever", subtype="error_during_execution")
        with pytest.raises(ai.AIError, match="非 success"):
            ai.parse_response(raw)

    def test_code_fence(self):
        raw = make_envelope('```json\n{"emotion": "happy"}\n```')
        assert ai.parse_response(raw) == {"emotion": "happy"}

    def test_code_fence_no_lang(self):
        raw = make_envelope('```\n{"emotion": "sad"}\n```')
        assert ai.parse_response(raw) == {"emotion": "sad"}

    def test_bad_envelope_json(self):
        with pytest.raises(ai.AIError, match="envelope"):
            ai.parse_response("not json at all")

    def test_bad_inner_json(self):
        raw = make_envelope("這不是 JSON")
        with pytest.raises(ai.AIError, match="result"):
            ai.parse_response(raw)

    def test_missing_result(self):
        raw = json.dumps({"type": "result", "subtype": "success"})
        with pytest.raises(ai.AIError, match="result"):
            ai.parse_response(raw)

    def test_inner_not_dict(self):
        raw = make_envelope('["a", "b"]')
        with pytest.raises(ai.AIError, match="不是物件"):
            ai.parse_response(raw)


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


class TestCheckCli:
    """環境檢查只採信確定訊號，寧可漏判也不誤殺。"""

    def test_cli_missing(self, monkeypatch):
        monkeypatch.setattr(ai.shutil, "which", lambda cmd: None)
        with pytest.raises(ai.AICliMissingError):
            asyncio.run(ai.check_cli())

    def test_not_logged_in(self, monkeypatch):
        monkeypatch.setattr(ai.shutil, "which", lambda cmd: "/usr/bin/claude")
        monkeypatch.setattr(ai, "_auth_status", fake_auth({"loggedIn": False}))
        with pytest.raises(ai.AINotLoggedInError):
            asyncio.run(ai.check_cli())

    def test_logged_in_passes(self, monkeypatch):
        monkeypatch.setattr(ai.shutil, "which", lambda cmd: "/usr/bin/claude")
        monkeypatch.setattr(ai, "_auth_status", fake_auth({"loggedIn": True}))
        asyncio.run(ai.check_cli())

    def test_undetermined_status_is_not_logged_out(self, monkeypatch):
        """auth status 讀不到時不能當成未登入——那會擋掉能用的環境。"""
        monkeypatch.setattr(ai.shutil, "which", lambda cmd: "/usr/bin/claude")
        monkeypatch.setattr(ai, "_auth_status", fake_auth(None))
        asyncio.run(ai.check_cli())


class TestAskRetry:
    def test_retry_once_then_success(self, monkeypatch):
        calls = []

        async def fake_run(prompt, allowed_tools=None, profile="plant"):
            calls.append(1)
            if len(calls) == 1:
                return "broken output"
            return make_envelope('{"ok": true}')

        monkeypatch.setattr(ai, "_run_claude", fake_run)
        assert asyncio.run(ai.ask("hi")) == {"ok": True}
        assert len(calls) == 2

    def test_fail_twice_raises(self, monkeypatch):
        calls = []

        async def fake_run(prompt, allowed_tools=None, profile="plant"):
            calls.append(1)
            return "broken output"

        monkeypatch.setattr(ai, "_run_claude", fake_run)
        with pytest.raises(ai.AIError):
            asyncio.run(ai.ask("hi"))
        assert len(calls) == 2

    def test_cli_missing_not_retried(self, monkeypatch):
        calls = []

        async def fake_run(prompt, allowed_tools=None, profile="plant"):
            calls.append(1)
            raise ai.AICliMissingError("沒裝")

        monkeypatch.setattr(ai, "_run_claude", fake_run)
        with pytest.raises(ai.AICliMissingError):
            asyncio.run(ai.ask("hi"))
        assert len(calls) == 1

    def test_failure_diagnosed_as_not_logged_in(self, monkeypatch):
        """重按不會好的問題要講明原因，而非一律「樹睡著了」。"""

        async def fake_run(prompt, allowed_tools=None, profile="plant"):
            return "broken output"

        async def diagnose():
            raise ai.AINotLoggedInError("尚未登入")

        monkeypatch.setattr(ai, "_run_claude", fake_run)
        monkeypatch.setattr(ai, "check_cli", diagnose)
        with pytest.raises(ai.AINotLoggedInError):
            asyncio.run(ai.ask("hi"))

    def test_timeout_not_retried(self, monkeypatch):
        calls = []

        async def fake_run(prompt, allowed_tools=None, profile="plant"):
            calls.append(1)
            raise ai.AITimeoutError("逾時")

        monkeypatch.setattr(ai, "_run_claude", fake_run)
        with pytest.raises(ai.AITimeoutError):
            asyncio.run(ai.ask("hi"))
        assert len(calls) == 1


class TestToolPolicy:
    """日記內容會原樣進 prompt，工具必須是排他白名單而不是全開。"""

    def _capture_cmd(self, monkeypatch) -> dict:
        captured = {}

        class FakeProc:
            returncode = 0

            async def communicate(self, data=None):
                return make_envelope('{"ok": true}').encode(), b""

        async def fake_exec(*cmd, **kwargs):
            captured["cmd"] = list(cmd)
            return FakeProc()

        monkeypatch.setattr(ai.asyncio, "create_subprocess_exec", fake_exec)
        return captured

    def test_no_tools_by_default(self, monkeypatch):
        captured = self._capture_cmd(monkeypatch)
        asyncio.run(ai.ask("hi"))
        cmd = captured["cmd"]
        assert cmd[cmd.index("--tools") + 1] == ""
        assert "--allowedTools" not in cmd

    def test_photo_opens_read_and_nothing_else(self, monkeypatch):
        captured = self._capture_cmd(monkeypatch)
        asyncio.run(ai.ask("hi", allowed_tools=["Read"]))
        cmd = captured["cmd"]
        assert cmd[cmd.index("--tools") + 1] == "Read"
        assert cmd[cmd.index("--allowedTools") + 1] == "Read"

    def test_user_settings_not_inherited(self, monkeypatch):
        captured = self._capture_cmd(monkeypatch)
        asyncio.run(ai.ask("hi"))
        cmd = captured["cmd"]
        assert cmd[cmd.index("--setting-sources") + 1] == ""

    def test_never_bypasses_permissions(self, monkeypatch):
        """權限模式在 -p 下擋不住工具，更不該主動全開。"""
        captured = self._capture_cmd(monkeypatch)
        asyncio.run(ai.ask("hi", allowed_tools=["Read"]))
        assert "bypassPermissions" not in captured["cmd"]


class TestProfiles:
    """跑的是使用者的訂閱額度，不同用途不該一律走最貴的檔次。"""

    def _capture_cmd(self, monkeypatch) -> dict:
        return TestToolPolicy()._capture_cmd(monkeypatch)

    def test_plant_and_weekly_use_different_profiles(self, monkeypatch):
        captured = self._capture_cmd(monkeypatch)
        asyncio.run(ai.ask("hi", profile="plant"))
        plant = list(captured["cmd"])
        asyncio.run(ai.ask("hi", profile="weekly"))
        weekly = captured["cmd"]

        def pair(cmd):
            return cmd[cmd.index("--model") + 1], cmd[cmd.index("--effort") + 1]

        assert pair(plant) != pair(weekly)
        assert pair(plant) == ai.DEFAULT_PROFILES["plant"]
        assert pair(weekly) == ai.DEFAULT_PROFILES["weekly"]

    def test_no_profile_pinned_to_top_effort(self):
        """xhigh／max 寫死在日記生成上只是燒額度。"""
        assert all(
            effort not in ("xhigh", "max") for _, effort in ai.DEFAULT_PROFILES.values()
        )

    def test_env_var_overrides(self, monkeypatch):
        monkeypatch.setenv("TREESAY_PLANT_MODEL", "claude-haiku-4-5")
        monkeypatch.setenv("TREESAY_PLANT_EFFORT", "low")
        assert ai.resolve_profile("plant") == ("claude-haiku-4-5", "low")

    def test_env_override_reaches_cli_after_import(self, monkeypatch):
        """設定不在 import 時凍結：模組載入後才設的變數也要吃到。"""
        captured = self._capture_cmd(monkeypatch)
        monkeypatch.setenv("TREESAY_PLANT_MODEL", "claude-haiku-4-5")
        monkeypatch.setenv("TREESAY_PLANT_EFFORT", "low")
        asyncio.run(ai.ask("hi", profile="plant"))
        cmd = captured["cmd"]
        assert cmd[cmd.index("--model") + 1] == "claude-haiku-4-5"
        assert cmd[cmd.index("--effort") + 1] == "low"

    def test_invalid_effort_falls_back_to_default(self, monkeypatch):
        """typo 原樣傳下去只會變成使用者查不出原因的「樹睡著了」。"""
        monkeypatch.setenv("TREESAY_PLANT_EFFORT", "hgih")
        assert ai.resolve_profile("plant") == ai.DEFAULT_PROFILES["plant"]

    def test_summary_shows_effective_values(self, monkeypatch):
        """啟動時要能看出覆寫有沒有吃到。"""
        monkeypatch.setenv("TREESAY_WEEKLY_MODEL", "claude-haiku-4-5")
        summary = ai.profile_summary()
        weekly_effort = ai.DEFAULT_PROFILES["weekly"][1]
        assert f"weekly=claude-haiku-4-5 / {weekly_effort}" in summary
        assert "plant={} / {}".format(*ai.DEFAULT_PROFILES["plant"]) in summary


class TestStripCodeFence:
    def test_plain_text_untouched(self):
        assert ai.strip_code_fence('{"a": 1}') == '{"a": 1}'

    def test_fence_with_lang(self):
        assert ai.strip_code_fence('```json\n{"a": 1}\n```') == '{"a": 1}'

    def test_fence_with_surrounding_whitespace(self):
        assert ai.strip_code_fence('\n```json\n{"a": 1}\n```\n') == '{"a": 1}'
