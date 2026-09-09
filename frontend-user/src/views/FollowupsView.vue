<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  formatFollowupDateTime,
  formatFollowupRecordType,
  formatFollowupStatus,
  formatFollowupType,
} from '../shared/followup-labels'

interface Item {
  id: string
  recordType?: string
  recordTypeLabel?: string
  followupType?: string
  followupTypeLabel?: string
  status?: string
  title?: string
  summary?: string
  plannedAt?: string
  dueAt?: string
  completedAt?: string
  gmtCreated?: string
}

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const items = ref<Item[]>([])

function typeLabel(row: Item) {
  return (
    row.followupTypeLabel ||
    formatFollowupType(row.followupType) ||
    row.recordTypeLabel ||
    formatFollowupRecordType(row.recordType) ||
    '随访'
  )
}

function timeLabel(row: Item) {
  if (row.status === 'DONE') {
    const t = formatFollowupDateTime(row.completedAt)
    return t ? `完成 ${t}` : ''
  }
  const t = formatFollowupDateTime(row.plannedAt || row.dueAt || row.gmtCreated)
  return t ? `计划 ${t}` : ''
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: Item[] }>('/api/c/v1/followups?limit=50')
    items.value = res.data ?? []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

const requestOpen = ref(false)
const requestReason = ref('')
const requestPreferredDay = ref('')
const datePickerOpen = ref(false)
const datePickerValue = ref<string[]>([])
const minDate = new Date()
const maxDate = new Date(Date.now() + 90 * 24 * 3600 * 1000)

function onDateConfirm({ selectedValues }: { selectedValues: string[] }) {
  requestPreferredDay.value = selectedValues.join('-')
  datePickerOpen.value = false
}

/** 返回 false 可阻止弹窗关闭，用于提交失败时保留已填内容 */
async function onRequestClose(action: string) {
  if (action !== 'confirm') {
    resetRequestForm()
    return true
  }
  try {
    const res = await api<{ data: { created?: boolean; message?: string } }>(
      '/api/c/v1/followups/requests',
      {
        method: 'POST',
        body: JSON.stringify({
          reason: requestReason.value.trim() || null,
          preferredDay: requestPreferredDay.value || null,
        }),
      },
    )
    showToast(res.data?.message || '已提交回访申请')
    resetRequestForm()
    await load()
    return true
  } catch (e) {
    showToast(e instanceof Error ? e.message : '提交失败')
    return false
  }
}

function resetRequestForm() {
  requestReason.value = ''
  requestPreferredDay.value = ''
  datePickerValue.value = []
}

onMounted(() => {
  // 助手的「申请回访」按钮带 ?request=1 进来，直接把弹窗打开
  if (route.query.request) {
    requestOpen.value = true
  }
  void load()
})
</script>

<template>
  <div class="page">
    <van-nav-bar
      title="随访记录"
      left-arrow
      right-text="申请回访"
      @click-left="router.back()"
      @click-right="requestOpen = true"
    />
    <van-loading v-if="loading" class="loading" vertical>加载中</van-loading>
    <template v-else>
      <van-empty v-if="!items.length" description="暂无随访记录">
        <van-button round type="primary" size="small" @click="requestOpen = true">
          申请回访
        </van-button>
      </van-empty>
      <van-cell-group v-else inset class="group">
        <van-cell
          v-for="row in items"
          :key="row.id"
          is-link
          :title="row.title || typeLabel(row)"
          :label="[typeLabel(row), row.summary, timeLabel(row)].filter(Boolean).join('\n')"
          :value="formatFollowupStatus(row.status)"
          :value-class="row.status === 'OPEN' ? 'tone-warn' : 'tone-ok'"
          @click="router.push(`/followups/${row.id}`)"
        />
      </van-cell-group>
    </template>

    <van-dialog
      v-model:show="requestOpen"
      title="申请回访"
      show-cancel-button
      :before-close="onRequestClose"
    >
      <div class="request-form">
        <van-field
          v-model="requestReason"
          type="textarea"
          rows="3"
          maxlength="200"
          show-word-limit
          label="想沟通的问题"
          placeholder="选填，例如：血压一直偏高，想调整方案"
        />
        <van-field
          :model-value="requestPreferredDay"
          is-link
          readonly
          label="希望日期"
          placeholder="选填，默认尽快"
          @click="datePickerOpen = true"
        />
      </div>
    </van-dialog>

    <van-popup v-model:show="datePickerOpen" position="bottom" round>
      <van-date-picker
        v-model="datePickerValue"
        title="选择希望日期"
        :min-date="minDate"
        :max-date="maxDate"
        @cancel="datePickerOpen = false"
        @confirm="onDateConfirm"
      />
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding: 12px 0 28px;
}
.loading {
  padding: 48px 0;
}
.request-form {
  padding: 8px 0 4px;
}
.group :deep(.van-cell__title) {
  font-size: 15px;
  font-weight: 600;
  color: var(--hx-text);
}
.group :deep(.van-cell__label) {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--hx-muted);
  white-space: pre-line;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.group :deep(.van-cell__value) {
  font-size: 12px;
  font-weight: 600;
}
.group :deep(.tone-ok) {
  color: #1f8a6e !important;
}
.group :deep(.tone-warn) {
  color: #c4841a !important;
}
</style>
