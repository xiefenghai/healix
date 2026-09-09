<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearSession, getCurrentOrgName, getEntry } from '../shared/http'

const props = defineProps<{
  title: string
  menus: Array<{ path: string; label: string; icon?: string }>
  hideSider?: boolean
  orgSwitchable?: boolean
}>()

const emit = defineEmits<{
  switchOrg: []
}>()

const router = useRouter()
const route = useRoute()
const keyword = ref('')
const collapsed = ref(false)
const entry = computed(() => getEntry())
const orgName = computed(() => getCurrentOrgName())
const active = computed(() => {
  const path = route.path
  const matched = props.menus.find((m) => path === m.path || path.startsWith(`${m.path}/`))
  return matched?.path ?? path
})

function logout() {
  clearSession()
  router.replace('/entry')
}

function goSecurity() {
  const e = entry.value
  if (e === 'ops') router.push('/ops/security')
  else if (e === 'tenant') router.push('/tenant/security')
  else router.push('/workspace/security')
}

function onSearch() {
  /* placeholder global search */
}

function onSwitchOrg() {
  emit('switchOrg')
}
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="left">
        <div class="brand">
          <span class="brand-mark" aria-hidden="true" />
          <span class="brand-text">Healix</span>
        </div>
        <el-divider direction="vertical" class="divider" />
        <span class="portal">{{ title }}</span>
        <template v-if="orgName && entry === 'workspace'">
          <el-dropdown v-if="orgSwitchable" trigger="click">
            <el-tag size="small" type="success" effect="plain" class="org-tag">
              {{ orgName }}
              <el-icon class="org-caret"><ArrowDown /></el-icon>
            </el-tag>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="onSwitchOrg">切换机构</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-tag v-else size="small" type="success" effect="plain">{{ orgName }}</el-tag>
        </template>
      </div>
      <div class="right">
        <el-input
          v-model="keyword"
          placeholder="搜索用户 / 机构"
          clearable
          class="search-input"
          @keyup.enter="onSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-dropdown>
          <span class="admin">
            <el-avatar :size="32" class="admin-avatar">管</el-avatar>
            <span class="admin-name">管理员</span>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="goSecurity">安全设置</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="body" :class="{ 'no-sider': hideSider }">
      <aside v-if="!hideSider" class="sider" :class="{ collapsed }">
        <div class="sider-inner">
          <el-menu :default-active="active" router :collapse="collapsed" class="side-menu">
            <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
              <el-icon v-if="m.icon === 'dashboard'"><Odometer /></el-icon>
              <el-icon v-else-if="m.icon === 'user'"><User /></el-icon>
              <el-icon v-else-if="m.icon === 'data'"><DataLine /></el-icon>
              <el-icon v-else-if="m.icon === 'chat'"><ChatDotRound /></el-icon>
              <el-icon v-else-if="m.icon === 'setting'"><Setting /></el-icon>
              <el-icon v-else-if="m.icon === 'office'"><OfficeBuilding /></el-icon>
              <el-icon v-else-if="m.icon === 'ticket'"><Ticket /></el-icon>
              <el-icon v-else-if="m.icon === 'timer'"><Timer /></el-icon>
              <el-icon v-else><Menu /></el-icon>
              <span>{{ m.label }}</span>
            </el-menu-item>
          </el-menu>
        </div>
        <el-button class="collapse-btn" text @click="collapsed = !collapsed">
          {{ collapsed ? '展开' : '收起侧栏' }}
        </el-button>
      </aside>
      <main class="content">
        <slot />
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  background: var(--admin-bg);
}

.topbar {
  height: 56px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--admin-topbar-bg);
  border-bottom: 1px solid var(--admin-topbar-border);
  box-shadow: var(--admin-shadow);
  position: sticky;
  top: 0;
  z-index: 20;
}

.left,
.right,
.admin {
  display: flex;
  align-items: center;
  gap: 12px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-mark {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: linear-gradient(135deg, var(--admin-primary) 0%, #0891b2 100%);
  box-shadow: 0 2px 8px rgba(13, 148, 136, 0.35);
}

.brand-text {
  font-weight: 700;
  color: var(--admin-text);
  font-size: 17px;
  letter-spacing: -0.02em;
}

.divider {
  border-color: var(--admin-border) !important;
  height: 20px;
}

.portal {
  font-size: 14px;
  color: var(--admin-text-secondary);
  font-weight: 500;
}

.org-tag {
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border-color: #a7f3d0 !important;
  background: #ecfdf5 !important;
  color: #047857 !important;
}

.org-caret {
  margin-left: 2px;
  font-size: 12px;
}

.search-input {
  width: 240px;
}

.admin {
  cursor: pointer;
  font-size: 13px;
  padding: 4px 8px;
  border-radius: 8px;
  transition: background 0.15s;
}

.admin:hover {
  background: var(--admin-bg);
}

.admin-avatar {
  background: linear-gradient(135deg, var(--admin-primary), #0891b2) !important;
  font-size: 13px;
  font-weight: 600;
}

.admin-name {
  color: var(--admin-text-secondary);
  font-weight: 500;
}

.body {
  display: grid;
  grid-template-columns: auto 1fr;
  min-height: calc(100vh - 56px);
}

.body.no-sider {
  grid-template-columns: 1fr;
}

.sider {
  width: 220px;
  background: var(--admin-sidebar-bg);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
}

.sider.collapsed {
  width: 64px;
}

.sider-inner {
  flex: 1;
  padding: 12px 0;
  overflow-y: auto;
}

.content {
  padding: 20px 24px 32px;
  min-width: 0;
}

.collapse-btn {
  margin: 8px 12px 12px;
  color: var(--admin-sidebar-text) !important;
  font-size: 12px;
  justify-content: flex-start;
}

.collapse-btn:hover {
  color: var(--admin-sidebar-text-active) !important;
  background: var(--admin-sidebar-hover) !important;
}

:deep(.side-menu) {
  border-right: none;
  background: transparent;
}

:deep(.side-menu.el-menu--collapse) {
  width: 64px;
}

:deep(.side-menu .el-menu-item) {
  height: 44px;
  line-height: 44px;
  margin: 2px 10px;
  border-radius: 8px;
  color: var(--admin-sidebar-text);
  font-size: 14px;
}

:deep(.side-menu .el-menu-item .el-icon) {
  color: inherit;
}

:deep(.side-menu .el-menu-item:hover) {
  background: var(--admin-sidebar-hover) !important;
  color: var(--admin-sidebar-text-active);
}

:deep(.side-menu .el-menu-item.is-active) {
  background: var(--admin-sidebar-active) !important;
  color: var(--admin-sidebar-text-active) !important;
  font-weight: 600;
  box-shadow: inset 3px 0 0 var(--admin-primary);
}

:deep(.side-menu.el-menu--collapse .el-menu-item.is-active) {
  box-shadow: none;
  border-left: 3px solid var(--admin-primary);
}
</style>
