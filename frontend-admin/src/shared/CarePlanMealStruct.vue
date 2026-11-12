<script setup lang="ts">
import { computed } from 'vue'
import {
  MEAL_PART_LABELS,
  parseMealStructure,
  type MealPart,
  type MealPartKind,
} from './care-plan-meal-format'

const props = withDefaults(
  defineProps<{
    text?: string | null
    /** 加餐列：水果/坚果/奶制品用加餐色系 */
    snackish?: boolean
    allergenKeywords?: string[]
    allergyUnknown?: boolean
    emptyText?: string
  }>(),
  {
    text: '',
    snackish: false,
    allergenKeywords: () => [],
    allergyUnknown: false,
    emptyText: '—',
  },
)

const parts = computed((): MealPart[] => {
  const raw = String(props.text || '').trim()
  if (!raw) return []
  return parseMealStructure(raw, {
    allergenKeywords: props.allergenKeywords,
    allergyUnknown: props.allergyUnknown,
  }).parts
})

function labelOf(kind: MealPartKind) {
  return MEAL_PART_LABELS[kind] || '其他'
}
</script>

<template>
  <div v-if="parts.length" class="meal-struct">
    <div
      v-for="(part, i) in parts"
      :key="i"
      class="meal-struct-row"
      :data-kind="part.kind"
      :class="{
        hit: !!part.allergenHit,
        warn: !!part.highRisk && !part.allergenHit,
        snackish: snackish,
      }"
    >
      <span class="meal-struct-lab">{{ labelOf(part.kind) }}</span>
      <span class="meal-struct-txt">{{ part.text }}</span>
      <span
        v-if="part.allergenHit"
        class="meal-flag meal-flag--bad"
        :title="'命中过敏：' + part.allergenHit"
      >×</span>
      <span
        v-else-if="part.highRisk"
        class="meal-flag meal-flag--warn"
        title="高致敏，请确认过敏史"
      >!</span>
    </div>
  </div>
  <div v-else class="meal-struct-empty">{{ emptyText }}</div>
</template>

<style scoped>
.meal-struct {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 100%;
}

.meal-struct-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 6px;
  font-size: 12.5px;
  line-height: 1.45;
}

.meal-struct-lab {
  flex-shrink: 0;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: #64748b;
  background: #f1f5f9;
  border-radius: 4px;
  padding: 0 5px;
  line-height: 1.6;
}

.meal-struct-row[data-kind='staple'] .meal-struct-lab {
  color: #b45309;
  background: #fffbeb;
}

.meal-struct-row[data-kind='protein'] .meal-struct-lab {
  color: #1d4ed8;
  background: #eff6ff;
}

.meal-struct-row[data-kind='veg'] .meal-struct-lab {
  color: #047857;
  background: #ecfdf5;
}

.meal-struct-row[data-kind='soup'] .meal-struct-lab {
  color: #6d28d9;
  background: #f5f3ff;
}

.meal-struct-row[data-kind='tip'] .meal-struct-lab {
  color: #0f766e;
  background: #ccfbf1;
}

.meal-struct-row[data-kind='fruit'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='fruit'] .meal-struct-lab {
  color: #9d174d;
  background: #fce7f3;
}

.meal-struct-row[data-kind='nut'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='nut'] .meal-struct-lab {
  color: #7c3aed;
  background: #f3e8ff;
}

.meal-struct-row[data-kind='dairy'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='dairy'] .meal-struct-lab {
  color: #c026d3;
  background: #fae8ff;
}

.meal-struct-row[data-kind='snack'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='snack'] .meal-struct-lab,
.meal-struct-row.snackish[data-kind='protein'] .meal-struct-lab {
  color: #a21caf;
  background: #fdf4ff;
}

.meal-struct-row[data-kind='other'] .meal-struct-lab {
  color: #475569;
  background: #f1f5f9;
}

.meal-struct-txt {
  color: #1e293b;
  word-break: break-word;
}

.meal-struct-row.hit .meal-struct-txt {
  color: #b91c1c;
}

.meal-struct-row.warn {
  background: #fff7ed;
  border-radius: 4px;
  padding: 1px 2px;
}

.meal-struct-row.hit {
  background: #fef2f2;
  border-radius: 4px;
  padding: 1px 2px;
}

.meal-flag {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 800;
  line-height: 1;
}

.meal-flag--bad {
  color: #dc2626;
}

.meal-flag--warn {
  color: #d97706;
}

.meal-struct-empty {
  font-size: 12.5px;
  color: #94a3b8;
}
</style>
