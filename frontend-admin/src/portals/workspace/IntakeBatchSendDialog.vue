<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { api, getCurrentOrgName } from '../../shared/http'
import { toast } from '../../shared/toast'

export interface IntakeBatchPatient {
  peopleId: string
  displayName: string
}

interface IssuedLink {
  peopleId: string
  displayName: string
  patientLabel: string
  url: string
  copyText: string
  orgName: string
  staffDisplayName: string
}

/** 方案 A 专业贴心 / B 亲切关怀 / C 中老年极简 */
type ScriptStyle = 'pro' | 'warm' | 'elder'

const props = defineProps<{
  modelValue: boolean
  patients: IntakeBatchPatient[]
  careTeamName?: string | null
}>()

const emit = defineEmits<{
  'update:modelValue': [boolean]
  /** 用户已完成复制/确认发卷 */
  sent: [payload: { count: number; mode: 'wechat' | 'list' | 'qr' }]
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

const loading = ref(false)
const issuing = ref(false)
const links = ref<IssuedLink[]>([])
const mode = ref<'wechat' | 'sms' | 'qr'>('wechat')
const scriptStyle = ref<ScriptStyle>('pro')
const qrIndex = ref(0)
const qrDataUrl = ref('')
/** 单人复制后的短时高亮 peopleId（2s 后复位） */
const copiedPeopleId = ref<string | null>(null)
let copiedClearTimer: ReturnType<typeof setTimeout> | null = null
/** 防止连点导致重复 Toast / 重复写剪贴板 */
let copyInFlight = false
/** 本会话是否已向父级回报过一次「已发卷」 */
const notifiedSent = ref(false)

const orgName = computed(() => getCurrentOrgName()?.trim() || '本机构')
const teamLabel = computed(() => (props.careTeamName || '').trim() || '您的健管组')
const count = computed(() => links.value.length || props.patients.length)
const currentQr = computed(() => links.value[qrIndex.value] || null)
const staffNameHint = computed(() => {
  const fromApi = links.value.find((r) => r.staffDisplayName)?.staffDisplayName
  return (fromApi || '').trim()
})

/** 按当前语气实时生成每人话术（风格 Tab 切换即刷新） */
const scriptRows = computed(() =>
  links.value.map((row) => ({
    ...row,
    preview: formatOneScript(row, scriptStyle.value),
  })),
)

function clearCopiedState() {
  if (copiedClearTimer) {
    clearTimeout(copiedClearTimer)
    copiedClearTimer = null
  }
  copiedPeopleId.value = null
}

function markCopiedTemporarily(peopleId: string) {
  clearCopiedState()
  copiedPeopleId.value = peopleId
  copiedClearTimer = setTimeout(() => {
    if (copiedPeopleId.value === peopleId) {
      copiedPeopleId.value = null
    }
    copiedClearTimer = null
  }, 2000)
}

watch(
  () => props.modelValue,
  (open) => {
    if (!open) {
      clearCopiedState()
      return
    }
    mode.value = 'wechat'
    scriptStyle.value = 'pro'
    qrIndex.value = 0
    qrDataUrl.value = ''
    links.value = []
    notifiedSent.value = false
    clearCopiedState()
    void issueAllLinks()
  },
)

onBeforeUnmount(() => {
  clearCopiedState()
})

watch([mode, qrIndex, links], async () => {
  if (mode.value !== 'qr') return
  const row = links.value[qrIndex.value]
  if (!row?.url) {
    qrDataUrl.value = ''
    return
  }
  try {
    qrDataUrl.value = await QRCode.toDataURL(row.url, {
      width: 220,
      margin: 2,
      color: { dark: '#0f172a', light: '#ffffff' },
    })
  } catch {
    qrDataUrl.value = ''
  }
})

function formatOneScript(row: IssuedLink, style: ScriptStyle): string {
  const address = (row.patientLabel || row.displayName || '朋友').trim()
  const staff = (row.staffDisplayName || staffNameHint.value).trim()
  const staffLine = staff ? `我是您的专属健管师${staff}` : '我是您的专属健管师'
  const url = row.url

  if (style === 'warm') {
    return (
      `${address}您好，${staffLine}。\n` +
      `很高兴为您服务！为了帮您精准找到健康提升的切入点，我为您准备了一份专属的初步健康测评。\n` +
      `👉 戳这里开始：${url}\n` +
      `题目都很简单，几分钟就能搞定。有任何疑问随时给我发微信哦！`
    )
  }
  if (style === 'elder') {
    return (
      `${address}您好，${staffLine}。为了更好帮您调理身体，请点开下面链接填一下健康信息，大概需要两分钟（7天内有效，信息已加密）。\n` +
      `链接：${url}`
    )
  }
  // pro：优先用服务端原文（含落款），否则本地拼专业贴心版
  if (row.copyText?.trim()) {
    return row.copyText.trim()
  }
  return (
    `${address}您好，${staffLine}。\n` +
    `为了能给您制定更适合的健康管理方案，劳烦您花约 2 分钟填写基础健康问卷（7天内有效，信息严格保密）。\n` +
    `👉 点击填写：${url}\n` +
    `填写完成后，我会尽快为您出具健康管理建议。祝您生活愉快！`
  )
}

async function issueAllLinks() {
  const list = props.patients.filter((p) => p.peopleId)
  if (!list.length) {
    toast.warning('没有可发卷的患者')
    return
  }
  issuing.value = true
  loading.value = true
  const out: IssuedLink[] = []
  try {
    for (const p of list) {
      try {
        const res = await api<{
          data: {
            url?: string
            copyText?: string
            orgName?: string
            patientLabel?: string
            staffDisplayName?: string
          }
        }>(`/api/b/v1/patients/${p.peopleId}/intake-access-links`, {
          method: 'POST',
          body: JSON.stringify({ validDays: 7 }),
        })
        const url = (res.data?.url || '').trim()
        const copyText = (res.data?.copyText || '').trim()
        if (!url && !copyText) continue
        out.push({
          peopleId: p.peopleId,
          displayName: (p.displayName || '患者').trim(),
          patientLabel: (res.data?.patientLabel || p.displayName || '朋友').trim(),
          url: url || extractUrlFromText(copyText) || copyText,
          copyText: copyText || url,
          orgName: (res.data?.orgName || orgName.value).trim(),
          staffDisplayName: (res.data?.staffDisplayName || '').trim(),
        })
      } catch {
        /* 单个失败不阻断 */
      }
    }
    links.value = out
    if (qrIndex.value >= out.length) qrIndex.value = Math.max(0, out.length - 1)
    if (!out.length) toast.error('未能生成任何问卷链接')
  } finally {
    issuing.value = false
    loading.value = false
  }
}

function extractUrlFromText(text: string) {
  const m = text.match(/https?:\/\/\S+/)
  return m?.[0]?.replace(/[)）,.，。；;]+$/, '') || ''
}

