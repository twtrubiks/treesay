<script setup lang="ts">
// 拿到完整文字後在本地做打字機效果（不做 SSE 串流）
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

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
  <span>{{ shown }}<span v-if="shown.length < text.length" class="cursor"></span></span>
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
</style>
