<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, apiUpload } from '../api/http'
import { compressImage } from '../shared/image'
import { MED_DOSE_UNIT_LABELS, MED_FREQUENCY_LABELS } from '../shared/medication-labels'

interface OcrItem {
  drugName: string
  usageMethod?: string | null
  frequency?: string | null
  doseAmount?: string | null
  doseUnit?: string | null
  timingNote?: string | null
  courseDays?: number | null
  startDate?: string | null
}

interface Line {
  key: number
  drugName: string
  usageMethod: string
  frequency: string
  doseAmount: string
  doseUnit: string
  timingNote: string
  courseDays: number | null
}

const MAX_ITEMS = 10
const FREQS = ['QD', 'BID', 'TID', 'QN', 'QOD', 'PRN']
const UNITS = ['TABLET', 'CAPSULE', 'MG', 'G', 'ML', 'BAG']

const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const preview = ref('')
const recognizing = ref(false)
const saving = ref(false)
const warnings = ref<string[]>([])
const lines = ref<Line[]>([])
let seq = 0

function pick() {
  fileInput.value?.click()
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (preview.value) URL.revokeObjectURL(preview.value)
  preview.value = URL.createObjectURL(file)
  recognizing.value = true
  try {
    const res = await apiUpload<{ data: { items: OcrItem[]; warnings: string[] } }>(
      '/api/c/v1/me/medications/ocr',
      await compressImage(file),
    )
    warnings.value = res.data.warnings || []
    const next = (res.data.items || []).map(
      (it): Line => ({
        key: ++seq,
        drugName: it.drugName,
        usageMethod: it.usageMethod || 'ORAL',
        frequency: it.frequency || 'QD',
        doseAmount: it.doseAmount || '',
        doseUnit: it.doseUnit || 'TABLET',
        timingNote: it.timingNote || '',
        courseDays: it.courseDays ?? null,
      }),
    )
    lines.value = [...lines.value, ...next].slice(0, MAX_ITEMS)
    if (!next.length) showToast(warnings.value[0] || '没有识别到药品')
  } catch (err) {
    showToast(err instanceof Error ? err.message : '识别失败')
  } finally {
    recognizing.value = false
  }
}

function addBlank() {
  if (lines.value.length >= MAX_ITEMS) return showToast(`一次最多 ${MAX_ITEMS} 种`)
  lines.value.push({
    key: ++seq,
    drugName: '',
    usageMethod: 'ORAL',
    frequency: 'QD',
    doseAmount: '',
    doseUnit: 'TABLET',
    timingNote: '',
    courseDays: null,
  })
}

function removeLine(key: number) {
  lines.value = lines.value.filter((l) => l.key !== key)
}

