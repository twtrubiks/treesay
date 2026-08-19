<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'

import { api, ApiError } from '@/api'
import EmotionTree from '@/components/EmotionTree.vue'
import TypeWriter from '@/components/TypeWriter.vue'
import { EMOTION_META } from '@/emotions'
import {
  effectiveDateNow,
  formatDateLabel,
  formatTime,
  greetingForNow,
  isEveningNow,
  parseDate,
} from '@/format'
import { useTodayStore } from '@/stores/today'
import type { MessageOut } from '@/types'

const store = useTodayStore()

const input = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const composerRef = ref<HTMLElement | null>(null)
// 「聊聊這個」按下去要看得見：輸入框被 chat-spacer 推到頁尾，
// 只做 focus 的話畫面毫無動靜，等於按了沒反應
const composerNudge = ref(false)
let nudgeTimer: ReturnType<typeof setTimeout> | undefined
// 按下「聊聊這個」後，把問題留在輸入框上方——按鈕說了「這個」，
// 寫的時候就得看得到「這個」是什麼。送出後自動退場，不留成待辦
const answering = ref<string | null>(null)
const photoFile = ref<File | null>(null)
const photoPreview = ref<string | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const questionHidden = ref(false)
// 正在改哪一則（null 就是沒人在改）
const editingId = ref<number | null>(null)
const editText = ref('')

// 種樹按鈕跟著一天的節奏：入夜且說過話才暖起來。開著跨過 18:00 也要跟上，每分鐘對一次時，
// 順便對日期——頁面開著跨過凌晨 4:00 的話，day 還停在前一天，不重抓就寫不了今天
const evening = ref(isEveningNow())
const minuteTimer = setInterval(() => {
  evening.value = isEveningNow()
  reloadIfStale()
}, 60_000)

// 種樹全螢幕流程：none → growing →（成功）done
const overlay = ref<'none' | 'growing' | 'done'>('none')
const replyTyped = ref(false)

const localError = ref<string | null>(null)

const day = computed(() => store.today)
const isCollecting = computed(() => day.value?.status === 'collecting')
const isPlanted = computed(() => day.value?.status === 'planted')
const emotionMeta = computed(() =>
  day.value?.emotion ? EMOTION_META[day.value.emotion] : null,
)
// 補種邀請怎麼稱呼那一天：窗口只有兩天，不是昨天就是前天
const backfillLabel = computed(() => {
  const target = day.value?.backfill_candidate
  if (!target) return null
  const diff = Math.round(
    (parseDate(effectiveDateNow()).getTime() - parseDate(target).getTime()) / 86_400_000,
  )
  return diff === 1 ? '昨天' : '前天'
})

// 樹隨訊息數慢慢長大（中性樹，種樹後才有情緒）
const treeWidth = computed(() => {
  const n = day.value?.messages.length ?? 0
  return Math.min(34 + n * 7, 90)
})
onMounted(async () => {
  // 筆電睡眠時 timer 不會跑，回到分頁那一刻先對一次日期，不讓過期畫面多留一分鐘
  document.addEventListener('visibilitychange', onVisibilityChange)
  await store.load().catch(() => {
    localError.value = '樹睡著了，等等再來 🌙'
  })
  if (store.today?.status === 'planting') overlay.value = 'growing'
})

// 日期過期才重抓，平常的分鐘節拍不多打任何 API；種樹儀式進行中不動它
async function reloadIfStale() {
  if (overlay.value !== 'none') return
  if (!store.today || store.today.date === effectiveDateNow()) return
  try {
    await store.load()
    // 新的一天有新的問題：昨天按過「先不想」、答到一半的引用都不該跟過來
    questionHidden.value = false
    answering.value = null
  } catch {
    // 靜默就好，下一分鐘還會再試——凌晨四點不需要錯誤 toast
  }
}

function onVisibilityChange() {
  if (document.visibilityState === 'visible') reloadIfStale()
}

// 輸入框跟著字長高。固定兩行的話，打到第三行就開始往上滾——寫的人看不見
// 自己剛剛說了什麼。上限 320px（約 10 行）：再高會把上面的訊息擠掉，
// 一個佔滿螢幕的輸入框本身就是在催人寫滿它
const COMPOSER_MAX_HEIGHT = 320

