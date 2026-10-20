<script setup lang="ts">
// 設計稿的圓圈樹：樹幹＋三顆圓葉，依情緒上色；未判定時為中性綠
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

import { EMOTION_META, NEUTRAL_TREE } from '@/emotions'
import { reduceMotion } from '@/motion'
import { SEASON_TREE, sky, VISITOR_META, visitorFor } from '@/season'
import type { Visitor } from '@/season'
import type { Emotion } from '@/types'

const props = withDefaults(
  defineProps<{
    emotion?: Emotion | null
    width?: number
    breathe?: boolean
    // 閒置動畫：葉子被風輕輕推著。只是「它在這裡」——
    // 不隨訊息多少、情緒、天數變化，不帶任何狀態意義
    idle?: boolean
    // 隨真實季節與時段換樣貌（只對還沒有情緒的中性樹生效；情緒色是語意，不蓋）。
    // 偶爾來訪的小東西也只跟著季節來（要同時開 idle）：花瓣、飛絮、螢火蟲、落葉、雪
    seasonal?: boolean
  }>(),
  { emotion: null, width: 28, breathe: false, idle: false, seasonal: false },
)

// 季節與時段只看系統時鐘（season.ts 的 sky，整個畫面共用一個節拍）。
// 有情緒的樹不吃季節：入夜不調暗情緒色，也不讓東西從它身上飄下來
const seasonOn = computed(() => props.seasonal && !props.emotion)

const meta = computed(() => {
  if (props.emotion) return EMOTION_META[props.emotion]
  return props.seasonal ? SEASON_TREE[sky.value.season] : NEUTRAL_TREE
})
const night = computed(() => seasonOn.value && sky.value.night)
// 設計稿基準尺寸 28×34，等比縮放
const s = computed(() => props.width / 28)

// 偶爾有小東西來訪。先隨機等 40～100 秒，再等下一陣風最大時出發（見 visit），
// 所以實際間隔是 40～109 秒、落在風 9 秒一陣的節拍上：規律的節拍會被讀成計時器，
// 不規律才像風。剛打開頁面時第一次來得早一點：先等 6～14 秒再對上風，
// 幾乎都落在第 13.5 秒那陣風，之後就隨緣。
// 分頁不在前面時這次跳過，照常隔 40～100 秒再看一次。
// 沒有季節的樹不來訪：情緒樹掉的會是自己的葉子，顏色就是今天的情緒，
// 難過、疲憊的那棵一直掉葉子會被讀成凋零
const visiting = computed(() => props.idle && seasonOn.value && !reduceMotion.value)
const FIRST_VISIT_MS = 6_000
const FIRST_VISIT_SPAN_MS = 8_000
const VISIT_MS = 40_000
const VISIT_SPAN_MS = 60_000

// 風每 9 秒一陣（ts-gust-root），最大的那一刻在 46%，樹冠再晚 0.35 秒跟上。
// 來訪者對準那一刻出發——是風把它們吹下來的，不是自己掉的。
// 風的 CSS 動畫從 idle 開始那刻起算，所以記下時間就能算到下一次風最大是什麼時候
const GUST_MS = 9_000
const GUST_PEAK_MS = 4_500
let idleStart = 0

// 各種來訪者飄完一趟的秒數：花瓣快，雪和螢火蟲慢
const DRIFT_S: Record<Visitor, number> = {
  petal: 4.2,
  fluff: 6,
  firefly: 7,
  leaf: 4.8,
  snow: 7,
}

// 一次來 2～3 個，從樹冠不同的位置錯開出發；每一個的路徑都是現場抽的
interface Drifter {
  id: number
  kind: Visitor
  color?: string
  style: Record<string, string>
}
const drifters = ref<Drifter[]>([])
let nextDrifterId = 0
let visitTimer: ReturnType<typeof setTimeout> | undefined
const driftTimers = new Set<ReturnType<typeof setTimeout>>()

function scheduleVisit(base: number, span: number) {
  clearTimeout(visitTimer)
  visitTimer = setTimeout(visit, base + Math.random() * span)
}

function visit() {
  if (document.visibilityState !== 'visible') {
    scheduleVisit(VISIT_MS, VISIT_SPAN_MS)
    return
  }
  const phase = (performance.now() - idleStart) % GUST_MS
  const wait = (GUST_PEAK_MS - phase + GUST_MS) % GUST_MS
  visitTimer = setTimeout(() => {
    spawnGroup()
    scheduleVisit(VISIT_MS, VISIT_SPAN_MS)
  }, wait)
}

