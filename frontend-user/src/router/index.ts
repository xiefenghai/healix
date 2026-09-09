import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true, hideTab: true } },
    { path: '/register', component: () => import('../views/RegisterView.vue'), meta: { public: true, hideTab: true } },
    { path: '/activate', component: () => import('../views/ActivateView.vue'), meta: { public: true, hideTab: true } },
    { path: '/patient-cards', component: () => import('../views/PatientCardsView.vue'), meta: { hideTab: true } },
    { path: '/home', component: () => import('../views/HomeView.vue'), meta: { title: '首页' } },
    { path: '/archive', component: () => import('../views/ArchiveHubView.vue'), meta: { hideTab: true } },
    { path: '/archive/lifestyle', component: () => import('../views/ArchiveLifestyleView.vue'), meta: { hideTab: true } },
    { path: '/archive/basic', component: () => import('../views/ArchiveBasicView.vue'), meta: { hideTab: true } },
    {
      path: '/archive/disease/:diseaseCode',
      component: () => import('../views/ArchiveDiseaseView.vue'),
      meta: { hideTab: true },
    },
    { path: '/health-data', component: () => import('../views/HealthDataHubView.vue'), meta: { hideTab: true } },
    {
      path: '/health-data/labs/create',
      component: () => import('../views/LabCreateView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/health-data/labs/:id/edit',
      component: () => import('../views/LabCreateView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/health-data/labs/:id',
      component: () => import('../views/HealthReportDetailView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/health-data/exams/create',
      component: () => import('../views/ExamCreateView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/health-data/exams/:id/edit',
      component: () => import('../views/ExamCreateView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/health-data/exams/:id',
      component: () => import('../views/HealthReportDetailView.vue'),
      meta: { hideTab: true },
    },
    { path: '/care-plan', component: () => import('../views/CarePlanView.vue'), meta: { hideTab: true } },
    { path: '/medications', component: () => import('../views/MedicationsView.vue'), meta: { hideTab: true } },
    {
      path: '/management-reports',
      component: () => import('../views/ManagementReportsView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/management-reports/:id',
      component: () => import('../views/ManagementReportDetailView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/followups',
      component: () => import('../views/FollowupsView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/followups/:id',
      component: () => import('../views/FollowupDetailView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/notifications',
      component: () => import('../views/NotificationsView.vue'),
      meta: { hideTab: true },
    },
    { path: '/health', component: () => import('../views/HealthDataHubView.vue'), meta: { title: '健康' } },
    { path: '/health/record', component: () => import('../views/VitalsView.vue'), meta: { hideTab: true } },
    { path: '/discover', component: () => import('../views/AgentView.vue'), meta: { title: '发现' } },
    { path: '/me', component: () => import('../views/ProfileView.vue'), meta: { title: '我的' } },
    { path: '/join', component: () => import('../views/JoinView.vue'), meta: { hideTab: true } },
    { path: '/profile', redirect: '/me' },
    { path: '/vitals', redirect: '/health/record' },
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
