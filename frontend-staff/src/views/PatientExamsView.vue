<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { formatDateTime } from '../shared/format'

const EXAM_TYPE_LABELS: Record<string, string> = {
  ECG: '心电图',
  UCG: '心脏彩超',
  CAROTID_US: '颈动脉彩超',
  ABDOMINAL_US: '腹部超声',
  THYROID_US: '甲状腺超声',
  CHEST_IMAGING: '胸部影像',
  ABDOMINAL_CT: '腹部CT',
  PFT: '肺功能',
  BMD: '骨密度',
  FUNDUS: '眼底检查',
}

interface ExamReport {
  id: string
  examType: string
  examinedAt?: string
  conclusion?: string
  findings?: Record<string, unknown>
  source?: string
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const list = ref<ExamReport[]>([])
const expanded = ref('')

function typeLabel(code?: string) {
  if (!code) return '检查'
  return EXAM_TYPE_LABELS[code] || code
}

function findingsLines(row: ExamReport) {
  const f = row.findings || {}
  return Object.entries(f)
    .filter(([, v]) => v != null && v !== '')
    .slice(0, 12)
    .map(([k, v]) => `${k}: ${typeof v === 'object' ? JSON.stringify(v) : String(v)}`)
}

function toggle(id: string) {
  expanded.value = expanded.value === id ? '' : id
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: ExamReport[] }>(
      `/api/b/v1/patients/${peopleId.value}/exam-reports`,
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
    <van-nav-bar title="检查报告" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else class="hx-list">
      <van-empty v-if="!list.length" description="暂无检查" />
      <article
        v-for="row in list"
        :key="row.id"
        class="card hx-surface"
        @click="toggle(row.id)"
      >
        <div class="top">
          <strong>{{ typeLabel(row.examType) }}</strong>
          <span class="meta hx-muted">{{ formatDateTime(row.examinedAt) || '—' }}</span>
        </div>
        <p class="conclusion">{{ row.conclusion || '暂无结论' }}</p>
        <div v-if="expanded === row.id && findingsLines(row).length" class="findings">
          <p v-for="(line, i) in findingsLines(row)" :key="i" class="hx-muted">{{ line }}</p>
        </div>
        <p v-else-if="findingsLines(row).length" class="hint hx-muted">点击查看所见</p>
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
  align-items: baseline;
  gap: 8px;
}
.top strong {
  font-size: 15px;
  color: var(--hx-ink);
}
.meta {
  font-size: 12px;
}
.conclusion {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.45;
  color: var(--hx-text);
}
.findings {
  margin-top: 10px;
  border-top: 1px solid var(--hx-line);
  padding-top: 8px;
}
.findings p {
  margin: 0 0 4px;
  font-size: 12px;
  word-break: break-all;
}
.hint {
  margin: 8px 0 0;
  font-size: 11px;
}
.tip {
  margin: 8px 4px 0;
  font-size: 12px;
}
</style>
