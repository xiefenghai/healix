import { createRouter, createWebHistory } from 'vue-router'
import { getAud, getCurrentOrgId, getEntry, getToken, hasRole } from '../shared/http'

/**
 * B/Ops 路由。
 * - meta.aud：JWT 受众（ops | b），鉴权用
 * - meta.entry：门户会话（ops | tenant | workspace），决定壳与菜单
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/entry' },
    { path: '/entry', component: () => import('../shared/EntryView.vue'), meta: { public: true } },
    {
      path: '/login/:entry(ops|tenant|workspace)',
      component: () => import('../shared/LoginView.vue'),
      meta: { public: true },
    },
    {
      path: '/ops',
      component: () => import('../portals/ops/OpsLayout.vue'),
      meta: { aud: 'ops', entry: 'ops' },
      children: [
        { path: '', redirect: '/ops/tenants' },
        { path: 'tenants', component: () => import('../portals/ops/TenantsView.vue') },
        { path: 'tenants/:id', component: () => import('../portals/ops/TenantDetailView.vue') },
        { path: 'jobs', component: () => import('../portals/ops/PlatformJobsView.vue') },
        { path: 'patients', component: () => import('../portals/ops/PatientSearchView.vue') },
        { path: 'security', component: () => import('../shared/SecuritySettingsView.vue') },
      ],
    },
    {
      path: '/tenant',
      component: () => import('../portals/tenant/TenantLayout.vue'),
      meta: { aud: 'b', entry: 'tenant', role: 'TENANT_ADMIN' },
      children: [
        { path: '', redirect: '/tenant/orgs' },
        { path: 'orgs', component: () => import('../portals/tenant/TenantOrgsView.vue') },
        { path: 'staff', component: () => import('../portals/tenant/TenantStaffView.vue') },
        { path: 'security', component: () => import('../shared/SecuritySettingsView.vue') },
      ],
    },
    {
      path: '/workspace',
      component: () => import('../portals/workspace/WorkspaceLayout.vue'),
      meta: { aud: 'b', entry: 'workspace' },
      children: [
        {
          path: '',
          redirect: () => (getCurrentOrgId() ? '/workspace/cockpit' : '/workspace/orgs'),
        },
        { path: 'orgs', component: () => import('../portals/workspace/OrgPickerView.vue') },
        { path: 'security', component: () => import('../shared/SecuritySettingsView.vue') },
        {
          path: 'cockpit',
          component: () => import('../portals/workspace/CockpitView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'tasks',
          component: () => import('../portals/workspace/WorkspaceTasksView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'care-chat/:peopleId?',
          component: () => import('../portals/workspace/WorkspaceCareChatView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'staff',
          component: () => import('../portals/workspace/OrgStaffView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'care-teams',
          component: () => import('../portals/workspace/CareTeamsView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'care-teams/:teamId',
          component: () => import('../portals/workspace/CareTeamDetailView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'patients',
          component: () => import('../portals/workspace/PatientsView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'adherence',
          component: () => import('../portals/workspace/AdherenceBoardView.vue'),
          meta: { requireOrg: true },
        },
        {
          path: 'patients/:peopleId',
          component: () => import('../portals/workspace/PatientDetailLayout.vue'),
          meta: { requireOrg: true },
          redirect: (to) => `/workspace/patients/${to.params.peopleId}/archive`,
          children: [
            {
              path: 'archive',
              component: () => import('../portals/workspace/PatientArchiveView.vue'),
            },
            {
              path: 'medications',
              component: () => import('../portals/workspace/PatientMedicationView.vue'),
            },
            {
              path: 'care-plan',
              component: () => import('../portals/workspace/PatientCarePlanView.vue'),
            },
            {
              path: 'adherence',
              component: () => import('../portals/workspace/PatientAdherenceView.vue'),
            },
            {
              path: 'followups',
              component: () => import('../portals/workspace/PatientFollowupView.vue'),
            },
            {
              path: 'health-reports',
              component: () => import('../portals/workspace/PatientHealthReportView.vue'),
            },
            {
              path: 'assessments',
              component: () => import('../portals/workspace/PatientAssessmentsView.vue'),
            },
            {
              path: 'observations',
              component: () => import('../portals/workspace/PatientObservationLayout.vue'),
              redirect: (to) => `/workspace/patients/${to.params.peopleId}/observations/metrics`,
              children: [
                {
                  path: 'metrics',
                  component: () => import('../portals/workspace/PatientMetricView.vue'),
                },
                {
                  path: 'trends',
                  component: () => import('../portals/workspace/PatientMetricTrendView.vue'),
                },
                {
                  path: 'labs',
                  component: () => import('../portals/workspace/PatientLabView.vue'),
                },
                {
                  path: 'exams',
                  component: () => import('../portals/workspace/PatientExamView.vue'),
                },
              ],
            },
            {
              path: 'revisions',
              component: () => import('../portals/workspace/PatientRevisionView.vue'),
            },
            {
              path: 'chat',
              component: () => import('../portals/workspace/PatientChatView.vue'),
            },
          ],
        },
      ],
    },
  ],
})

router.beforeEach((to) => {
  if (to.meta.public) return true
  if (!getToken()) return { path: '/entry' }

  const needAud = to.meta.aud as string | undefined
  if (needAud && getAud() !== needAud) return { path: '/entry' }

  const needEntry = to.meta.entry as string | undefined
  if (needEntry && getEntry() !== needEntry) return { path: '/entry' }

  const needRole = to.meta.role as string | undefined
  if (needRole && !hasRole(needRole)) return { path: '/entry' }

  if (to.meta.requireOrg && !getCurrentOrgId()) {
    return { path: '/workspace/orgs' }
  }
  return true
})

export default router