function autosizeComposer() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  // 先量再設：設完 height 之後 scrollHeight 就被上限夾住了，判斷會失準
  const needed = el.scrollHeight
  el.style.height = `${Math.min(needed, COMPOSER_MAX_HEIGHT)}px`
  // 沒滿之前不讓捲軸露臉，否則剛好滿一行時它會閃一下
  el.style.overflowY = needed > COMPOSER_MAX_HEIGHT ? 'auto' : 'hidden'
}

async function send() {
  const content = input.value.trim()
  if (!content && !photoFile.value) return
  try {
    await store.send(content, photoFile.value ?? undefined)
    input.value = ''
    clearPhoto()
    answering.value = null
    // 清空是 v-model 下一輪才進 DOM，這時候量高度會量到還沒清掉的字
    nextTick(autosizeComposer)
  } catch {
    // 錯誤文案已寫入 store.error
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function pickPhoto() {
  fileInputRef.value?.click()
}

function onPhotoChange(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  photoFile.value = file
  photoPreview.value = URL.createObjectURL(file)
}

function clearPhoto() {
  if (photoPreview.value) URL.revokeObjectURL(photoPreview.value)
  photoFile.value = null
  photoPreview.value = null
  if (fileInputRef.value) fileInputRef.value.value = ''
}

async function removeMessage(id: number) {
  try {
    await store.remove(id)
  } catch (e) {
    localError.value = e instanceof ApiError ? e.message : '樹睡著了，等等再來 🌙'
  }
}

// 打錯字改回來：泡泡原地變輸入框。只有今天、樹還沒收下的話能改
function startEdit(m: MessageOut) {
  editingId.value = m.id
  editText.value = m.content
}

function cancelEdit() {
  editingId.value = null
  editText.value = ''
}

// 一進編輯就把游標放句尾、高度撐開，讓人直接接著改。
// 等 nextTick：ref 回呼跑在元素進 DOM 之前，那時量到的 scrollHeight 是 0
function onEditMount(el: unknown) {
  const node = el as HTMLTextAreaElement | null
  if (!node) return
  nextTick(() => {
    node.focus()
    node.setSelectionRange(node.value.length, node.value.length)
    autosizeEdit(node)
  })
}

function autosizeEdit(el: HTMLTextAreaElement) {
  el.style.height = 'auto'
  el.style.height = `${el.scrollHeight}px`
}

function onEditInput(e: Event) {
  autosizeEdit(e.target as HTMLTextAreaElement)
}

async function saveEdit(m: MessageOut) {
  const content = editText.value.trim()
  // 沒改，或整句被清空（想整句拿掉請用收回）：安靜退出，不打擾也不報錯
  if (content === m.content || (!content && !m.photo_url)) {
    cancelEdit()
    return
  }
  try {
    await store.edit(m.id, content)
    cancelEdit()
  } catch (e) {
    localError.value = e instanceof ApiError ? e.message : '樹睡著了，等等再來 🌙'
  }
}

function onEditKeydown(e: KeyboardEvent, m: MessageOut) {
  if (e.key === 'Escape') {
    e.preventDefault()
    cancelEdit()
  } else if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    saveEdit(m)
  }
}

function dropAnswering() {
  answering.value = null
  textareaRef.value?.focus()
}

function focusInput() {
  answering.value = day.value?.question ?? null
  // preventScroll：讓下面的 smooth 捲動接手，否則瀏覽器會先瞬移一次
  textareaRef.value?.focus({ preventScroll: true })
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  composerRef.value?.scrollIntoView({
    behavior: reduceMotion ? 'auto' : 'smooth',
    block: 'center',
  })
  // 連按時重新播一次微光：先關掉，下一幀再開，動畫才會重跑
  composerNudge.value = false
  clearTimeout(nudgeTimer)
  requestAnimationFrame(() => {
    composerNudge.value = true
    nudgeTimer = setTimeout(() => (composerNudge.value = false), 1600)
  })
}

onUnmounted(() => {
  clearTimeout(nudgeTimer)
  clearInterval(minuteTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})

async function plant() {
  overlay.value = 'growing'
  replyTyped.value = false
  try {
    await store.plant()
    overlay.value = 'done'
  } catch {
    overlay.value = 'none'
  }
}

function closeOverlay() {
  overlay.value = 'none'
}

const errorText = computed(() => store.error ?? localError.value)

function dismissError() {
  store.error = null
  localError.value = null
}
</script>

<template>
  <div v-if="day" class="page">
    <!-- 頂部：日期＋問候＋種樹按鈕 -->
    <header class="header">
      <div>
        <div class="date">{{ formatDateLabel(day.date) }}</div>
        <div class="greeting">
          {{ isPlanted ? '今天的樹已經種下了' : greetingForNow() }}
        </div>
      </div>
      <div class="header-right">
        <EmotionTree
          v-if="!isPlanted"
          :width="treeWidth"
          :breathe="day.messages.length > 0"
        />
        <!-- 三個狀態陪一天走：沒訊息時沉睡、有訊息後等著、入夜亮起。邀請，不是提醒 -->
        <button
          v-if="isCollecting"
          class="plant-btn"
          :class="{
            dormant: day.messages.length === 0,
            lit: evening && day.messages.length > 0,
          }"
          :disabled="day.messages.length === 0"
          @click="plant"
        >
          種下今天的樹 🌱
        </button>
      </div>
    </header>

    <!-- 今日問題卡片（collecting 才出現） -->
    <div v-if="isCollecting && !questionHidden" class="question-card">
      <div class="question-text">{{ day.question }}</div>
      <div class="question-actions">
        <button class="q-btn primary" @click="focusInput">聊聊這個</button>
        <button class="q-btn quiet" @click="questionHidden = true">先不想</button>
      </div>
    </div>

    <!-- 前一兩天留了話沒收尾：遞一個安靜的邀請，不是待辦——不想理它也沒關係，
         窗口過了它自己消失。今天種完樹就不再出現，收好的一天不遞新的事 -->
    <RouterLink
      v-if="isCollecting && day.backfill_candidate"
      class="backfill-invite"
      :to="`/forest/${day.backfill_candidate}`"
    >
      {{ backfillLabel }}說的話還放著，想替它種棵樹嗎 🌱
    </RouterLink>

    <!-- 已種下：日記卡片（唯讀） -->
    <div v-if="isPlanted" class="diary-area">
      <div class="diary-card">
        <div class="diary-head">
          <EmotionTree :emotion="day.emotion" :width="34" breathe />
          <span v-if="emotionMeta" class="emotion-label">{{ emotionMeta.label }}</span>
        </div>
        <div class="diary-text">{{ day.diary }}</div>
      </div>
      <div class="reply-row">
        <div class="tree-avatar"></div>
        <div class="reply-bubble">{{ day.tree_reply }}</div>
      </div>
      <div class="tomorrow">新的想法，留到明天的樹。明天見 🌙</div>
    </div>

    <!-- 收集中：聊天泡泡 -->
    <div v-if="!isPlanted" class="chat">
      <template v-for="m in day.messages" :key="m.id">
        <div class="bubble-row" :class="{ 'row-editing': editingId === m.id }">
          <template v-if="isCollecting && editingId !== m.id">
            <button class="msg-action" title="改一下這句話" @click="startEdit(m)">✎</button>
            <button class="msg-action" title="收回這句話" @click="removeMessage(m.id)">✕</button>
          </template>
          <!-- 改字：泡泡原地變輸入框，不彈視窗——一句打錯的話不值得一個對話框 -->
          <div v-if="editingId === m.id" class="bubble editing">
            <img v-if="m.photo_url" class="bubble-photo" :src="m.photo_url" alt="" />
            <textarea
              :ref="onEditMount"
              v-model="editText"
              class="edit-input"
              rows="1"
              @input="onEditInput"
              @keydown="onEditKeydown($event, m)"
            ></textarea>
            <div class="edit-hint">Enter 改好 · Esc 不改了</div>
          </div>
          <div v-else class="bubble">
            <img v-if="m.photo_url" class="bubble-photo" :src="m.photo_url" alt="" />
            <div v-if="m.content">{{ m.content }}</div>
          </div>
        </div>
        <div class="bubble-meta">{{ formatTime(m.created_at) }} · 樹已經收下</div>
      </template>
    </div>

    <div
      v-if="!isPlanted"
      class="chat-spacer"
      :class="{ grow: day.messages.length > 0 }"
    ></div>

    <!-- 輸入區 -->
    <div v-if="isCollecting" ref="composerRef" class="composer" :class="{ nudge: composerNudge }">
      <!-- 按了「聊聊這個」才出現：讓「這個」在寫的時候還看得見 -->
      <div v-if="answering" class="answering">
        <span class="answering-label">正在回應</span>
        <span class="answering-text">{{ answering }}</span>
        <button class="answering-drop" title="不聊這個了" @click="dropAnswering">✕</button>
      </div>
      <div v-if="photoPreview" class="photo-preview">
        <img :src="photoPreview" alt="" />
        <button class="photo-remove" @click="clearPhoto">✕</button>
      </div>
      <textarea
        ref="textareaRef"
        v-model="input"
        class="composer-input"
        rows="2"
        placeholder="想說什麼都可以，樹在聽……"
        @input="autosizeComposer"
        @keydown="onKeydown"
      ></textarea>
      <div class="composer-actions">
        <button class="photo-btn" title="附一張照片" @click="pickPhoto">📷</button>
        <input
          ref="fileInputRef"
          type="file"
          accept="image/*"
          hidden
          @change="onPhotoChange"
        />
        <button class="send-btn" @click="send">丟給樹</button>
      </div>
    </div>
    <!-- 入夜後把動線縫起來：寫字的地方在下面，收工的按鈕在右上角 -->
    <div v-if="isCollecting" class="composer-hint">
      {{
        evening && day.messages.length > 0
          ? '說得差不多了的話，右上角可以種下今天的樹 🌱'
          : '沒有字數要求，寫一句話也可以，什麼都不寫也沒關係。'
      }}
    </div>

    <!-- 錯誤（療癒文案） -->
    <div v-if="errorText" class="error-toast" @click="dismissError">
      {{ errorText }}
    </div>

    <!-- 種樹全螢幕畫面 -->
    <div v-if="overlay !== 'none'" class="grow-overlay">
      <!-- 最後一句話陪著等待；日記出來後淡出——話被樹收進日記裡了 -->
      <div
        v-if="day.messages.length"
        class="grow-msg"
        :class="{ absorbed: overlay === 'done' }"
      >
        {{ day.messages[day.messages.length - 1]!.content }}
      </div>

      <div class="grow-tree">
        <div class="glow"></div>
        <div class="g-trunk"></div>
        <div class="g-c1"></div>
        <div class="g-c2"></div>
        <div class="g-c3"></div>
        <div class="spark s1"></div>
        <div class="spark s2"></div>
        <div class="spark s3"></div>
      </div>

      <div v-if="overlay === 'growing'" class="grow-wait">
        <div class="grow-wait-title">樹正在慢慢長大……</div>
        <div class="grow-wait-sub">深呼吸一下，等待也是今天的一部分。</div>
      </div>

      <template v-if="overlay === 'done'">
        <div class="grow-diary">
          <div class="grow-diary-head">
            <EmotionTree :emotion="day.emotion" :width="28" />
            <span v-if="emotionMeta">{{ emotionMeta.label }}</span>
          </div>
          <div class="grow-diary-text">{{ day.diary }}</div>
        </div>
        <div class="grow-reply-row">
          <div class="tree-avatar"></div>
          <div class="grow-reply">
            <TypeWriter :text="day.tree_reply ?? ''" @done="replyTyped = true" />
          </div>
        </div>
      </template>

      <div class="grow-spacer"></div>

      <div v-if="overlay === 'done'" class="grow-footer" :class="{ ready: replyTyped }">
        <button class="grow-pill" @click="closeOverlay">
          這棵樹種好了，今天先到這裡 🌙
        </button>
      </div>
      <div v-else class="grow-footer ready">
        <button class="grow-retry" @click="plant">等太久了嗎？再種一次</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  min-height: 100%;
  padding: 56px 64px;
  max-width: 1035px;
}