function later(ms: number, fn: () => void) {
  const t = setTimeout(() => {
    driftTimers.delete(t)
    fn()
  }, ms)
  driftTimers.add(t)
}

function spawnGroup() {
  const kind = visitorFor(sky.value)
  const color = VISITOR_META[kind].color
  const n = 2 + (Math.random() < 0.5 ? 1 : 0)
  for (let i = 0; i < n; i++) {
    later(i * 320 + Math.random() * 260, () => spawnOne(kind, color))
  }
}

function spawnOne(kind: Visitor, color: string | undefined) {
  const k = s.value
  const rise = VISITOR_META[kind].rise
  // 起點落在樹冠範圍內，順著風往左走（樹右邊是種樹按鈕）：落下的飄到地面，
  // 飛絮往上飄遠；只有螢火蟲是自己飛的，隨便挑一邊
  const y = (11 + Math.random() * 12) * k
  const dir = kind === 'firefly' && Math.random() < 0.5 ? 1 : -1
  const side = dir * (5 + Math.random() * 5) * k
  const dist = rise ? -(20 + Math.random() * 8) * k : y
  const dur = DRIFT_S[kind] * (0.85 + Math.random() * 0.3)
  const id = nextDrifterId++
  drifters.value.push({
    id,
    kind,
    color,
    style: {
      left: `${28 + Math.random() * 46}%`,
      bottom: `${y}px`,
      '--side': `${side}px`,
      '--dist': `${dist}px`,
      '--dur': `${dur}s`,
      '--sway': `${(1.5 + Math.random() * 1.5) * k * 0.7}px`,
      // 形狀隨樹長大，但不等比：90px 的樹上花瓣不該有葉子的三分之一大
      '--k': `${0.6 + 0.4 * k}`,
      '--ph': `${-Math.random() * 3}s`,
    },
  })
  later(dur * 1000 + 100, () => {
    drifters.value = drifters.value.filter((d) => d.id !== id)
  })
}

function startIdle() {
  if (!visiting.value) return
  idleStart = performance.now()
  scheduleVisit(FIRST_VISIT_MS, FIRST_VISIT_SPAN_MS)
}

function stopIdle() {
  clearTimeout(visitTimer)
  driftTimers.forEach(clearTimeout)
  driftTimers.clear()
  drifters.value = []
}

// 側邊欄切「關」時正在飄的那一個也收掉；切回「開」重新排
watch(visiting, (on) => (on ? startIdle() : stopIdle()))

onMounted(startIdle)
onUnmounted(stopIdle)
</script>

<template>
  <div
    class="tree"
    :class="{ idle, seasonal: seasonOn, night, quiet: reduceMotion }"
    :style="{ width: `${width}px`, height: `${34 * s}px`, '--moon': `${5 * s}px` }"
    :title="emotion ? meta.label : undefined"
  >
    <!-- 風分三層：樹身從根部彎、樹冠從樹幹頂再多彎一截、葉子只從底部微顫。
         葉子的幅度刻意很小——顫得太大三顆圓會互相滑來滑去，看起來不像風，像零件鬆了 -->
    <div class="body">
      <div
        class="trunk"
        :style="{ width: `${4 * s}px`, height: `${12 * s}px`, bottom: 0 }"
      ></div>
      <!-- 呼吸是整個樹冠一起從樹幹頂端脹縮，不錯開 -->
      <div class="crown" :class="{ breathing: breathe }">
        <div
          class="leaf l1"
          :style="{
            width: `${20 * s}px`,
            height: `${20 * s}px`,
            bottom: `${9 * s}px`,
            left: '50%',
            translate: '-50% 0',
            background: meta.c1,
          }"
        ></div>
        <div
          class="leaf l2"
          :style="{
            width: `${14 * s}px`,
            height: `${14 * s}px`,
            bottom: `${13 * s}px`,
            left: '33%',
            background: meta.c2,
          }"
        ></div>
        <div
          class="leaf l3"
          :style="{
            width: `${12 * s}px`,
            height: `${12 * s}px`,
            bottom: `${14 * s}px`,
            left: '56%',
            background: meta.c3,
          }"
        ></div>
      </div>
    </div>
    <!-- 來訪的小東西：外層管整趟路（落到地面或飄遠、到點前淡掉），
         內層管形狀、左右搖和翻滾。放在樹身外面，不跟著彎 -->
    <div v-for="d in drifters" :key="d.id" class="vis" :class="`vis-${d.kind}`" :style="d.style">
      <div
        class="vis-shape"
        :style="d.color ? { background: d.color } : undefined"
      ></div>
    </div>
  </div>
