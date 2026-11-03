import { createRouter, createWebHistory } from 'vue-router'

/**
 * C 端路由。
 * 底栏舵式：首页 · 健康 · ＋ · 沟通 · 我的；「＋」打开快速记录。
 * AI管家在「我的」入口（/ai，hideTab）。
 * `/health`：底栏 Tab；`/health-data`：同组件 hideTab 堆叠页。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    { path: '/login', component: () => import('../views/LoginView.vue'), meta: { public: true, hideTab: true } },
    { path: '/register', component: () => import('../views/RegisterView.vue'), meta: { public: true, hideTab: true } },
    { path: '/activate', component: () => import('../views/ActivateView.vue'), meta: { public: true, hideTab: true } },
    {
      path: '/intake/t/:token',
      component: () => import('../views/IntakeAccessLandingView.vue'),
      meta: { public: true, hideTab: true },
    },
    {
      path: '/legal/:doc(terms|privacy)',
      component: () => import('../views/LegalView.vue'),
      meta: { public: true, hideTab: true },
    },
    { path: '/patient-cards', component: () => import('../views/PatientCardsView.vue'), meta: { hideTab: true } },
    { path: '/home', component: () => import('../views/HomeView.vue'), meta: { title: '首页' } },
    { path: '/ai', component: () => import('../views/AiButlerView.vue'), meta: { title: 'AI管家', hideTab: true } },
    { path: '/chat', component: () => import('../views/ChatHubView.vue'), meta: { title: '沟通' } },
    { path: '/archive', component: () => import('../views/ArchiveHubView.vue'), meta: { hideTab: true } },
    { path: '/archive/lifestyle', component: () => import('../views/ArchiveLifestyleView.vue'), meta: { hideTab: true } },
    { path: '/archive/basic', component: () => import('../views/ArchiveBasicView.vue'), meta: { hideTab: true } },
    { path: '/archive/allergies', component: () => import('../views/AllergyView.vue'), meta: { hideTab: true } },
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
    { path: '/achievements', component: () => import('../views/AchievementsView.vue'), meta: { hideTab: true } },
    { path: '/science', component: () => import('../views/ScienceView.vue'), meta: { hideTab: true } },
    { path: '/medications', component: () => import('../views/MedicationsView.vue'), meta: { hideTab: true } },
    {
      path: '/medications/reminder',
      component: () => import('../views/MedicationReminderView.vue'),
      meta: { hideTab: true },
    },
    { path: '/medications/scan', component: () => import('../views/MedicationScanView.vue'), meta: { hideTab: true } },
    { path: '/medications/:id', component: () => import('../views/MedicationDetailView.vue'), meta: { hideTab: true } },
    { path: '/diet', component: () => import('../views/DietView.vue'), meta: { hideTab: true } },
    { path: '/diet/goal', component: () => import('../views/DietGoalView.vue'), meta: { hideTab: true } },
    { path: '/diet/add', component: () => import('../views/DietAddView.vue'), meta: { hideTab: true } },
    { path: '/water', component: () => import('../views/WaterView.vue'), meta: { hideTab: true } },
    { path: '/sleep', component: () => import('../views/SleepView.vue'), meta: { hideTab: true } },
    { path: '/mood', component: () => import('../views/MoodView.vue'), meta: { hideTab: true } },
    { path: '/exercise', component: () => import('../views/ExerciseView.vue'), meta: { hideTab: true } },
    { path: '/exercise/add', component: () => import('../views/ExerciseAddView.vue'), meta: { hideTab: true } },
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
      path: '/assessment-questionnaire',
      component: () => import('../views/AssessmentQuestionnaireView.vue'),
      meta: { hideTab: true },
    },
    { path: '/assessments', redirect: '/assessment-questionnaire' },
    {
      path: '/intake',
      component: () => import('../views/IntakeQuestionnaireView.vue'),
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
    {
      path: '/care-chat',
      redirect: (to) => ({ path: '/chat', query: to.query, replace: true }),
    },
    {
      path: '/care-chat/:threadId',
      component: () => import('../views/CareChatThreadView.vue'),
      meta: { hideTab: true },
    },
    { path: '/health', component: () => import('../views/HealthDataHubView.vue'), meta: { title: '健康' } },
    { path: '/health/record', component: () => import('../views/VitalsView.vue'), meta: { hideTab: true } },
    { path: '/discover', redirect: '/ai' },
    { path: '/me', component: () => import('../views/ProfileView.vue'), meta: { title: '我的' } },
    { path: '/join', component: () => import('../views/JoinView.vue'), meta: { hideTab: true } },
    { path: '/profile', redirect: '/me' },
    { path: '/vitals', redirect: '/health/record' },
    { path: '/agent', redirect: '/ai' },
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
