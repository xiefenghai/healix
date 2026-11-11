<script setup lang="ts">
import { computed, ref } from 'vue'
import {
  searchObservationCatalog,
  type ObservationSearchHit,
} from './observation-search'

const props = withDefaults(
  defineProps<{
    placeholder?: string
    disabled?: boolean
  }>(),
  {
    placeholder: '🔍 搜索全院指标、检验或检查项目...',
    disabled: false,
  },
)

const emit = defineEmits<{
  select: [hit: ObservationSearchHit]
}>()

const keyword = ref('')
const popoverVisible = ref(false)

const results = computed(() => searchObservationCatalog(keyword.value, 14))

function onInput() {
  popoverVisible.value = !!keyword.value.trim()
}

function onSelect(hit: ObservationSearchHit) {
  emit('select', hit)
  keyword.value = ''
  popoverVisible.value = false
}

function onBlur() {
  // 稍延迟，允许点击结果
  window.setTimeout(() => {
    popoverVisible.value = false
  }, 160)
}

function kindTag(kind: ObservationSearchHit['kind']) {
  if (kind === 'metric') return '指标'
  if (kind === 'lab') return '化验'
  return '检查'
}
</script>

<template>
  <div class="obs-global-search" :class="{ disabled: props.disabled }">
    <el-popover
      :visible="popoverVisible && results.length > 0"
      placement="bottom-start"
      :width="420"
      trigger="manual"
      popper-class="obs-global-search-popper"
    >
      <template #reference>
        <el-input
          v-model="keyword"
          clearable
          :disabled="props.disabled"
          :placeholder="props.placeholder"
          class="obs-search-input"
          @input="onInput"
          @focus="onInput"
          @blur="onBlur"
          @keydown.enter.prevent="results[0] && onSelect(results[0])"
        />
      </template>

      <ul class="obs-search-list" role="listbox">
        <li
          v-for="hit in results"
          :key="hit.id"
          class="obs-search-item"
          role="option"
          @mousedown.prevent="onSelect(hit)"
        >
          <span class="obs-search-kind" :data-kind="hit.kind">{{ kindTag(hit.kind) }}</span>
          <span class="obs-search-main">
            <span class="obs-search-label">{{ hit.label }}</span>
            <span class="obs-search-meta">
              {{ hit.groupLabel }}
              <template v-if="hit.panelLabel"> · {{ hit.panelLabel }}</template>
            </span>
          </span>
          <span class="obs-search-code">{{ hit.code }}</span>
        </li>
      </ul>
    </el-popover>

    <p v-if="popoverVisible && keyword.trim() && !results.length" class="obs-search-empty">
      未找到匹配项目
    </p>
  </div>
</template>

<style scoped>
.obs-global-search {
  position: relative;
  flex: 1 1 auto;
  min-width: 220px;
  max-width: 460px;
}

.obs-global-search.disabled {
  opacity: 0.55;
  pointer-events: none;
}

.obs-search-input :deep(.el-input__wrapper) {
  border-radius: 999px;
  background: #f8fafc;
  box-shadow: 0 0 0 1px #e2e8f0 inset;
}

.obs-search-input :deep(.el-input__wrapper:hover),
.obs-search-input :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--brand-400, #60a5fa) inset;
}

.obs-search-input :deep(.el-input__inner) {
  font-size: 13px;
}

.obs-search-empty {
  position: absolute;
  left: 0;
  right: 0;
  top: calc(100% + 6px);
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #e2e8f0;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  color: #94a3b8;
  font-size: 12px;
  z-index: 20;
}

.obs-search-list {
  list-style: none;
  margin: 0;
  padding: 4px;
  max-height: 360px;
  overflow: auto;
}

.obs-search-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
}

.obs-search-item:hover {
  background: #f1f5f9;
}

.obs-search-kind {
  flex: 0 0 auto;
  min-width: 36px;
  text-align: center;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  padding: 3px 6px;
  border-radius: 999px;
  color: #475569;
  background: #e2e8f0;
}

.obs-search-kind[data-kind='metric'] {
  color: #1d4ed8;
  background: #dbeafe;
}

.obs-search-kind[data-kind='lab'] {
  color: #0f766e;
  background: #ccfbf1;
}

.obs-search-kind[data-kind='exam'] {
  color: #a16207;
  background: #fef3c7;
}

.obs-search-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.obs-search-label {
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
}

.obs-search-meta {
  font-size: 11px;
  color: #94a3b8;
}

.obs-search-code {
  flex: 0 0 auto;
  font-size: 11px;
  color: #94a3b8;
  font-variant-numeric: tabular-nums;
}
</style>
