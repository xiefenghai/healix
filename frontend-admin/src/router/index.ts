import { createRouter, createWebHistory } from 'vue-router'
import { getAud, getCurrentOrgId, getEntry, getToken, hasRole } from '../shared/http'

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
        { path: 'patients', component: () => import('../portals/ops/PatientSearchView.vue') },
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
      ],
    },
    {
      path: '/workspace',
      component: () => import('../portals/workspace/WorkspaceLayout.vue'),
      meta: { aud: 'b', entry: 'workspace' },
      children: [
        { path: '', redirect: '/workspace/orgs' },
        { path: 'orgs', component: () => import('../portals/workspace/OrgPickerView.vue') },
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
          path: 'patients/:peopleId/archive',
          component: () => import('../portals/workspace/PatientArchiveView.vue'),
          meta: { requireOrg: true },
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