.header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 36px;
}

.date {
  font: 400 18px var(--sans);
  color: var(--ink-mute);
}

.greeting {
  font: 600 33px var(--serif);
  color: var(--ink);
  margin-top: 4px;
}

.header-right {
  display: flex;
  align-items: flex-end;
  gap: 18px;
}

.plant-btn {
  border: 1px solid var(--accent);
  color: var(--accent);
  padding: 10px 22px;
  border-radius: 20px;
  font: 600 18px var(--sans);
  transition: all 0.3s ease;
}

.plant-btn:hover {
  background: var(--accent);
  color: var(--accent-contrast);
}

/* 還沒有話丟給樹：按鈕還在睡。看得到（知道儀式在哪裡），但明顯不是時候 */
.plant-btn.dormant {
  opacity: 0.35;
  pointer-events: none;
}

/* 入夜且說過話了：燈亮起來，可以收工了 */
.plant-btn.lit {
  background: var(--accent);
  color: var(--accent-contrast);
}

/* 今日問題 */
.question-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 18px;
  padding: 18px 22px;
  margin-bottom: 28px;
  max-width: 810px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.question-text {
  font: 500 19px/1.7 var(--sans);
  color: var(--ink);
}

.question-actions {
  display: flex;
  gap: 10px;
}

.q-btn {
  font: 500 17px var(--sans);
  color: var(--ink-soft);
  background: var(--bg-app);
  border: 1px solid var(--border);
  padding: 6px 14px;
  border-radius: 14px;
  transition: all 0.25s ease;
}

