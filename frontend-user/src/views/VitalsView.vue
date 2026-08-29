<script setup lang="ts">
import { ref } from 'vue'
import { showToast } from 'vant'
import { api } from '../api/http'

const metricType = ref('BLOOD_GLUCOSE')
const value = ref('')
const unit = ref('mmol/L')
const records = ref<any[]>([])
const loading = ref(false)

async function record() {
  loading.value = true
  try {
    await api('/api/c/v1/vitals', {
      method: 'POST',
      body: JSON.stringify({
        metricType: metricType.value,
        value: Number(value.value),
        unit: unit.value,
      }),
    })
    showToast({ type: 'success', message: '已记录' })
    value.value = ''
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '录入失败')
  } finally {
    loading.value = false
  }
}

async function load() {
  try {
    const to = new Date().toISOString()
    const from = new Date(Date.now() - 7 * 24 * 3600 * 1000).toISOString()
    const res = await api<{ data: any[] }>(
      `/api/c/v1/vitals?metricType=${encodeURIComponent(metricType.value)}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
    )
    records.value = res.data ?? []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '查询失败')
  }
}

function onMetricChange() {
  unit.value = metricType.value === 'STEPS' ? '步' : metricType.value === 'WEIGHT' ? 'kg' : 'mmol/L'
}
</script>

<template>
  <div class="page">
    <header>
      <h1>健康数据</h1>
      <p>温柔追踪，不制造焦虑</p>
    </header>

    <section class="card">
      <h2>记录体征</h2>
      <van-field name="metric" label="指标">
        <template #input>
          <select v-model="metricType" class="select" @change="onMetricChange">
            <option value="BLOOD_GLUCOSE">血糖</option>
            <option value="STEPS">步数</option>
            <option value="WEIGHT">体重</option>
            <option value="HEART_RATE">心率</option>
          </select>
        </template>
      </van-field>
      <van-field v-model="value" type="number" label="数值" placeholder="输入数值" />
      <van-field v-model="unit" label="单位" />
      <div class="actions">
        <van-button round type="primary" block :loading="loading" @click="record">保存记录</van-button>
        <van-button round plain type="primary" block @click="load">近 7 天</van-button>
      </div>
    </section>

    <section class="card">
      <h2>最近记录</h2>
      <van-empty v-if="!records.length" description="还没有记录，先轻松记一条吧" />
      <van-cell-group v-else inset>
        <van-cell
          v-for="item in records"
          :key="item.id"
          :title="`${item.value} ${item.unit || ''}`"
          :label="item.recordedAt"
          :value="item.metricType"
        />
      </van-cell-group>
    </section>
  </div>
</template>

<style scoped>
.page { padding: 18px 16px 24px; }
header h1 { margin: 0; font-size: 20px; font-weight: 700; }
header p { margin: 6px 0 16px; color: var(--hx-muted); font-size: 13px; }
.card {
  background: #fff;
  border-radius: 18px;
  padding: 14px 12px 16px;
  margin-bottom: 14px;
  box-shadow: var(--hx-shadow);
}
h2 { margin: 0 8px 8px; font-size: 16px; }
.select {
  width: 100%;
  border: 0;
  background: transparent;
  color: var(--hx-text);
}
.actions {
  display: grid;
  gap: 8px;
  padding: 8px 8px 0;
}
:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border-color: #2b9e9e;
}
:deep(.van-button--plain.van-button--primary) {
  background: #fff;
  color: var(--hx-teal);
}
</style>