</template>

<style scoped>
.tree {
  position: relative;
  flex-shrink: 0;
}

.body {
  position: absolute;
  inset: 0;
  /* 從根部彎 */
  transform-origin: 50% 100%;
}

.trunk {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  background: var(--trunk);
  border-radius: 2px;
}

.crown {
  position: absolute;
  inset: 0;
  /* 樹冠最低點落在離地 9/34 處，呼吸從那裡（約樹幹頂端）脹縮 */
  transform-origin: 50% 73.5%;
}

/* 呼吸用 scale 個別屬性，跟下面風的 rotate 互不打架，兩個動畫能疊在同一層。
   main.css 的 ts-breathe 動 transform、幅度 1.035，在這麼小的樹冠上不到 1px，看不見 */
.crown.breathing {
  animation: ts-crown-breathe 5s ease-in-out infinite;
}

.leaf {
  position: absolute;
  border-radius: 50%;
  /* 從葉子底部微顫：旋轉變成葉尖的小位移，不是葉子在滑 */
  transform-origin: 50% 100%;
}

/* 換季與入夜都慢慢過去，不要 18:00 一到就啪一聲換掉。只給季節樹：
   森林換月份時同一格會沿用同一棵樹，情緒色要是也慢慢變，中途的混色會被讀成別的情緒 */
.tree.seasonal .leaf {
  transition:
    background 1.2s ease,
    filter 1.2s ease,
    box-shadow 1.2s ease;
}

/* 入夜：葉色沉一點、冷一點，像月光照著 */
.tree.night .leaf {
  filter: saturate(0.78) brightness(0.9);
  box-shadow: 0 0 var(--moon) oklch(88% 0.03 240 / 0.3);
}

/* 風：不是鐘擺。被推過去、停一下、再補一下，然後慢慢回正、略過頭。
   風只從右邊來，所以只往左彎（CSS 的 rotate 負值是逆時針，樹頂往左），
   跟 spawnOne 裡來訪者飄的方向是同一陣風，要改一起改。分三層：根部彎得少，樹冠從樹幹頂再多彎一截、
   慢 0.35 秒跟上（樹冠比樹幹軟），三片葉各自以互不整除的週期微顫——
   疊起來整棵樹的動作不會每 9 秒重播一次，不會被讀成計時器。
   整棵樹從根部彎 4° 在 34px 的樹上樹頂只動 3px，看不見，所以要分層把幅度做出來 */
.tree.idle .body {
  animation: ts-gust-root 9s ease-in-out infinite;
}

.tree.idle .crown {
  animation: ts-gust-crown 9s ease-in-out 0.35s infinite;
}

.tree.idle .crown.breathing {
  animation:
    ts-gust-crown 9s ease-in-out 0.35s infinite,
    ts-crown-breathe 5s ease-in-out infinite;
}

.tree.idle .l1 {
  animation: ts-flutter 3.7s ease-in-out infinite;
}

.tree.idle .l2 {
  animation: ts-flutter 4.3s ease-in-out -1.1s infinite;
}

.tree.idle .l3 {
  animation: ts-flutter 5.1s ease-in-out -2.4s infinite;
}

@keyframes ts-gust-root {
  0% {
    rotate: 0deg;
  }
  22% {
    rotate: -3.6deg;
  }
  34% {
    rotate: -2.2deg;
  }
  46% {
    rotate: -4.2deg;
  }
  80% {
    rotate: 0.9deg;
  }
  100% {
    rotate: 0deg;
  }
}

@keyframes ts-gust-crown {
  0% {
    rotate: 0deg;
  }
  22% {
    rotate: -7.5deg;
  }
  34% {
    rotate: -4.5deg;
  }
  46% {
    rotate: -9deg;
  }
  80% {
    rotate: 2.1deg;
  }
  100% {
    rotate: 0deg;
  }
}

@keyframes ts-crown-breathe {
  0%,
  100% {
    scale: 1;
  }
  50% {
    scale: 1.055;
  }
}

@keyframes ts-flutter {
  0%,
  100% {
    rotate: 1.6deg;
  }
  50% {
    rotate: -2.4deg;
  }
}

