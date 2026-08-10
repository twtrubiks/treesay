# 樹說（TreeSay）設計文件

> 本文件描述目前的實作。定位、體驗原則與反目標見
> `2026-07-24-treesay-uiux-positioning.md`；實作時不能踩的紅線見
> 專案根目錄 `CLAUDE.md`。

## 產品定位

**一個安靜聽你說、替你把一天收好的傾聽者。** 像傳訊息一樣把想法丟給「樹」，
白天隨手丟碎片，晚上按一個鍵，AI 一次整理成日記、判定情緒、寫下溫暖的回覆。

**單人本機使用，無登入。**

「本機」指的是**儲存與帳號**：日記存在自己的磁碟、不必註冊、沒有其他使用者。
AI 生成仍會把內容經 `claude -p` 送到 Anthropic——文件與 UI 文案都不得宣稱
「無雲端」或「不會離開這台電腦」。

樹知道的，就是使用者親口告訴它的。App 不自動讀取這台電腦上的任何東西。

命名「樹說」取「訴說」諧音——把心事說給樹聽。目錄與 DB 以 `treesay` 為名。

## 核心決策

| 決策 | 結論 |
|---|---|
| 使用情境 | 單人本機，沒有帳號、沒有其他使用者、沒有分享出口 |
| AI 引擎 | 本機 `claude -p`（走 Claude 訂閱，async subprocess，單次 timeout 240s） |
| 技術棧 | FastAPI ＋ SQLAlchemy ＋ SQLite；Vue 3 ＋ Vite ＋ TypeScript ＋ Pinia |
| 功能範圍 | 聊天寫日記、AI 代寫、樹回覆、情緒判斷、樹的長期記憶、月曆森林、補種、週報、今日問題、照片日記、完整匯出 |
| 互動節奏 | 結算式：白天只收集訊息不呼叫 AI，按「種下今天的樹」才一次生成 |
| 串流 | 不做 SSE。前端用樹生長動畫吸收 AI 等待，拿到完整結果後本地做打字機效果 |
| 週報 | lazy 生成：僅已結束的週可生成並永久快取；當週顯示「這週還在生長中」 |
| 切日點 | 本地時間凌晨 4:00——之前的訊息歸前一天；所有 datetime 存本地時間 |
| 補種窗口 | 2 天（昨天、前天）。刻意短，理由見下方「補種」 |

## 架構

```
瀏覽器 (Vue 3 SPA)
    │  HTTP (JSON / multipart)
FastAPI (uvicorn, 僅綁 127.0.0.1)
    │  async subprocess（timeout 240s）
claude -p --output-format json
    │
SQLite (treesay.db)  +  photos/ 照片目錄
```

前端 build 後的 `frontend/dist/` 由後端直接提供，跑起來只有一個 port（8000）。
資料落點預設 `~/.treesay/`，可用 `TREESAY_DATA_DIR` 覆寫。

```
treesay/
├── backend/
│   ├── main.py        # FastAPI 路由
│   ├── ai.py          # claude CLI 封裝（subprocess + JSON 解析 + 重試 + 用途檔次）
│   ├── models.py      # SQLAlchemy 模型、切日邏輯、資料目錄
│   ├── prompts.py     # 所有 prompt 模板（繁中、療癒語氣）
│   ├── questions.py   # 今日問題內建題庫（依日期定題，不耗 AI）
│   └── tests/         # pytest（mock subprocess，不真呼叫 claude）
└── frontend/          # Vue 3 + Vite + TS + Pinia（create-vue 鷹架）
    └── src/
        ├── views/         # 5 個頁面
        ├── components/    # EmotionTree（情緒樹 SVG）、TypeWriter
        ├── stores/        # today.ts（Pinia）
        └── api.ts / types.ts / emotions.ts / format.ts
```

Python 環境：自己開一個獨立的虛擬環境即可（venv、pyenv 都行），專案不綁環境名稱。

## 資料模型（SQLite / SQLAlchemy）

