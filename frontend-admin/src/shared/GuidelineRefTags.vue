<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { guidelineIndex, loadGuidelineLibrary, type GuidelineLibraryView } from './guidelines'

const props = defineProps<{ refs: string[] }>()

const lib = ref<GuidelineLibraryView | null>(null)
const index = computed(() => guidelineIndex(lib.value))
const items = computed(() =>
  props.refs.map((code) => ({ code, rec: index.value.get(code) ?? null })),
)

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
</script>

<template>
  <div v-if="refs.length" class="ref-tags">
    <span class="ref-label">依据</span>
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
.ref-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-right: 2px;
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
