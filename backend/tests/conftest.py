import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# 必須在 import models／main 之前設定：資料落點在 import 時就決定，
# 測試不該碰到真實的日記目錄
os.environ.setdefault("TREESAY_DATA_DIR", tempfile.mkdtemp(prefix="treesay-test-"))

import pytest  # noqa: E402

import ai  # noqa: E402


@pytest.fixture(autouse=True)
def stub_claude_env(monkeypatch):
    """把環境探測擋成「已安裝且已登入」，測試不受本機 claude 狀態影響。

    否則沒裝 claude 的機器上，走環境診斷的測試會拿到 503 而非預期結果。
    要驗證探測邏輯本身的測試，在測試內覆寫這兩個點即可。
    """
    monkeypatch.setattr(ai.shutil, "which", lambda cmd: "/usr/bin/claude")

    async def logged_in():
        return {"loggedIn": True}

    monkeypatch.setattr(ai, "_auth_status", logged_in)
