// fetch 封裝：錯誤時把後端的療癒文案（detail）帶出來

import type {
  DayDetail,
  ForestDay,
  MemoryOut,
  MessageOut,
  ReportResponse,
  TodayResponse,
} from './types'

export class ApiError extends Error {
  status: number

  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

const FALLBACK_MESSAGE = '樹睡著了，等等再來 🌙'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response
  try {
    res = await fetch(path, init)
  } catch {
    throw new ApiError(0, FALLBACK_MESSAGE)
  }
  if (!res.ok) {
    let detail = FALLBACK_MESSAGE
    try {
      const body = await res.json()
      if (typeof body.detail === 'string') detail = body.detail
    } catch {
      // 非 JSON 錯誤內容，用預設文案
    }
    throw new ApiError(res.status, detail)
  }
  return res.json()
}

export const api = {
  getToday: () => request<TodayResponse>('/api/today'),

  sendMessage: (content: string, photo?: File) => {
    const form = new FormData()
    form.append('content', content)
    if (photo) form.append('photo', photo)
    return request<MessageOut>('/api/messages', { method: 'POST', body: form })
  },

  // 改一句說錯的話（後端限今天、樹還沒收下；只動文字，照片要換得收回重傳）
  updateMessage: (id: number, content: string) =>
    request<MessageOut>(`/api/messages/${id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    }),

  deleteMessage: (id: number) =>
    request<{ ok: boolean }>(`/api/messages/${id}`, { method: 'DELETE' }),

  plant: () => request<DayDetail>('/api/today/plant', { method: 'POST' }),

  // 補種那天忘了按的樹（後端限定窗口內）
  plantDay: (date: string) =>
    request<DayDetail>(`/api/days/${date}/plant`, { method: 'POST' }),

  getMonth: (month: string) =>
    request<ForestDay[]>(`/api/days?month=${encodeURIComponent(month)}`),

  getDay: (date: string) => request<DayDetail>(`/api/days/${date}`),

  getReport: (weekStart: string) => request<ReportResponse>(`/api/reports/${weekStart}`),

  getMemories: () => request<MemoryOut[]>('/api/memories'),

  // 讓樹放下一件事（刪了就是刪了，之後的種樹與週報都不會再看到）
  deleteMemory: (id: number) =>
    request<{ ok: boolean }>(`/api/memories/${id}`, { method: 'DELETE' }),
}
