import { createRouter, createWebHistory } from 'vue-router'
import { getCurrentOrgId, getToken } from '../shared/http'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/tasks' },
    {
      path: '/login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, hideTab: true },
    },
    {
      path: '/orgs',
      component: () => import('../views/OrgPickerView.vue'),
      meta: { hideTab: true },
    },
    {
      path: '/tasks',
      component: () => import('../views/TasksView.vue'),
      meta: { requireOrg: true, title: '待办' },
    },
    {
      path: '/tasks/:taskId',
      component: () => import('../views/TaskDetailView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/tasks/:taskId/join',
      component: () => import('../views/JoinTeamView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/tasks/:taskId/report',
      component: () => import('../views/ReportReviewView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/tasks/:taskId/care-plan',
      component: () => import('../views/CarePlanCreateView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/tasks/:taskId/plan-review',
      component: () => import('../views/PlanReviewView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/chat',
      component: () => import('../views/CareChatListView.vue'),
      meta: { requireOrg: true, title: '沟通' },
    },
    {
      path: '/chat/:peopleId',
      component: () => import('../views/CareChatThreadView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients',
      component: () => import('../views/PatientsView.vue'),
      meta: { requireOrg: true, title: '患者' },
    },
    {
      path: '/patients/:peopleId',
      component: () => import('../views/PatientDetailView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/care-plan',
      component: () => import('../views/PatientCarePlanView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/medications',
      component: () => import('../views/PatientMedicationsView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/metrics',
      component: () => import('../views/PatientMetricsTrendView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/assessments',
      component: () => import('../views/PatientAssessmentsView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/labs',
      component: () => import('../views/PatientLabsView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/exams',
      component: () => import('../views/PatientExamsView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/adherence',
      component: () => import('../views/AdherenceBoardView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/health-reports',
      component: () => import('../views/PatientHealthReportsView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/health-reports/:reportId',
      component: () => import('../views/PatientHealthReportDetailView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/followups',
      component: () => import('../views/PatientFollowupsView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/patients/:peopleId/followups/create',
      component: () => import('../views/FollowupCreateView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/followups/:followupId',
      component: () => import('../views/FollowupDetailView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/followups/:followupId/complete',
      component: () => import('../views/FollowupCompleteView.vue'),
      meta: { requireOrg: true, hideTab: true },
    },
    {
      path: '/me',
      component: () => import('../views/MeView.vue'),
      meta: { requireOrg: true, title: '我的' },
    },
  ],
  scrollBehavior() {
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!getToken()) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.requireOrg && !getCurrentOrgId()) {
    return { path: '/orgs' }
  }
  return true
})

export default router