.q-btn:hover {
  color: var(--accent);
  border-color: var(--accent);
}

/* 「說話」跟「算了」不該長得一樣重——一個很累的人要能一眼看到說話在哪 */
.q-btn.primary {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast);
}

.q-btn.primary:hover {
  background: var(--accent-deep);
  border-color: var(--accent-deep);
  color: var(--accent-contrast);
}

.q-btn.quiet {
  background: none;
  border-color: transparent;
  color: var(--ink-mute);
}

.q-btn.quiet:hover {
  color: var(--ink-soft);
  border-color: transparent;
}

/* 補種邀請：一行安靜的字，壓成 mute 色不用全域的 accent 連結色——
   它是邀請不是紅點，hover 時才透出可以點的暖色 */
.backfill-invite {
  align-self: flex-start;
  font: 400 17px var(--sans);
  color: var(--ink-mute);
  margin: 0 2px 26px;
  transition: color 0.25s ease;
}

.backfill-invite:hover {
  color: var(--accent);
}

/* 聊天 */
.chat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 810px;
}

.bubble-row {
  align-self: flex-end;
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 80%;
  margin-top: 10px;
}

.bubble {
  background: var(--bg-bubble-user);
  color: var(--ink-strong);
  padding: 14px 18px;
  border-radius: 18px 18px 4px 18px;
  font: 400 19px/1.6 var(--sans);
  animation: ts-fade-in 0.4s ease;
}