function notifySentOnce(mode: 'wechat' | 'list' | 'qr', count: number) {
  if (notifiedSent.value) return
  notifiedSent.value = true
  emit('sent', { count, mode })
}

async function copyOneScript(row: { peopleId: string; displayName: string; preview: string }) {
  if (copyInFlight) return
  const text = (row.preview || '').trim()
  if (!text) {
    toast.info('话术尚未生成')
    return
  }
  copyInFlight = true
  try {
    await navigator.clipboard.writeText(text)
    markCopiedTemporarily(row.peopleId)
    toast.success(`已复制【${row.displayName}】的话术与链接`)
    // 每人复制不反复刷对话；本会话只向父级回报一次
    notifySentOnce('wechat', links.value.length)
  } catch {
    toast.error('复制失败，请检查浏览器剪贴板权限')
  } finally {
    copyInFlight = false
  }
}

async function copyAllLinksOnly() {
  if (!links.value.length || copyInFlight) return
  copyInFlight = true
  try {
    // 按行排列：每人一行「姓名 + 链接」
    const text = links.value.map((r) => `【${r.displayName}】 ${r.url}`).join('\n')
    await navigator.clipboard.writeText(text)
    toast.success(`已复制 ${links.value.length} 条专属链接`)
    notifySentOnce('list', links.value.length)
  } catch {
    toast.error('复制失败，请检查浏览器剪贴板权限')
  } finally {
    copyInFlight = false
  }
}

async function copyCurrentQrLink() {
  const row = currentQr.value
  if (!row?.url || copyInFlight) return
  copyInFlight = true
  try {
    const text = formatOneScript(row, scriptStyle.value)
    await navigator.clipboard.writeText(text)
    markCopiedTemporarily(row.peopleId)
    toast.success(`已复制【${row.displayName}】的填写话术`)
  } catch {
    toast.error('复制失败，请检查浏览器剪贴板权限')
  } finally {
    copyInFlight = false
  }
}

