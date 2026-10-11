<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'
import {
  formatAbnormalFlag,
  formatDateTime,
  formatExamFindingRows,
  formatExamSource,
  formatExamType,
  formatLabItemValue,
  formatLabRefRange,
  formatLabSource,
  formatSpecimenType,
} from '../shared/health-data-labels'
import { isPatientSource } from '../shared/health-data-source'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const deleting = ref(false)
const report = ref<any>(null)
const kind = route.path.includes('/labs/') ? 'labs' : 'exams'
const id = String(route.params.id || '')

const title = computed(() => {
  if (!report.value) return kind === 'labs' ? '检验详情' : '检查详情'
  if (kind === 'labs') return `${formatSpecimenType(report.value.specimenType)}检验`
  return formatExamType(report.value.examType)
})

const examFindingRows = computed(() => {
  if (kind !== 'exams' || !report.value) return []
  return formatExamFindingRows(report.value.examType, report.value.findings)
})

const canEdit = computed(() => isPatientSource(report.value?.source))

onMounted(async () => {
  try {
    const res = await api<{ data: any }>(`/api/c/v1/me/${kind}/${id}`)
    report.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

function flagClass(flag?: string | null) {
  if (!flag || flag === 'N') return ''
  return 'abnormal'
}

function goEdit() {
  router.push(`/health-data/${kind}/${id}/edit`)
}

async function removeReport() {
  try {
    await showConfirmDialog({
      title: '删除确认',
      message: kind === 'labs' ? '确定删除这份检验记录？' : '确定删除这份检查记录？',
    })
  } catch {
    return
  }
  deleting.value = true
  try {
    await api(`/api/c/v1/me/${kind}/${id}`, { method: 'DELETE' })
    showToast({ type: 'success', message: '已删除' })
    router.replace('/health-data')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar :title="title" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" vertical style="padding: 40px 0">加载中</van-loading>
    <template v-else-if="report">
      <section v-if="kind === 'labs'" class="card">
        <div class="meta-row">
          <span class="label">标本</span>
          <span>{{ formatSpecimenType(report.specimenType) }}</span>
        </div>
        <div class="meta-row">
          <span class="label">采样时间</span>
          <span>{{ formatDateTime(report.sampledAt) }}</span>
        </div>
        <div class="meta-row">
          <span class="label">报告时间</span>
          <span>{{ formatDateTime(report.reportedAt) }}</span>
        </div>
        <div class="meta-row">
          <span class="label">数据来源</span>
          <span>{{ formatLabSource(report.source) }}</span>
        </div>
        <div v-if="report.note" class="meta-row">
          <span class="label">备注</span>
          <span>{{ report.note }}</span>
        </div>
      </section>

      <section v-if="kind === 'labs'" class="card">
        <h3>检验项目</h3>
        <van-empty v-if="!report.items?.length" description="暂无明细" image-size="64" />
        <div v-for="item in report.items || []" :key="item.id || item.itemCode" class="lab-item">
          <div class="lab-head">
            <strong>{{ item.itemName || item.itemCode }}</strong>
            <span v-if="formatAbnormalFlag(item.abnormalFlag)" class="flag" :class="flagClass(item.abnormalFlag)">
              {{ formatAbnormalFlag(item.abnormalFlag) }}
            </span>
          </div>
          <div class="lab-value" :class="flagClass(item.abnormalFlag)">
            {{ formatLabItemValue(item) }}
          </div>
          <div v-if="formatLabRefRange(item)" class="lab-ref">参考 {{ formatLabRefRange(item) }}</div>
        </div>
      </section>

      <section v-else class="card">
        <div class="meta-row">
          <span class="label">检查类型</span>
          <span>{{ formatExamType(report.examType) }}</span>
        </div>
        <div class="meta-row">
          <span class="label">检查时间</span>
          <span>{{ formatDateTime(report.examinedAt) }}</span>
        </div>
        <div class="meta-row">
          <span class="label">数据来源</span>
          <span>{{ formatExamSource(report.source) }}</span>
        </div>
        <div v-if="report.conclusion" class="block">
          <div class="label">结论</div>
          <p class="conclusion">{{ report.conclusion }}</p>
        </div>
        <div v-if="examFindingRows.length" class="block">
          <div class="label">关键测量</div>
          <div v-for="row in examFindingRows" :key="row.label" class="finding-row">
            <span>{{ row.label }}</span>
            <strong>{{ row.value }}</strong>
          </div>
        </div>
        <van-empty
          v-if="!report.conclusion && !examFindingRows.length"
          description="暂无检查内容"
          image-size="64"
        />
      </section>

      <div v-if="canEdit" class="actions">
        <van-button round block type="primary" @click="goEdit">修改</van-button>
        <van-button round block plain type="danger" :loading="deleting" @click="removeReport">删除</van-button>
      </div>
      <p v-else class="readonly-hint">医护代录或设备同步的数据不可在此修改，如有疑问请联系健管师</p>
    </template>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--hx-bg); padding-bottom: 24px; }
.card {
  margin: 12px 16px; padding: 16px; border-radius: 16px; background: #fff;
  box-shadow: var(--hx-shadow);
}
h3 { margin: 0 0 12px; font-size: 15px; }
.meta-row {
  display: flex; justify-content: space-between; gap: 12px;
  font-size: 14px; line-height: 1.5;
}
.meta-row + .meta-row { margin-top: 10px; }
.label { color: var(--hx-muted); font-size: 12px; flex-shrink: 0; }
.block { margin-top: 14px; padding-top: 14px; border-top: 1px solid #f0f3f3; }
.conclusion { margin: 6px 0 0; font-size: 14px; line-height: 1.6; white-space: pre-wrap; }
.finding-row {
  display: flex; justify-content: space-between; gap: 12px;
  margin-top: 8px; font-size: 14px;
}
.lab-item + .lab-item { margin-top: 14px; padding-top: 14px; border-top: 1px solid #f0f3f3; }
.lab-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.lab-value { margin-top: 4px; font-size: 18px; font-weight: 600; }
.lab-ref { margin-top: 2px; color: var(--hx-muted); font-size: 12px; }
.flag {
  font-size: 11px; padding: 2px 6px; border-radius: 999px;
  background: #fee2e2; color: #b91c1c;
}
.abnormal { color: #b91c1c; }
.actions {
  display: grid;
  gap: 10px;
  margin: 16px 16px 8px;
}
.readonly-hint {
  margin: 8px 20px 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.45;
}
</style>
