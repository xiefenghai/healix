<script setup lang="ts">
import { computed, ref, useSlots } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearSession, getCurrentOrgName, getEntry } from '../shared/http'

const props = withDefaults(
  defineProps<{
    title: string
    menus: Array<{ path: string; label: string; icon?: string; badge?: boolean | number }>
    hideSider?: boolean
    orgSwitchable?: boolean
    flushContent?: boolean
  }>(),
  {
    flushContent: false,
  },
)

const emit = defineEmits<{
  switchOrg: []
}>()

const slots = useSlots()
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
const orgInitial = computed(() => (orgName.value || '机').slice(0, 1))

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
        <div class="crumbs">
          <span class="crumb-muted">机构工作台</span>
          <span class="crumb-sep">/</span>
          <strong>{{ title }}</strong>
        </div>
        <div v-if="slots.chips" class="chip-slot">
          <slot name="chips" />
        </div>
      </div>
      <div class="search-wrap">
        <el-input
          v-model="keyword"
          placeholder="搜索用户 / 机构 / 患者…"
          clearable
          class="search-input"
          @keyup.enter="onSearch"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
      </div>
      <div class="right">
        <template v-if="orgName && entry === 'workspace'">
          <el-dropdown v-if="orgSwitchable" trigger="click">
            <button type="button" class="org-switch">
              <span class="org-avatar">{{ orgInitial }}</span>
              <span class="org-meta">
                <span class="org-name">{{ orgName }}</span>
                <span class="org-role">点击切换机构</span>
              </span>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item @click="onSwitchOrg">切换机构</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <div v-else class="org-switch static">
            <span class="org-avatar">{{ orgInitial }}</span>
            <span class="org-meta">
              <span class="org-name">{{ orgName }}</span>
            </span>
          </div>
        </template>
        <el-dropdown>
          <span class="admin">
            <span class="user-avatar">管</span>
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
        <div class="brand">
          <span class="brand-logo" aria-hidden="true" />
          <div v-if="!collapsed" class="brand-copy">
            <span class="brand-name">Healix</span>
            <small>机构工作台</small>
          </div>
        </div>
        <div class="sider-inner">
          <div v-if="!collapsed" class="nav-title">主导航</div>
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
              <template v-if="!collapsed">
                <span>{{ m.label }}</span>
                <i v-if="m.badge === true || (typeof m.badge === 'number' && m.badge > 0)" class="menu-dot" />
                <span
                  v-if="typeof m.badge === 'number' && m.badge > 0"
                  class="menu-badge"
                >{{ m.badge > 99 ? '99+' : m.badge }}</span>
              </template>
              <template v-else>
                <i v-if="m.badge === true || (typeof m.badge === 'number' && m.badge > 0)" class="menu-dot collapsed-dot" />
              </template>
            </el-menu-item>
          </el-menu>
        </div>
        <el-button class="collapse-btn" text @click="collapsed = !collapsed">
          {{ collapsed ? '展开' : '收起侧栏' }}
        </el-button>
      </aside>
      <main class="content" :class="{ flush: flushContent }">
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
  height: 60px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  gap: 16px;
  background: var(--admin-topbar-bg);
  border-bottom: 1px solid var(--admin-topbar-border);
  position: sticky;
  top: 0;
  z-index: 20;
}

.left,
.right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--ink-500);
  white-space: nowrap;
}

.crumb-muted {
  color: var(--ink-500);
}

.crumb-sep {
  opacity: 0.45;
}

.crumbs strong {
  color: var(--ink-800);
  font-weight: 600;
}

.chip-slot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
  flex-wrap: wrap;
}

.search-wrap {
  flex: 1;
  max-width: 380px;
  min-width: 180px;
}

.search-input {
  width: 100%;
}

.search-input :deep(.el-input__wrapper) {
  background: var(--ink-50);
  box-shadow: 0 0 0 1px var(--ink-200) inset;
  border-radius: 8px;
  transition: box-shadow var(--admin-transition), background var(--admin-transition);
}

.search-input :deep(.el-input__wrapper:hover) {
  background: #fff;
}

.search-input :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--brand-500), 0 0 0 3px rgba(44, 126, 248, 0.12) !important;
}

