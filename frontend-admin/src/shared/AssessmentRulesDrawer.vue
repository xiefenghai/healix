<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from './http'
import { type AssessmentRuleSheet, toneTagType } from './assessment'

const props = defineProps<{
  modelValue: boolean
  /** 打开时默认选中的引擎 */
  engineCode?: string
}>()

const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const open = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const sheets = ref<AssessmentRuleSheet[]>([])
const active = ref('')
const loading = ref(false)

async function load() {
  if (sheets.value.length) return
  loading.value = true
  try {
    const res = await api<{ data: AssessmentRuleSheet[] }>('/api/b/v1/assessment-rules')
    sheets.value = res.data
  } catch (e) {
    ElMessage.error((e as Error).message || '加载规则失败')
  } finally {
    loading.value = false
  }
}

watch(
  () => props.modelValue,
  async (v) => {
    if (!v) return
    await load()
    active.value = props.engineCode || sheets.value[0]?.engineCode || ''
  },
)

const grouped = computed(() => [
  { title: '患病风险评估', items: sheets.value.filter((s) => s.category === 'RISK') },
  { title: '疾病严重等级分层', items: sheets.value.filter((s) => s.category === 'SEVERITY') },
])

const current = computed(() => sheets.value.find((s) => s.engineCode === active.value) || null)
</script>

<template>
  <el-drawer v-model="open" title="评估规则说明" size="720px">
    <div v-loading="loading" class="rules">
      <aside class="nav">
        <div v-for="g in grouped" :key="g.title" class="nav-group">
          <div class="nav-title">{{ g.title }}</div>
          <button
            v-for="s in g.items"
            :key="s.engineCode"
            type="button"
            class="nav-item"
            :class="{ active: s.engineCode === active }"
            @click="active = s.engineCode"
          >
            {{ s.engineLabel }}
          </button>
        </div>
      </aside>
      <article v-if="current" class="body">
        <h3>{{ current.engineLabel }}</h3>
        <div class="meta">
          <span v-if="current.guidelineName">依据：{{ current.guidelineName }}</span>
          <span>规则包 {{ current.rulePackVersion }}</span>
        </div>
        <el-alert
          v-if="current.provisional"
          type="warning"
          :closable="false"
          show-icon
          title="暂定口径，待医学团队校准"
          class="gap"
        />
        <p v-if="current.sheet.summary" class="summary">{{ current.sheet.summary }}</p>
        <div class="levels">
          <el-tag v-for="l in current.levels" :key="l.code" :type="toneTagType(l.tone)" effect="light">
            {{ l.label }}<template v-if="l.tierLabel"> · {{ l.tierLabel }}</template>
          </el-tag>
        </div>
        <section v-for="sec in current.sheet.sections" :key="sec.title" class="sec">
          <h4>{{ sec.title }}</h4>
          <table v-if="sec.headers?.length" class="tbl">
            <thead>
              <tr>
                <th v-for="h in sec.headers" :key="h">{{ h }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, i) in sec.rows || []" :key="i">
                <td v-for="(cell, j) in row" :key="j">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
          <ul v-if="sec.bullets?.length">
            <li v-for="b in sec.bullets" :key="b">{{ b }}</li>
          </ul>
        </section>
        <ul v-if="current.sheet.notes?.length" class="notes">
          <li v-for="n in current.sheet.notes" :key="n">{{ n }}</li>
        </ul>
      </article>
    </div>
  </el-drawer>
</template>

<style scoped>
.rules {
  display: flex;
  gap: 16px;
  min-height: 300px;
}
.nav {
  width: 180px;
  flex-shrink: 0;
}
.nav-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 4px 0 6px;
}
.nav-group + .nav-group {
  margin-top: 14px;
}
.nav-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 7px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  font-size: 13px;
  cursor: pointer;
}
.nav-item.active {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.body {
  flex: 1;
  min-width: 0;
}
.body h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  gap: 12px;
  margin-bottom: 10px;
}
.gap {
  margin-bottom: 10px;
}
.summary {
  font-size: 13px;
  line-height: 1.7;
  margin: 0 0 10px;
}
.levels {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.sec h4 {
  font-size: 13px;
  margin: 14px 0 6px;
}
.sec ul,
.notes {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  line-height: 1.8;
}
.notes {
  margin-top: 14px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.tbl th,
.tbl td {
  border: 1px solid var(--el-border-color-lighter);
  padding: 6px 8px;
  text-align: left;
}
.tbl th {
  background: #fafafa;
  font-weight: 600;
}
</style>
