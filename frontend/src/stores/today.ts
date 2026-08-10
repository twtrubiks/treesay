import { defineStore } from 'pinia'
import { ref } from 'vue'

import { api, ApiError } from '@/api'
import type { TodayResponse } from '@/types'

export const useTodayStore = defineStore('today', () => {
  const today = ref<TodayResponse | null>(null)
  const planting = ref(false)
  const error = ref<string | null>(null)

  async function load() {
    today.value = await api.getToday()
  }

  async function send(content: string, photo?: File) {
    error.value = null
    try {
      const msg = await api.sendMessage(content, photo)
      today.value?.messages.push(msg)
    } catch (e) {
      error.value = e instanceof ApiError ? e.message : '樹睡著了，等等再來 🌙'
      throw e
    }
  }

  async function edit(id: number, content: string) {
    const msg = await api.updateMessage(id, content)
    if (today.value) {
      today.value.messages = today.value.messages.map((m) => (m.id === id ? msg : m))
    }
  }

  async function remove(id: number) {
    await api.deleteMessage(id)
    if (today.value) {
      today.value.messages = today.value.messages.filter((m) => m.id !== id)
    }
  }

  async function plant() {
    if (planting.value) return
    planting.value = true
    error.value = null
    try {
      const day = await api.plant()
      if (today.value) today.value = { ...today.value, ...day }
    } catch (e) {
      error.value = e instanceof ApiError ? e.message : '樹睡著了，等等再來 🌙'
      throw e
    } finally {
      planting.value = false
    }
  }

  return { today, planting, error, load, send, edit, remove, plant }
})
