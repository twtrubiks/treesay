<script setup lang="ts">
// 設計稿的圓圈樹：樹幹＋三顆圓葉，依情緒上色；未判定時為中性綠
import { computed } from 'vue'

import { EMOTION_META, NEUTRAL_TREE } from '@/emotions'
import type { Emotion } from '@/types'

const props = withDefaults(
  defineProps<{
    emotion?: Emotion | null
    width?: number
    breathe?: boolean
  }>(),
  { emotion: null, width: 28, breathe: false },
)

const meta = computed(() => (props.emotion ? EMOTION_META[props.emotion] : NEUTRAL_TREE))
// 設計稿基準尺寸 28×34，等比縮放
const s = computed(() => props.width / 28)
</script>

<template>
  <div
    class="tree"
    :style="{ width: `${width}px`, height: `${34 * s}px` }"
    :title="emotion ? meta.label : undefined"
  >
    <div
      class="trunk"
      :style="{ width: `${4 * s}px`, height: `${12 * s}px`, bottom: 0 }"
    ></div>
    <div
      class="leaf breathing-0"
      :class="{ breathing: breathe }"
      :style="{
        width: `${20 * s}px`,
        height: `${20 * s}px`,
        bottom: `${9 * s}px`,
        left: '50%',
        transform: 'translateX(-50%)',
        background: meta.c1,
      }"
    ></div>
    <div
      class="leaf breathing-1"
      :class="{ breathing: breathe }"
      :style="{
        width: `${14 * s}px`,
        height: `${14 * s}px`,
        bottom: `${13 * s}px`,
        left: '33%',
        background: meta.c2,
      }"
    ></div>
    <div
      class="leaf breathing-2"
      :class="{ breathing: breathe }"
      :style="{
        width: `${12 * s}px`,
        height: `${12 * s}px`,
        bottom: `${14 * s}px`,
        left: '56%',
        background: meta.c3,
      }"
    ></div>
  </div>
</template>

<style scoped>
.tree {
  position: relative;
  flex-shrink: 0;
}

.trunk {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  background: var(--trunk);
  border-radius: 2px;
}

.leaf {
  position: absolute;
  border-radius: 50%;
}

.leaf.breathing {
  animation: ts-breathe 5s ease-in-out infinite;
}

.leaf.breathing-1.breathing {
  animation-delay: 0.3s;
}

.leaf.breathing-2.breathing {
  animation-delay: 0.6s;
}
</style>
