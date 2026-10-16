<script setup lang="ts">
import { computed } from 'vue'
import {
  type AssessmentSnapshot,
  formatDateTime,
  sourceLabel,
  tierTone,
  toneTagType,
} from './assessment'

const props = defineProps<{
  snapshot: AssessmentSnapshot
  /** 允许点「问卷补充」跳转 */
  canFill?: boolean
}>()

const emit = defineEmits<{
  (e: 'fill', field: string): void
}>()

const snap = computed(() => props.snapshot)
const scored = computed(() => snap.value.items.some((i) => i.points != null))
const groups = computed(() => snap.value.factorGroups.filter((g) => g.hits.length || g.unchecked.length))
</script>

<template>
  <div class="detail">
    <el-alert
      v-if="snap.legacy"
      type="info"
      :closable="false"
      show-icon
      title="这是规则升级前的历史记录，仅展示当时的结论与评分项"
      class="mb"
    />

    <div v-if="snap.dimensions.length" class="dims">
      <div v-for="d in snap.dimensions" :key="d.code" class="dim" :class="tierTone(d.tier)">
        <div class="dim-label">{{ d.label }}</div>
        <div class="dim-value">{{ d.value || '—' }}</div>
        <div v-if="d.hint" class="dim-hint">{{ d.hint }}</div>
      </div>
    </div>

    <div v-if="snap.items.length" class="block">
      <div class="block-title">
        {{ scored ? '评分明细' : '评估数据' }}
        <span v-if="snap.score != null" class="block-sub">合计 {{ snap.score }} 分</span>
      </div>
      <el-table :data="snap.items" size="small" border>
        <el-table-column prop="label" label="项目" width="130" />
        <el-table-column prop="value" label="取值" min-width="150" />
        <el-table-column v-if="scored" prop="points" label="得分" width="70" align="center" />
        <el-table-column prop="rationale" label="说明" min-width="160" />
      </el-table>
    </div>

    <div v-for="g in groups" :key="g.code" class="block">
      <div class="block-title">
        {{ g.title }}
        <span class="block-sub">命中 {{ g.hits.length }} 项</span>
      </div>
      <div v-if="g.hits.length" class="hits">
        <div v-for="h in g.hits" :key="h.code" class="hit">
          <span class="hit-dot" aria-hidden="true" />
          <span class="hit-label">{{ h.label }}</span>
          <span v-if="h.detail" class="hit-detail">{{ h.detail }}</span>
          <el-tag v-if="h.source" size="small" effect="plain" type="info">{{ sourceLabel(h.source) }}</el-tag>
        </div>
      </div>
      <div v-if="g.unchecked.length" class="unchecked">
        <span class="unchecked-title">未核实：</span>
        <span v-for="u in g.unchecked" :key="u.field + u.label" class="unchecked-item">
          {{ u.label }}
          <el-button
            v-if="canFill && u.questionnaire"
            link
            type="primary"
            size="small"
            @click="emit('fill', u.field)"
          >
            问卷补充
          </el-button>
        </span>
      </div>
    </div>

    <div v-if="snap.comparisons.length" class="block">
      <div class="block-title">与档案记录比对</div>
      <div v-for="c in snap.comparisons" :key="c.label" class="cmp" :class="{ diff: !c.consistent }">
        <span class="cmp-label">{{ c.label }}</span>
        <span>档案：{{ c.value || '—' }}</span>
        <span>系统计算：{{ c.computed || '—' }}</span>
        <el-tag size="small" :type="c.consistent ? 'success' : 'warning'">{{ c.consistent ? '一致' : '不一致' }}</el-tag>
        <span v-if="c.hint" class="cmp-hint">{{ c.hint }}</span>
      </div>
    </div>

    <ul v-if="snap.notes.length" class="notes">
      <li v-for="n in snap.notes" :key="n">{{ n }}</li>
    </ul>

    <div class="meta">
      <span v-if="snap.guideline?.name">依据：{{ snap.guideline.name }}</span>
      <span v-if="snap.rulePackVersion">规则包 {{ snap.rulePackVersion }}</span>
      <el-tag v-if="snap.provisional" size="small" type="warning" effect="plain">暂定口径</el-tag>
      <span>{{ snap.triggerLabel || '' }} · {{ formatDateTime(snap.assessedAt) }}</span>
      <el-tag v-if="snap.tierLabel" size="small" :type="toneTagType(snap.tone)">统一等级：{{ snap.tierLabel }}</el-tag>
    </div>
  </div>
</template>

<style scoped>
.detail {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.mb {
  margin-bottom: 2px;
}
.dims {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 10px;
}
.dim {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  background: #fafafa;
}
.dim.danger {
  background: #fff5f5;
  border-color: #fcd4d4;
}
.dim.warning {
  background: #fffbeb;
  border-color: #fde68a;
}
.dim.success {
  background: #f0fdf4;
  border-color: #bbf7d0;
}
.dim-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.dim-value {
  font-size: 14px;
  font-weight: 600;
  margin-top: 4px;
  line-height: 1.4;
}
.dim-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 4px;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.block-sub {
  font-weight: 400;
  color: var(--el-text-color-secondary);
  margin-left: 6px;
}
.hits {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.hit {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.hit-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-danger);
  flex-shrink: 0;
}
.hit-detail {
  color: var(--el-text-color-secondary);
}
.unchecked {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.9;
}
.unchecked-item {
  margin-right: 12px;
}
.cmp {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.cmp-label {
  font-weight: 600;
}
.cmp-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
}
.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  border-top: 1px dashed var(--el-border-color-lighter);
  padding-top: 10px;
}
</style>
