<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowRight, Loading, OfficeBuilding, Search } from '@element-plus/icons-vue'
import { api, getCurrentOrgId, getCurrentOrgName, updateOrgSession } from '../../shared/http'
import { enableStatusTagType, formatEnableStatus, formatOrgType } from '../../shared/enums'
import { matchOrgQuery } from '../../shared/org-search'

type OrgItem = {
  id: string
  name: string
  orgType: string
  status: string
  orgCode?: string | null
  isDefault?: boolean
}

const PAGE_SIZE = 10

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const enteringId = ref<string | null>(null)
const autoEnteringName = ref('')
const displayName = ref('')
const orgs = ref<OrgItem[]>([])
const currentOrgId = ref<string | null>(getCurrentOrgId())
const keyword = ref('')
const page = ref(1)

/**
 * 仅工作台内主动「切换机构」（?from=switch）才是切换态。
 * 登录后也会带 currentOrgId（默认机构），不能据此当成切换态，
 * 否则首登选机构页会误出「取消，返回驾驶舱」。
 */
const isSwitchMode = computed(() => route.query.from === 'switch')

/** 后端暂无「最近访问」字段；有数据再展示 */
const recentOrgs = computed<OrgItem[]>(() => [])

/** 机构数 > 5 才展示搜索（少量数据直接浏览即可） */
const showSearch = computed(() => orgs.value.length > 5)

/** 仅多机构展示列表；单机构直接跳转，不渲染本页 */
const showPicker = computed(
  () => !loading.value && !autoEnteringName.value && orgs.value.length > 1,
)

const autoEnteringTip = computed(() =>
  autoEnteringName.value ? `正在为您进入【${autoEnteringName.value}】...` : '',
)

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 12) return '早上好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const welcomeLine = computed(() => {
  const name = displayName.value.trim()
  const who = name || '同事'
  if (isSwitchMode.value) {
    return `${greeting.value}，${who}。请选择要切换进入的工作机构`
  }
  return `${greeting.value}，${who}。请选择您要进入的工作机构`
})

const currentOrgLabel = computed(
  () => getCurrentOrgName() || orgs.value.find((o) => isCurrentOrg(o))?.name || '已选机构',
)

const filteredOrgs = computed(() => {
  const q = keyword.value
  return orgs.value.filter((o) => matchOrgQuery(o, q))
})

const totalFiltered = computed(() => filteredOrgs.value.length)

const pagedOrgs = computed(() => {
  const start = (page.value - 1) * PAGE_SIZE
  return filteredOrgs.value.slice(start, start + PAGE_SIZE)
})

const showPagination = computed(() => totalFiltered.value > PAGE_SIZE)

const emptySearchText = computed(() =>
  keyword.value.trim()
    ? '未找到相关机构，请检查搜索词'
    : '暂无可用机构，请联系管理员开通',
)

watch(keyword, () => {
  page.value = 1
})

watch(totalFiltered, (n) => {
  const maxPage = Math.max(1, Math.ceil(n / PAGE_SIZE) || 1)
  if (page.value > maxPage) page.value = maxPage
})

function goBackToCockpit() {
  // 勿用 history.back()：登录 → 选机构 时上一页是登录页
  void router.replace('/workspace/cockpit')
}

function isCurrentOrg(org: { id: string }) {
  return !!currentOrgId.value && String(org.id) === String(currentOrgId.value)
}

