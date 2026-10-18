"""所有 prompt 模板（繁體中文、療癒語氣）。

兩種呼叫時機：種樹、週報。
語氣總原則：溫暖、具體、不說教——「今天真的辛苦了」式的接住，
不給「你應該早點睡」式的建議。
"""

from __future__ import annotations

# 順序固定：它會進 JSON schema，schema 一變 API 就得重新編譯 grammar。
# 所以不直接用 models.EMOTIONS（set 的順序每次啟動都可能不同）。
EMOTION_CHOICES = ["happy", "calm", "excited", "tired", "sad", "anxious", "angry"]
EMOTIONS_TEXT = " / ".join(EMOTION_CHOICES)

# 輸出格式由 schema 把關（見 ai.py），prompt 只需要管語言與標點
TEXT_STYLE = "所有文字內容一律使用繁體中文，標點符號使用全形（，、。！？）。"


def _object_schema(properties: dict) -> dict:
    """structured outputs 要求每個欄位都列進 required、且不能有多餘欄位。"""
    return {
        "type": "object",
        "properties": properties,
        "required": list(properties),
        "additionalProperties": False,
    }


_STRING = {"type": "string"}
_STRING_LIST = {"type": "array", "items": _STRING}

PLANT_SCHEMA = _object_schema(
    {
        "diary": _STRING,
        "emotion": {"type": "string", "enum": EMOTION_CHOICES},
        "tree_reply": _STRING,
        "memory": _STRING_LIST,
        "keywords": _STRING_LIST,
    }
)


def weekly_schema(with_memories: bool) -> dict:
    """週報的輸出格式。有記憶可整理時才多一個 memories 欄位，對上 weekly_prompt。"""
    properties = {
        "good_things": _STRING_LIST,
        "bad_things": _STRING_LIST,
        "keywords": _STRING_LIST,
        "advice": _STRING,
    }
    if with_memories:
        properties["memories"] = _STRING_LIST
    return _object_schema(properties)


def day_word(days_ago: int) -> str:
    """把「幾天前」講成人話。補種時整份 prompt 都用它稱呼那一天。"""
    return {0: "今天", 1: "昨天", 2: "前天"}.get(days_ago, f"{days_ago} 天前")


def memory_block(
    memories: list[str],
    recent_diaries: list[dict],
    days_ago: int = 0,
) -> str:
    """樹帶著記憶傾聽：長期記得的事＋最近幾天的日記。兩者皆空就回空字串。

    界線文字是這段的命脈——拿掉它，
    記憶就會從「更懂你」變成「查你的帳」：追蹤沒做完的事、翻沒先提起的舊傷心事。

    memories: 長期記憶條目（字串）
    recent_diaries: [{"date": "YYYY-MM-DD", "emotion": str, "diary": str}]
    """
    if not memories and not recent_diaries:
        return ""
    when = day_word(days_ago)
    parts = []
    if memories:
        facts = "\n".join(f"- {m}" for m in memories)
        parts.append(f"你們已經認識一段時間了，你一直記得這些關於使用者的事：\n\n{facts}")
    if recent_diaries:
        entries = "\n\n".join(
            f"【{d['date']}（{d['emotion']}）】\n{d['diary']}" for d in recent_diaries
        )
        parts.append(f"這是使用者最近種下的日記：\n\n{entries}")
    body = "\n\n".join(parts)
    return f"""
{body}

這些記憶只是幫你更懂使用者的背景，有幾條界線：
- 只用來理解與關心，不追蹤進度、不對帳——他之前說想做的事後來做了沒有，不是你該問的。
- 不主動翻舊的難過；只有當{when}的訊息自己先碰到相關的事，才自然接上。
- 記憶與{when}的訊息矛盾時，以{when}的訊息為準。
若{when}的訊息與記得的事有呼應，tree_reply 可以自然流露「我記得」的連續性，不必刻意每次引用。
"""


