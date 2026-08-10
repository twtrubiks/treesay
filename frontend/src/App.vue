<script setup lang="ts">
import { RouterLink, RouterView, useRoute } from 'vue-router'

import EmotionTree from '@/components/EmotionTree.vue'

const route = useRoute()

function isActive(prefix: string): boolean {
  if (prefix === '/') return route.path === '/'
  return route.path.startsWith(prefix)
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="logo">
        <EmotionTree :width="30" />
        <div class="logo-text">樹說</div>
      </div>

      <RouterLink to="/" class="nav-item" :class="{ active: isActive('/') }">
        <div class="icon icon-home"></div>
        <span>首頁</span>
      </RouterLink>

      <RouterLink to="/forest" class="nav-item" :class="{ active: isActive('/forest') }">
        <div class="icon icon-forest">
          <div class="forest-trunk"></div>
          <div class="forest-leaf"></div>
        </div>
        <span>森林</span>
      </RouterLink>

      <RouterLink to="/report" class="nav-item" :class="{ active: isActive('/report') }">
        <div class="icon icon-report">
          <div class="report-line"></div>
          <div class="report-line short"></div>
        </div>
        <span>週報</span>
      </RouterLink>

      <RouterLink to="/memories" class="nav-item" :class="{ active: isActive('/memories') }">
        <div class="icon icon-memory">
          <div class="memory-bubble"></div>
          <div class="memory-bubble small"></div>
        </div>
        <span>記憶</span>
      </RouterLink>

      <div class="spacer"></div>
      <!-- 不寫「只有你和樹知道」——內容仍會送到 Anthropic 生成，只能說沒有別人看得到 -->
      <div class="sidebar-footer">沒有別人看得到。<br />不用登入，說完就好。</div>
    </aside>

    <main class="content">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
}

.sidebar {
  width: 220px;
  flex-shrink: 0;
  background: var(--bg-side);
  padding: 32px 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-right: 1px solid var(--border);
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 8px 28px;
}

.logo-text {
  font: 700 24px var(--serif);
  color: var(--ink);
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border-radius: 14px;
  font: 500 19px var(--sans);
  color: var(--ink-soft);
}

.nav-item:hover {
  background: oklch(96% 0.015 78);
  color: var(--ink-soft);
}

.nav-item.active {
  background: var(--bg-card);
  font-weight: 600;
  color: var(--ink);
}

.icon {
  position: relative;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}

/* 首頁：圓角方塊 */
.icon-home {
  border-radius: 6px;
  background: oklch(58% 0.02 55);
}

.nav-item.active .icon-home {
  background: var(--accent);
}

/* 森林：小樹 */
.forest-trunk {
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 3px;
  height: 8px;
  background: oklch(60% 0.03 55);
  border-radius: 1px;
}

.forest-leaf {
  position: absolute;
  bottom: 5px;
  left: 50%;
  transform: translateX(-50%);
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: oklch(60% 0.05 140);
}

.nav-item.active .forest-trunk {
  background: var(--trunk);
}

.nav-item.active .forest-leaf {
  background: oklch(65% 0.09 140);
}

/* 週報：紙頁 */
.icon-report {
  width: 15px;
  height: 18px;
  border-radius: 4px;
  border: 1.5px solid oklch(60% 0.03 55);
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 3px;
}

.report-line {
  width: 7px;
  height: 1.5px;
  border-radius: 1px;
  background: oklch(60% 0.03 55);
}

.report-line.short {
  width: 5px;
}

.nav-item.active .icon-report {
  border-color: var(--accent);
}

.nav-item.active .report-line {
  background: var(--accent);
}

/* 記憶：兩顆思緒泡泡 */
.memory-bubble {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: oklch(60% 0.03 55);
}

.memory-bubble.small {
  left: auto;
  bottom: auto;
  top: 0;
  right: 0;
  width: 8px;
  height: 8px;
  background: oklch(72% 0.03 55);
}

.nav-item.active .memory-bubble {
  background: var(--accent);
}

.nav-item.active .memory-bubble.small {
  background: oklch(65% 0.09 140);
}

.spacer {
  flex: 1;
}

.sidebar-footer {
  font: 400 15px/1.6 var(--sans);
  color: var(--ink-mute);
  padding: 0 8px;
}

.content {
  flex: 1;
  overflow-y: auto;
  position: relative;
}
</style>
