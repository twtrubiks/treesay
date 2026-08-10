import { createRouter, createWebHistory } from 'vue-router'

import TodayView from '../views/TodayView.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'today',
      component: TodayView,
    },
    {
      path: '/forest',
      name: 'forest',
      component: () => import('../views/ForestView.vue'),
    },
    {
      path: '/forest/:date',
      name: 'day-detail',
      component: () => import('../views/DayDetailView.vue'),
    },
    {
      path: '/report',
      name: 'report',
      component: () => import('../views/ReportView.vue'),
    },
    {
      path: '/memories',
      name: 'memories',
      component: () => import('../views/MemoryView.vue'),
    },
    // 沒對上的網址（打錯的、過期的書籤）安靜回首頁，不留空白畫面
    {
      path: '/:pathMatch(.*)*',
      redirect: { name: 'today' },
    },
  ],
})

export default router
