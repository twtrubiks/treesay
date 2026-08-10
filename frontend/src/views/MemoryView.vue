<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { api } from '@/api'
import { parseDate } from '@/format'
import type { MemoryOut } from '@/types'

const memories = ref<MemoryOut[]>([])
const loading = ref(true)
const errorText = ref('')
// 兩段式放下：第一按只是問一聲，再按一次才真的放下——沒有跳出視窗的驚嚇
const confirmingId = ref<number | null>(null)

function sourceLabel(dateStr: string): string {
  const d = parseDate(dateStr)
  return `${d.getMonth() + 1}月${d.getDate()}日記下`
}

async function load() {
  loading.value = true
  errorText.value = ''
  try {
    memories.value = await api.getMemories()
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '樹睡著了，等等再來 🌙'
  } finally {
    loading.value = false
  }
}

async function letGo(m: MemoryOut) {
  if (confirmingId.value !== m.id) {
    confirmingId.value = m.id
    return
  }
  confirmingId.value = null
  try {
    await api.deleteMemory(m.id)
    memories.value = memories.value.filter((x) => x.id !== m.id)
  } catch (e) {
    errorText.value = e instanceof Error ? e.message : '樹睡著了，等等再來 🌙'
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="inner">
      <header class="header">
        <div class="title">樹記得的事</div>
        <div class="subtitle">
          種樹的時候，樹會順手記下一些關於你的事，讓它更懂你。<br />
          哪一件不想被記得，隨時可以讓它放下。
        </div>
      </header>

      <div v-if="loading" class="quiet-card">樹正在想……</div>

      <div v-else-if="errorText" class="quiet-card">{{ errorText }}</div>

      <div v-else-if="memories.length === 0" class="quiet-card">
        樹還沒記下什麼。<br />
        <span class="quiet-sub">多說幾天話，它會慢慢認識你 🌱</span>
      </div>

      <div v-else class="memory-list">
        <div v-for="m in memories" :key="m.id" class="memory-card">
          <div class="memory-body">
            <div class="memory-content">{{ m.content }}</div>
            <div class="memory-date">{{ sourceLabel(m.source_date) }}</div>
          </div>
          <button
            class="let-go"
            :class="{ confirming: confirmingId === m.id }"
            @click="letGo(m)"
            @blur="confirmingId === m.id && (confirmingId = null)"
          >
            {{ confirmingId === m.id ? '再按一次，就放下' : '放下' }}
          </button>
        </div>
      </div>
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

.memory-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  animation: ts-fade-in 0.5s ease;
}

.memory-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 18px;
  padding: 20px 24px;
  display: flex;
  align-items: center;
  gap: 18px;
}

.memory-body {
  flex: 1;
  min-width: 0;
}

.memory-content {
  font: 400 19px/1.7 var(--sans);
  color: var(--ink);
  overflow-wrap: anywhere;
}

.memory-date {
  margin-top: 6px;
  font: 400 15px var(--sans);
  color: var(--ink-mute);
}

.let-go {
  flex-shrink: 0;
  font: 500 16px var(--sans);
  color: var(--ink-mute);
  border: 1px solid var(--border);
  padding: 7px 14px;
  border-radius: 12px;
  transition: all 0.25s ease;
}

.let-go:hover {
  border-color: var(--accent);
  color: var(--accent);
}

.let-go.confirming {
  border-color: var(--accent);
  background: var(--bg-bubble-tree);
  color: var(--accent-serif);
}
</style>
