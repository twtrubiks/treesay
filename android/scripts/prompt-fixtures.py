#!/usr/bin/env python3
"""用網頁版 backend/prompts.py 產生 Android 版 PromptsTest 的對照檔。

輸入是 app/src/test/resources/web-prompts/cases.json，輸出放在同一個資料夾：
  expected/<case>.txt  網頁版產生的 prompt
  schemas.json         網頁版的 JSON schema
  source.sha256        產生時 prompts.py 的 hash；網頁版改了 prompt，PromptsTest 會要你重跑這支

改了 backend/prompts.py 之後，在 repo 根目錄執行：
  python3 android/scripts/prompt-fixtures.py
再照著把 Prompts.kt 改到 PromptsTest 通過。只用標準函式庫，不需要網頁版的虛擬環境。
"""

import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
OUT = ROOT / "android/app/src/test/resources/web-prompts"

sys.path.insert(0, str(BACKEND))
import prompts  # noqa: E402

cases = json.loads((OUT / "cases.json").read_text(encoding="utf-8"))
expected = OUT / "expected"
expected.mkdir(exist_ok=True)

for c in cases["plant"]:
    text = prompts.plant_prompt(
        c["messages"],
        question=c["question"],
        days_ago=c["days_ago"],
        memories=c["memories"],
        recent_diaries=c["recent_diaries"],
    )
    (expected / f"{c['name']}.txt").write_text(text, encoding="utf-8")

for c in cases["weekly"]:
    text = prompts.weekly_prompt(c["diaries"], memories=c["memories"])
    (expected / f"{c['name']}.txt").write_text(text, encoding="utf-8")

schemas = {
    "plant": prompts.PLANT_SCHEMA,
    "weekly_with_memories": prompts.weekly_schema(with_memories=True),
    "weekly_without_memories": prompts.weekly_schema(with_memories=False),
}
(OUT / "schemas.json").write_text(
    json.dumps(schemas, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)

digest = hashlib.sha256((BACKEND / "prompts.py").read_bytes()).hexdigest()
(OUT / "source.sha256").write_text(digest + "\n", encoding="utf-8")
print(f"寫好了 {OUT.relative_to(ROOT)}（prompts.py {digest[:12]}）")
