"""SQLAlchemy 模型與資料庫初始化。

所有 datetime 一律存本地時間；切日點為凌晨 4:00（之前的訊息歸前一天）。
"""

from __future__ import annotations

import datetime
import os
from pathlib import Path

from sqlalchemy import ForeignKey, String, Text, create_engine
from sqlalchemy.orm import (
    DeclarativeBase,
    Mapped,
    mapped_column,
    relationship,
    sessionmaker,
)

import questions

BASE_DIR = Path(__file__).resolve().parent
DEFAULT_DATA_DIR = Path.home() / ".treesay"


def resolve_data_dir() -> Path:
    """決定日記資料的落點。

    預設放在家目錄，避免 `git clean -xdf` 之類的操作連同不可再生的
    日記一起清掉。要換位置請設 TREESAY_DATA_DIR。
    """
    env = os.environ.get("TREESAY_DATA_DIR")
    if env:
        return Path(env).expanduser()
    return DEFAULT_DATA_DIR


DATA_DIR = resolve_data_dir()
DB_PATH = DATA_DIR / "treesay.db"
PHOTOS_DIR = DATA_DIR / "photos"

EMOTIONS = {"happy", "calm", "excited", "tired", "sad", "anxious", "angry"}
DEFAULT_EMOTION = "calm"

DAY_CUTOVER_HOUR = 4


def now_local() -> datetime.datetime:
    return datetime.datetime.now()


def effective_date(dt: datetime.datetime) -> datetime.date:
    """依凌晨 4:00 切日點計算 dt 所屬的日記日期。"""
    return (dt - datetime.timedelta(hours=DAY_CUTOVER_HOUR)).date()


class Base(DeclarativeBase):
    pass


class Day(Base):
    __tablename__ = "days"

    id: Mapped[int] = mapped_column(primary_key=True)
    date: Mapped[datetime.date] = mapped_column(unique=True, index=True)
    status: Mapped[str] = mapped_column(String(20), default="collecting")
    # 那天樹問的問題。建立這一列時就蓋章存下——題庫依「日期 mod 題數」定題，
    # 往題庫加一題，所有日期算出來的題目就會位移；問過什麼是那一天的事實，
    # 跟 diary_text 一樣落盤，不在讀取時重算。
    question: Mapped[str | None] = mapped_column(Text)
    diary_text: Mapped[str | None] = mapped_column(Text)
    emotion: Mapped[str | None] = mapped_column(String(20))
    tree_reply: Mapped[str | None] = mapped_column(Text)
    planted_at: Mapped[datetime.datetime | None]
    # 種樹防連點：卡在 planting 超過 3 分鐘視為失敗、允許重按，據此欄位判斷
    planting_started_at: Mapped[datetime.datetime | None]

    messages: Mapped[list[Message]] = relationship(
        back_populates="day", cascade="all, delete-orphan", order_by="Message.id"
    )


class Message(Base):
    __tablename__ = "messages"

    id: Mapped[int] = mapped_column(primary_key=True)
    day_id: Mapped[int] = mapped_column(ForeignKey("days.id"), index=True)
    content: Mapped[str] = mapped_column(Text)
    photo_path: Mapped[str | None] = mapped_column(String(255))
    created_at: Mapped[datetime.datetime] = mapped_column(default=now_local)

    day: Mapped[Day] = relationship(back_populates="messages")


class Memory(Base):
    """樹的長期記憶：關於使用者本人、會持續一段時間的事。

    種樹時由 AI 從當天訊息順手抽出（不另外呼叫），週報生成時整理
    （合併重複、放下過時的）。內容使用者看得到、刪得掉——看不見的
    記憶比沒有記憶更可怕。
    """

    __tablename__ = "memories"

    id: Mapped[int] = mapped_column(primary_key=True)
    content: Mapped[str] = mapped_column(Text)
    # 樹記下這件事的那天（種樹的日記日期；週報整理合併出的新條目則是整理當天）
    source_date: Mapped[datetime.date] = mapped_column(index=True)
    created_at: Mapped[datetime.datetime] = mapped_column(default=now_local)


class WeeklyReport(Base):
    __tablename__ = "weekly_reports"

    id: Mapped[int] = mapped_column(primary_key=True)
    week_start: Mapped[datetime.date] = mapped_column(unique=True, index=True)
    content_json: Mapped[str] = mapped_column(Text)
    created_at: Mapped[datetime.datetime] = mapped_column(default=now_local)


engine = create_engine(
    f"sqlite:///{DB_PATH}", connect_args={"check_same_thread": False}
)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def init_db() -> None:
    """建表與升級。只新增不刪除。

    升級不動既有的表：某張表即使不再使用，裡面仍是使用者寫下的東西，
    不該被一次升級默默清掉。留著不用，要撈的人用 sqlite3 直接查得到。
    """
    PHOTOS_DIR.mkdir(parents=True, exist_ok=True)
    Base.metadata.create_all(engine)
    _upgrade_days_table()


def _upgrade_days_table() -> None:
    """幫既有的 days 表補上後來新增的欄位。

    create_all 只建缺的表、不會幫舊表加欄位，老資料庫起新程式第一個查詢
    就會炸 no such column，所以這裡自己補。純加法且冪等：缺欄位才 ALTER、
    question 只回填還是 NULL 的列，全新資料庫走到這裡什麼都不會發生。

    question 回填用當下的 question_for()——趁題庫還沒變動，把「那天問過
    什麼」凍結成事實；之後題庫再怎麼長，歷史的問題都不會跟著位移。
    """
    with engine.begin() as conn:
        cols = {row[1] for row in conn.exec_driver_sql("PRAGMA table_info(days)")}
        if "question" not in cols:
            conn.exec_driver_sql("ALTER TABLE days ADD COLUMN question TEXT")
        rows = conn.exec_driver_sql(
            "SELECT id, date FROM days WHERE question IS NULL"
        ).fetchall()
        for day_id, date_str in rows:
            conn.exec_driver_sql(
                "UPDATE days SET question = ? WHERE id = ?",
                (questions.question_for(datetime.date.fromisoformat(date_str)), day_id),
            )