.bubble-photo {
  display: block;
  max-width: 240px;
  border-radius: 12px;
  margin-bottom: 6px;
}

/* 改字與收回：平時淡淡的、不搶話，但看得見——完全隱形的話，
   打錯字的人只會以為說出去就改不了了 */
.msg-action {
  opacity: 0.35;
  color: var(--ink-faint);
  font-size: 15px;
  transition:
    opacity 0.25s ease,
    color 0.25s ease;
}

.bubble-row:hover .msg-action {
  opacity: 1;
}

.msg-action:hover {
  color: var(--ink-soft);
}

/* 編輯時整列撐到允許的最大寬度（仍受 .bubble-row 的 80% 上限）：
   textarea 的預設寬度比原泡泡窄，不撐開的話一進編輯文字就重新折行 */
.bubble-row.row-editing {
  width: 100%;
}

.bubble.editing {
  flex: 1;
  animation: none;
  box-shadow: 0 0 0 2px var(--accent) inset;
}

.edit-input {
  display: block;
  width: 100%;
  border: none;
  background: transparent;
  padding: 0;
  resize: none;
  overflow: hidden;
  color: inherit;
  font: inherit;
}

.edit-input:focus {
  outline: none;
}

.edit-hint {
  margin-top: 6px;
  font: 400 13px var(--sans);
  color: var(--ink-mute);
}

.bubble-meta {
  align-self: flex-end;
  font: 400 15px var(--sans);
  color: var(--ink-mute);
  margin: 2px 4px 0 0;
}

/* 一句話都還沒說的時候不要撐開：問題在上、輸入框在遠遠的下面，
   中間那片空白讀起來是「這裡沒有人」，正好跟這個 App 想給的相反。
   有了第一則訊息，空白才變成等待下一句的呼吸空間，這時才沉底 */
.chat-spacer {
  min-height: 32px;
}

.chat-spacer.grow {
  flex: 1;
}

/* 輸入區 */
.composer {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 22px;
  padding: 22px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  max-width: 810px;
  transition:
    border-color 0.5s ease,
    box-shadow 0.5s ease;
}

/* 游標在寫字的地方，框就跟著醒過來——很淡，是「我在聽」不是「請填寫」 */
.composer:focus-within {
  border-color: oklch(80% 0.06 55);
}

