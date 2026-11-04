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
const allergyText = ref('')
const pendingRequest = ref(false)

onMounted(async () => {
  try {
    const [res, diseases, allergy] = await Promise.all([
      api<{ data: any }>('/api/c/v1/me/archive/summary'),
      loadDiseaseDict().catch(() => []),
      api<{ data: { status: string; bannerLabels: string[] } }>('/api/c/v1/me/allergies').catch(() => null),
    ])
    api<{ data: { id: string } | null }>('/api/c/v1/me/assessments/questionnaire-request')
      .then((r) => (pendingRequest.value = !!r.data))
      .catch(() => (pendingRequest.value = false))
    summary.value = res.data
    diseaseLabels.value = toLabelMap(diseases)
    if (allergy) {
      const a = allergy.data
      allergyText.value =
        a.status === 'HAS' ? a.bannerLabels.join('、') : a.status === 'NKA' ? '无过敏' : '未填写'
    }
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
        <p class="hint">由健管师维护，可在此查看；生活方式和过敏史可自行更新。</p>
        <van-cell
          title="健康信息"
          is-link
          @click="router.push('/archive/basic')"
        />
        <van-cell title="生活方式" is-link to="/archive/lifestyle" />
        <van-cell title="过敏史" is-link :value="allergyText" to="/archive/allergies" />
        <van-cell
          title="完善健康信息"
          label="补充家族史、既往病史等，帮助健管师更了解你"
          is-link
          :value="pendingRequest ? '待填写' : ''"
          to="/assessment-questionnaire"
        />
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
  margin: 12px 16px; padding: 16px; border-radius: var(--hx-r-lg); background: #fff;
  box-shadow: var(--hx-elev-2);
}
h2 { margin: 0 0 6px; font-size: 20px; }
h3 { margin: 0 0 8px; font-size: 15px; }
.meta, .hint { margin: 0; color: var(--hx-muted); font-size: 13px; line-height: 1.5; }
.hint { margin-bottom: 8px; }
</style>