- `days`：date (unique)、status（`collecting`／`planting`／`planted`）、diary_text、
  emotion、tree_reply、planted_at、planting_started_at（防連點與逾期判定用）
- `messages`：day_id FK、content、photo_path (nullable)、created_at
- `memories`：content、source_date、created_at——樹長期記得的、關於使用者的事
- `weekly_reports`：week_start (unique)、content_json、created_at

情緒固定 7 種枚舉：`happy / calm / excited / tired / sad / anxious / angry`，
前端據此對應情緒樹 SVG。枚舉外的值 fallback 為 `calm`。

照片存 `photos/YYYY-MM-DD/<uuid>.<ext>`，DB 只記相對路徑，FastAPI 靜態服務。

`init_db()` 只建表不刪表：升級不動既有的表，某張表即使不再使用，裡面仍是使用者
寫下的東西，不該被一次升級默默清掉。

### 樹的記憶

記憶的抽取搭種樹的便車（plant 的 JSON 多回一個 `memory` 欄位），不另外呼叫 AI。
四個上限的目的不是省 token，是防走樣——記憶清單一旦長到讀不完，
「樹記得你」就退化成「樹存了一堆你的資料」：

| 常數 | 值 | 意義 |
|---|---|---|
| `PLANT_MEMORY_LIMIT` | 3 | 種一次樹最多記下幾件事 |
| `MEMORY_PROMPT_LIMIT` | 20 | 注入 prompt 的條數上限（取最新的） |
| `MEMORY_KEEP_LIMIT` | 30 | 週報整理後的保留上限 |
| `RECENT_DIARY_DAYS` / `LIMIT` / `CHARS` | 7 / 3 / 300 | 近期日記注入：目標日往前 7 天內最多 3 篇，每篇截 300 字 |

界線：注入的 prompt 必須帶「不追蹤進度、不對帳、不主動翻舊的難過」的約束；
補種以那一天為基準往回看，不能引用未來的日記。週報是記憶唯一的瘦身時機
（合併重複、放下過時），AI 回空清單視為可疑、不套用——樹不該一次忘掉所有事。
每一條記憶使用者在「記憶」頁看得到、刪得掉，刪了不會復活。

## AI 呼叫（`ai.py`）

無狀態單次 `claude -p`，prompt 要求輸出純 JSON。每個用途各自挑檔次——跑的是
使用者自己的訂閱額度，日記不該跟工作搶最貴的檔次：

| 用途 | 預設檔次 | 輸入 | 輸出 JSON |
|---|---|---|---|
| 種樹 `plant` | `claude-opus-5` / `medium` | 當天全部訊息＋照片路徑＋記憶＋近期日記＋`days_ago` | `{diary, emotion, tree_reply, memory[]}` |
| 週報 `weekly` | `claude-sonnet-5` / `medium` | 該週所有已種樹的日記＋現有記憶（0 篇則不呼叫） | `{good_things, bad_things, keywords, advice, memories[]}` |

- 覆寫：`TREESAY_PLANT_MODEL`／`TREESAY_PLANT_EFFORT`／`TREESAY_WEEKLY_MODEL`／
  `TREESAY_WEEKLY_EFFORT`。effort 打錯字會退回預設並在終端機提醒，不會變成種樹失敗
  （否則前端只看得到「樹睡著了」，使用者查不出是自己打錯字）。啟動時印一行實際生效的檔次。
- 照片：種樹 prompt 附照片檔路徑，開 `Read` 讓 Claude 看圖編入日記。
- **工具權限是排他白名單**：日記內容原樣進 prompt，等同不可信輸入。`--tools` 列舉
  這個 session 有哪些工具（預設空字串＝全關，看照片才開 `Read`），再加
  `--setting-sources ""` 不載入使用者的 `settings.json`、不繼承那裡的放行規則。
  不改用 `--permission-mode` 收斂——實測 `-p` 無頭模式下它擋不住工具，只有
  `--tools` 是 fail-closed 的。
