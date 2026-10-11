<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatDateTime } from '../shared/format'
import {
  formatFollowupContactTarget,
  formatFollowupMethod,
  formatFollowupStatus,
  formatFollowupType,
} from '../shared/labels'

interface FollowupDetail {
  id: string
  peopleId?: string
  recordType?: string
  recordTypeLabel?: string
  status?: string
  title?: string
  summary?: string
  content?: Record<string, unknown>
  contactChannel?: string
  completedByName?: string
  completedAt?: string
  plannedAt?: string
  gmtCreated?: string
}

const route = useRoute()
const router = useRouter()
const followupId = computed(() => String(route.params.followupId || ''))
const loading = ref(false)
const detail = ref<FollowupDetail | null>(null)

const section = computed(() => {
  const s = detail.value?.content?.section
  return s && typeof s === 'object' ? (s as Record<string, unknown>) : null
})

function contentText(key: string) {
  const c = detail.value?.content
  if (!c) return ''
  const v = c[key]
  return v == null ? '' : String(v)
}

function sectionText(key: string) {
  const s = section.value
  if (!s) return ''
  const v = s[key]
  if (Array.isArray(v)) return v.map((x) => String(x)).join('、')
  return v == null ? '' : String(v)
}

const canComplete = computed(
  () => detail.value?.status === 'OPEN' && detail.value?.recordType === 'PERIODIC',
)

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: FollowupDetail }>(`/api/b/v1/followups/${followupId.value}`)
    detail.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="随访详情" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="detail">
      <section class="hero hx-surface">
        <div class="chips">
          <span class="hx-chip">{{
            formatFollowupType(contentText('followupType')) ||
            detail.recordTypeLabel ||
            '随访'
          }}</span>
          <span class="hx-chip" :class="{ warn: detail.status === 'OPEN' }">
            {{ formatFollowupStatus(detail.status) }}
          </span>
        </div>
        <h2>{{ detail.title || detail.summary || '随访记录' }}</h2>
        <p class="hx-muted">
          {{
            formatDateTime(detail.completedAt || detail.plannedAt || detail.gmtCreated)
          }}
          <template v-if="detail.completedByName"> · {{ detail.completedByName }}</template>
        </p>
      </section>

      <section class="block hx-surface">
        <div v-if="contentText('contactTarget') || detail.contactChannel" class="row">
          <span>联系</span>
          <strong>
            {{ formatFollowupContactTarget(contentText('contactTarget')) || '—' }}
            ·
            {{
              formatFollowupMethod(contentText('followupMethod') || detail.contactChannel) || '—'
            }}
          </strong>
        </div>
        <div v-if="detail.summary" class="row">
          <span>摘要</span>
          <strong>{{ detail.summary }}</strong>
        </div>
        <div v-if="contentText('guidance')" class="row">
          <span>指导</span>
          <strong>{{ contentText('guidance') }}</strong>
        </div>
        <div v-if="contentText('abnormalReason')" class="row">
          <span>异常说明</span>
          <strong>{{ contentText('abnormalReason') }}</strong>
        </div>
        <div v-if="sectionText('adherenceNote')" class="row">
          <span>打卡概况</span>
          <strong>{{ sectionText('adherenceNote') }}</strong>
        </div>
        <div v-if="sectionText('lifestyleLevel')" class="row">
          <span>生活习惯</span>
          <strong>{{ sectionText('lifestyleLevel') }}</strong>
        </div>
        <div v-if="sectionText('planSatisfaction')" class="row">
          <span>方案满意度</span>
          <strong>{{ sectionText('planSatisfaction') }}</strong>
        </div>
        <div v-if="sectionText('unsatisfiedReason')" class="row">
          <span>不满意原因</span>
          <strong>{{ sectionText('unsatisfiedReason') }}</strong>
        </div>
        <div v-if="sectionText('lifestyleNote')" class="row">
          <span>生活备注</span>
          <strong>{{ sectionText('lifestyleNote') }}</strong>
        </div>
      </section>

      <div v-if="canComplete" class="actions">
        <van-button
          block
          type="primary"
          round
          @click="router.push(`/followups/${followupId}/complete`)"
        >
          办结随访
        </van-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.hero,
.block {
  margin: 0 16px 12px;
  padding: 14px 16px;
}
.chips {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.hero h2 {
  margin: 0 0 6px;
  font-size: 18px;
  color: var(--hx-ink);
}
.hero p {
  margin: 0;
  font-size: 13px;
}
.row {
  display: grid;
  grid-template-columns: 72px 1fr;
  gap: 8px;
  padding: 8px 0;
  font-size: 13px;
}
.row + .row {
  border-top: 1px solid var(--hx-line);
}
.row span {
  color: var(--hx-muted);
}
.row strong {
  font-weight: 550;
  color: var(--hx-text);
  line-height: 1.45;
}
.actions {
  padding: 4px 16px 24px;
}
</style>
