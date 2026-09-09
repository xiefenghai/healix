<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../shared/http'

interface QuotaView {
  key: string
  label: string
  period: string
  periodKey: string
  usedValue: number
  limitValue: number | null
}

interface FlagView {
  key: string
  label: string
  enabled: boolean
  source: string
  configJson: string | null
}

interface TenantConfig {
  appName?: string | null
  logoUrl?: string | null
  primaryColor?: string | null
  loginSlogan?: string | null
  supportPhone?: string | null
}

const props = defineProps<{ tenantId: string; canWrite: boolean }>()

const loading = ref(false)
const quotas = ref<QuotaView[]>([])
const flags = ref<FlagView[]>([])
const savingKey = ref('')
const configSaving = ref(false)
const config = ref<TenantConfig>({})
/** 上限输入框的本地值：null 表示不限量 */
const limitDraft = ref<Record<string, number | null>>({})

const SOURCE_LABEL: Record<string, string> = {
  TENANT: '租户配置',
  PLATFORM: '平台默认',
  DEFAULT: '内置默认',
}

async function load() {
  if (!props.tenantId) return
  loading.value = true
  try {
    const res = await api<{
      data: { quotas: QuotaView[]; flags: FlagView[]; config: TenantConfig | null }
    }>(`/api/ops/v1/tenants/${props.tenantId}/governance`)
    quotas.value = res.data.quotas ?? []
    flags.value = res.data.flags ?? []
    config.value = res.data.config ?? {}
    limitDraft.value = Object.fromEntries(quotas.value.map((q) => [q.key, q.limitValue]))
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载治理配置失败')
  } finally {
    loading.value = false
  }
}

async function saveQuota(row: QuotaView) {
  savingKey.value = row.key
  try {
    const res = await api<{ data: QuotaView[] }>(
      `/api/ops/v1/tenants/${props.tenantId}/governance/quotas`,
      {
        method: 'PUT',
        body: JSON.stringify({ quotaKey: row.key, limitValue: limitDraft.value[row.key] ?? null }),
      },
    )
    quotas.value = res.data ?? quotas.value
    limitDraft.value = Object.fromEntries(quotas.value.map((q) => [q.key, q.limitValue]))
    ElMessage.success('配额已更新')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    savingKey.value = ''
  }
}

async function toggleFlag(row: FlagView, enabled: boolean) {
  savingKey.value = row.key
  try {
    const res = await api<{ data: FlagView[] }>(
      `/api/ops/v1/tenants/${props.tenantId}/governance/flags`,
      {
        method: 'PUT',
        body: JSON.stringify({ flagKey: row.key, enabled, configJson: row.configJson ?? null }),
      },
    )
    flags.value = res.data ?? flags.value
    ElMessage.success(`${row.label}已${enabled ? '开通' : '关闭'}`)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
    await load()
  } finally {
    savingKey.value = ''
  }
}

async function saveConfig() {
  configSaving.value = true
  try {
    const res = await api<{ data: TenantConfig }>(
      `/api/ops/v1/tenants/${props.tenantId}/governance/config`,
      { method: 'PUT', body: JSON.stringify(config.value) },
    )
    config.value = res.data ?? config.value
    ElMessage.success('白标配置已保存')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    configSaving.value = false
  }
}

function usageText(row: QuotaView) {
  if (row.limitValue === null || row.limitValue === undefined) return `${row.usedValue} / 不限`
  return `${row.usedValue} / ${row.limitValue}`
}

function usageTagType(row: QuotaView) {
  if (row.limitValue === null || row.limitValue === undefined) return 'info'
  if (row.usedValue >= row.limitValue) return 'danger'
  if (row.usedValue >= row.limitValue * 0.8) return 'warning'
  return 'success'
}

const whiteLabelOn = () => flags.value.find((f) => f.key === 'WHITE_LABEL')?.enabled ?? false

watch(() => props.tenantId, load)
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="governance">
    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>配额</span>
          <span class="hint">上限留空表示不限量；按月项在每月 1 日自动开新周期</span>
        </div>
      </template>
      <el-table :data="quotas" size="small">
        <el-table-column prop="label" label="配额项" min-width="160" />
        <el-table-column label="周期" width="110">
          <template #default="{ row }">
            {{ row.period === 'MONTH' ? `按月 ${row.periodKey}` : '存量' }}
          </template>
        </el-table-column>
        <el-table-column label="用量" width="140">
          <template #default="{ row }">
            <el-tag size="small" :type="usageTagType(row)" effect="plain">{{ usageText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上限" min-width="220">
          <template #default="{ row }">
            <div class="limit-cell">
              <el-input-number
                v-model="limitDraft[row.key]"
                :min="0"
                :step="100"
                :disabled="!canWrite"
                controls-position="right"
                placeholder="不限"
                size="small"
              />
              <el-button
                v-if="canWrite"
                size="small"
                type="primary"
                link
                :loading="savingKey === row.key"
                @click="saveQuota(row)"
              >
                保存
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>功能开关</span>
          <span class="hint">关闭后对应接口直接拒绝，前端入口同步隐藏</span>
        </div>
      </template>
      <el-table :data="flags" size="small">
        <el-table-column prop="label" label="能力" min-width="180" />
        <el-table-column label="当前值来源" width="140">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ SOURCE_LABEL[row.source] || row.source }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="140">
          <template #default="{ row }">
            <el-switch
              :model-value="row.enabled"
              :disabled="!canWrite || savingKey === row.key"
              @change="(v: boolean) => toggleFlag(row, v)"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="card-head">
          <span>白标配置</span>
          <span class="hint">
            {{ whiteLabelOn() ? '已开通白标，配置对 B/C 端生效' : '未开通白标，配置可先填但不会生效' }}
          </span>
        </div>
      </template>
      <el-form label-width="96px" :disabled="!canWrite">
        <el-form-item label="应用名">
          <el-input v-model="config.appName" maxlength="32" show-word-limit placeholder="默认 Healix" />
        </el-form-item>
        <el-form-item label="Logo 地址">
          <el-input v-model="config.logoUrl" placeholder="https://…" />
        </el-form-item>
        <el-form-item label="主色">
          <div class="color-cell">
            <el-color-picker v-model="config.primaryColor" />
            <el-input v-model="config.primaryColor" placeholder="#2b9e9e" style="width: 160px" />
          </div>
        </el-form-item>
        <el-form-item label="登录副标题">
          <el-input v-model="config.loginSlogan" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="客服电话">
          <el-input v-model="config.supportPhone" maxlength="20" />
        </el-form-item>
        <el-form-item v-if="canWrite">
          <el-button type="primary" :loading="configSaving" @click="saveConfig">保存白标配置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.governance {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.card-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.limit-cell,
.color-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
</style>
