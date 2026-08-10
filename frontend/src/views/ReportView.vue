<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { api } from '@/api'
import EmotionTree from '@/components/EmotionTree.vue'
import { parseDate } from '@/format'
import type { Emotion, ReportResponse } from '@/types'

interface WeekOption {
  start: string // 週一 YYYY-MM-DD
  label: string
  isCurrent: boolean
}

const WEEK_LABELS = ['一', '二', '三', '四', '五', '六', '日']

function toKey(d: Date): string {
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

function mondayOf(d: Date): Date {
  const r = new Date(d)
  r.setDate(r.getDate() - ((r.getDay() + 6) % 7))
  return r
}

function shortDate(d: Date): string {
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

const weeks = computed<WeekOption[]>(() => {
  const current = mondayOf(new Date())
  const list: WeekOption[] = []
  for (let i = 0; i < 8; i++) {
    const start = new Date(current)
    start.setDate(start.getDate() - i * 7)
    const end = new Date(start)
    end.setDate(end.getDate() + 6)
    list.push({
      start: toKey(start),
      label: `${shortDate(start)} － ${shortDate(end)}`,
      isCurrent: i === 0,
    })
  }
  return list
})

const selected = ref<WeekOption | null>(null)
const report = ref<ReportResponse | null>(null)
const ready = computed(() =>
  report.value?.status === 'ready' ? report.value : null,
)
const loading = ref(false)
const failed = ref(false)
const weekTrees = ref<{ label: string; date: string; emotion: Emotion | null }[]>([])

async function loadWeekTrees(startKey: string) {
  const start = parseDate(startKey)
  const end = new Date(start)
  end.setDate(end.getDate() + 6)
  const months = new Set<string>()
  for (const d of [start, end]) {
    months.add(`${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`)
  }
  const emotionByDate = new Map<string, Emotion>()
  for (const m of months) {
    const days = await api.getMonth(m).catch(() => [])
    // 森林清單也含沒收尾的日子，這排小樹只畫真的種下的
    for (const d of days) {
      if (d.status === 'planted' && d.emotion) emotionByDate.set(d.date, d.emotion)
    }
  }
  weekTrees.value = WEEK_LABELS.map((label, i) => {
    const d = new Date(start)
    d.setDate(d.getDate() + i)
    const key = toKey(d)
    return { label, date: shortDate(d), emotion: emotionByDate.get(key) ?? null }
  })
}

async function select(week: WeekOption) {
  selected.value = week
  report.value = null
  failed.value = false
  loading.value = true
  weekTrees.value = []
  try {
    const [res] = await Promise.all([api.getReport(week.start), loadWeekTrees(week.start)])
    report.value = res
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // 預設打開最近一個已結束的週
  const lastEnded = weeks.value[1]
  if (lastEnded) select(lastEnded)
})
</script>

<template>
  <div class="page">
    <div class="inner">
      <header class="header">
        <div class="range">{{ selected?.label ?? '' }}</div>
        <div class="title">森林朋友幫你寫的週記</div>
      </header>

      <div class="week-picker">
        <button
          v-for="w in weeks"
          :key="w.start"
          class="week-chip"
          :class="{ chosen: selected?.start === w.start }"
          @click="select(w)"
        >
          {{ w.label }}{{ w.isCurrent ? '（本週）' : '' }}
        </button>
      </div>

      <!-- 生成中：落葉飄動 -->
      <div v-if="loading" class="loading">
        <div class="loading-bird">
          <div class="b b1"></div>
          <div class="b b2"></div>
          <div class="b b3"></div>
        </div>
        <div class="loading-text">樹正在幫你整理這一週……</div>
      </div>

      <div v-else-if="failed" class="quiet-card">樹睡著了，等等再來 🌙</div>

      <template v-else-if="report">
        <div v-if="report.status === 'growing'" class="quiet-card">
          這週還在生長中 🌱<br />
          <span class="quiet-sub">等這週過完，森林朋友會幫你整理。</span>
        </div>

        <div v-else-if="report.status === 'empty'" class="quiet-card">
          這一週是安靜的土壤。<br />
          <span class="quiet-sub">沒有種下樹的日子，也是好好過的日子。</span>
        </div>

        <template v-else-if="ready">
          <div class="report-card">
            <div class="section">
              <div class="section-title">這週的好事</div>
              <ul class="list">
                <li v-for="(g, i) in ready.report.good_things" :key="i">{{ g }}</li>
              </ul>
            </div>
            <div v-if="ready.report.bad_things.length" class="section">
              <div class="section-title">辛苦的部分</div>
              <ul class="list">
                <li v-for="(b, i) in ready.report.bad_things" :key="i">{{ b }}</li>
              </ul>
            </div>
            <div class="section">
              <div class="section-title">這週的關鍵字</div>
              <div class="keywords">
                <span v-for="(k, i) in ready.report.keywords" :key="i" class="keyword">
                  {{ k }}
                </span>
              </div>
            </div>
            <div class="advice">{{ ready.report.advice }}</div>
          </div>

          <div class="trees-block">
            <div class="trees-title">這一週的樹</div>
            <div class="trees-grid">
              <div v-for="t in weekTrees" :key="t.date" class="tree-cell">
                <div class="tree-label">週{{ t.label }}</div>
                <EmotionTree v-if="t.emotion" :emotion="t.emotion" :width="28" />
                <div v-else class="tree-empty"></div>
                <div class="tree-date">{{ t.date }}</div>
              </div>
            </div>
          </div>

          <div class="footer">
            <div class="footer-text">謝謝你，這一週也好好活著。</div>
            <RouterLink to="/forest" class="footer-link">回森林看看 →</RouterLink>
          </div>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  justify-content: center;
  padding: 52px 60px;
}

.inner {
  max-width: 965px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.range {
  font: 400 17px var(--sans);
  color: var(--ink-mute);
  min-height: 20px;
}

.title {
  font: 600 33px var(--serif);
  color: var(--ink);
  margin-top: 4px;
}

.week-picker {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.week-chip {
  font: 500 17px var(--sans);
  color: var(--ink-soft);
  border: 1px solid var(--border);
  padding: 6px 14px;
  border-radius: 14px;
  transition: all 0.25s ease;
}

.week-chip:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.week-chip.chosen {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 22px;
  padding: 64px 0;
}

.loading-bird {
  position: relative;
  width: 90px;
  height: 64px;
  animation: ts-fly 3.5s ease-in-out infinite;
}

.b {
  position: absolute;
  border-radius: 50%;
}

.b1 {
  left: 22px;
  top: 22px;
  width: 46px;
  height: 26px;
  background: oklch(72% 0.11 40);
}

.b2 {
  left: 4px;
  top: 16px;
  width: 32px;
  height: 16px;
  background: oklch(80% 0.09 40);
  transform: rotate(-18deg);
}

.b3 {
  left: 54px;
  top: 14px;
  width: 18px;
  height: 18px;
  background: oklch(74% 0.1 40);
}

.loading-text {
  font: 400 18px var(--sans);
  color: var(--ink-soft);
}

.quiet-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 22px;
  padding: 48px 36px;
  text-align: center;
  font: 500 20px/1.9 var(--sans);
  color: var(--ink-soft);
}

.quiet-sub {
  font: 400 17px var(--sans);
  color: var(--ink-mute);
}

.report-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 22px;
  padding: 32px 36px;
  display: flex;
  flex-direction: column;
  gap: 24px;
  animation: ts-fade-in 0.5s ease;
}

.section-title {
  font: 600 18px var(--sans);
  color: var(--ink-soft);
  margin-bottom: 10px;
}

.list {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font: 400 19px/1.8 var(--sans);
  color: var(--ink);
}

.keywords {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.keyword {
  font: 500 17px var(--sans);
  color: var(--accent-serif);
  background: var(--bg-bubble-tree);
  padding: 5px 14px;
  border-radius: 12px;
}

.advice {
  border-top: 1px solid var(--border-soft);
  padding-top: 20px;
  font: 500 20px/1.9 var(--serif);
  color: var(--accent-serif);
  text-wrap: pretty;
}

.trees-block {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.trees-title {
  font: 600 18px var(--sans);
  color: var(--ink-soft);
}

.trees-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 10px;
}

.tree-cell {
  background: var(--bg-card);
  border: 1px solid var(--border-soft);
  border-radius: 14px;
  padding: 14px 8px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

.tree-label {
  font: 500 15px var(--sans);
  color: var(--ink-mute);
}

.tree-empty {
  width: 20px;
  height: 7px;
  border: 1.5px dashed var(--ink-faint);
  border-radius: 50%;
  margin: 13px 0;
}

.tree-date {
  font: 400 14px var(--sans);
  color: var(--ink-mute);
}

.footer {
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 6px 0 24px;
}

.footer-text {
  font: 500 20px var(--serif);
  color: var(--accent-serif);
}

.footer-link {
  font: 500 18px var(--sans);
}
</style>
