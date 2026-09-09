<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { formatDiseaseName, formatGender } from '../shared/archive-labels'
import { loadDiseaseDict, toLabelMap } from '../shared/dict'

const router = useRouter()
const loading = ref(true)
const summary = ref<any>(null)
const diseaseLabels = ref<Record<string, string>>({})

onMounted(async () => {
  try {
    const [res, diseases] = await Promise.all([
      api<{ data: any }>('/api/c/v1/me/archive/summary'),
      loadDiseaseDict().catch(() => []),
    ])
    summary.value = res.data
    diseaseLabels.value = toLabelMap(diseases)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
})

</script>

<template>
  <div class="page">
    <van-nav-bar title="健康档案" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" vertical style="padding: 40px 0">加载中</van-loading>
    <template v-else-if="summary">
      <section class="card">
        <h2>{{ summary.displayName || '就诊人' }}</h2>
        <p class="meta">
          {{ formatGender(summary.gender) }}
          ·
          {{ summary.birthday || '生日未填' }}
          ·
          {{ summary.identityMask || '证件未填' }}
        </p>
      </section>

      <section class="card">
        <h3>基础档案</h3>
        <p class="hint">由健管师维护，可在此查看；生活方式可自行更新。</p>
        <van-cell
          title="健康信息"
          is-link
          @click="router.push('/archive/basic')"
        />
        <van-cell title="生活方式" is-link to="/archive/lifestyle" />
      </section>

      <section class="card">
        <h3>病种档案</h3>
        <van-empty
          v-if="!summary.diseases?.length"
          description="暂无病种档案"
          image-size="64"
        />
        <van-cell
          v-for="d in summary.diseases || []"
          :key="d.diseaseCode"
          :title="formatDiseaseName(d.diseaseCode, diseaseLabels[d.diseaseCode])"
          is-link
          @click="router.push(`/archive/disease/${d.diseaseCode}`)"
        />
      </section>
    </template>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--hx-bg); padding-bottom: 24px; }
.card {
  margin: 12px 16px; padding: 16px; border-radius: 16px; background: #fff;
  box-shadow: var(--hx-shadow);
}
h2 { margin: 0 0 6px; font-size: 20px; }
h3 { margin: 0 0 8px; font-size: 15px; }
.meta, .hint { margin: 0; color: var(--hx-muted); font-size: 13px; line-height: 1.5; }
.hint { margin-bottom: 8px; }
</style>
