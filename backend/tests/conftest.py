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
def stub_api(monkeypatch):
    """給一把假 key，並把 SDK client 換成一碰就失敗的替身。

    測試不受本機有沒有設 key 影響，也絕不會真的打到 API（花錢、要網路）。
    要驗證請求內容的測試，在測試內換上自己的假 client 即可。
    """
    monkeypatch.setenv(ai.API_KEY_ENV, "sk-ant-test")

    class NoNetwork:
        def __init__(self, *args, **kwargs):
            raise AssertionError("測試不該真的呼叫 Claude API")

    monkeypatch.setattr(ai.anthropic, "AsyncAnthropic", NoNetwork)
