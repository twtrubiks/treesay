<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { api } from '@/api'
import type { UsageResponse } from '@/types'

const usage = ref<UsageResponse | null>(null)
const loading = ref(true)
const errorText = ref('')

function monthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number)
  return `${y} 年 ${m} 月`
}

// 一次種樹只要幾分錢，固定兩位小數會把大部分月份顯示成 US$0.00
function usd(v: number): string {
  if (v === 0) return 'US$0'
  if (v < 0.001) return '不到 US$0.001'
  return `US$${v.toFixed(v < 1 ? 3 : 2)}`
}

onMounted(async () => {
  try {
    usage.value = await api.getUsage()
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '樹睡著了，等等再來 🌙'
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page">
    <div class="inner">
      <header class="header">
        <div class="title">AI 費用</div>
        <div class="subtitle">
          種樹和週報都會呼叫 Claude API。這裡依官方定價估算每個月花了多少，<br />
          實際金額以
          <a href="https://platform.claude.com/" target="_blank" rel="noopener">Claude Console</a>
          的帳單為準。
        </div>
      </header>

      <div v-if="loading" class="quiet-card">樹正在算……</div>

      <div v-else-if="errorText" class="quiet-card">{{ errorText }}</div>

      <div v-else-if="!usage || usage.months.length === 0" class="quiet-card">
        還沒有花過錢。<br />
        <span class="quiet-sub">種下第一棵樹之後，這裡才會有數字 🌱</span>
      </div>

      <template v-else>
        <div class="month-list">
          <div v-for="m in usage.months" :key="m.month" class="month-card">
            <div class="month-head">
              <div class="month-label">{{ monthLabel(m.month) }}</div>
              <div class="month-total">{{ usd(m.total_usd) }}</div>
            </div>
            <div class="month-detail">
              種樹 {{ usd(m.plant_usd) }}<span class="dot">·</span>週報 {{ usd(m.weekly_usd) }}
            </div>
            <div v-if="m.unpriced_calls > 0" class="month-note">
              另有 {{ m.unpriced_calls }} 次呼叫用的模型不在價目表上，沒有算進去
            </div>
          </div>
        </div>
        <div class="all-time">到目前為止，一共約 {{ usd(usage.total_usd) }}</div>
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
  max-width: 720px;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.title {
  font: 600 33px var(--serif);
  color: var(--ink);
}

.subtitle {
  margin-top: 10px;
  font: 400 17px/1.8 var(--sans);
  color: var(--ink-mute);
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

.month-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  animation: ts-fade-in 0.5s ease;
}

.month-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 18px;
  padding: 20px 24px;
}

.month-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 18px;
}

.month-label {
  font: 500 19px var(--sans);
  color: var(--ink);
}

.month-total {
  font: 600 22px var(--serif);
  color: var(--ink);
}

.month-detail {
  margin-top: 6px;
  font: 400 15px var(--sans);
  color: var(--ink-mute);
}

.dot {
  margin: 0 8px;
}

.month-note {
  margin-top: 6px;
  font: 400 15px var(--sans);
  color: var(--accent-serif);
}

.all-time {
  text-align: center;
  font: 400 15px var(--sans);
  color: var(--ink-mute);
}
</style>
