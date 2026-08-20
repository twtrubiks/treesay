// 各 API request／response 的型別定義（對應 backend/main.py）

export type Emotion = 'happy' | 'calm' | 'excited' | 'tired' | 'sad' | 'anxious' | 'angry'

export type DayStatus = 'collecting' | 'planting' | 'planted'

export interface MessageOut {
  id: number
  content: string
  photo_url: string | null
  created_at: string
}

export interface DayDetail {
  date: string
  status: DayStatus
  // 那天樹問的問題（Day 誕生時蓋章存檔，不隨題庫變動位移）
  question: string
  diary: string | null
  emotion: Emotion | null
  tree_reply: string | null
  // 種樹順手抽出的關鍵詞。先存不顯示，為「那年今天」與日後搜尋鋪路；
  // 落盤前種的樹是空陣列。永遠不做次數統計或標籤雲
  keywords: string[]
  planted_at: string | null
  messages: MessageOut[]
  // 那一天現在還能不能種樹（今天隨時可以，過去的只在補種窗口內）
  can_plant: boolean
}

export interface TodayResponse extends DayDetail {
  // 補種窗口內留了話卻沒收尾、最早的一天（沒有就 null）——首頁遞補種邀請用
  backfill_candidate: string | null
}

export interface ForestDay {
  date: string
  // 未種下的日子沒有情緒，只有那天留下的話
  emotion: Emotion | null
  status: DayStatus
}

export interface MemoryOut {
  id: number
  content: string
  // 樹記下這件事的那天
  source_date: string
  created_at: string
}

export interface WeeklyReportContent {
  good_things: string[]
  bad_things: string[]
  keywords: string[]
  advice: string
}

export type ReportResponse =
  | { status: 'growing' | 'empty'; message: string }
  | { status: 'ready'; report: WeeklyReportContent; generated_at: string }
