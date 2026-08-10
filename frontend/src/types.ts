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
  diary: string | null
  emotion: Emotion | null
  tree_reply: string | null
  planted_at: string | null
  messages: MessageOut[]
  // 那一天現在還能不能種樹（今天隨時可以，過去的只在補種窗口內）
  can_plant: boolean
}

export interface TodayResponse extends DayDetail {
  question: string
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
