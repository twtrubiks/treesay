"""models.py 單元測試：日記資料落點的解析規則、舊資料庫升級。"""

import datetime
import pathlib
import sqlite3

from sqlalchemy import create_engine

import models
import questions


class TestResolveDataDir:
    def test_env_var_wins(self, monkeypatch):
        monkeypatch.setenv("TREESAY_DATA_DIR", "/tmp/custom-treesay")
        assert models.resolve_data_dir() == pathlib.Path("/tmp/custom-treesay")

    def test_env_var_expands_user(self, monkeypatch):
        monkeypatch.setenv("TREESAY_DATA_DIR", "~/my-treesay")
        assert models.resolve_data_dir() == pathlib.Path.home() / "my-treesay"

    def test_defaults_to_home(self, monkeypatch):
        monkeypatch.delenv("TREESAY_DATA_DIR", raising=False)
        assert models.resolve_data_dir() == models.DEFAULT_DATA_DIR


class TestUpgradeOldDb:
    """老資料庫起新程式：init_db 要自己補欄位（create_all 只建缺的表）。"""

    OLD_DAYS_SQL = """
        CREATE TABLE days (
            id INTEGER NOT NULL PRIMARY KEY,
            date DATE NOT NULL UNIQUE,
            status VARCHAR(20) NOT NULL,
            diary_text TEXT,
            emotion VARCHAR(20),
            tree_reply TEXT,
            planted_at DATETIME,
            planting_started_at DATETIME
        )
    """

    def _init_on(self, db_path, monkeypatch):
        engine = create_engine(f"sqlite:///{db_path}")
        monkeypatch.setattr(models, "engine", engine)
        models.init_db()
        return engine

    def _make_old_db(self, tmp_path, dates):
        db_path = tmp_path / "old.db"
        conn = sqlite3.connect(db_path)
        conn.execute(self.OLD_DAYS_SQL)
        for d in dates:
            conn.execute(
                "INSERT INTO days (date, status, diary_text) VALUES (?, 'planted', '日記')",
                (d,),
            )
        conn.commit()
        conn.close()
        return db_path

    def test_question_column_added_and_backfilled(self, tmp_path, monkeypatch):
        db_path = self._make_old_db(tmp_path, ["2026-08-05", "2026-08-19"])
        engine = self._init_on(db_path, monkeypatch)
        with engine.connect() as conn:
            rows = dict(
                conn.exec_driver_sql("SELECT date, question FROM days").fetchall()
            )
        # 回填值＝當時畫面上真正顯示過的那題（題庫自建立以來沒動過）
        assert rows["2026-08-05"] == questions.question_for(datetime.date(2026, 8, 5))
        assert rows["2026-08-19"] == questions.question_for(datetime.date(2026, 8, 19))

    def test_upgrade_is_idempotent_and_keeps_stamped_question(
        self, tmp_path, monkeypatch
    ):
        db_path = self._make_old_db(tmp_path, ["2026-08-05"])
        engine = self._init_on(db_path, monkeypatch)
        # 模擬「蓋章後題庫變了」：手動改掉存值，再跑一次升級不得覆寫
        with engine.begin() as conn:
            conn.exec_driver_sql("UPDATE days SET question = '蓋章的那題'")
        models.init_db()
        with engine.connect() as conn:
            (question,) = conn.exec_driver_sql("SELECT question FROM days").fetchone()
        assert question == "蓋章的那題"

    def test_fresh_db_gets_full_schema(self, tmp_path, monkeypatch):
        engine = self._init_on(tmp_path / "fresh.db", monkeypatch)
        with engine.connect() as conn:
            cols = {
                row[1] for row in conn.exec_driver_sql("PRAGMA table_info(days)")
            }
        assert "question" in cols
