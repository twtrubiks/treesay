// 7 種情緒的顏色與中文標籤。
// happy/calm/sad/tired/excited 五組色票取自設計稿，anxious/angry 依同色系規則補足。

import type { Emotion } from './types'

export interface EmotionMeta {
  label: string
  c1: string
  c2: string
  c3: string
}

export const EMOTION_META: Record<Emotion, EmotionMeta> = {
  happy: {
    label: '開心',
    c1: 'oklch(80% 0.13 85)',
    c2: 'oklch(88% 0.10 85)',
    c3: 'oklch(72% 0.14 85)',
  },
  calm: {
    label: '平靜',
    c1: 'oklch(78% 0.06 190)',
    c2: 'oklch(85% 0.05 190)',
    c3: 'oklch(70% 0.07 190)',
  },
  excited: {
    label: '期待',
    c1: 'oklch(78% 0.10 35)',
    c2: 'oklch(85% 0.08 35)',
    c3: 'oklch(70% 0.12 35)',
  },
  tired: {
    label: '疲憊',
    c1: 'oklch(72% 0.04 290)',
    c2: 'oklch(80% 0.03 290)',
    c3: 'oklch(64% 0.05 290)',
  },
  sad: {
    label: '難過',
    c1: 'oklch(75% 0.07 250)',
    c2: 'oklch(82% 0.05 250)',
    c3: 'oklch(66% 0.08 250)',
  },
  anxious: {
    label: '不安',
    c1: 'oklch(74% 0.05 320)',
    c2: 'oklch(82% 0.04 320)',
    c3: 'oklch(66% 0.06 320)',
  },
  angry: {
    label: '生氣',
    c1: 'oklch(72% 0.09 20)',
    c2: 'oklch(80% 0.07 20)',
    c3: 'oklch(64% 0.11 20)',
  },
}

// 尚未判定情緒時的中性生長樹（同設計稿 logo 綠）
export const NEUTRAL_TREE: EmotionMeta = {
  label: '生長中',
  c1: 'oklch(70% 0.09 140)',
  c2: 'oklch(78% 0.08 140)',
  c3: 'oklch(64% 0.09 140)',
}
