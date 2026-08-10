// 日期／時間顯示格式

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

export function parseDate(dateStr: string): Date {
  const [y, m, d] = dateStr.split('-').map(Number)
  return new Date(y!, m! - 1, d!)
}

export function formatDateLabel(dateStr: string): string {
  const d = parseDate(dateStr)
  return `${d.getMonth() + 1}月${d.getDate()}日 星期${WEEKDAYS[d.getDay()]}`
}

export function formatTime(iso: string): string {
  const d = new Date(iso)
  const h = d.getHours()
  const mm = String(d.getMinutes()).padStart(2, '0')
  if (h < 12) return `上午 ${h === 0 ? 12 : h}:${mm}`
  return `下午 ${h === 12 ? 12 : h - 12}:${mm}`
}

export function greetingForNow(): string {
  const h = new Date().getHours()
  if (h >= 4 && h < 11) return '早安，今天想說些什麼？'
  if (h >= 11 && h < 18) return '午安，今天過得怎麼樣？'
  return '晚安，今天辛苦了嗎？'
}

// 入夜＝問候語進入「晚安」的時段（凌晨 4:00 切日前都算今晚）
export function isEveningNow(): boolean {
  const h = new Date().getHours()
  return h >= 18 || h < 4
}

// 現在所屬的日記日期，與後端 effective_date 同一條規則：減 4 小時取日期。
// 單人本機 app，前後端同一個時鐘，前端照抄這條規則不會有偏移
export function effectiveDateNow(): string {
  const d = new Date(Date.now() - 4 * 60 * 60 * 1000)
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}
