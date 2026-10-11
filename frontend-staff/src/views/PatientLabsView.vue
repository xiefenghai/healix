<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatDateTime } from '../shared/format'

interface LabItem {
  id?: string
  itemCode: string
  itemName?: string
  valueNum?: number | null
  valueText?: string
  unit?: string
  refLow?: number | null
  refHigh?: number | null
  abnormalFlag?: string
}

interface LabReport {
  id: string
  specimenType?: string
  sampledAt?: string
  reportedAt?: string
  source?: string
  note?: string
  items: LabItem[]
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const list = ref<LabReport[]>([])
const expanded = ref('')

function specimenLabel(v?: string) {
  if (v === 'BLOOD') return '血液'
  if (v === 'URINE') return '尿液'
  return v || '检验'
}

function flagLabel(f?: string) {
  if (f === 'H') return '偏高'
  if (f === 'L') return '偏低'
  if (f === 'N') return '正常'
  return ''
}

function itemValue(it: LabItem) {
  if (it.valueText) return it.valueText
  if (it.valueNum != null) return `${it.valueNum}${it.unit ? ` ${it.unit}` : ''}`
  return '—'
}

function abnormalCount(row: LabReport) {
  return (row.items || []).filter((i) => i.abnormalFlag === 'H' || i.abnormalFlag === 'L').length
}

function toggle(id: string) {
  expanded.value = expanded.value === id ? '' : id
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: LabReport[] }>(
      `/api/b/v1/patients/${peopleId.value}/lab-reports`,
    )
    list.value = res.data || []
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
    <van-nav-bar title="检验报告" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else class="hx-list">
      <van-empty v-if="!list.length" description="暂无检验" />
      <article
        v-for="row in list"
        :key="row.id"
        class="card hx-surface"
        @click="toggle(row.id)"
      >
        <div class="top">
          <strong>{{ specimenLabel(row.specimenType) }}</strong>
          <span v-if="abnormalCount(row)" class="hx-chip warn">异常 {{ abnormalCount(row) }}</span>
          <span v-else class="hx-chip">{{ (row.items || []).length }} 项</span>
        </div>
        <p class="meta hx-muted">
          报告 {{ formatDateTime(row.reportedAt) || formatDateTime(row.sampledAt) || '—' }}
        </p>
        <p v-if="row.note" class="meta hx-muted">{{ row.note }}</p>
        <div v-if="expanded === row.id" class="items">
          <div
            v-for="(it, idx) in row.items"
            :key="it.id || it.itemCode || idx"
            class="item"
            :class="{ bad: it.abnormalFlag === 'H' || it.abnormalFlag === 'L' }"
          >
            <div>
              <span>{{ it.itemName || it.itemCode }}</span>
              <small v-if="it.refLow != null || it.refHigh != null" class="hx-muted">
                参考 {{ it.refLow ?? '—' }}~{{ it.refHigh ?? '—' }}{{ it.unit ? ` ${it.unit}` : '' }}
              </small>
            </div>
            <em>
              {{ itemValue(it) }}
              <template v-if="flagLabel(it.abnormalFlag)"> · {{ flagLabel(it.abnormalFlag) }}</template>
            </em>
          </div>
        </div>
        <p v-else class="hint hx-muted">点击展开明细</p>
      </article>
      <p class="tip hx-muted">只读；录入与 OCR 请使用电脑端。</p>
    </div>
  </div>
</template>

<style scoped>
.card {
  padding: 14px;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.top strong {
  font-size: 15px;
  color: var(--hx-ink);
}
.meta {
  margin: 6px 0 0;
  font-size: 12px;
}
.hint {
  margin: 8px 0 0;
  font-size: 11px;
}
.items {
  margin-top: 10px;
  border-top: 1px solid var(--hx-line);
  padding-top: 6px;
}
.item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 0;
  font-size: 13px;
}
.item small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
}
.item em {
  font-style: normal;
  font-weight: 650;
  color: var(--hx-ink);
  white-space: nowrap;
}
.item.bad em {
  color: var(--hx-danger);
}
.tip {
  margin: 8px 4px 0;
  font-size: 12px;
}
</style>
