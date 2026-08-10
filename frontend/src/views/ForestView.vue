<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { api } from '@/api'
import EmotionTree from '@/components/EmotionTree.vue'
import { EMOTION_META } from '@/emotions'
import type { Emotion, ForestDay } from '@/types'

const router = useRouter()

const now = new Date()
const year = ref(now.getFullYear())
const month = ref(now.getMonth() + 1)
const days = ref<ForestDay[]>([])
const failed = ref(false)

const MONTH_NAMES = [
  '一', '二', '三', '四', '五', '六', '七', '八', '九', '十', '十一', '十二',
]

// 對齊格子的排法：leading 以 getDay() 計算，同樣週日起始
const WEEKDAY_NAMES = ['日', '一', '二', '三', '四', '五', '六']

const monthKey = computed(
  () => `${year.value}-${String(month.value).padStart(2, '0')}`,
)
const title = computed(() => `${MONTH_NAMES[month.value - 1]}月的森林`)
// 只數真的種下的樹——沒收尾的日子也在清單裡，但它們不是樹
const treeCount = computed(
  () => days.value.filter((d) => d.status === 'planted').length,
)
const isCurrentMonth = computed(
  () => year.value === now.getFullYear() && month.value === now.getMonth() + 1,
)

interface Cell {
  day: number | null
  date: string | null
  emotion: Emotion | null
  // 留了話但沒種下樹的日子——回得去，但不當成一格成績
  stirred: boolean
}

const EMPTY_CELL: Cell = { day: null, date: null, emotion: null, stirred: false }

const cells = computed<Cell[]>(() => {
  const byDate = new Map(days.value.map((d) => [d.date, d]))
  const first = new Date(year.value, month.value - 1, 1)
  const daysInMonth = new Date(year.value, month.value, 0).getDate()
  const leading = first.getDay() // 週日起始
  const result: Cell[] = []
  for (let i = 0; i < leading; i++) result.push({ ...EMPTY_CELL })
  for (let d = 1; d <= daysInMonth; d++) {
    const date = `${monthKey.value}-${String(d).padStart(2, '0')}`
    const found = byDate.get(date)
    result.push({
      day: d,
      date,
      emotion: found?.status === 'planted' ? found.emotion : null,
      stirred: !!found && found.status !== 'planted',
    })
  }
  while (result.length % 7 !== 0) result.push({ ...EMPTY_CELL })
  return result
})

async function load() {
  failed.value = false
  try {
    days.value = await api.getMonth(monthKey.value)
  } catch {
    days.value = []
    failed.value = true
  }
}

function prevMonth() {
  if (month.value === 1) {
    month.value = 12
    year.value -= 1
  } else {
    month.value -= 1
  }
}

function nextMonth() {
  if (isCurrentMonth.value) return
  if (month.value === 12) {
    month.value = 1
    year.value += 1
  } else {
    month.value += 1
  }
}

function openDay(cell: Cell) {
  if (cell.date && (cell.emotion || cell.stirred)) router.push(`/forest/${cell.date}`)
}

onMounted(load)
watch(monthKey, load)
</script>

<template>
  <div class="page">
    <header class="header">
      <div>
        <div class="title-row">
          <button class="month-nav" title="上個月" @click="prevMonth">‹</button>
          <div class="title">{{ title }}</div>
          <button
            class="month-nav"
            :class="{ hidden: isCurrentMonth }"
            title="下個月"
            @click="nextMonth"
          >
            ›
          </button>
        </div>
        <div class="subtitle">
          {{
            failed
              ? '樹睡著了，等等再來 🌙'
              : treeCount > 0
                ? `這個月，你在這裡留下了 ${treeCount} 棵樹。`
                : '這個月的土壤還很安靜。'
          }}
        </div>
      </div>
      <div class="legend">
        <div v-for="(meta, key) in EMOTION_META" :key="key" class="legend-item">
          <div class="legend-dot" :style="{ background: meta.c1 }"></div>
          <div class="legend-label">{{ meta.label }}</div>
        </div>
      </div>
    </header>

    <div class="weekdays">
      <div v-for="name in WEEKDAY_NAMES" :key="name" class="weekday">{{ name }}</div>
    </div>

    <div class="grid">
      <div
        v-for="(cell, i) in cells"
        :key="i"
        class="cell"
        :class="{ clickable: !!cell.emotion || cell.stirred }"
        @click="openDay(cell)"
      >
        <template v-if="cell.day">
          <div class="cell-day">{{ cell.day }}</div>
          <div class="cell-body">
            <EmotionTree v-if="cell.emotion" :emotion="cell.emotion" :width="28" />
            <div v-else class="soil" :class="{ stirred: cell.stirred }"></div>
          </div>
        </template>
      </div>
    </div>

    <!-- 空著的格子最容易被讀成待辦。這行字常駐、不指名任何一天——
         只有某個月有空格時才出現的話，它就變成「你漏了」的提示，正好相反。
         那天沒說話就補不了訊息（訊息一律寫進今天），但事情永遠說得出口 -->
    <div class="footnote">空著的格子不用補。想起哪一天的事，今天說給樹聽就好 🌱</div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding: 52px 60px;
}

.header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 36px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.title {
  font: 600 33px var(--serif);
  color: var(--ink);
}

.month-nav {
  font: 400 28px var(--sans);
  color: var(--ink-mute);
  padding: 0 6px;
  transition: color 0.25s ease;
}

.month-nav:hover {
  color: var(--ink);
}

.month-nav.hidden {
  visibility: hidden;
}

.subtitle {
  font: 400 18px var(--sans);
  color: var(--ink-soft);
  margin-top: 6px;
}

.legend {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.legend-item {
  display: flex;
  align-items: center;
  gap: 6px;
}

.legend-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.legend-label {
  font: 400 15px var(--sans);
  color: var(--ink-soft);
}

/* 只是讓人讀得出哪一格是星期幾，所以比格子裡的日期還淡；
   週末不特別標色——森林裡沒有哪一天比較重要 */
.weekdays {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  margin-bottom: 10px;
}

.weekday {
  /* 置中：對齊的是格子中央那棵樹，不是左上角的日期 */
  text-align: center;
  font: 400 16px var(--sans);
  color: var(--ink-mute);
}

.grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 6px;
  flex: 1;
}

.cell {
  border-radius: 14px;
  background: var(--bg-card);
  border: 1px solid var(--border-soft);
  display: flex;
  flex-direction: column;
  padding: 10px;
  min-height: 100px;
}

.cell.clickable {
  cursor: pointer;
  transition: border-color 0.25s ease, transform 0.25s ease;
}

.cell.clickable:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
}

.cell-day {
  font: 500 15px var(--sans);
  color: var(--ink-mute);
}

.cell-body {
  flex: 1;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.soil {
  width: 20px;
  height: 7px;
  border: 1.5px dashed var(--ink-faint);
  border-radius: 50%;
}

/* 留了話但沒種下樹：土壤被翻動過的痕跡。刻意跟空白日只差一點——
   它是回去看看的入口，不是月曆上的一筆待辦 */
.soil.stirred {
  border-style: solid;
  background: var(--ink-faint);
  opacity: 0.55;
}

/* 比圖例還淡：它是想起來時才會讀到的一句話，不是頁面要傳達的重點 */
.footnote {
  margin-top: 24px;
  font: 400 16px var(--sans);
  color: var(--ink-mute);
}
</style>
