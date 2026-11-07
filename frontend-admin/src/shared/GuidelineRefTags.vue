<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { guidelineIndex, loadGuidelineLibrary, type GuidelineLibraryView } from './guidelines'

const props = defineProps<{ refs: string[] }>()

const lib = ref<GuidelineLibraryView | null>(null)
const expanded = ref(false)
const index = computed(() => guidelineIndex(lib.value))
const items = computed(() =>
  props.refs.map((code) => ({ code, rec: index.value.get(code) ?? null })),
)
const summaryLabel = computed(() => {
  const n = props.refs.length
  if (n <= 0) return ''
  return expanded.value ? '收起依据' : `依据了 ${n} 部权威指南`
})

watch(
  () => props.refs.length > 0,
  async (has) => {
    if (!has || lib.value) return
    try {
      lib.value = await loadGuidelineLibrary()
    } catch {
      // 标签只是辅助信息，加载失败时退化为显示编码
    }
  },
  { immediate: true },
)

watch(
  () => props.refs.join('|'),
  () => {
    expanded.value = false
  },
)
</script>

<template>
  <div v-if="refs.length" class="ref-tags">
    <button type="button" class="ref-summary" @click="expanded = !expanded">
      {{ summaryLabel }}
    </button>
    <template v-if="expanded">
      <el-popover v-for="it in items" :key="it.code" placement="top" :width="320" trigger="hover">
        <template #reference>
          <el-tag size="small" :type="it.rec?.kind === 'SAFETY' ? 'danger' : 'info'" effect="plain" class="ref-tag">
            {{ it.rec?.sourceLabel ?? it.code }}
          </el-tag>
        </template>
        <div v-if="it.rec" class="ref-pop">
          <div class="ref-text">{{ it.rec.staffText }}</div>
          <div v-if="it.rec.constraint" class="ref-meta">校验：{{ it.rec.constraint }}</div>
          <div class="ref-meta">{{ it.rec.sourceLabel }} · {{ it.code }}</div>
        </div>
        <div v-else class="ref-meta">{{ it.code }}（条目未上线或已下线）</div>
      </el-popover>
    </template>
  </div>
</template>

<style scoped>
.ref-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
}
.ref-summary {
  border: 1px solid #dbe3f0;
  border-radius: 999px;
  padding: 2px 10px;
  background: #f8fafc;
  color: #475569;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  line-height: 1.6;
}
.ref-summary:hover {
  background: #eff6ff;
  border-color: #bfdbfe;
  color: #1d4ed8;
}
.ref-tag {
  cursor: default;
}
.ref-text {
  font-size: 13px;
  line-height: 1.6;
}
.ref-meta {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
