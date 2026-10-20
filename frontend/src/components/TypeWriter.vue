<script setup lang="ts">
// 拿到完整文字後在本地做打字機效果（不做 SSE 串流）
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { reduceMotion } from '@/motion'

const props = withDefaults(defineProps<{ text: string; speed?: number }>(), { speed: 55 })
const emit = defineEmits<{ done: [] }>()

const shown = ref('')
let timer: number | undefined

function start() {
  window.clearInterval(timer)
  shown.value = ''
  let i = 0
  timer = window.setInterval(() => {
    i += 1
    shown.value = props.text.slice(0, i)
    if (i >= props.text.length) {
      window.clearInterval(timer)
      emit('done')
    }
  }, props.speed)
}

onMounted(start)
watch(() => props.text, start)
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <!-- 畫面安靜時游標不閃，字照樣一個一個出來：那是樹回話的節奏，不是裝飾 -->
  <span>{{ shown }}<span v-if="shown.length < text.length" class="cursor" :class="{ quiet: reduceMotion }"></span></span>
</template>

<style scoped>
.cursor {
  display: inline-block;
  width: 3px;
  height: 17px;
  background: currentColor;
  margin-left: 3px;
  vertical-align: middle;
  animation: ts-cursor 1s steps(1) infinite;
}

.cursor.quiet {
  animation: none;
}
</style>
