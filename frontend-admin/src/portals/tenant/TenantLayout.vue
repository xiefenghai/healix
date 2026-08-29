<script setup lang="ts">
import AdminShell from '../../shared/AdminShell.vue'

const menus = [
  { path: '/tenant/orgs', label: '机构管理', icon: 'office' },
  { path: '/tenant/staff', label: '账号与权限', icon: 'user' },
]
</script>

<template>
  <AdminShell title="租户管理" :menus="menus">
    <RouterView />
  </AdminShell>
</template>
