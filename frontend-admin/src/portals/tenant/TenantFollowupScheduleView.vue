<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'

interface RuleItem {
  id: string
  orgId: string
  orgName: string
  diseaseCode: string
  diseaseLabel: string
  intervalWithPlan: number
  intervalNoPlan: number
  enabled: boolean
  gmtModified?: string
}

interface RulesResponse {
  rules: RuleItem[]
  defaultIntervalWithPlan: number
  defaultIntervalNoPlan: number
}

interface OrgItem {
  id: string
  name: string
}

const DISEASE_OPTIONS = [
  { value: '', label: '不限病种' },
  { value: 'diabetes', label: '糖尿病' },
  { value: 'hypertension', label: '高血压' },
  { value: 'obesity', label: '肥胖症' },
]

const rules = ref<RuleItem[]>([])
const orgs = ref<OrgItem[]>([])
const defaults = ref({ withPlan: 30, noPlan: 90 })
const loading = ref(false)

const dialogVisible = ref(false)
const saving = ref(false)
const form = ref({
  id: '',
  orgId: '',
  diseaseCode: '',
  intervalWithPlan: 30,
  intervalNoPlan: 90,
  enabled: true,
})
const editing = computed(() => Boolean(form.value.id))

async function load() {
  loading.value = true
  try {
    const [r, o] = await Promise.all([
      api<{ data: RulesResponse }>('/api/b/v1/followup-schedule-rules'),
      api<{ data: OrgItem[] }>('/api/b/v1/orgs'),
    ])
    rules.value = r.data?.rules ?? []
    defaults.value = {
      withPlan: r.data?.defaultIntervalWithPlan ?? 30,
      noPlan: r.data?.defaultIntervalNoPlan ?? 90,
    }
    orgs.value = o.data ?? []
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.value = {
    id: '',
    orgId: '',
    diseaseCode: '',
    intervalWithPlan: defaults.value.withPlan,
    intervalNoPlan: defaults.value.noPlan,
    enabled: true,
  }
  dialogVisible.value = true
}

function openEdit(row: RuleItem) {
  form.value = {
    id: row.id,
    orgId: row.orgId,
    diseaseCode: row.diseaseCode,
    intervalWithPlan: row.intervalWithPlan,
    intervalNoPlan: row.intervalNoPlan,
    enabled: row.enabled,
  }
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    await api('/api/b/v1/followup-schedule-rules', {
      method: 'POST',
      body: JSON.stringify(form.value.id ? form.value : { ...form.value, id: undefined }),
    })
    ElMessage.success('已保存，下次排期扫描生效')
    dialogVisible.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove(row: RuleItem) {
  try {
    await ElMessageBox.confirm(
      `删除「${row.orgName} · ${row.diseaseLabel}」后，该范围将改用上一级规则。`,
      '删除排期规则',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await api(`/api/b/v1/followup-schedule-rules/${row.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-title">
      <div>
        <h1>随访排期</h1>
        <p>
          按机构、病种设置定期随访间隔。越具体的规则越优先：机构+病种 › 机构通用 › 全部机构+病种 ›
          全部机构通用 › 系统默认（有方案 {{ defaults.withPlan }} 天 / 无方案 {{ defaults.noPlan }} 天）。
          患者有多个病种时取最短间隔。
        </p>
      </div>
      <el-button type="primary" @click="openCreate">新增规则</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rules" empty-text="尚未配置，全部使用系统默认间隔">
        <el-table-column label="适用机构" min-width="160">
          <template #default="{ row }">
            <span :class="{ muted: !row.orgId }">{{ row.orgName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="病种" width="140">
          <template #default="{ row }">
            <span :class="{ muted: !row.diseaseCode }">{{ row.diseaseLabel }}</span>
          </template>
        </el-table-column>
        <el-table-column label="有执行中方案" width="130" align="right">
          <template #default="{ row }">
            <span v-if="row.enabled">每 {{ row.intervalWithPlan }} 天</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="无方案" width="130" align="right">
          <template #default="{ row }">
            <span v-if="row.enabled">每 {{ row.intervalNoPlan }} 天</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="自动排期" width="110" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.enabled" type="success" size="small">开启</el-tag>
            <el-tag v-else type="info" size="small">关闭</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑排期规则' : '新增排期规则'" width="460px">
      <el-form label-width="110px">
        <el-form-item label="适用机构">
          <el-select v-model="form.orgId" :disabled="editing" style="width: 100%">
            <el-option value="" label="全部机构" />
            <el-option v-for="o in orgs" :key="o.id" :value="o.id" :label="o.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="病种">
          <el-select v-model="form.diseaseCode" :disabled="editing" style="width: 100%">
            <el-option v-for="d in DISEASE_OPTIONS" :key="d.value" :value="d.value" :label="d.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="自动排期">
          <el-switch v-model="form.enabled" active-text="开启" inactive-text="关闭" />
        </el-form-item>
        <template v-if="form.enabled">
          <el-form-item label="有执行中方案">
            <el-input-number v-model="form.intervalWithPlan" :min="7" :max="365" />
            <span class="unit">天</span>
          </el-form-item>
          <el-form-item label="无方案">
            <el-input-number v-model="form.intervalNoPlan" :min="7" :max="365" />
            <span class="unit">天</span>
          </el-form-item>
        </template>
        <el-alert
          v-else
          type="info"
          :closable="false"
          title="关闭后，命中该规则的患者不再自动生成定期随访，仍可手动发起"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.muted {
  color: var(--el-text-color-secondary);
}
.unit {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}
</style>
