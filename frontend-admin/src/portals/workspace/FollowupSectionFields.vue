<script setup lang="ts">
import { computed } from 'vue'
import {
  BASELINE_METRIC_OPTIONS,
  LIFESTYLE_LEVEL_OPTIONS,
  MISSED_DOSE_FREQUENCY_OPTIONS,
  MISSED_DOSE_REASON_OPTIONS,
  ONBOARDING_NEXT_ACTION_OPTIONS,
  PLAN_BLOCKER_OPTIONS,
  PLAN_SATISFACTION_OPTIONS,
  SYMPTOM_DISPOSITION_OPTIONS,
  SYMPTOM_TAG_OPTIONS,
  type BaselineMetricEntry,
  type FollowupSection,
  usesStructuredSection,
} from '../../shared/followup-labels'

const props = defineProps<{
  followupType: string
  modelValue: FollowupSection
}>()

const emit = defineEmits<{
  'update:modelValue': [FollowupSection]
}>()

function patch(p: Partial<FollowupSection>) {
  emit('update:modelValue', { ...props.modelValue, ...p })
}

const baselineFull = computed(
  () => (props.modelValue.baselineMetrics?.length || 0) >= BASELINE_METRIC_OPTIONS.length,
)

function unitOf(metricType: string) {
  return BASELINE_METRIC_OPTIONS.find((o) => o.value === metricType)?.unit
}

/** 已选类型（排除当前行），用于禁用重复项 */
function usedMetricTypes(exceptIdx: number) {
  const list = props.modelValue.baselineMetrics || []
  return new Set(list.filter((_, i) => i !== exceptIdx).map((m) => m.metricType).filter(Boolean))
}

function addBaseline() {
  const list: BaselineMetricEntry[] = [...(props.modelValue.baselineMetrics || [])]
  list.push({ metricType: '', value: '' })
  patch({ baselineMetrics: list })
}

function patchBaseline(idx: number, p: Partial<BaselineMetricEntry>) {
  const list: BaselineMetricEntry[] = [...(props.modelValue.baselineMetrics || [])]
  if (!list[idx]) return
  list[idx] = { ...list[idx], ...p }
  patch({ baselineMetrics: list })
}

function removeBaseline(idx: number) {
  const list: BaselineMetricEntry[] = [...(props.modelValue.baselineMetrics || [])]
  list.splice(idx, 1)
  patch({ baselineMetrics: list })
}
</script>

