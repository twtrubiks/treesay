import os
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

# 必須在 import models／main 之前設定：資料落點在 import 時就決定，
# 測試不該碰到真實的日記目錄
os.environ.setdefault("TREESAY_DATA_DIR", tempfile.mkdtemp(prefix="treesay-test-"))

import pytest  # noqa: E402
from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import sessionmaker  # noqa: E402

import ai  # noqa: E402
import models  # noqa: E402

# 每個 AI 回應都會記一筆用量。沒指定 db_factory 的測試記進這個暫存庫，
# 先把表建好，免得記帳失敗的 warning 混進測試輸出
models.init_db()


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


@pytest.fixture()
def db_factory(tmp_path, monkeypatch):
    """每個測試一個乾淨的資料庫與照片目錄。"""
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
