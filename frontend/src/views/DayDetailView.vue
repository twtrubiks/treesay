<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { api, ApiError } from '@/api'
import EmotionTree from '@/components/EmotionTree.vue'
import { EMOTION_META } from '@/emotions'
import { formatDateLabel, formatTime } from '@/format'
import type { DayDetail } from '@/types'

const route = useRoute()

const day = ref<DayDetail | null>(null)
const notFound = ref(false)
const error = ref<string | null>(null)
const showMessages = ref(false)
const planting = ref(false)

const emotionMeta = computed(() =>
  day.value?.emotion ? EMOTION_META[day.value.emotion] : null,
)

async function load() {
  day.value = null
  notFound.value = false
  error.value = null
  showMessages.value = false
  try {
    day.value = await api.getDay(String(route.params.date))
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) notFound.value = true
    else error.value = e instanceof ApiError ? e.message : '樹睡著了，等等再來 🌙'
  }
}

// 補種：那天忘了按，回來把它種下。過了窗口後端會擋，這裡就不給按鈕。
async function plantThisDay() {
  if (!day.value || planting.value) return
  planting.value = true
  error.value = null
  try {
    day.value = await api.plantDay(day.value.date)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '樹睡著了，等等再來 🌙'
  } finally {
    planting.value = false
  }
}

watch(() => route.params.date, load, { immediate: true })
</script>

<template>
  <div class="page">
    <RouterLink to="/forest" class="back">← 回森林</RouterLink>

    <div v-if="notFound" class="quiet">這一天還是安靜的土壤。</div>

    <template v-else-if="day">
      <header class="header">
        <div class="date">{{ formatDateLabel(day.date) }}</div>
        <div class="head-row">
          <!-- 沒種下的日子不畫樹：頁面說「還沒種下」，上面卻有一棵樹會自相矛盾 -->
          <EmotionTree
            v-if="day.status === 'planted'"
            :emotion="day.emotion"
            :width="40"
            breathe
          />
          <span v-if="emotionMeta" class="emotion-label">{{ emotionMeta.label }}</span>
        </div>
      </header>

      <template v-if="day.status === 'planted'">
        <div class="diary-card">{{ day.diary }}</div>

        <div class="reply-row">
          <div class="tree-avatar"></div>
          <div class="reply-bubble">{{ day.tree_reply }}</div>
        </div>
      </template>

      <!-- 沒收尾的日子：那些話一樣要看得見，不是被 App 吞掉 -->
      <div v-else class="unfinished">
        <div class="quiet">
          {{
            day.can_plant
              ? '這一天的樹還沒種下——還來得及。'
              : '這一天的樹沒有種下。那些話還留在這裡。'
          }}
        </div>
        <button
          v-if="day.can_plant"
          class="plant-btn"
          :disabled="planting"
          @click="plantThisDay"
        >
          {{ planting ? '樹正在生長…' : '種下這一天的樹' }}
        </button>
      </div>

      <div v-if="day.messages.length" class="messages-block">
        <button
          v-if="day.status === 'planted'"
          class="messages-toggle"
          @click="showMessages = !showMessages"
        >
          {{ showMessages ? '收起' : '看看' }}那天的隻字片語
          {{ showMessages ? '︿' : '﹀' }}
        </button>
        <div v-if="showMessages || day.status !== 'planted'" class="messages">
          <template v-for="m in day.messages" :key="m.id">
            <div class="bubble">
              <img v-if="m.photo_url" class="bubble-photo" :src="m.photo_url" alt="" />
              <div v-if="m.content">{{ m.content }}</div>
            </div>
            <div class="bubble-meta">{{ formatTime(m.created_at) }}</div>
          </template>
        </div>
      </div>
    </template>

    <div v-if="error" class="error-toast" @click="error = null">{{ error }}</div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 22px;
  padding: 52px 64px;
  max-width: 965px;
}

.back {
  font: 500 18px var(--sans);
  align-self: flex-start;
}

.quiet {
  color: var(--ink-mute);
  font: 400 19px var(--sans);
  padding: 48px 0;
}

/* 沒收尾的日子：說明與補種入口，語氣要是「還來得及」而不是「你漏了」 */
.unfinished {
  display: flex;
  align-items: center;
  gap: 20px;
  flex-wrap: wrap;
}

.unfinished .quiet {
  padding: 0;
}

.plant-btn {
  border: 1px solid var(--accent);
  color: var(--accent);
  padding: 10px 22px;
  border-radius: 20px;
  font: 600 18px var(--sans);
  transition: all 0.3s ease;
  flex-shrink: 0;
}

.plant-btn:hover:not(:disabled) {
  background: var(--accent);
  color: var(--accent-contrast);
}

.plant-btn:disabled {
  opacity: 0.5;
  cursor: default;
}

.date {
  font: 400 18px var(--sans);
  color: var(--ink-mute);
}

.head-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
}

.emotion-label {
  font: 500 18px var(--sans);
  color: var(--ink-soft);
}

.diary-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 22px;
  padding: 28px 32px;
  font: 400 20px/2 var(--sans);
  color: var(--ink);
  white-space: pre-wrap;
  text-wrap: pretty;
  animation: ts-fade-in 0.5s ease;
}

.reply-row {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.tree-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: oklch(78% 0.1 35);
  flex-shrink: 0;
  margin-top: 2px;
}

.reply-bubble {
  background: var(--bg-bubble-tree);
  color: var(--ink);
  padding: 16px 20px;
  border-radius: 4px 18px 18px 18px;
  font: 400 20px/1.8 var(--sans);
}

.messages-block {
  margin-top: 8px;
}

.messages-toggle {
  font: 500 17px var(--sans);
  color: var(--ink-mute);
  transition: color 0.25s ease;
}

.messages-toggle:hover {
  color: var(--ink-soft);
}

.messages {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 14px;
  max-width: 710px;
}

.bubble {
  align-self: flex-end;
  background: var(--bg-bubble-user);
  color: var(--ink-strong);
  padding: 12px 16px;
  border-radius: 18px 18px 4px 18px;
  font: 400 18px/1.6 var(--sans);
  max-width: 80%;
  margin-top: 8px;
}

.bubble-photo {
  display: block;
  max-width: 220px;
  border-radius: 12px;
  margin-bottom: 6px;
}

.bubble-meta {
  align-self: flex-end;
  font: 400 14px var(--sans);
  color: var(--ink-mute);
  margin: 2px 4px 0 0;
}

.error-toast {
  position: fixed;
  bottom: 28px;
  left: 50%;
  transform: translateX(-50%);
  background: var(--ink);
  color: var(--bg-card);
  padding: 12px 26px;
  border-radius: 20px;
  font: 500 18px var(--sans);
  cursor: pointer;
  z-index: 60;
}
</style>