.right {
  margin-left: auto;
}

.org-switch {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 3px 10px 3px 3px;
  border-radius: 999px;
  background: var(--ink-50);
  border: 1px solid var(--ink-200);
  cursor: pointer;
  font: inherit;
  color: inherit;
}

.org-switch.static {
  cursor: default;
}

.org-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #f59e0b, #ef4444);
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  flex-shrink: 0;
}

.org-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
  text-align: left;
}

.org-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800);
}

.org-role {
  font-size: 11px;
  color: var(--ink-500);
}

.admin {
  cursor: pointer;
  display: inline-flex;
}

.user-avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  background: linear-gradient(135deg, #8b5cf6, #2c7ef8);
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 600;
  font-size: 12px;
}

.body {
  display: grid;
  grid-template-columns: auto 1fr;
  min-height: calc(100vh - 60px);
}

.body.no-sider {
  grid-template-columns: 1fr;
}

.sider {
  width: 248px;
  background: linear-gradient(180deg, #0f172a 0%, #131c30 100%);
  border-right: 1px solid var(--admin-sidebar-border);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
}

.sider.collapsed {
  width: 72px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 20px 14px 8px;
  color: #fff;
}

.brand-logo {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  flex-shrink: 0;
  background: linear-gradient(135deg, #2c7ef8, #00b8a9);
  box-shadow: 0 6px 16px -4px rgba(44, 126, 248, 0.6);
}

.brand-copy {
  min-width: 0;
}

.brand-name {
  display: block;
  font-weight: 700;
  font-size: 16px;
  line-height: 1.2;
}

.brand-copy small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  font-weight: 500;
  color: var(--ink-400);
  letter-spacing: 0.04em;
}

.sider-inner {
  flex: 1;
  padding: 4px 0 12px;
  overflow-y: auto;
}

.nav-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 1.2px;
  color: var(--ink-500);
  text-transform: uppercase;
  padding: 12px 22px 8px;
}

.content {
  padding: 24px 32px 40px;
  min-width: 0;
}

.content.flush {
  padding: 0;
  overflow: hidden;
  height: calc(100vh - 60px);
}

.collapse-btn {
  margin: 8px 12px 16px;
  color: var(--admin-sidebar-text) !important;
  font-size: 12px;
  justify-content: flex-start;
}

.collapse-btn:hover {
  color: #fff !important;
  background: var(--admin-sidebar-hover) !important;
}

:deep(.side-menu) {
  border-right: none;
  background: transparent;
}

:deep(.side-menu.el-menu--collapse) {
  width: 72px;
}

:deep(.side-menu .el-menu-item) {
  height: 40px;
  line-height: 40px;
  margin: 2px 10px;
  border-radius: 8px;
  color: var(--admin-sidebar-text);
  font-size: 13.5px;
  font-weight: 500;
  position: relative;
  display: flex;
  align-items: center;
}

:deep(.side-menu .el-menu-item .el-icon) {
  color: inherit;
  opacity: 0.85;
}

:deep(.side-menu .el-menu-item:hover) {
  background: var(--admin-sidebar-hover) !important;
  color: #fff;
}

:deep(.side-menu .el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(44, 126, 248, 0.18), rgba(44, 126, 248, 0.04)) !important;
  color: #fff !important;
  font-weight: 600;
  box-shadow: none;
}

:deep(.side-menu .el-menu-item.is-active .el-icon) {
  opacity: 1;
}

:deep(.side-menu .el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  background: var(--brand-500);
  border-radius: 0 3px 3px 0;
}

:deep(.side-menu.el-menu--collapse .el-menu-item.is-active::before) {
  display: none;
}

.menu-dot {
  width: 8px;
  height: 8px;
  margin-left: 6px;
  border-radius: 50%;
  background: #ef4444;
  box-shadow: 0 0 0 3px rgba(239, 68, 68, 0.18);
  flex-shrink: 0;
}

.menu-dot.collapsed-dot {
  position: absolute;
  top: 8px;
  right: 10px;
  margin: 0;
}

.menu-badge {
  margin-left: auto;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  background: #ef4444;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
}
</style>
