<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../shared/http'
import { asEnergyTarget, energyMacroText } from '../shared/energy-target'

interface CarePlanBundle {
  plan?: { title?: string; goalSummary?: string } | null
  draft?: {
    exercise?: { goal?: string; precautions?: string[] }
    diet?: { principles?: string[]; calorieHint?: string; energyTarget?: unknown }
    execution?: { tasks?: Array<{ title?: string; enabled?: boolean }> }
    contextSnapshot?: { summary?: string }
  } | null
  activeVersion?: {
    id: string
    title?: string
    versionLabel?: string
    publishedAt?: string
    signStatus?: string
    exercise?: { goal?: string; precautions?: string[] }
    diet?: { principles?: string[]; calorieHint?: string; energyTarget?: unknown }
    execution?: { tasks?: Array<{ title?: string; enabled?: boolean }> }
    contextSnapshot?: { summary?: string }
  } | null
}

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const loading = ref(false)
const bundle = ref<CarePlanBundle | null>(null)

const src = computed(() => bundle.value?.activeVersion || bundle.value?.draft || null)
const isDraftOnly = computed(() => !bundle.value?.activeVersion && !!bundle.value?.draft)
const title = computed(
  () => bundle.value?.activeVersion?.title || bundle.value?.plan?.title || '健康管理方案',
)
const goal = computed(
  () =>
    bundle.value?.plan?.goalSummary ||
    src.value?.exercise?.goal ||
    '',
)
const summary = computed(() => {
  const raw = src.value?.contextSnapshot?.summary
  return typeof raw === 'string' ? raw.trim() : ''
})
const principles = computed(() =>
  Array.isArray(src.value?.diet?.principles)
    ? src.value!.diet!.principles!.filter(Boolean).slice(0, 6)
    : [],
)
const energy = computed(() => asEnergyTarget(src.value?.diet?.energyTarget))
const tasks = computed(() =>
  (src.value?.execution?.tasks || []).filter((t) => t.enabled !== false).slice(0, 12),
)

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: CarePlanBundle }>(`/api/b/v1/patients/${peopleId.value}/care-plan`)
    bundle.value = res.data
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
    <van-nav-bar title="管理方案" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <template v-else-if="src">
      <section class="hero hx-surface">
        <div class="chips">
          <span class="hx-chip" :class="{ warn: isDraftOnly }">
            {{ isDraftOnly ? '草稿' : '已生效' }}
          </span>
          <span v-if="bundle?.activeVersion?.versionLabel" class="hx-chip">
            {{ bundle.activeVersion.versionLabel }}
          </span>
          <span
            v-if="bundle?.activeVersion?.signStatus === 'SIGNED'"
            class="hx-chip"
          >
            已签署
          </span>
        </div>
        <h2>{{ title }}</h2>
        <p v-if="bundle?.activeVersion?.publishedAt" class="hx-muted">
          发布于 {{ String(bundle.activeVersion.publishedAt).replace('T', ' ').slice(0, 16) }}
        </p>
      </section>
      <section v-if="goal" class="block hx-surface">
        <h3>管理目标</h3>
        <p>{{ goal }}</p>
      </section>
      <section v-if="summary" class="block hx-surface">
        <h3>方案摘要</h3>
        <p>{{ summary }}</p>
      </section>
      <section v-if="principles.length" class="block hx-surface">
        <h3>饮食要点</h3>
        <p v-for="(p, i) in principles" :key="i">· {{ p }}</p>
      </section>
      <section v-if="energy || src?.diet?.calorieHint" class="block hx-surface">
        <h3>热量</h3>
        <p v-if="energy">每日 {{ energy.energyKcal }} 千卡 · {{ energyMacroText(energy) }}</p>
        <p v-if="src?.diet?.calorieHint">{{ src.diet.calorieHint }}</p>
      </section>
      <section v-if="tasks.length" class="block hx-surface">
        <h3>执行任务</h3>
        <p v-for="(t, i) in tasks" :key="i">· {{ t.title || '任务' }}</p>
      </section>
      <p class="hint hx-muted">手机端只读查看；复杂编排请用电脑端</p>
    </template>
    <van-empty v-else description="暂无方案" />
  </div>
</template>

<style scoped>
.hero,
.block {
  margin: 0 16px 12px;
  padding: 14px 16px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.hero h2 {
  margin: 0 0 6px;
  font-size: 18px;
  color: var(--hx-ink);
}
.hero p,
.block p,
.hint {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
}
.block h3 {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--hx-teal-deep);
}
.block p + p {
  margin-top: 6px;
}
.hint {
  padding: 0 20px 20px;
}
</style>