/* 來訪者。外層走整趟路（translate ＋ 淡入淡出），內層搖（translate）和翻（rotate）——
   分兩層，直線就變成一條會晃的路。落下的用前慢後快的曲線，雪等速，上升的前快後慢 */
.vis {
  position: absolute;
  opacity: 0;
  /* 飄出樹的範圍時不擋到底下的東西 */
  pointer-events: none;
  animation: ts-vis-fall var(--dur) cubic-bezier(0.35, 0.1, 0.7, 1) forwards;
}

.vis-snow {
  animation-timing-function: linear;
}

.vis-fluff,
.vis-firefly {
  animation-name: ts-vis-rise;
  animation-timing-function: ease-out;
}

.vis-shape {
  width: calc(6px * var(--k));
  height: calc(4px * var(--k));
  border-radius: 50%;
  animation:
    ts-sway 1.6s ease-in-out var(--ph) infinite alternate,
    ts-tumble 2.6s ease-in-out var(--ph) infinite;
}

/* 花瓣：扁橢圓、兩頭顏色不同，翻起來才看得出在翻 */
.vis-petal .vis-shape {
  background: linear-gradient(90deg, oklch(90% 0.06 350), oklch(84% 0.1 350));
}

/* 落葉：葉形，往另一邊滾 */
.vis-leaf .vis-shape {
  width: calc(7px * var(--k));
  height: calc(4.5px * var(--k));
  border-radius: 70% 0 70% 0;
  animation-name: ts-sway, ts-tumble-back;
  animation-duration: 1.4s, 3s;
}

/* 雪：圓的，不翻，只慢慢左右飄 */
.vis-snow .vis-shape {
  width: calc(4px * var(--k));
  height: calc(4px * var(--k));
  box-shadow: 0 0 2px oklch(100% 0 0 / 0.8);
  animation: ts-sway 2.4s ease-in-out var(--ph) infinite alternate;
}

/* 飛絮：毛茸茸的，邊緣糊掉 */
.vis-fluff .vis-shape {
  width: calc(5px * var(--k));
  height: calc(5px * var(--k));
  filter: blur(0.4px);
  opacity: 0.9;
  animation: ts-sway 2.8s ease-in-out var(--ph) infinite alternate;
}

/* 螢火蟲：小、帶光暈、一明一滅，路線比較亂 */
.vis-firefly .vis-shape {
  width: calc(3px * var(--k));
  height: calc(3px * var(--k));
  box-shadow: 0 0 calc(5px * var(--k)) calc(2px * var(--k)) oklch(90% 0.14 95 / 0.4);
  animation:
    ts-sway 1.1s ease-in-out var(--ph) infinite alternate,
    ts-blink 1.2s ease-in-out var(--ph) infinite alternate;
}

@keyframes ts-vis-fall {
  0% {
    opacity: 0;
    translate: 0 0;
  }
  10% {
    opacity: 0.95;
  }
  82% {
    opacity: 0.9;
  }
  100% {
    opacity: 0;
    translate: var(--side) var(--dist);
  }
}

@keyframes ts-vis-rise {
  0% {
    opacity: 0;
    translate: 0 0;
  }
  12% {
    opacity: 0.95;
  }
  75% {
    opacity: 0.8;
  }
  100% {
    opacity: 0;
    translate: var(--side) var(--dist);
  }
}

@keyframes ts-sway {
  from {
    translate: calc(var(--sway) * -1) 0;
  }
  to {
    translate: var(--sway) 0;
  }
}

@keyframes ts-tumble {
  from {
    rotate: 0deg;
  }
  to {
    rotate: 360deg;
  }
}

@keyframes ts-tumble-back {
  from {
    rotate: 0deg;
  }
  to {
    rotate: -360deg;
  }
}

@keyframes ts-blink {
  from {
    opacity: 0.25;
  }
  to {
    opacity: 1;
  }
}

/* 畫面安靜（側邊欄的「動態」切關、或跟系統且系統要求減少動態）：不彎、不呼吸、不顫。
   見 motion.ts。寫成 class 而不是 @media，決定權才在 App 手上。
   放在最後，壓過上面 .tree.idle 的規則（來訪者由 script 端不排程） */
.tree.quiet .body,
.tree.quiet .crown,
.tree.quiet .crown.breathing,
.tree.quiet .leaf {
  animation: none;
}
</style>