<template>
  <template v-if="followupType === 'ONBOARDING'">
    <el-form-item label="下次动作" required>
      <el-select
        :model-value="modelValue.nextAction"
        placeholder="请选择"
        style="width: 100%"
        @update:model-value="(v: string) => patch({ nextAction: v })"
      >
        <el-option
          v-for="o in ONBOARDING_NEXT_ACTION_OPTIONS"
          :key="o.value"
          :label="o.label"
          :value="o.value"
        />
      </el-select>
    </el-form-item>

    <el-form-item label="基线指标">
      <div class="baseline-box">
        <div
          v-for="(m, idx) in modelValue.baselineMetrics || []"
          :key="idx"
          class="baseline-row"
        >
          <el-select
            :model-value="m.metricType"
            placeholder="指标"
            class="baseline-metric"
            @update:model-value="(v: string) => patchBaseline(idx, { metricType: v, unit: unitOf(v) })"
          >
            <el-option
              v-for="o in BASELINE_METRIC_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
              :disabled="usedMetricTypes(idx).has(o.value)"
            />
          </el-select>
          <el-input
            :model-value="m.value"
            placeholder="数值"
            class="baseline-value"
            @update:model-value="(v: string) => patchBaseline(idx, { value: v })"
          >
            <template v-if="m.unit" #append>{{ m.unit }}</template>
          </el-input>
          <el-button link type="danger" @click="removeBaseline(idx)">删除</el-button>
        </div>
        <div class="baseline-actions">
          <el-button link type="primary" :disabled="baselineFull" @click="addBaseline">
            + 添加基线指标
          </el-button>
          <span class="baseline-hint">选填，办结时写入体征记录（医护代录）</span>
        </div>
      </div>
    </el-form-item>
  </template>

  <template v-else-if="followupType === 'ROUTINE'">
    <el-form-item label="近期打卡概况" required>
      <el-input
        :model-value="modelValue.adherenceNote"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        maxlength="400"
        show-word-limit
        placeholder="近7日完成情况、连续未打等"
        @update:model-value="(v: string) => patch({ adherenceNote: v })"
      />
    </el-form-item>

    <div class="section-row">
      <el-form-item label="生活习惯执行" required class="section-col">
        <el-radio-group
          class="choice-group"
          :model-value="modelValue.lifestyleLevel"
          @update:model-value="(v: string) => patch({ lifestyleLevel: v })"
        >
          <el-radio-button v-for="o in LIFESTYLE_LEVEL_OPTIONS" :key="o.value" :value="o.value">
            {{ o.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="方案满意度" required class="section-col">
        <el-radio-group
          class="choice-group"
          :model-value="modelValue.planSatisfaction"
          @update:model-value="(v: string) => patch({ planSatisfaction: v })"
        >
          <el-radio-button v-for="o in PLAN_SATISFACTION_OPTIONS" :key="o.value" :value="o.value">
            {{ o.label }}
          </el-radio-button>
        </el-radio-group>
      </el-form-item>
    </div>

    <el-form-item
      v-if="modelValue.planSatisfaction === 'UNSATISFIED'"
      label="不满意原因"
      required
    >
      <el-input
        :model-value="modelValue.unsatisfiedReason"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        maxlength="400"
        show-word-limit
        placeholder="请说明不满意的原因"
        @update:model-value="(v: string) => patch({ unsatisfiedReason: v })"
      />
    </el-form-item>

    <el-form-item label="生活习惯说明">
      <el-input
        :model-value="modelValue.lifestyleNote"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 10 }"
        maxlength="400"
        show-word-limit
        placeholder="选填，可写饮食、运动、作息等补充说明"
        @update:model-value="(v: string) => patch({ lifestyleNote: v })"
      />
    </el-form-item>
    <el-form-item label="症状备注">
      <el-input
        :model-value="modelValue.symptomNote"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 10 }"
        maxlength="400"
        show-word-limit
        placeholder="选填，可写不适症状、持续时间等"
        @update:model-value="(v: string) => patch({ symptomNote: v })"
      />
    </el-form-item>
  </template>

  <template v-else-if="followupType === 'PLAN_ADHERENCE'">
    <div class="section-row">
      <el-form-item label="自评执行率" required class="section-col">
        <el-input-number
          :model-value="modelValue.selfRatePct ?? undefined"
          :min="0"
          :max="100"
          :step="5"
          controls-position="right"
          style="width: 100%"
          @update:model-value="(v: number | undefined) => patch({ selfRatePct: v ?? null })"
        />
      </el-form-item>
      <el-form-item label="主要未完成原因" required class="section-col">
        <el-select
          :model-value="modelValue.mainBlocker"
          placeholder="请选择"
          style="width: 100%"
          @update:model-value="(v: string) => patch({ mainBlocker: v })"
        >
          <el-option v-for="o in PLAN_BLOCKER_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </el-form-item>
    </div>

    <el-form-item
      label="原因说明"
      :required="modelValue.mainBlocker === 'OTHER'"
    >
      <el-input
        :model-value="modelValue.blockerNote"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        maxlength="400"
        show-word-limit
        :placeholder="modelValue.mainBlocker === 'OTHER' ? '请说明具体原因' : '选填，可补充具体情况'"
        @update:model-value="(v: string) => patch({ blockerNote: v })"
      />
    </el-form-item>

    <el-form-item label="拟调整项" required>
      <el-input
        :model-value="modelValue.planChange"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        maxlength="400"
        show-word-limit
        placeholder="如降低运动强度、调整打卡时段等"
        @update:model-value="(v: string) => patch({ planChange: v })"
      />
    </el-form-item>
  </template>

  <template v-else-if="followupType === 'MEDICATION'">
    <el-form-item label="漏服频次" required>
      <el-radio-group
        class="choice-group"
        :model-value="modelValue.missedDoseFrequency"
        @update:model-value="(v: string) => patch({ missedDoseFrequency: v })"
      >
        <el-radio-button v-for="o in MISSED_DOSE_FREQUENCY_OPTIONS" :key="o.value" :value="o.value">
          {{ o.label }}
        </el-radio-button>
      </el-radio-group>
    </el-form-item>

    <el-form-item
      v-if="modelValue.missedDoseFrequency && modelValue.missedDoseFrequency !== 'NONE'"
      label="漏服原因"
      required
    >
      <el-select
        :model-value="modelValue.missedDoseReason"
        placeholder="请选择"
        style="width: 100%"
        @update:model-value="(v: string) => patch({ missedDoseReason: v })"
      >
        <el-option
          v-for="o in MISSED_DOSE_REASON_OPTIONS"
          :key="o.value"
          :label="o.label"
          :value="o.value"
        />
      </el-select>
    </el-form-item>

    <div class="section-row">
      <el-form-item label="有不良反应" required class="section-col">
        <el-radio-group
          class="choice-group"
          :model-value="modelValue.hasAdverseReaction"
          @update:model-value="(v: boolean) => patch({ hasAdverseReaction: v })"
        >
          <el-radio-button :value="false">无</el-radio-button>
          <el-radio-button :value="true">有</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="需医生调药" class="section-col">
        <el-radio-group
          class="choice-group"
          :model-value="!!modelValue.needDoctorAdjust"
          @update:model-value="(v: boolean) => patch({ needDoctorAdjust: v })"
        >
          <el-radio-button :value="false">否</el-radio-button>
          <el-radio-button :value="true">是</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </div>

    <el-form-item
      label="不良反应描述"
      :required="modelValue.hasAdverseReaction === true"
    >
      <el-input
        :model-value="modelValue.adverseNote"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        maxlength="400"
        show-word-limit
        :placeholder="modelValue.hasAdverseReaction ? '请描述症状、出现时间与处理' : '选填'"
        @update:model-value="(v: string) => patch({ adverseNote: v })"
      />
    </el-form-item>
  </template>

  <template v-else-if="followupType === 'SYMPTOM_METRIC'">
    <el-form-item label="症状清单">
      <el-select
        :model-value="modelValue.symptoms || []"
        multiple
        filterable
        allow-create
        default-first-option
        placeholder="可多选，也可直接输入新增"
        style="width: 100%"
        @update:model-value="(v: string[]) => patch({ symptoms: v })"
      >
        <el-option v-for="s in SYMPTOM_TAG_OPTIONS" :key="s" :label="s" :value="s" />
      </el-select>
    </el-form-item>

    <el-form-item label="症状说明">
      <el-input
        :model-value="modelValue.symptomNote"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        maxlength="400"
        show-word-limit
        placeholder="未选清单时必填其一：持续时间、诱因、缓解方式等"
        @update:model-value="(v: string) => patch({ symptomNote: v })"
      />
    </el-form-item>

    <el-form-item label="复测值">
      <el-input
        :model-value="modelValue.retestNote"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        maxlength="400"
        show-word-limit
        placeholder="选填，如血压 138/86、空腹血糖 7.2"
        @update:model-value="(v: string) => patch({ retestNote: v })"
      />
    </el-form-item>

    <el-form-item label="处置结论" required>
      <el-select
        :model-value="modelValue.disposition"
        placeholder="请选择"
        style="width: 100%"
        @update:model-value="(v: string) => patch({ disposition: v })"
      >
        <el-option
          v-for="o in SYMPTOM_DISPOSITION_OPTIONS"
          :key="o.value"
          :label="o.label"
          :value="o.value"
        />
      </el-select>
    </el-form-item>
  </template>

  <template v-else-if="!usesStructuredSection(followupType)">
    <el-form-item label="随访内容" required>
      <el-input
        :model-value="modelValue.content"
        type="textarea"
        :autosize="{ minRows: 3, maxRows: 10 }"
        maxlength="400"
        show-word-limit
        placeholder="沟通要点、患者反馈等"
        @update:model-value="(v: string) => patch({ content: v })"
      />
    </el-form-item>
  </template>
</template>

<style scoped>
.section-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}
.section-col {
  margin-bottom: 14px;
}
.choice-group {
  display: flex;
  width: 100%;
}
.choice-group :deep(.el-radio-button) {
  flex: 1;
}
.choice-group :deep(.el-radio-button__inner) {
  width: 100%;
  padding: 8px 0;
}
.baseline-box {
  width: 100%;
}
.baseline-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.baseline-metric {
  width: 150px;
  flex: none;
}
.baseline-value {
  flex: 1;
  min-width: 120px;
}
.baseline-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}
.baseline-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
@media (max-width: 640px) {
  .section-row {
    grid-template-columns: 1fr;
  }
}
</style>
