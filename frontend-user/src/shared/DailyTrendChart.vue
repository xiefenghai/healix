<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { axisLabel, captionDate, type TrendBar } from './daily-trend'

const props = withDefaults(
  defineProps<{
    bars: TrendBar[]
    /** 主题色，如 var(--hx-water) */
    color: string
    /** 纵轴上限；不传按最大值与目标取 */
    max?: number
    /** 目标线（同 value 单位） */
    target?: number | null
    targetLabel?: string
    height?: number
  }>(),
  { max: undefined, target: null, targetLabel: '目标', height: 72 },
)

const selected = ref<string | null>(null)

watch(
  () => props.bars.map((b) => b.date).join(),
  () => {
    selected.value = null
  },
)

const top = computed(() => {
  if (props.max) return props.max
  return Math.max(1, props.target || 0, ...props.bars.map((b) => b.value)) * 1.08
})

const targetPct = computed(() => (props.target ? Math.min(100, (props.target / top.value) * 100) : null))

const caption = computed(() => {
  const b = props.bars.find((x) => x.date === selected.value)
  if (!b) return ''
  return `${captionDate(b.date)} · ${b.display || (b.tone === 'future' ? '还没到' : '没有记录')}`
})

function pct(b: TrendBar) {
  return b.value > 0 ? Math.max(6, (b.value / top.value) * 100) : 0
}

function toggle(date: string) {
  selected.value = selected.value === date ? null : date
}
</script>

<template>
  <div class="dtc" :class="{ 'has-target': targetPct != null }" :style="{ '--c': color }">
    <div class="plot" :style="{ height: height + 'px' }">
      <div v-if="targetPct != null" class="target" :style="{ bottom: targetPct + '%' }">
        <span>{{ targetLabel }}</span>
      </div>
      <button
        v-for="b in bars"
        :key="b.date"
        type="button"
        class="col"
        :class="{ sel: selected === b.date }"
        :aria-label="`${captionDate(b.date)} ${b.display || '没有记录'}`"
        @click="toggle(b.date)"
      >
        <i :class="[b.tone, { empty: !b.value }]" :style="{ height: pct(b) + '%' }" />
      </button>
    </div>
    <div class="axis">
      <span v-for="b in bars" :key="b.date" :class="{ sel: selected === b.date }">{{ axisLabel(b.date, bars.length) }}</span>
    </div>
    <p class="caption" :class="{ show: !!caption }">{{ caption || '点柱子看当天数值' }}</p>
  </div>
</template>

<style scoped>
.dtc.has-target {
  padding-top: 12px;
}

.plot {
  position: relative;
  display: flex;
  align-items: flex-end;
  gap: 4px;
  border-bottom: 1px solid var(--hx-n-200);
}

.target {
  position: absolute;
  left: 0;
  right: 0;
  border-top: 1px dashed var(--c);
  opacity: 0.6;
  pointer-events: none;
}

.target span {
  position: absolute;
  right: 0;
  top: -15px;
  font-size: 10px;
  font-weight: 600;
  color: var(--c);
}

.col {
  flex: 1;
  height: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 0;
  border: 0;
  background: transparent;
  border-radius: 6px 6px 0 0;
}

.col.sel {
  background: rgba(19, 34, 46, 0.05);
}

.col i {
  display: block;
  width: 100%;
  max-width: 22px;
  border-radius: 5px 5px 0 0;
  background: var(--hx-n-300);
}

.col i.empty {
  height: 3px !important;
  border-radius: 2px;
  background: var(--hx-n-200);
}

.col i.soft {
  background: var(--c);
  opacity: 0.4;
}

.col i.on {
  background: var(--c);
}

.col i.warn {
  background: var(--hx-red);
  opacity: 0.8;
}

.col i.future {
  background: transparent;
}

.axis {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}

.axis span {
  flex: 1;
  text-align: center;
  font-size: 10px;
  color: var(--hx-n-500);
}

.axis span.sel {
  color: var(--c);
  font-weight: 800;
}

.caption {
  margin: 6px 0 0;
  min-height: 16px;
  font-size: 11px;
  color: var(--hx-n-400);
}

.caption.show {
  color: var(--hx-text);
  font-weight: 700;
}
</style>
