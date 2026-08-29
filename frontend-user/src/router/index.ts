import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true, hideTab: true } },
    { path: '/register', component: () => import('../views/RegisterView.vue'), meta: { public: true, hideTab: true } },
    { path: '/home', component: () => import('../views/HomeView.vue'), meta: { title: '首页' } },
    { path: '/health', component: () => import('../views/VitalsView.vue'), meta: { title: '健康' } },
    { path: '/discover', component: () => import('../views/AgentView.vue'), meta: { title: '发现' } },
    { path: '/me', component: () => import('../views/ProfileView.vue'), meta: { title: '我的' } },
    { path: '/join', component: () => import('../views/JoinView.vue'), meta: { hideTab: true } },
    { path: '/profile', redirect: '/me' },
    { path: '/vitals', redirect: '/health' },
    { path: '/agent', redirect: '/discover' },
  ],
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!localStorage.getItem('healix_c_token')) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
