// 畫面要不要動，整個 App 只問這裡一個地方。
// 預設「開」：這是單人本機 App，作業系統關桌面動畫不代表想關掉這棵樹
// （GNOME 的 enable-animations 會被瀏覽器直接轉成 prefers-reduced-motion）。
// 真的需要安靜畫面的人在側邊欄切成「關」，或切「跟系統」讓瀏覽器決定。
// 選擇記在這個瀏覽器的 localStorage，跟「誰在看」走，不進資料庫。

import { computed, ref } from 'vue'

export type MotionPref = 'on' | 'off' | 'system'

export const MOTION_OPTIONS: { value: MotionPref; label: string }[] = [
  { value: 'on', label: '開' },
  { value: 'off', label: '關' },
  { value: 'system', label: '跟系統' },
]

const STORAGE_KEY = 'treesay.motion'
const DEFAULT_PREF: MotionPref = 'on'

function isPref(v: unknown): v is MotionPref {
  return v === 'on' || v === 'off' || v === 'system'
}

function readPref(): MotionPref {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return isPref(v) ? v : DEFAULT_PREF
  } catch {
    return DEFAULT_PREF
  }
}

// 開發時熱更新會重跑這個檔案，先拆掉上一份掛的監聽，不然一個個疊上去
// （這個檔案不自己 accept，hot.dispose 不會被呼叫，只能靠 hot.data 交接）
import.meta.hot?.data.listeners?.abort()
const listeners = new AbortController()
if (import.meta.hot) import.meta.hot.data.listeners = listeners

const query = window.matchMedia('(prefers-reduced-motion: reduce)')
const systemReduce = ref(query.matches)
query.addEventListener('change', (e) => (systemReduce.value = e.matches), {
  signal: listeners.signal,
})

export const motionPref = ref<MotionPref>(readPref())

// 同一個瀏覽器開著好幾個分頁時，一邊切了另一邊跟著變（key 為 null 是整個 storage 被清掉）
window.addEventListener(
  'storage',
  (e) => {
    if (e.key === STORAGE_KEY || e.key === null) motionPref.value = readPref()
  },
  { signal: listeners.signal },
)

export function setMotionPref(pref: MotionPref) {
  motionPref.value = pref
  try {
    localStorage.setItem(STORAGE_KEY, pref)
  } catch {
    // 存不進去就只在這次開著的頁面生效
  }
}

// true ＝ 畫面安靜：不擺動、不呼吸、不來訪、捲動不平滑。
// 元件各自把它綁成 class（如 `.tree.quiet`）再寫 CSS，不用 @media——
// Vue scoped 樣式裡 `:global(html.x) .y` 會被編譯成只剩 `html.x`，掛 <html> class 行不通
export const reduceMotion = computed(() => {
  switch (motionPref.value) {
    case 'on':
      return false
    case 'off':
      return true
    case 'system':
      return systemReduce.value
  }
})
