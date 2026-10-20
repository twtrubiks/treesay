// 樹活在同一個世界：樣貌隨真實的季節與時段走。
// 只看系統時鐘，不看使用者的任何資料——這是環境感，不是狀態，
// 永遠不要把訊息數、情緒、天數接進來。

import { ref } from 'vue'

import type { EmotionMeta } from './emotions'
import { isEveningNow } from './format'

export type Season = 'spring' | 'summer' | 'autumn' | 'winter'

// 偶爾來訪的小東西：春天花瓣、夏天白日飛絮／夜裡螢火蟲、秋天落葉、冬天雪
export type Visitor = 'petal' | 'fluff' | 'firefly' | 'leaf' | 'snow'

export interface Sky {
  season: Season
  night: boolean
}

// 北半球、以月份切：3～5 春、6～8 夏、9～11 秋、其餘冬
export function seasonFor(d: Date): Season {
  const m = d.getMonth() + 1
  if (m >= 3 && m <= 5) return 'spring'
  if (m >= 6 && m <= 8) return 'summer'
  if (m >= 9 && m <= 11) return 'autumn'
  return 'winter'
}

// 「夜裡」跟問候語進入「晚安」、種樹按鈕亮起是同一條線（18:00 到切日前），
// 畫面上三件事一起入夜，不另立一個時間
function skyNow(): Sky {
  return { season: seasonFor(new Date()), night: isEveningNow() }
}

// 整個畫面共用這一份、一個節拍：各自每分鐘對一次的話，開著跨過 18:00 時
// 種樹按鈕和樹最多會差快一分鐘才入夜
export const sky = ref<Sky>(skyNow())
// 開發時熱更新會重跑這個檔案，先收掉上一份留下的計時器，不然一個個疊上去
// （這個檔案不自己 accept，hot.dispose 不會被呼叫，只能靠 hot.data 交接）
clearInterval(import.meta.hot?.data.skyTimer)
const skyTimer = setInterval(() => (sky.value = skyNow()), 60_000)
if (import.meta.hot) import.meta.hot.data.skyTimer = skyTimer

// 四季的中性樹。同一套圓圈樹只換色：春天嫩葉帶一朵花、夏天濃綠、
// 秋天轉黃、冬天葉色淡下去、積一點白。明度都落在情緒色票同一區間。
// 大葉（c1）一律留在綠色系，季節的顏色只放在小葉上：情緒樹是三片同色、
// 色票裡沒有綠，這樣才不會被讀成某種情緒——整棵橘的秋天像「期待」、
// 整棵青的冬天像「平靜」，還沒說話就像被判了心情
export const SEASON_TREE: Record<Season, EmotionMeta> = {
  spring: {
    label: '生長中',
    c1: 'oklch(77% 0.11 128)',
    c2: 'oklch(85% 0.08 120)',
    c3: 'oklch(85% 0.07 350)',
  },
  summer: {
    label: '生長中',
    c1: 'oklch(66% 0.12 145)',
    c2: 'oklch(74% 0.11 140)',
    c3: 'oklch(58% 0.12 150)',
  },
  autumn: {
    label: '生長中',
    c1: 'oklch(72% 0.10 118)',
    c2: 'oklch(81% 0.12 82)',
    c3: 'oklch(63% 0.13 45)',
  },
  winter: {
    label: '生長中',
    c1: 'oklch(72% 0.04 150)',
    c2: 'oklch(94% 0.008 230)',
    c3: 'oklch(64% 0.045 155)',
  },
}

export function visitorFor(sky: Sky): Visitor {
  switch (sky.season) {
    case 'spring':
      return 'petal'
    case 'summer':
      return sky.night ? 'firefly' : 'fluff'
    case 'autumn':
      return 'leaf'
    case 'winter':
      return 'snow'
  }
}

// 來訪者的顏色與方向。rise 的往上飄（飛絮、螢火蟲），其餘往下落。
// 花瓣兩頭顏色不同，由 EmotionTree 的 CSS 漸層畫，這裡不給單色
export const VISITOR_META: Record<Visitor, { color?: string; rise: boolean }> = {
  petal: { rise: false },
  fluff: { color: 'oklch(95% 0.012 90)', rise: true },
  firefly: { color: 'oklch(90% 0.14 95)', rise: true },
  leaf: { color: 'oklch(68% 0.13 50)', rise: false },
  snow: { color: 'oklch(97% 0.005 220)', rise: false },
}