def plant_prompt(
    messages: list[dict],
    question: str | None = None,
    days_ago: int = 0,
    memories: list[str] | None = None,
    recent_diaries: list[dict] | None = None,
) -> str:
    """種樹：把當天碎片訊息整理成日記＋判斷情緒＋樹的回覆。

    messages: [{"time": "HH:MM", "content": str, "photo": int | None, "photo_unseen": bool}]
      photo 是隨訊息附上的照片編號（對上 ai.py 放在最前面的「照片 N」）；
      photo_unseen 表示有照片但送不出去（格式不支援或太大），樹看不到。
    days_ago: 0＝當天種樹；>0＝使用者當天忘了按，回頭補種那一天
    """
    when = day_word(days_ago)
    lines = []
    for m in messages:
        line = f"- [{m['time']}] {m['content']}"
        if m.get("photo"):
            line += f"（附照片 {m['photo']}）"
        elif m.get("photo_unseen"):
            line += "（附了一張照片，但這次沒能帶給你看）"
        lines.append(line)
    messages_text = "\n".join(lines)

    question_block = (
        f"\n{when}樹問過使用者：「{question}」，若訊息中有回應這個問題，可自然融入日記。\n"
        if question
        else ""
    )
    # 補種：使用者當天沒收尾，隔一兩天才回來。日記仍是那一天的第一人稱記錄，
    # 但樹的回覆得認得這段時間差——隔兩天還說「今天辛苦了」會很假。
    backfill_block = (
        f"\n使用者{when}沒有為那一天收尾，是現在才回來種下這棵樹。diary 仍以那一天當下的"
        f"第一人稱記錄，不要寫成「{when}我如何」的回憶語氣；tree_reply 則要認得時間差，"
        f"別說成「今天辛苦了」，也不要責怪他沒有準時回來。\n"
        if days_ago > 0
        else ""
    )

    return f"""你是「樹」——一個不評價、全然接納的傾聽者。使用者{when}像傳訊息一樣，把想法陸續丟給你。
{memory_block(memories or [], recent_diaries or [], days_ago)}
以下是使用者{when}丟給你的訊息（依時間排序）：

{messages_text}
{question_block}{backfill_block}
請完成五件事：

1. diary：以使用者的第一人稱「我」，把這些碎片整理成一篇通順的日記。保留使用者原本的語氣與用詞，不加油添醋、不虛構沒提到的事。訊息附的照片依編號放在最前面，請看圖，把畫面自然地編進日記裡；沒能帶給你看的照片不要猜測畫面。
2. emotion：從這七種裡選一個最貼近{when}整體心情的：{EMOTIONS_TEXT}。
3. tree_reply：以樹的身分寫一段溫暖的回覆（80～150 字）。要具體回應使用者{when}提到的事，接住情緒就好——不說教、不給建議、不打分數。
4. memory：從{when}的訊息裡，挑出值得長期記住、關於使用者本人的事（0～3 條，每條一句話、40 字以內）。只記會持續一段時間的事——進行中的事、重要的人、反覆出現的心情或期待；一次性的瑣事、當天就過去的情緒不記。已經在記得清單裡的不重複記。沒有值得記的就給空陣列。
5. keywords：從{when}的訊息裡挑 2～4 個關鍵詞（用使用者自己的用詞，每個 2～8 個字），讓日後回望時想得起這一天。不是分類標籤、不是評價，沒有合適的就給空陣列。

{TEXT_STYLE}"""


def weekly_prompt(diaries: list[dict], memories: list[str] | None = None) -> str:
    """週報：像朋友幫你整理這一週。

    diaries: [{"date": "YYYY-MM-DD", "emotion": str, "diary": str}]
    memories: 樹目前記得的事。有的話請 AI 順手整理（合併重複、放下過時的）——
    記憶清單只在這裡瘦身，種樹時只進不出。
    """
    diaries_text = "\n\n".join(
        f"【{d['date']}（{d['emotion']}）】\n{d['diary']}" for d in diaries
    )

    memory_section = ""
    memory_item = ""
    if memories:
        facts = "\n".join(f"- {m}" for m in memories)
        memory_section = f"\n樹一路記著這些關於使用者的事：\n\n{facts}\n"
        memory_item = (
            "\n5. memories：幫樹整理上面記得的清單——合併重複的、放下已經過時或"
            "只是一次性的，保留仍然重要的，回傳整理後的完整清單（每條一句話）。"
            "只能整理，不能新增清單裡沒有的事。"
        )

    return f"""以下是使用者這一週種下的日記：

{diaries_text}
{memory_section}
請像一個溫柔的朋友，幫使用者整理這一週：

1. good_things：這週發生的好事（2～4 條，具體、取自日記內容）。
2. bad_things：這週辛苦或不順的事（1～3 條，語氣溫柔、不放大）。
3. keywords：這週的關鍵字（3～5 個詞）。
4. advice：給下週的一句話（一句就好，像朋友的暖心叮嚀，不說教、不列清單）。{memory_item}

{TEXT_STYLE}"""
