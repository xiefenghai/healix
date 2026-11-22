<script setup lang="ts">
import { computed, onMounted, ref, useSlots, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  api,
  clearSession,
  getAud,
  getCurrentOrgName,
  getEntry,
  getStaffDisplayName,
  getToken,
  orgSessionTick,
  setStaffDisplayName,
} from '../shared/http'
import brandShieldUrl from '../assets/brand-shield.png'

export type ShellMenuItem = {
  path: string
  label: string
  icon?: string
  badge?: boolean | number
  /** 侧栏分组标题；同组连续展示 */
  group?: string
}

export type ShellCrumbItem = {
  label: string
  /** 有 to 时可点击跳转 */
  to?: string
}

const props = withDefaults(
  defineProps<{
    title: string
    menus: ShellMenuItem[]
    hideSider?: boolean
    /** 全屏业务页（如首诊工作台）隐藏壳顶栏 */
    hideTopbar?: boolean
    /** 选机构等页：隐藏左侧面包屑与机构切换，只保留用户菜单 */
    hideCrumbs?: boolean
    /** 多级面包屑；不传则仅展示 title */
    crumbItems?: ShellCrumbItem[]
    orgSwitchable?: boolean
    flushContent?: boolean
  }>(),
  {
    flushContent: false,
    hideTopbar: false,
    hideCrumbs: false,
    crumbItems: () => [],
  },
)

const emit = defineEmits<{
  switchOrg: []
}>()

const slots = useSlots()
const router = useRouter()
const route = useRoute()
const collapsed = ref(false)
const entry = computed(() => getEntry())
const orgName = computed(() => {
  void orgSessionTick.value
  return getCurrentOrgName()
})
const staffName = computed(() => {
  void orgSessionTick.value
  return getStaffDisplayName()
})
const active = computed(() => {
  const path = route.path
  const matched = props.menus.find((m) => path === m.path || path.startsWith(`${m.path}/`))
  return matched?.path ?? path
})
const orgInitial = computed(() => (orgName.value || '机').slice(0, 1))
const staffInitial = computed(() => (staffName.value || (entry.value === 'ops' ? '超' : '管')).slice(0, 1))

async function ensureStaffDisplayName() {
  if (getAud() !== 'b' || !getToken()) return
  if (getStaffDisplayName()) return
  try {
    const res = await api<{ data: { displayName?: string | null } }>('/api/b/v1/session/me')
    setStaffDisplayName(res.data?.displayName)
  } catch {
    /* 顶栏姓名非关键路径，静默即可 */
  }
}

onMounted(() => {
  void ensureStaffDisplayName()
})
watch(
  () => [getToken(), getAud(), orgSessionTick.value] as const,
  () => {
    void ensureStaffDisplayName()
  },
)

