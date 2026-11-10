<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { loadGuidelineLibrary, type GuidelineLibraryView } from '../../shared/guidelines'

const loading = ref(false)
const lib = ref<GuidelineLibraryView | null>(null)
const keyword = ref('')
const sourceCode = ref('')
const audience = ref('')
const kind = ref('')

const BLOCK_LABELS: Record<string, string> = { exercise: '运动', diet: '饮食', execution: '执行与监测' }

const audiences = computed(() => Object.keys(lib.value?.stats.byAudience ?? {}))

const rows = computed(() => {
  const kw = keyword.value.trim()
  return (lib.value?.recs ?? []).filter(
    (r) =>
      (!sourceCode.value || r.sourceCode === sourceCode.value) &&
      (!audience.value || r.audience === audience.value) &&
      (!kind.value || r.kind === kind.value) &&
      (!kw || r.staffText.includes(kw) || r.code.includes(kw.toUpperCase())),
  )
})

async function load() {
  loading.value = true
  try {
    lib.value = await loadGuidelineLibrary()
  } catch (e) {
    ElMessage.error((e as Error).message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <template v-if="lib">
      <el-alert
        v-if="!lib.enabled"
        type="info"
        :closable="false"
        show-icon
        title="依据库尚未启用"
        description="条目经健康顾问审定后上线；上线前生成的方案不显示依据标签。"
        class="mb12"
      />
      <el-alert
        v-else-if="lib.includesDraft"
        type="warning"
        :closable="false"
        show-icon
        title="当前环境包含未审定的草稿条目，仅供开发联调，不可用于对外演示"
        class="mb12"
      />

      <template v-if="lib.enabled">
        <div class="summary">
          <div class="card">
            <div class="card-title">条目</div>
            <div class="card-num">{{ lib.stats.total }}</div>
            <div class="card-sub">安全类 {{ lib.stats.safety }} · 目标类 {{ lib.stats.target }}</div>
          </div>
          <div class="card">
            <div class="card-title">适用人群</div>
            <div class="card-sub">
              <span v-for="(n, k) in lib.stats.byAudience" :key="k" class="aud">{{ k }} {{ n }}</span>
            </div>
          </div>
          <div class="card">
            <div class="card-title">审定</div>
            <div v-if="lib.reviewers.length" class="card-sub">
              <div v-for="r in lib.reviewers" :key="r.name">
                {{ r.name }}<template v-if="r.title"> · {{ r.title }}</template>
                <span class="muted">（{{ r.recCount }} 条，最近 {{ r.lastReviewedAt || '—' }}）</span>
              </div>
            </div>
            <div v-else class="card-sub muted">未审定</div>
          </div>
          <div class="card">
            <div class="card-title">版本</div>
            <div class="card-sub">{{ lib.version }}</div>
          </div>
        </div>

        <h4 class="section">指南来源</h4>
        <el-table :data="lib.sources" border size="small" class="mb12">
          <el-table-column prop="label" label="指南" min-width="220" />
          <el-table-column prop="publisher" label="发布机构" min-width="200">
            <template #default="{ row }">{{ row.publisher || '—' }}</template>
          </el-table-column>
          <el-table-column prop="recCount" label="条目数" width="90" />
        </el-table>

        <h4 class="section">推荐条目</h4>
        <div class="toolbar">
          <el-input v-model="keyword" clearable placeholder="搜索内容或编码" style="width: 220px" />
          <el-select v-model="sourceCode" clearable placeholder="指南" style="width: 200px">
            <el-option v-for="s in lib.sources" :key="s.code" :label="s.label" :value="s.code" />
          </el-select>
          <el-select v-model="audience" clearable placeholder="人群" style="width: 140px">
            <el-option v-for="a in audiences" :key="a" :label="a" :value="a" />
          </el-select>
          <el-select v-model="kind" clearable placeholder="类型" style="width: 120px">
            <el-option label="安全类" value="SAFETY" />
            <el-option label="目标类" value="TARGET" />
          </el-select>
        </div>
        <el-table :data="rows" border stripe size="small">
          <el-table-column prop="code" label="编码" width="170" />
          <el-table-column label="类型" width="80">
            <template #default="{ row }">
              <el-tag :type="row.kind === 'SAFETY' ? 'danger' : 'success'" size="small">
                {{ row.kind === 'SAFETY' ? '安全' : '目标' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="audience" label="人群" width="110" />
          <el-table-column label="方案块" width="100">
            <template #default="{ row }">{{ BLOCK_LABELS[row.block] || row.block }}</template>
          </el-table-column>
          <el-table-column label="推荐" min-width="280">
            <template #default="{ row }">
              <div>{{ row.staffText }}</div>
              <div v-if="row.constraint" class="muted">校验：{{ row.constraint }}</div>
            </template>
          </el-table-column>
          <el-table-column prop="sourceLabel" label="出处" min-width="200" />
          <el-table-column label="证据" width="90">
            <template #default="{ row }">{{ row.evidence || '—' }}</template>
          </el-table-column>
          <el-table-column v-if="lib.includesDraft" label="状态" width="80">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'DRAFT'" type="warning" size="small">草稿</el-tag>
              <el-tag v-else size="small">已审定</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="footnote">
          条目为对公开指南推荐意见的转述，用于健康管理方案的生成与校验，不构成诊疗建议；以指南原文为准。
        </p>
      </template>
    </template>
  </div>
</template>

<style scoped>
.page {
  padding: 4px 0 24px;
}
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}
.mb12 {
  margin-bottom: 12px;
}
.summary {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 12px 14px;
  background: var(--el-bg-color);
}
.card-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.card-num {
  font-size: 24px;
  font-weight: 600;
}
.card-sub {
  font-size: 13px;
  line-height: 1.7;
}
.aud {
  margin-right: 10px;
}
.section {
  margin: 8px 0;
  font-size: 14px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.footnote {
  margin-top: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