/* 「聊聊這個」按下後的微光，1.6 秒後自己退掉 */
.composer.nudge {
  border-color: var(--accent);
  box-shadow: 0 0 0 5px oklch(66% 0.12 45 / 0.14);
}

/* 正在回應的問題：淡引用，不是待填欄位——所以沒有底色、沒有邊框、隨手可拿掉 */
.answering {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-left: 10px;
  border-left: 2px solid var(--border-soft);
  color: var(--ink-mute);
}

.answering-label {
  flex: none;
  font: 400 15px/1.6 var(--sans);
  color: var(--ink-faint);
}

.answering-text {
  flex: 1;
  font: 400 17px/1.5 var(--serif);
  color: var(--ink-soft);
}

.answering-drop {
  flex: none;
  font-size: 14px;
  color: var(--ink-faint);
  opacity: 0.7;
  transition: opacity 0.25s ease;
}

.answering-drop:hover {
  opacity: 1;
}

.composer-input {
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  padding: 0;
  font: 400 20px/1.6 var(--sans);
  color: var(--ink);
  /* 兩行是起點不是天花板：空框的呼吸感留著，高度交給 autosizeComposer。
     max-height 要跟 COMPOSER_MAX_HEIGHT 同一個數字 */
  min-height: 64px;
  max-height: 320px;
  overflow-y: hidden;
  /* 真的寫滿才出現的那條，也要是暖的——原生捲軸在這一頁像另一個世界 */
  scrollbar-width: thin;
  scrollbar-color: var(--ink-faint) transparent;
}

/* 舊版 WebKit 不吃上面的標準屬性，那條帶箭頭的原生捲軸會照樣冒出來 */
.composer-input::-webkit-scrollbar {
  width: 6px;
}

.composer-input::-webkit-scrollbar-thumb {
  background: var(--ink-faint);
  border-radius: 3px;
}

.composer-input::-webkit-scrollbar-track {
  background: transparent;
}

@media (prefers-reduced-motion: reduce) {
  .composer {
    transition: none;
  }
}

.composer-input::placeholder {
  color: var(--ink-mute);
}

.composer-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
}

.photo-btn {
  font-size: 23px;
  opacity: 0.6;
  transition: opacity 0.25s ease;
}

.photo-btn:hover {
  opacity: 1;
}

.photo-preview {
  position: relative;
  align-self: flex-start;
}

.photo-preview img {
  max-height: 96px;
  border-radius: 12px;
  display: block;
}

.photo-remove {
  position: absolute;
  top: -8px;
  right: -8px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ink-soft);
  color: var(--bg-card);
  font-size: 14px;
}

.send-btn {
  background: var(--accent);
  color: var(--accent-contrast);
  padding: 10px 22px;
  border-radius: 20px;
  font: 600 18px var(--sans);
  transition: background 0.25s ease;
}

.send-btn:hover {
  background: var(--accent-deep);
}

.composer-hint {
  font: 400 17px var(--sans);
  color: var(--ink-mute);
  margin-top: 16px;
}

/* 已種下（唯讀） */
.diary-area {
  display: flex;
  flex-direction: column;
  gap: 22px;
  max-width: 810px;
}

.diary-card {
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 22px;
  padding: 28px 32px;
  animation: ts-fade-in 0.5s ease;
}

.diary-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.emotion-label {
  font: 500 17px var(--sans);
  color: var(--ink-soft);
}

.diary-text {
  font: 400 20px/2 var(--sans);
  color: var(--ink);
  white-space: pre-wrap;
  text-wrap: pretty;
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

.tomorrow {
  margin-top: 6px;
  font: 400 17px var(--sans);
  color: var(--ink-mute);
}

/* 錯誤（療癒文案） */
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
  animation: ts-fade-in 0.4s ease;
  z-index: 60;
}

/* 種樹全螢幕 */
.grow-overlay {
  position: fixed;
  inset: 0;
  z-index: 40;
  background: linear-gradient(160deg, oklch(48% 0.08 55), oklch(28% 0.06 25));
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 64px 80px;
  overflow-y: auto;
  animation: ts-fade-in 0.6s ease;
}

.grow-msg {
  align-self: flex-end;
  background: oklch(94% 0.02 78 / 0.9);
  color: var(--ink);
  padding: 14px 18px;
  border-radius: 18px 18px 4px 18px;
  font: 400 19px/1.6 var(--sans);
  max-width: 585px;
  transition: opacity 0.8s ease;
}