- **輸出是雙層 JSON**：`--output-format json` 回的是 CLI envelope
  （`{"type": "result", "subtype": ..., "result": "..."}`）。先解析 envelope 並
  檢查 `subtype` 是否 success，再取 `result` 字串剝 code fence 後 `json.loads`。
- 穩健性：任一層解析失敗重試 1 次；再失敗回 502。

## API

```
GET    /api/today                    今日狀態＋訊息＋今日問題＋補種邀請
POST   /api/messages                 丟訊息（multipart，可附照片）
PATCH  /api/messages/{id}            改字（限今天、collecting）
DELETE /api/messages/{id}            收回（限今天、collecting）
POST   /api/today/plant              種下今天的樹 → AI 生成
POST   /api/days/{date}/plant        補種那天忘了按的樹（窗口內）
GET    /api/days?month=YYYY-MM       森林月曆（已種下的樹＋留了話沒收尾的日子）
GET    /api/days/{date}              單日詳情
GET    /api/memories                 樹記得的事
DELETE /api/memories/{id}            忘掉一件事
GET    /api/reports/{week_start}     週報（week_start 為週一；無快取則生成）
GET    /api/export                   完整匯出 JSON（日記、週報、記憶；照片只記檔名）
/photos/**                           照片靜態檔
```

其餘 `/api/**` 一律 404，擋在 SPA fallback 前面——避免打錯的 API 路徑拿到 200 HTML。
非 API 的未知網址（打錯的、過期的書籤）安靜導回首頁，不留空白畫面。

TypeScript 著力點：`frontend/src/types.ts` 為各 API request／response 定義 interface。

### 訊息可改可收回，但只到種樹前

修改與收回共用 `editable_message` 一條界線：**今天、`status == collecting`**。
打錯字改不了會讓人送出前多一分猶豫，而「要想好再說」正是這個 App 想拿掉的壓力；
種下之後日記是從這些話長出來的，事後改素材會讓日記對不上，那天的錯字也是那天的一部分。
不記錄編輯痕跡（沒有 `edited_at`、不標「已編輯」）——自己的日記沒有另一個人
需要知道你改過什麼。

### 補種

忘記按種樹的那天，`BACKFILL_WINDOW_DAYS = 2` 天內（昨天、前天）可從森林詳情頁
補種，之後過期——訊息仍讀得到，只是不再長樹。窗口刻意很短，兩個理由都不能放寬：

1. 任何一天都補得回來的話，森林就從「發生過的紀錄」變成可以填滿的成果，
   空格成了待辦事項——正是這個 App 最想避開的東西。
2. 窗口一旦跨週就會撞上週報快取：週報只生成已結束的週且永久快取，補種若晚於
   週報生成，那天會永遠不在週報裡（`test_window_stays_shorter_than_a_week` 釘住這條）。

補種的 prompt 帶 `days_ago`，樹的回覆要認得時間差，但不能責怪使用者沒準時回來。
首頁只遞一天的補種邀請（挑最早的，它最先過期），多天並列就成了待辦清單。

## 前端頁面（5 個 view）

1. **🌱 今日** `/`：樹（隨訊息數長大，此階段為中性樹——情緒種樹後才判定）＋聊天泡泡＋
   輸入框＋照片鈕；頂部「今日問題」卡片（依日期定題，重整不變，送出後退場）；
   種樹按鈕跟著一天的節奏，入夜且說過話才亮起（開著跨過 18:00 也會跟上）；
   種樹 → 全螢幕生長動畫 → 日記卡片＋樹回覆打字機；種完當天轉唯讀，
   不開放補寫（之後的想法留到明天）。頁面開著跨過凌晨 4:00 會自動翻到新的一天。
2. **🌳 森林** `/forest`：月曆網格（含星期表頭），每天一棵情緒樹 SVG；
   留了話沒收尾的日子是一道淡土壤痕跡，不標「未完成」、不計數。
