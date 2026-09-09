<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  formatFamilyHistory,
  formatLifestyleRows,
  formatPastHistory,
  formatPresentIllness,
} from '../shared/archive-labels'
import { loadDictOptions, toLabelMap } from '../shared/dict'

const router = useRouter()
const loading = ref(true)
const content = ref<Record<string, unknown>>({})
const illnessLabels = ref<Record<string, string>>({})
const lifestyleLabels = ref<Record<string, Record<string, string>>>({})

onMounted(async () => {
  try {
    const [
      res,
      illness,
      smoking,
      drinking,
      drinkingFrequency,
      appetiteLevel,
      dietHabit,
      dietType,
      exerciseFrequency,
      exerciseIntensity,
      sleepQuality,
      sleepDisorder,
    ] = await Promise.all([
      api<{ data: { contentJson?: Record<string, unknown> } }>(
        '/api/c/v1/me/archive/basic',
      ),
      loadDictOptions('presentIllness').catch(() => []),
      loadDictOptions('smoking').catch(() => []),
      loadDictOptions('drinking').catch(() => []),
      loadDictOptions('drinkingFrequency').catch(() => []),
      loadDictOptions('appetiteLevel').catch(() => []),
      loadDictOptions('dietHabit').catch(() => []),
      loadDictOptions('dietType').catch(() => []),
      loadDictOptions('exerciseFrequency').catch(() => []),
      loadDictOptions('exerciseIntensity').catch(() => []),
      loadDictOptions('sleepQuality').catch(() => []),
      loadDictOptions('sleepDisorder').catch(() => []),
    ])
    content.value = (res.data.contentJson as Record<string, unknown>) || {}
    illnessLabels.value = toLabelMap(illness)
    lifestyleLabels.value = {
      smoking: toLabelMap(smoking),
      drinking: toLabelMap(drinking),
      drinkingFrequency: toLabelMap(drinkingFrequency),
      appetite: toLabelMap(appetiteLevel),
      dietHabit: toLabelMap(dietHabit),
      dietType: toLabelMap(dietType),
      exerciseFrequency: toLabelMap(exerciseFrequency),
      exerciseIntensity: toLabelMap(exerciseIntensity),
      sleepQuality: toLabelMap(sleepQuality),
      sleepDisorder: toLabelMap(sleepDisorder),
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

const healthRows = computed(() => [
  { label: '现有疾病', value: formatPresentIllness(content.value, illnessLabels.value) },
  { label: '家族史', value: formatFamilyHistory(content.value) },
  { label: '既往史', value: formatPastHistory(content.value) },
])

const lifestyleRows = computed(() => formatLifestyleRows(content.value, lifestyleLabels.value))
</script>

<template>
  <div class="page">
    <van-nav-bar title="基础档案" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" vertical style="padding: 40px 0">加载中</van-loading>
    <template v-else>
      <p class="hint">只读展示，内容由健管师维护。生活方式可自行更新。</p>

      <section class="card">
        <div class="card-head">
          <h3>健康信息</h3>
        </div>
        <div v-for="row in healthRows" :key="row.label" class="row">
          <div class="label">{{ row.label }}</div>
          <div class="value">{{ row.value }}</div>
        </div>
      </section>

      <section class="card">
        <div class="card-head">
          <h3>生活方式</h3>
          <button class="link" type="button" @click="router.push('/archive/lifestyle')">去更新</button>
        </div>
        <div v-for="row in lifestyleRows" :key="row.label" class="row">
          <div class="label">{{ row.label }}</div>
          <div class="value">{{ row.value }}</div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--hx-bg); padding-bottom: 24px; }
.hint {
  margin: 12px 16px 0; color: var(--hx-muted); font-size: 13px; line-height: 1.5;
}
.card {
  margin: 12px 16px; padding: 16px; border-radius: 16px; background: #fff;
  box-shadow: var(--hx-shadow);
}
.card-head {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 12px;
}
h3 { margin: 0; font-size: 15px; }
.link {
  border: 0; background: transparent; color: var(--hx-primary, #0d9488);
  font-size: 13px; padding: 0;
}
.row + .row { margin-top: 14px; padding-top: 14px; border-top: 1px solid #f0f3f3; }
.label { color: var(--hx-muted); font-size: 12px; margin-bottom: 4px; }
.value { font-size: 14px; line-height: 1.55; word-break: break-word; white-space: pre-wrap; }
</style>