async function load() {
  loading.value = true
  try {
    const res = await api<{
      data: {
        displayName?: string | null
        orgs: OrgItem[]
        currentOrgId: string | null
      }
    }>('/api/b/v1/session/me')
    displayName.value = res.data.displayName?.trim() || ''
    orgs.value = (res.data.orgs ?? []).map((o) => ({
      ...o,
      id: String(o.id),
    }))
    const sid = res.data.currentOrgId != null ? String(res.data.currentOrgId) : getCurrentOrgId()
    currentOrgId.value = sid

    // 只有 1 个机构：不展示本页。切换模式下若已是当前机构则直接退回，否则静默进入。
    if (orgs.value.length === 1) {
      const only = orgs.value[0]
      loading.value = false
      if (isSwitchMode.value && isCurrentOrg(only)) {
        goBackToCockpit()
        return
      }
      autoEnteringName.value = only.name
      await enterOrg(only, { silent: true, auto: true })
      return
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function enterOrg(
  org: { id: string; name: string },
  opts?: { silent?: boolean; auto?: boolean },
) {
  if (enteringId.value) return

  // 仅「切换机构」态点当前机构 ≈ 取消；首登点默认「当前」机构须正式进入驾驶舱
  if (!opts?.auto && isSwitchMode.value && isCurrentOrg(org)) {
    goBackToCockpit()
    return
  }

  enteringId.value = String(org.id)
  if (opts?.auto) {
    autoEnteringName.value = org.name
  }
  try {
    const res = await api<{ data: { accessToken: string } }>('/api/b/v1/session/org', {
      method: 'PUT',
      body: JSON.stringify({ orgId: org.id }),
    })
    updateOrgSession(res.data.accessToken, org.id, org.name)
    currentOrgId.value = String(org.id)
    if (!opts?.silent) {
      ElMessage.success(`已进入 ${org.name}`)
    }
    await router.replace('/workspace/cockpit')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '进入失败')
    enteringId.value = null
    autoEnteringName.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="org-picker" v-loading="loading">
    <div v-if="autoEnteringTip" class="auto-enter" aria-live="polite">
      <el-icon class="is-loading" :size="28"><Loading /></el-icon>
      <p>{{ autoEnteringTip }}</p>
    </div>

    <div v-else-if="showPicker" class="picker-inner">
      <header class="hero">
        <p class="welcome">{{ welcomeLine }}</p>
        <h1>{{ isSwitchMode ? '切换工作机构' : '选择工作机构' }}</h1>
        <p class="sub">
          {{
            isSwitchMode
              ? `当前正在服务：${currentOrgLabel}`
              : '进入后将打开智能驾驶舱，开始今日健管工作'
          }}
        </p>
      </header>

      <div v-if="showSearch" class="search-wrap">
        <el-input
          v-model="keyword"
          clearable
          size="large"
          class="org-search"
          placeholder="搜索机构名称或拼音首字母..."
          :prefix-icon="Search"
        />
      </div>

      <div v-if="recentOrgs.length" class="recent">
        <span class="recent-label">最近访问</span>
        <div class="recent-tags">
          <button
            v-for="org in recentOrgs"
            :key="`recent-${org.id}`"
            type="button"
            class="recent-tag"
            :disabled="!!enteringId"
            @click="enterOrg(org)"
          >
            {{ org.name }}
          </button>
        </div>
      </div>

      <div class="list-meta" v-if="orgs.length">
        <span v-if="keyword.trim()">
          找到 <strong>{{ totalFiltered }}</strong> 家机构
        </span>
        <span v-else>
          共 <strong>{{ orgs.length }}</strong> 家可选机构
        </span>
      </div>

      <div v-if="pagedOrgs.length" class="org-list" :class="{ locked: !!enteringId }">
        <button
          v-for="org in pagedOrgs"
          :key="org.id"
          type="button"
          class="org-card"
          :class="{
            current: isCurrentOrg(org),
            entering: enteringId === String(org.id),
            muted: !!enteringId && enteringId !== String(org.id),
          }"
          :disabled="!!enteringId"
          @click="enterOrg(org)"
        >
          <span class="org-icon" aria-hidden="true">
            <el-icon :size="18"><OfficeBuilding /></el-icon>
          </span>
          <div class="org-copy">
            <div class="name-row">
              <span class="name">{{ org.name }}</span>
              <el-tag
                size="small"
                effect="light"
                round
                :type="enableStatusTagType(org.status)"
              >
                {{ formatEnableStatus(org.status) }}
              </el-tag>
              <span v-if="isCurrentOrg(org)" class="badge">当前</span>
            </div>
            <div class="meta">{{ formatOrgType(org.orgType) }}</div>
          </div>
          <el-icon class="go-arrow" :size="16" aria-hidden="true">
            <ArrowRight />
          </el-icon>
          <span v-if="enteringId === String(org.id)" class="card-loading" aria-live="polite">
            <el-icon class="is-loading" :size="20"><Loading /></el-icon>
            <span>正在进入…</span>
          </span>
        </button>
      </div>

      <div v-else class="empty-search">
        <p class="empty-title">{{ emptySearchText }}</p>
        <p v-if="keyword.trim()" class="empty-hint">试试更短的关键词，或清空搜索查看全部机构</p>
        <button
          v-if="keyword.trim()"
          type="button"
          class="empty-clear"
          @click="keyword = ''"
        >
          清空搜索
        </button>
      </div>

      <div v-if="showPagination" class="pager">
        <el-pagination
          v-model:current-page="page"
          :page-size="PAGE_SIZE"
          :total="totalFiltered"
          layout="prev, pager, next"
          background
          small
        />
      </div>

      <div v-if="isSwitchMode" class="cancel-wrap">
        <button
          type="button"
          class="cancel-btn"
          :disabled="!!enteringId"
          @click="goBackToCockpit"
        >
          取消，返回驾驶舱
        </button>
      </div>
    </div>

    <el-empty
      v-if="!loading && !enteringId && !autoEnteringTip && !orgs.length"
      description="暂无可用机构，请联系管理员开通"
    />
  </div>
</template>

<style scoped>
.org-picker {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  min-height: calc(100vh - 56px);
  height: 100%;
  padding: 36px 24px 48px;
  box-sizing: border-box;
  overflow-y: auto;
  background:
    radial-gradient(900px 420px at 50% -10%, rgba(44, 126, 248, 0.08), transparent 60%),
    var(--admin-bg, #f8fafc);
}

.auto-enter {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  min-height: 240px;
  color: var(--ink-600, #475569);
}

.auto-enter .is-loading {
  color: var(--brand-500, #2c7ef8);
}

.auto-enter p {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--ink-700, #334155);
}

.picker-inner {
  width: 100%;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
}

.hero {
  text-align: center;
  margin-bottom: 20px;
}

.welcome {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--ink-500, #64748b);
  line-height: 1.5;
}

.hero h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: -0.4px;
  color: var(--ink-900, #0f172a);
}

.sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--ink-500, #64748b);
}

.search-wrap {
  width: 100%;
  margin-bottom: 14px;
}

.org-search :deep(.el-input__wrapper) {
  border-radius: 12px;
  box-shadow: 0 0 0 1px var(--ink-200, #e2e8f0) inset;
  background: #fff;
}

.recent {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 14px;
}

.recent-label {
  flex-shrink: 0;
  margin-top: 4px;
  font-size: 12px;
  color: #999;
}

.recent-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.recent-tag {
  appearance: none;
  border: 1px solid var(--ink-200, #e2e8f0);
  background: #fff;
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  color: var(--ink-700, #334155);
  cursor: pointer;
  transition: all 0.2s ease;
}

.recent-tag:hover:not(:disabled) {
  border-color: var(--brand-300, #93c5fd);
  color: var(--brand-500, #2c7ef8);
}

.recent-tag:disabled {
  opacity: 0.55;
  cursor: wait;
}

.list-meta {
  margin-bottom: 10px;
  font-size: 12px;
  color: #999;
}

.list-meta strong {
  color: var(--ink-700, #334155);
  font-weight: 600;
}

.org-list {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.org-list.locked {
  pointer-events: none;
}

.org-card {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 12px;
  border: 1px solid transparent;
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: inherit;
  overflow: hidden;
  transition: all 0.2s ease;
}

/* Hover：浅灰底 + 灰影，不用蓝框，避免与「当前」双蓝冲突 */
.org-card:hover:not(:disabled):not(.current) {
  background: #f5f7fa;
  border-color: transparent;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
  cursor: pointer;
}

.org-card:hover:not(:disabled) .go-arrow {
  color: var(--brand-500, #2c7ef8);
  transform: translateX(2px);
}

.org-card.muted {
  opacity: 0.55;
  filter: grayscale(0.15);
}

.org-card.entering {
  opacity: 1;
  filter: none;
  border-color: var(--brand-300, #93c5fd);
}

.org-card:disabled {
  cursor: wait;
}

/* Active：当前机构保留蓝框与浅蓝底 */
.org-card.current:not(.entering):not(.muted) {
  border-color: var(--brand-500, #2c7ef8);
  background: linear-gradient(180deg, #fff 0%, #f8fbff 100%);
  box-shadow: 0 0 0 1px rgba(44, 126, 248, 0.08);
}

.org-card.current:hover:not(:disabled) {
  background: #f0f7ff;
  border-color: var(--brand-500, #2c7ef8);
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.06);
}

.org-icon {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  color: var(--brand-500, #2c7ef8);
  background: var(--brand-50, #eff6ff);
  border: 1px solid #dbeafe;
}

.org-copy {
  min-width: 0;
  flex: 1;
}

.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.name {
  font-size: 15px;
  font-weight: 650;
  color: var(--ink-900, #0f172a);
  line-height: 1.3;
}

.meta {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: #999999;
}

.badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  color: var(--brand-500, #2c7ef8);
  background: #eff6ff;
  border: 1px solid #dbeafe;
}

.go-arrow {
  flex-shrink: 0;
  color: #999;
  transition: color 0.2s ease, transform 0.2s ease;
}

.card-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(1px);
  color: var(--ink-700, #334155);
  font-size: 13px;
  font-weight: 500;
  z-index: 2;
}

.card-loading .is-loading {
  color: var(--brand-500, #2c7ef8);
}

.empty-search {
  padding: 32px 16px;
  text-align: center;
  background: #fff;
  border: 1px dashed var(--ink-200, #e2e8f0);
  border-radius: 12px;
}

.empty-title {
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-700, #334155);
}

.empty-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: #999;
}

.empty-clear {
  margin-top: 14px;
  appearance: none;
  border: 1px solid var(--ink-200, #e2e8f0);
  background: #fff;
  border-radius: 8px;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--brand-500, #2c7ef8);
  cursor: pointer;
}

.empty-clear:hover {
  border-color: var(--brand-300, #93c5fd);
  background: #f8fbff;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 18px;
}

.cancel-wrap {
  display: flex;
  justify-content: center;
  margin-top: 32px;
}

.cancel-btn {
  appearance: none;
  min-width: 200px;
  height: 40px;
  padding: 0 20px;
  border-radius: 10px;
  border: 1px solid #d1d5db;
  background: #fff;
  color: #6b7280;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s ease;
}

.cancel-btn:hover:not(:disabled) {
  border-color: #9ca3af;
  color: #4b5563;
  background: #f9fafb;
}

.cancel-btn:disabled {
  opacity: 0.55;
  cursor: wait;
}
</style>