3. **📖 單日詳情** `/forest/:date`：日記、樹的回覆、當天訊息；未種下的日子同樣
   顯示訊息，窗口內提供補種。
4. **📋 週報** `/report`：週選擇器＋報告卡片，僅已結束的週可點開，首次點開現場
   生成並快取；當週顯示「這週還在生長中 🌱」。
5. **🧠 記憶** `/memories`：樹記得的事，一條一條看得到、刪得掉。

視覺：溫暖手繪風、奶油底色＋大地色系、圓角卡片；樹用內嵌 SVG（自繪，
中性生長樹 × 階段 ＋ 7 情緒成樹——生長中情緒未定，不需 7×N 全矩陣）。

## 錯誤處理

1. 訊息即丟即存——AI 失敗不丟資料。
2. 種樹防連點——按下即轉 `planting` 並鎖按鈕；卡在 `planting` 超過
   `PLANTING_STALE_SECONDS = 300`（單次 timeout 240s ＋ 緩衝）視為失敗，可重按。
3. AI 錯誤以療癒文案呈現，不裸露技術錯誤：
   - 一般失敗：「樹睡著了，等等再來 🌙」
   - 找不到 CLI：「樹還沒連上森林 🌱 這台電腦找不到 claude 指令……」
   - 沒登入：「樹在等你開門 🌿 請在終端機執行 claude auth login……」
4. 啟動時檢查 claude CLI 是否可用並在終端機提示；沒有它仍可瀏覽既有日記，
   只是種樹與週報會失敗。

## 還沒做的想法

### 明確不做

- **逐則回應**：白天丟訊息不會即時得到回覆。零回應壓力是定位的一部分——
  逐則回應會把樹變成又一個等你回訊息的對象，AI 呼叫也從一天一次變 N 次。
- **連續紀錄／季節樹**：連續天數的變形，中斷一天就變成自責來源。
- **年度回顧＋情緒統計、趨勢圖、字數時數**：生產力儀表板，一天變成可比較的數字。
- **AI 扮演的陌生人回信、AI 角色即時聊天**：假社交，被識破時的傷害大於安慰。
- **自動讀取使用者的工作痕跡**（git commit、檔案異動等）當日記素材：樹知道的
  只能是使用者親口說的，「不評價」的前提是樹手上沒有可以拿來對帳的東西。
- **多 LLM 提供商設定頁**：零配置是體驗承諾。

### 留著的想法（不違反定位，只是沒做）

- **那年今天**：去年／上月同日的回顧卡，純 DB 查詢零 AI 成本。要做的話得先過
  一關：它很容易變成「你那時候比較有力氣」的對照。條件是安靜出現、不提醒、
  不並排比較。
- **語音輸入**（Web Speech API）：對想傾訴的人，說比打字自然。
- **種樹順便輸出 `keywords`**：JSON 多一個欄位，幾乎零成本，為「那年今天」
  與日後的搜尋鋪路——不是為了做 PKM。
- **PWA、深色模式**。

## 測試（成功標準）

1. `ai.py` 單元測試：JSON 解析各情境（envelope、error subtype、code fence、
   壞 JSON、枚舉外情緒），不真呼叫 claude。
2. API 整合測試（pytest ＋ mock subprocess）：丟訊息→種樹→日記入庫；
   凌晨 4:00 前訊息歸前一天；`planting` 中重複種樹被擋；補種窗口邊界；
   記憶注入與瘦身；週報快取命中。
3. 前端以手動走完一輪流程驗收。

```bash
pip install -r backend/requirements-dev.txt
cd backend && pytest
```

## 靈感來源

「像傳訊息一樣寫日記、AI 幫你整理」這個核心動作來自
[Mymory](https://apps.apple.com/tw/app/id6503250569)（非官方，不沿用其名稱），
起點是[這篇使用心得](https://vocus.cc/article/68394796fd89780001f72719)。
其餘設計獨立。