async function save() {
  const items = lines.value.filter((l) => l.drugName.trim())
  if (!items.length) return showToast('请至少保留一种药')
  const checks = await Promise.all(
    items.map((l) =>
      api<{ data: string[] }>('/api/c/v1/me/medications/allergy-check', {
        method: 'POST',
        body: JSON.stringify({ drugName: l.drugName.trim() }),
      })
        .then((r) => ({ name: l.drugName.trim(), hits: r.data || [] }))
        .catch(() => ({ name: l.drugName.trim(), hits: [] as string[] })),
    ),
  )
  const conflicts = checks.filter((c) => c.hits.length)
  if (conflicts.length) {
    try {
      await showConfirmDialog({
        title: '可能与过敏史冲突',
        message:
          conflicts.map((c) => `${c.name}：档案记录对【${c.hits.join('、')}】过敏`).join('\n') +
          '\n保存后会同时通知健管师，建议先咨询再服用。',
        confirmButtonText: '仍要保存',
        cancelButtonText: '再想想',
      })
    } catch {
      return
    }
  }
  saving.value = true
  try {
    await api('/api/c/v1/me/medications/batch', {
      method: 'POST',
      body: JSON.stringify({
        items: items.map((l) => ({
          drugName: l.drugName.trim(),
          usageMethod: l.usageMethod,
          frequency: l.frequency,
          doseAmount: l.doseAmount.trim() || null,
          doseUnit: l.doseUnit,
          timingNote: l.timingNote.trim() || null,
          courseDays: l.courseDays,
          status: 'ACTIVE',
        })),
      }),
    })
    showToast({ type: 'success', message: `已添加 ${items.length} 种药，健管师会帮你核对` })
    void router.replace('/medications?tab=box')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="拍药盒添加" left-arrow fixed placeholder :border="false" @click-left="router.back()" />

    <section class="card shot" @click="pick">
      <img v-if="preview" :src="preview" alt="" />
      <div v-else class="shot-empty">
        <van-icon name="photograph" />
        <strong>拍药盒、处方或用药清单</strong>
        <span>识别后逐条确认，不会直接保存</span>
      </div>
      <input ref="fileInput" type="file" accept="image/*" hidden @change="onFile" />
    </section>

    <div v-if="recognizing" class="recognizing"><van-loading size="18px">正在识别…</van-loading></div>

    <section v-if="warnings.length" class="card warns">
      <p v-for="w in warnings" :key="w">{{ w }}</p>
    </section>

    <section v-for="l in lines" :key="l.key" class="card line">
      <div class="line-head">
        <van-field v-model="l.drugName" placeholder="药品名称" maxlength="64" class="name" />
        <van-icon name="cross" class="del" @click="removeLine(l.key)" />
      </div>
      <div class="chips">
        <button
          v-for="f in FREQS"
          :key="f"
          type="button"
          class="chip sm"
          :class="{ active: l.frequency === f }"
          @click="l.frequency = f"
        >
          {{ MED_FREQUENCY_LABELS[f] }}
        </button>
      </div>
      <div class="dose">
        <van-field v-model="l.doseAmount" placeholder="单次剂量" class="amount" />
        <div class="chips">
          <button
            v-for="u in UNITS"
            :key="u"
            type="button"
            class="chip sm"
            :class="{ active: l.doseUnit === u }"
            @click="l.doseUnit = u"
          >
            {{ MED_DOSE_UNIT_LABELS[u] }}
          </button>
        </div>
      </div>
      <van-field v-model="l.timingNote" placeholder="服药时机，如 饭后 / 睡前（选填）" maxlength="64" />
    </section>

    <div class="more">
      <button v-if="lines.length" type="button" @click="pick"><van-icon name="photograph" /> 再拍一张</button>
      <button type="button" @click="addBlank"><van-icon name="plus" /> 手动加一条</button>
    </div>

    <div v-if="lines.length" class="footer">
      <van-button round block type="primary" :loading="saving" @click="save">保存 {{ lines.length }} 种药</van-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 96px;
}
:deep(.van-nav-bar) {
  background: rgba(255, 250, 242, 0.86);
  backdrop-filter: blur(10px);
}
:deep(.van-nav-bar__title) {
  font-weight: 800;
}
:deep(.van-nav-bar .van-icon) {
  color: var(--hx-teal);
}
.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.shot {
  padding: 0;
  overflow: hidden;
}
.shot img {
  display: block;
  width: 100%;
  max-height: 220px;
  object-fit: cover;
}
.shot-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 32px 16px;
  color: var(--hx-muted);
  font-size: 12px;
}
.shot-empty .van-icon {
  font-size: 40px;
  color: var(--hx-teal);
}
.shot-empty strong {
  font-size: 15px;
  color: var(--hx-text);
}
.recognizing {
  display: flex;
  justify-content: center;
  padding: 16px 0;
}
.warns p {
  margin: 0;
  font-size: 12px;
  color: var(--hx-warn-text);
  line-height: 1.6;
}
.line-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.line-head .name {
  flex: 1;
  font-weight: 700;
}
.del {
  color: var(--hx-muted);
  font-size: 18px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 6px 0;
}
.chip {
  border: 1px solid var(--hx-n-200);
  background: var(--hx-n-50);
  color: var(--hx-text);
  font-size: 12px;
  padding: 6px 10px;
  border-radius: 999px;
}
.chip.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 700;
}
.dose .amount {
  max-width: 160px;
}
.line :deep(.van-field) {
  padding: 6px 0;
  background: transparent;
}
.more {
  display: flex;
  justify-content: center;
  gap: 20px;
  margin: 14px 16px 0;
}
.more button {
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 14px;
  font-weight: 600;
}
.footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: #fff;
  box-shadow: 0 -6px 16px rgba(14, 159, 131, 0.08);
}
:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
</style>