/** 按 group 聚合；无 group 的归入空标题一组 */
const menuSections = computed(() => {
  const sections: Array<{ title: string; items: ShellMenuItem[] }> = []
  for (const m of props.menus) {
    const title = m.group?.trim() || ''
    const last = sections[sections.length - 1]
    if (last && last.title === title) {
      last.items.push(m)
    } else {
      sections.push({ title, items: [m] })
    }
  }
  return sections
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

function onSwitchOrg() {
  emit('switchOrg')
}
</script>

<template>
  <div class="shell" :class="{ 'no-topbar': hideTopbar }">
    <header v-if="!hideTopbar" class="topbar" :class="{ 'topbar-minimal': hideCrumbs }">
      <div class="left">
        <div v-if="!hideCrumbs" class="crumbs">
          <span class="crumb-muted" :title="orgName || '机构工作台'">{{ orgName || '机构工作台' }}</span>
          <template v-if="crumbItems.length">
            <template v-for="(item, idx) in crumbItems" :key="`${item.label}-${idx}`">
              <span class="crumb-sep">/</span>
              <button
                v-if="item.to"
                type="button"
                class="crumb-link"
                @click="router.push(item.to)"
              >
                {{ item.label }}
              </button>
              <strong v-else>{{ item.label }}</strong>
            </template>
          </template>
          <template v-else>
            <span class="crumb-sep">/</span>
            <strong>{{ title }}</strong>
          </template>
        </div>
        <div v-if="slots.chips" class="chip-slot">
          <slot name="chips" />
        </div>
      </div>
      <div class="right">
        <template v-if="!hideCrumbs && orgName && entry === 'workspace'">
          <button
            v-if="orgSwitchable"
            type="button"
            class="org-switch"
            @click="onSwitchOrg"
          >
            <span class="org-avatar">{{ orgInitial }}</span>
            <span class="org-meta">
              <span class="org-name">{{ orgName }}</span>
              <span class="org-role">点击切换机构</span>
            </span>
          </button>
          <div v-else class="org-switch static">
            <span class="org-avatar">{{ orgInitial }}</span>
            <span class="org-meta">
              <span class="org-name">{{ orgName }}</span>
            </span>
          </div>
        </template>
        <el-dropdown trigger="click" :teleported="true">
          <button type="button" class="admin" :aria-label="staffName ? `账号菜单 · ${staffName}` : '账号菜单'">
            <span class="user-avatar">{{ staffInitial }}</span>
            <span v-if="staffName" class="user-name" :title="staffName">{{ staffName }}</span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="goSecurity">账号设置</el-dropdown-item>
              <el-dropdown-item divided @click="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <div class="body" :class="{ 'no-sider': hideSider }">
      <aside v-if="!hideSider" class="sider" :class="{ collapsed }">
        <div class="brand">
          <span class="brand-mark" aria-hidden="true">
            <img class="brand-logo" :src="brandShieldUrl" alt="" />
          </span>
          <div v-if="!collapsed" class="brand-copy">
            <span class="brand-name">Healix</span>
            <small>机构工作台</small>
          </div>
        </div>

        <div class="sider-inner">
          <template v-for="(sec, si) in menuSections" :key="`${sec.title}-${si}`">
            <div v-if="!collapsed && sec.title" class="nav-title" :class="{ spaced: si > 0 }">
              {{ sec.title }}
            </div>
            <div v-else-if="collapsed && si > 0" class="nav-divider" aria-hidden="true" />
            <el-menu :default-active="active" router :collapse="collapsed" class="side-menu">
              <el-menu-item v-for="m in sec.items" :key="m.path" :index="m.path">
                <el-icon v-if="m.icon === 'dashboard'"><Odometer /></el-icon>
                <el-icon v-else-if="m.icon === 'user'"><User /></el-icon>
                <el-icon v-else-if="m.icon === 'staff'"><Avatar /></el-icon>
                <el-icon v-else-if="m.icon === 'team'"><Coordinate /></el-icon>
                <el-icon v-else-if="m.icon === 'chart'"><TrendCharts /></el-icon>
                <el-icon v-else-if="m.icon === 'data'"><DataLine /></el-icon>
                <el-icon v-else-if="m.icon === 'chat'"><ChatDotRound /></el-icon>
                <el-icon v-else-if="m.icon === 'setting'"><Setting /></el-icon>
                <el-icon v-else-if="m.icon === 'office'"><OfficeBuilding /></el-icon>
                <el-icon v-else-if="m.icon === 'ticket'"><Ticket /></el-icon>
                <el-icon v-else-if="m.icon === 'timer'"><Timer /></el-icon>
                <el-icon v-else-if="m.icon === 'merge'"><Connection /></el-icon>
                <el-icon v-else-if="m.icon === 'dish'"><Dish /></el-icon>
                <el-icon v-else-if="m.icon === 'reading'"><Reading /></el-icon>
                <el-icon v-else><Menu /></el-icon>
                <template v-if="!collapsed">
                  <span class="menu-label">{{ m.label }}</span>
                  <!-- 有数字只显示角标；布尔 true 才用纯红点 -->
                  <i v-if="m.badge === true" class="menu-dot" />
                  <span
                    v-if="typeof m.badge === 'number' && m.badge > 0"
                    class="menu-badge"
                  >{{ m.badge > 99 ? '99+' : m.badge }}</span>
                </template>
                <template v-else>
                  <i
                    v-if="m.badge === true || (typeof m.badge === 'number' && m.badge > 0)"
                    class="menu-dot collapsed-dot"
                  />
                </template>
              </el-menu-item>
            </el-menu>
          </template>
        </div>

        <button
          type="button"
          class="collapse-btn"
          :title="collapsed ? '展开侧栏' : '收起侧栏'"
          @click="collapsed = !collapsed"
        >
          <el-icon><DArrowLeft v-if="!collapsed" /><DArrowRight v-else /></el-icon>
          <span v-if="!collapsed">收起导航</span>
        </button>
      </aside>
      <main class="content" :class="{ flush: flushContent }">
        <div v-if="flushContent" class="flush-slot">
          <slot />
        </div>
        <div v-else class="page-scroll">
          <div class="content-container">
            <slot />
          </div>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
.shell {
  height: 100vh;
  max-width: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background: var(--admin-bg);
}

.shell.no-topbar .body {
  min-height: 0;
  height: 100%;
  flex: 1;
}

.shell.no-topbar .content.flush {
  height: 100%;
}

.topbar {
  height: var(--layout-topbar-h, 56px);
  flex: 0 0 auto;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 14px;
  background: var(--admin-topbar-bg);
  border-bottom: 1px solid var(--admin-topbar-border);
  position: relative;
  z-index: 20;
}

.left,
.right {
  display: flex;
  align-items: center;
  gap: 10px;
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
  max-width: 10em;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--ink-500);
}

.crumb-sep {
  opacity: 0.45;
}

.crumb-link {
  border: none;
  background: none;
  padding: 0;
  font: inherit;
  color: var(--ink-500);
  cursor: pointer;
}

.crumb-link:hover {
  color: var(--brand-600, #2563eb);
}

.crumbs strong {
  color: var(--ink-800);
  font-weight: 600;
}

.chip-slot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: 6px;
  flex-wrap: wrap;
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
  align-items: center;
  gap: 8px;
  max-width: 160px;
  padding: 4px 8px 4px 4px;
  margin: 0;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  line-height: 1.2;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.admin:hover {
  background: var(--slate-50, #f8fafc);
  border-color: var(--slate-200, #e2e8f0);
}

.admin:focus-visible {
  outline: 2px solid var(--brand-500);
  outline-offset: 2px;
}

.user-avatar {
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, #8b5cf6, #2c7ef8);
  display: grid;
  place-items: center;
  color: #fff;
  font-weight: 600;
  font-size: 12px;
}

.user-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--ink-800, #1f2937);
  font-size: 13px;
  font-weight: 600;
}

.topbar-minimal .left {
  min-width: 0;
}

.body {
  display: grid;
  grid-template-columns: auto 1fr;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
}

.body.no-sider {
  grid-template-columns: 1fr;
}

.sider {
  width: 228px;
  min-height: 0;
  overflow: hidden;
  background:
    radial-gradient(120% 60% at 0% 0%, rgba(44, 126, 248, 0.18), transparent 55%),
    linear-gradient(180deg, #0b1220 0%, #101826 48%, #0f172a 100%);
  border-right: 1px solid rgba(148, 163, 184, 0.08);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease;
}

.sider.collapsed {
  width: 68px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 18px 16px 14px;
  color: #fff;
  min-height: 72px;
  box-sizing: border-box;
  border-bottom: 1px solid rgba(148, 163, 184, 0.1);
}

.sider.collapsed .brand {
  justify-content: center;
  padding: 18px 8px 14px;
}

.brand-mark {
  width: 38px;
  height: 38px;
  flex-shrink: 0;
  border-radius: 11px;
  display: grid;
  place-items: center;
  background: linear-gradient(160deg, #ffffff 0%, #e8eef8 100%);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.12),
    0 8px 18px rgba(37, 99, 235, 0.28);
}

.brand-logo {
  width: 28px;
  height: 28px;
  object-fit: contain;
  object-position: center;
  display: block;
}

.brand-copy {
  min-width: 0;
  line-height: 1.15;
}

.brand-name {
  display: block;
  font-weight: 700;
  font-size: 16px;
  letter-spacing: 0.02em;
  color: #f8fafc;
}

.brand-copy small {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  font-weight: 500;
  color: #94a3b8;
  letter-spacing: 0.04em;
}

.sider-inner {
  flex: 1;
  padding: 8px 0 12px;
  overflow-y: auto;
  overflow-x: hidden;
}

.nav-title {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: none;
  color: #64748b;
  padding: 8px 18px 8px;
}

.nav-title.spaced {
  margin-top: 10px;
  padding-top: 14px;
  border-top: 1px solid rgba(148, 163, 184, 0.1);
}

.nav-divider {
  height: 1px;
  margin: 10px 14px;
  background: rgba(148, 163, 184, 0.12);
}

.content {
  min-width: 0;
  max-width: 100%;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.content.flush {
  padding: 0;
}

.page-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: var(--layout-page-pad-y, 24px) var(--layout-page-pad-x, 28px) 36px;
}

.flush-slot {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.collapse-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 4px 12px 14px;
  padding: 9px 12px;
  border: 1px solid rgba(148, 163, 184, 0.12);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.03);
  color: #94a3b8;
  font: inherit;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition:
    color 0.15s ease,
    background 0.15s ease,
    border-color 0.15s ease;
}

.collapse-btn:hover {
  color: #f1f5f9;
  background: rgba(255, 255, 255, 0.07);
  border-color: rgba(148, 163, 184, 0.22);
}

.sider.collapsed .collapse-btn {
  justify-content: center;
  margin: 4px 10px 14px;
  padding: 10px 0;
}

:deep(.side-menu) {
  border-right: none;
  background: transparent;
  padding: 0 10px;
}

:deep(.side-menu.el-menu--collapse) {
  width: 68px;
  padding: 0 8px;
}

:deep(.side-menu .el-menu-item) {
  height: 40px;
  line-height: 40px;
  margin: 3px 0;
  padding: 0 12px !important;
  border-radius: 10px;
  color: #94a3b8;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.01em;
  position: relative;
  display: flex;
  align-items: center;
  gap: 11px;
  transition:
    color 0.15s ease,
    background 0.15s ease;
}

:deep(.side-menu.el-menu--collapse .el-menu-item) {
  padding: 0 !important;
  justify-content: center;
  gap: 0;
}

:deep(.side-menu .el-menu-item .el-icon) {
  color: inherit;
  opacity: 0.92;
  font-size: 17px;
  margin: 0 !important;
  width: 18px;
  transition: color 0.15s ease;
}

.menu-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

:deep(.side-menu .el-menu-item:hover) {
  background: rgba(255, 255, 255, 0.06) !important;
  color: #e2e8f0;
}

:deep(.side-menu .el-menu-item.is-active) {
  background: linear-gradient(90deg, rgba(44, 126, 248, 0.28), rgba(44, 126, 248, 0.08)) !important;
  color: #fff !important;
  font-weight: 600;
  box-shadow: inset 0 0 0 1px rgba(96, 165, 250, 0.18);
}

:deep(.side-menu .el-menu-item.is-active .el-icon) {
  opacity: 1;
  color: #93c5fd;
}

:deep(.side-menu .el-menu-item.is-active::before) {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  background: linear-gradient(180deg, #60a5fa, #2563eb);
  border-radius: 0 3px 3px 0;
}

:deep(.side-menu.el-menu--collapse .el-menu-item.is-active::before) {
  display: none;
}

.menu-dot {
  width: 7px;
  height: 7px;
  margin-left: 4px;
  border-radius: 50%;
  background: #ef4444;
  box-shadow: 0 0 0 2px rgba(239, 68, 68, 0.2);
  flex-shrink: 0;
}

.menu-dot.collapsed-dot {
  position: absolute;
  top: 7px;
  right: 8px;
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
