<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import QRCode from 'qrcode'
import { api, getCurrentOrgName } from '../../shared/http'

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
const scriptText = ref('')

const orgName = computed(() => getCurrentOrgName()?.trim() || '本机构')
const teamLabel = computed(() => (props.careTeamName || '').trim() || '您的健管组')
const count = computed(() => links.value.length || props.patients.length)
const currentQr = computed(() => links.value[qrIndex.value] || null)
const staffNameHint = computed(() => {
  const fromApi = links.value.find((r) => r.staffDisplayName)?.staffDisplayName
  return (fromApi || '').trim()
})

watch(
  () => props.modelValue,
  (open) => {
    if (!open) return
    mode.value = 'wechat'
    scriptStyle.value = 'pro'
    qrIndex.value = 0
    qrDataUrl.value = ''
    links.value = []
    scriptText.value = ''
    void issueAllLinks()
  },
)

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

watch(scriptStyle, () => {
  if (!links.value.length) return
  scriptText.value = buildWechatScript(links.value, scriptStyle.value)
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
  // pro：与后端默认 copyText 对齐；优先用服务端原文（含落款）
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

function buildWechatScript(rows: IssuedLink[], style: ScriptStyle = scriptStyle.value) {
  if (!rows.length) return ''
  if (rows.length === 1) {
    return formatOneScript(rows[0]!, style)
  }
  return rows.map((r) => formatOneScript(r, style)).join('\n\n————\n\n')
}

async function issueAllLinks() {
  const list = props.patients.filter((p) => p.peopleId)
  if (!list.length) {
    ElMessage.warning('没有可发卷的患者')
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
        const url = res.data?.url || ''
        const copyText = res.data?.copyText || ''
        if (!url && !copyText) continue
        out.push({
          peopleId: p.peopleId,
          displayName: (p.displayName || '患者').trim(),
          patientLabel: (res.data?.patientLabel || p.displayName || '朋友').trim(),
          url: url || copyText,
          copyText: copyText || url,
          orgName: (res.data?.orgName || orgName.value).trim(),
          staffDisplayName: (res.data?.staffDisplayName || '').trim(),
        })
      } catch {
        /* 单个失败不阻断 */
      }
    }
    links.value = out
    scriptText.value = buildWechatScript(out, scriptStyle.value)
    if (!out.length) ElMessage.error('未能生成任何问卷链接')
  } finally {
    issuing.value = false
    loading.value = false
  }
}

async function copyWechatScript() {
  const text = scriptText.value.trim()
  if (!text) {
    ElMessage.info('话术尚未生成')
    return
  }
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制微信话术与链接，可直接粘贴发送')
  emit('sent', { count: links.value.length, mode: 'wechat' })
  visible.value = false
}

async function copyAllLinksOnly() {
  if (!links.value.length) return
  const text = links.value.map((r) => `【${r.displayName}】\n${r.url}`).join('\n\n')
  await navigator.clipboard.writeText(text)
  ElMessage.success(`已复制 ${links.value.length} 条专属链接`)
  emit('sent', { count: links.value.length, mode: 'list' })
}

async function copyCurrentQrLink() {
  const row = currentQr.value
  if (!row?.url) return
  await navigator.clipboard.writeText(row.copyText || row.url)
  ElMessage.success(`已复制【${row.displayName}】的填写链接`)
}

function markQrSent() {
  if (!links.value.length) return
  emit('sent', { count: links.value.length, mode: 'qr' })
  visible.value = false
  ElMessage.success('已记录发卷。请将二维码发给对应患者')
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
          @click="mode = 'wechat'"
        >
          💬 复制到微信
        </button>
        <button
          type="button"
          class="mode-tab"
          :class="{ active: mode === 'sms' }"
          @click="mode = 'sms'"
        >
          📱 系统短信
        </button>
        <button
          type="button"
          class="mode-tab"
          :class="{ active: mode === 'qr' }"
          @click="mode = 'qr'"
        >
          🔗 生成活码
        </button>
      </div>

      <template v-if="mode === 'wechat'">
        <p class="section-hint">
          推荐：按场景选一种语气，复制后粘贴到微信发给患者（多人时按条拆开即可）。
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
        <el-input
          v-model="scriptText"
          type="textarea"
          :rows="12"
          resize="vertical"
          placeholder="正在生成话术…"
        />
        <div class="actions">
          <el-button @click="copyAllLinksOnly" :disabled="!links.length">仅复制链接清单</el-button>
          <el-button type="primary" :disabled="!scriptText.trim()" @click="copyWechatScript">
            复制话术及链接
          </el-button>
        </div>
      </template>

      <template v-else-if="mode === 'sms'">
        <div class="sms-box">
          <p>
            系统短信通道即将开通。当前请先用「复制到微信」发送话术；开通后可在此一键下发短信。
          </p>
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
            <p class="qr-tip">扫码填写 · {{ teamLabel }}</p>
          </div>
          <div class="actions">
            <el-button @click="copyCurrentQrLink" :disabled="!currentQr">复制当前链接</el-button>
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
}
.style-tab.active {
  border-color: #fdba74;
  background: #fff7ed;
  color: #c2410c;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
.sms-box {
  padding: 16px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px dashed var(--ink-200, #e2e8f0);
}
.sms-box p {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--ink-500, #64748b);
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