/* 保留佔位，樹不會往上跳 */
.grow-msg.absorbed {
  opacity: 0;
}

.grow-tree {
  position: relative;
  width: 280px;
  height: 220px;
  margin-top: 32px;
  flex-shrink: 0;
}

.glow {
  position: absolute;
  left: 50%;
  top: 20px;
  transform: translateX(-50%);
  width: 200px;
  height: 200px;
  border-radius: 50%;
  background: radial-gradient(circle, oklch(85% 0.09 45 / 0.5), transparent 70%);
  filter: blur(6px);
  animation: ts-breathe 5s ease-in-out infinite;
}

.g-trunk {
  position: absolute;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  width: 16px;
  height: 70px;
  background: var(--trunk);
  border-radius: 6px;
}

.g-c1 {
  position: absolute;
  bottom: 56px;
  left: 50%;
  transform: translateX(-50%);
  width: 110px;
  height: 110px;
  border-radius: 50%;
  background: oklch(78% 0.1 35);
  animation: ts-breathe 5s ease-in-out infinite;
}

.g-c2 {
  position: absolute;
  bottom: 78px;
  left: 28%;
  width: 76px;
  height: 76px;
  border-radius: 50%;
  background: oklch(85% 0.08 35);
  animation: ts-breathe 5s ease-in-out infinite 0.3s;
}

.g-c3 {
  position: absolute;
  bottom: 86px;
  left: 56%;
  width: 64px;
  height: 64px;
  border-radius: 50%;
  background: oklch(70% 0.12 35);
  animation: ts-breathe 5s ease-in-out infinite 0.6s;
}

.spark {
  position: absolute;
  border-radius: 50%;
  background: oklch(92% 0.05 80);
}

.s1 {
  bottom: 120px;
  left: 30%;
  width: 6px;
  height: 6px;
  animation: ts-rise 3s ease-in infinite;
}

.s2 {
  bottom: 130px;
  left: 60%;
  width: 5px;
  height: 5px;
  animation: ts-rise 3.4s ease-in infinite 0.8s;
}

.s3 {
  bottom: 110px;
  left: 48%;
  width: 5px;
  height: 5px;
  animation: ts-rise 3.8s ease-in infinite 1.6s;
}

.grow-wait {
  margin-top: 28px;
  text-align: center;
}

.grow-wait-title {
  font: 600 25px var(--serif);
  color: oklch(96% 0.015 80);
}

.grow-wait-sub {
  margin-top: 8px;
  font: 400 18px var(--sans);
  color: oklch(90% 0.02 80 / 0.7);
}

.grow-diary {
  margin-top: 28px;
  background: oklch(98% 0.01 80 / 0.96);
  border-radius: 18px;
  padding: 22px 26px;
  max-width: 710px;
  width: 100%;
  animation: ts-fade-in 0.6s ease;
}

.grow-diary-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font: 500 17px var(--sans);
  color: var(--ink-soft);
  margin-bottom: 10px;
}

.grow-diary-text {
  font: 400 19px/1.9 var(--sans);
  color: var(--ink);
  white-space: pre-wrap;
}

.grow-reply-row {
  align-self: flex-start;
  display: flex;
  gap: 14px;
  margin-top: 24px;
  max-width: 710px;
}

.grow-reply {
  background: oklch(98% 0.01 80 / 0.96);
  color: var(--ink);
  padding: 16px 20px;
  border-radius: 4px 18px 18px 18px;
  font: 400 20px/1.8 var(--sans);
}

.grow-spacer {
  flex: 1;
  min-height: 30px;
}

.grow-footer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  opacity: 0;
  transition: opacity 0.8s ease;
}

.grow-footer.ready {
  opacity: 1;
}

.grow-pill {
  background: oklch(97% 0.015 80 / 0.15);
  border: 1px solid oklch(97% 0.015 80 / 0.35);
  color: oklch(97% 0.015 80);
  padding: 12px 28px;
  border-radius: 20px;
  font: 600 18px var(--sans);
  transition: background 0.3s ease;
}

.grow-pill:hover {
  background: oklch(97% 0.015 80 / 0.28);
}

.grow-retry {
  font: 400 17px var(--sans);
  color: oklch(90% 0.02 80 / 0.6);
}

.grow-retry:hover {
  color: oklch(90% 0.02 80);
}
</style>
