<script setup lang="ts">
import AdminShell from '../../shared/AdminShell.vue'

const menus = [
  { path: '/ops/tenants', label: '租户管理', icon: 'office' },
  { path: '/ops/patients', label: '用户检索', icon: 'user' },
]
</script>

<template>
  <AdminShell title="平台超管" :menus="menus">
    <RouterView />
  </AdminShell>
</template>
