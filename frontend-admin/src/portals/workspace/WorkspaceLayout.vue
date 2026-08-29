<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AdminShell from '../../shared/AdminShell.vue'
import { getCurrentOrgId, orgSessionTick } from '../../shared/http'

const route = useRoute()
const router = useRouter()

const BIZ_MENUS = [
  { path: '/workspace/patients', label: '患者管理', icon: 'user' },
  { path: '/workspace/care-teams', label: '健管组', icon: 'data' },
  { path: '/workspace/staff', label: '成员管理', icon: 'user' },
]

/** 选机构阶段：无侧栏；进入机构且不在选机构页时展示业务菜单 */
const inOrgWorkspace = computed(() => {
  void orgSessionTick.value
  return !!getCurrentOrgId() && route.path !== '/workspace/orgs'
})

const menus = computed(() => (inOrgWorkspace.value ? BIZ_MENUS : []))
const hideSider = computed(() => !inOrgWorkspace.value)
const orgSwitchable = computed(() => {
  void orgSessionTick.value
  return !!getCurrentOrgId()
})

function switchOrg() {
  router.push('/workspace/orgs')
}
</script>

<template>
  <AdminShell
    title="机构工作台"
    :menus="menus"
    :hide-sider="hideSider"
    :org-switchable="orgSwitchable"
    @switch-org="switchOrg"
  >
    <RouterView />
  </AdminShell>
</template>