function markQrSent() {
  if (!links.value.length) return
  notifySentOnce('qr', links.value.length)
  visible.value = false
  toast.success('已记录发卷。请将二维码发给对应患者')
}

function previewSnippet(text: string, max = 72) {
  const one = text.replace(/\s+/g, ' ').trim()
  if (one.length <= max) return one
  return `${one.slice(0, max)}…`
}
</script>

<template>
  <el-dialog
    v-model="visible"
    title="智能发卷工作台"
    width="560px"
    append-to-body
    align-center
    destroy-on-close
    class="intake-batch-send-dialog"
  >
    <div v-loading="loading" class="body">
      <p class="ai-line">
        已为您生成本批 <strong>{{ count }}</strong> 位患者的专属首诊问卷链接。
        检测到他们刚分配到「{{ teamLabel }}」，建议用统一话术发送，体验更专业。
      </p>

      <div class="mode-tabs" role="tablist">
        <button
          type="button"
          class="mode-tab"
          :class="{ active: mode === 'wechat' }"
          role="tab"
          :aria-selected="mode === 'wechat'"
          @click="mode = 'wechat'"
        >
          💬 复制到微信
        </button>
        <button
          type="button"
          class="mode-tab"
          :class="{ active: mode === 'sms' }"
          role="tab"
          :aria-selected="mode === 'sms'"
          @click="mode = 'sms'"
        >
          📱 系统短信
        </button>
        <button
          type="button"
          class="mode-tab"
          :class="{ active: mode === 'qr' }"
          role="tab"
          :aria-selected="mode === 'qr'"
          @click="mode = 'qr'"
        >
          🔗 生成活码
        </button>
      </div>

      <template v-if="mode === 'wechat'">
        <p class="section-hint">
          推荐：先选语气，再按患者逐条复制到微信（一人一条，避免发错人）。
        </p>
        <div class="style-tabs" role="tablist" aria-label="话术风格">
          <button
            type="button"
            class="style-tab"
            :class="{ active: scriptStyle === 'pro' }"
            @click="scriptStyle = 'pro'"
          >
            专业贴心
          </button>
          <button
            type="button"
            class="style-tab"
            :class="{ active: scriptStyle === 'warm' }"
            @click="scriptStyle = 'warm'"
          >
            亲切关怀
          </button>
          <button
            type="button"
            class="style-tab"
            :class="{ active: scriptStyle === 'elder' }"
            @click="scriptStyle = 'elder'"
          >
            中老年极简
          </button>
        </div>

        <div v-if="scriptRows.length" class="script-list">
          <div
            v-for="row in scriptRows"
            :key="row.peopleId"
            class="script-row"
            :class="{ copied: copiedPeopleId === row.peopleId }"
          >
            <div class="script-main">
              <div class="script-head">
                <strong class="script-name">{{ row.displayName }}</strong>
                <span class="script-badge">专属链接</span>
              </div>
              <p class="script-preview" :title="row.preview">
                {{ previewSnippet(row.preview) }}
              </p>
            </div>
            <button
              type="button"
              class="script-copy-btn"
              :class="{ done: copiedPeopleId === row.peopleId }"
              @click.stop="copyOneScript(row)"
            >
              {{ copiedPeopleId === row.peopleId ? '已复制' : '复制' }}
            </button>
          </div>
        </div>
        <el-empty v-else description="暂无可用话术" :image-size="56" />

        <div class="actions">
          <el-button @click="copyAllLinksOnly" :disabled="!links.length">仅复制链接清单</el-button>
        </div>
      </template>

      <template v-else-if="mode === 'sms'">
        <div class="sms-box">
          <p class="sms-title">短信发送确认</p>
          <p>
            将向本批 <strong>{{ count }}</strong> 位患者下发首诊问卷短信（含专属填写链接）。
            系统短信通道即将开通；开通前请先切回「复制到微信」，按人复制话术后发送。
          </p>
          <ul v-if="links.length" class="sms-names">
            <li v-for="r in links" :key="r.peopleId">{{ r.displayName }}</li>
          </ul>
          <el-button type="primary" disabled>确认并下发短信（即将开通）</el-button>
        </div>
      </template>

      <template v-else>
        <p class="section-hint">线下门店可用：逐人展示问卷二维码，请患者扫码填写。</p>
        <div v-if="links.length" class="qr-panel">
          <div class="qr-nav">
            <el-button
              size="small"
              :disabled="qrIndex <= 0"
              @click="qrIndex = Math.max(0, qrIndex - 1)"
            >
              上一位
            </el-button>
            <span class="qr-name">
              {{ currentQr?.displayName || '患者' }}（{{ qrIndex + 1 }}/{{ links.length }}）
            </span>
            <el-button
              size="small"
              :disabled="qrIndex >= links.length - 1"
              @click="qrIndex = Math.min(links.length - 1, qrIndex + 1)"
            >
              下一位
            </el-button>
          </div>
          <div class="qr-card">
            <img v-if="qrDataUrl" :src="qrDataUrl" alt="问卷二维码" class="qr-img" />
            <p v-else class="qr-empty">二维码生成中…</p>
            <p class="qr-tip">扫码填写 · {{ teamLabel }} · {{ currentQr?.displayName }}</p>
          </div>
          <div class="actions">
            <el-button @click="copyCurrentQrLink" :disabled="!currentQr">复制当前话术</el-button>
            <el-button type="primary" @click="markQrSent">我已发给患者</el-button>
          </div>
        </div>
        <el-empty v-else description="暂无可用链接" :image-size="56" />
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.body {
  min-height: 220px;
}
.ai-line {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ink-600, #475569);
}
.ai-line strong {
  color: #c2410c;
}
.mode-tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.mode-tab {
  flex: 1;
  border: 1px solid var(--ink-200, #e2e8f0);
  border-radius: 10px;
  padding: 10px 8px;
  background: #fff;
  color: var(--ink-600, #475569);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease, color 0.15s ease;
}
.mode-tab.active {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}
.section-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--ink-500, #6b7280);
  line-height: 1.45;
}
.style-tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
}
.style-tab {
  flex: 1;
  border: 1px solid var(--ink-200, #e2e8f0);
  border-radius: 8px;
  padding: 7px 6px;
  background: #fff;
  color: var(--ink-600, #475569);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease, color 0.15s ease;
}
.style-tab.active {
  border-color: #fdba74;
  background: #fff7ed;
  color: #c2410c;
}
.script-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: min(360px, 46vh);
  overflow: auto;
  padding: 2px 2px 4px 0;
}
.script-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--ink-100, #e5e7eb);
  border-radius: 10px;
  background: #fff;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.script-row.copied {
  border-color: #86efac;
  background: #f0fdf4;
}
.script-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.script-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.script-name {
  font-size: 13.5px;
  font-weight: 700;
  color: var(--ink-800, #1f2937);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.script-badge {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 600;
  color: #1d4ed8;
  background: #eff6ff;
  border-radius: 999px;
  padding: 1px 7px;
}
.script-preview {
  margin: 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--ink-500, #64748b);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  transition: opacity 0.18s ease;
}
.script-copy-btn {
  flex-shrink: 0;
  min-width: 64px;
  border: 1px solid #93c5fd;
  border-radius: 6px;
  padding: 5px 12px;
  background: #fff;
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}
.script-copy-btn:hover {
  background: #eff6ff;
  border-color: #60a5fa;
}
.script-copy-btn:active {
  background: #dbeafe;
}
/* 已复制：保持白底可点，2s 后复位；绝不锁成深蓝实心 */
.script-copy-btn.done {
  border-color: #86efac;
  background: #fff;
  color: #15803d;
}
.script-copy-btn.done:hover {
  background: #f0fdf4;
  border-color: #4ade80;
}
.actions {
  display: flex;
  justify-content: flex-start;
  gap: 8px;
  margin-top: 12px;
}
.sms-box {
  padding: 16px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px dashed var(--ink-200, #e2e8f0);
}
.sms-title {
  margin: 0 0 8px !important;
  font-size: 14px !important;
  font-weight: 700;
  color: var(--ink-800, #1f2937) !important;
}
.sms-box p {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--ink-500, #64748b);
}
.sms-box strong {
  color: #c2410c;
}
.sms-names {
  margin: 0 0 14px;
  padding: 0 0 0 18px;
  color: var(--ink-600, #475569);
  font-size: 12.5px;
  line-height: 1.6;
}
.qr-nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.qr-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-800, #1f2937);
}
.qr-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 16px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid var(--ink-100, #e5e7eb);
}
.qr-img {
  width: 220px;
  height: 220px;
}
.qr-empty {
  margin: 40px 0;
  font-size: 13px;
  color: var(--ink-400, #94a3b8);
}
.qr-tip {
  margin: 0;
  font-size: 12px;
  color: var(--ink-500, #64748b);
}
</style>
