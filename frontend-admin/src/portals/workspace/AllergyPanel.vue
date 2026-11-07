<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import {
  allergyTick,
  emptyAllergyForm,
  fetchAllergySummary,
  formFromItem,
  formToPayload,
  loadAllergyCatalog,
  type AllergyCatalog,
  type AllergyCategory,
  type AllergyItem,
  type AllergySummary,
} from '../../shared/allergy'

const props = defineProps<{ peopleId: string }>()

const loading = ref(false)
const saving = ref(false)
const summary = ref<AllergySummary | null>(null)
const catalog = ref<AllergyCatalog | null>(null)

const dialogVisible = ref(false)
const editingId = ref<string | null>(null)
const form = reactive(emptyAllergyForm())
/** 下拉值：字典 code 或手输文本 */
const allergenPick = ref('')
/**
 * UNKNOWN 下点「有过敏」的本地意图（后端不能直接设 HAS，需先加条目）。
 * 从 NKA 切过来时会先清成 UNKNOWN。
 */
const forceHas = ref(false)

const base = computed(() => `/api/b/v1/patients/${props.peopleId}/allergies`)

const items = computed(() => summary.value?.items ?? [])

/** 与家族史一致：'' 未选 / none 无 / has 有 */
const statusUi = computed<'none' | 'has' | ''>(() => {
  const s = summary.value?.status
  if (s === 'NKA') return 'none'
  if (s === 'HAS' || forceHas.value) return 'has'
  return ''
})

const showBody = computed(() => statusUi.value === 'has')

/** 药物 / 食物 / 环境固定分区；「其他」仅有数据时出现 */
const categorySections = computed(() => {
  const defaults: Array<{ code: AllergyCategory; label: string }> = [
    { code: 'DRUG', label: '药物' },
    { code: 'FOOD', label: '食物' },
    { code: 'ENVIRONMENT', label: '环境' },
    { code: 'OTHER', label: '其他' },
  ]
  const fromCatalog = catalog.value?.categories ?? []
  const ordered = defaults.map((d) => {
    const hit = fromCatalog.find((c) => c.code === d.code)
    return { code: d.code, label: hit?.label || d.label }
  })
  return ordered
    .filter((c) => c.code !== 'OTHER' || items.value.some((i) => i.category === 'OTHER'))
    .map((c) => ({
      ...c,
      rows: items.value.filter((i) => i.category === c.code),
    }))
})

const allergenOptions = computed(() =>
  (catalog.value?.allergens ?? []).filter((a) => a.category === form.category),
)

const highRiskReactions = computed(
  () => new Set((catalog.value?.reactions ?? []).filter((r) => r.highRisk).map((r) => r.code)),
)

const willBeHighRisk = computed(
  () => form.severity === 'SEVERE' || form.reactions.some((r) => highRiskReactions.value.has(r)),
)

/** 最近发作不可选未来日期 */
function disabledLastOccurrenceDate(d: Date) {
  const today = new Date()
  today.setHours(23, 59, 59, 999)
  return d.getTime() > today.getTime()
}

/** @param keepHasIntent 变更后刷新时：删光条目回 UNKNOWN 仍保持「有过敏」展开 */
async function load(keepHasIntent = false) {
  if (!props.peopleId) return
  loading.value = true
  try {
    const prevStatus = summary.value?.status
    const keep = keepHasIntent && (forceHas.value || prevStatus === 'HAS')
    const [s, c] = await Promise.all([fetchAllergySummary(props.peopleId), loadAllergyCatalog()])
    summary.value = s
    catalog.value = c
    if (s.status === 'NKA' || s.status === 'HAS') {
      forceHas.value = false
    } else if (keep && s.status === 'UNKNOWN') {
      forceHas.value = true
    } else {
      forceHas.value = false
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '过敏史加载失败')
  } finally {
    loading.value = false
  }
}

/** 本面板写入后会推 allergyTick；跳过紧随其后的自触发刷新 */
let skipTickReload = false

function afterChange(next?: AllergySummary) {
  skipTickReload = true
  allergyTick.value++
  if (next) {
    const prev = summary.value?.status
    summary.value = next
    if (next.status === 'NKA' || next.status === 'HAS') {
      forceHas.value = false
    } else if ((forceHas.value || prev === 'HAS') && next.status === 'UNKNOWN') {
      // 删光条目或从 NKA 切出后，保持「有过敏」展开
      forceHas.value = true
    }
  } else {
    void load(true)
  }
  queueMicrotask(() => {
    skipTickReload = false
  })
}

function openCreate(category?: AllergyCategory) {
  editingId.value = null
  Object.assign(form, emptyAllergyForm())
  if (category) form.category = category
  allergenPick.value = ''
  dialogVisible.value = true
}

function openEdit(item: AllergyItem) {
  editingId.value = item.id
  Object.assign(form, formFromItem(item))
  allergenPick.value = item.allergenCode ?? item.allergenName
  dialogVisible.value = true
}

async function onStatusUi(v: string | number | boolean | undefined) {
  if (v === 'none') {
    const ok = await setStatus('NKA')
    if (ok) forceHas.value = false
    return
  }
  if (v === 'has') {
    forceHas.value = true
    // 后端不允许直接设 HAS；有条目时已是 HAS。从 NKA 切出需清掉「无已知」声明。
    if (summary.value?.status === 'NKA') {
      await setStatus('UNKNOWN', { silent: true })
    }
  }
}

function onCategoryPick(v: string | number | boolean | undefined) {
  form.category = v as AllergyCategory
  allergenPick.value = ''
  form.allergenCode = ''
  form.allergenName = ''
}

function onAllergenPick(val: string) {
  const hit = catalog.value?.allergens.find((a) => a.code === val)
  if (hit) {
    form.allergenCode = hit.code
    form.allergenName = hit.label
    form.allergyType = hit.defaultType
  } else {
    form.allergenCode = ''
    form.allergenName = (val ?? '').trim()
  }
}

async function submit() {
  if (!form.allergenName.trim()) {
    ElMessage.warning('请选择或填写过敏原')
    return
  }
  if (!form.reactions.length && !form.reactionNote.trim()) {
    ElMessage.warning('请勾选至少一项反应表现，或填写反应补充')
    return
  }
  saving.value = true
  try {
    const body = JSON.stringify(formToPayload(form))
    if (editingId.value) {
      await api(`${base.value}/${editingId.value}`, { method: 'PUT', body })
    } else {
      await api(base.value, { method: 'POST', body })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    afterChange()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function verify(item: AllergyItem, verification: 'CONFIRMED' | 'REFUTED') {
  if (verification === 'REFUTED') {
    try {
      await ElMessageBox.confirm(`确认排除「${item.allergenName}」？排除后不再参与用药、饮食安全提醒。`, '排除过敏', {
        type: 'warning',
        confirmButtonText: '排除',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  try {
    await api(`${base.value}/${item.id}/verification`, {
      method: 'PUT',
      body: JSON.stringify({ verification }),
    })
    ElMessage.success(verification === 'CONFIRMED' ? '已确认' : '已排除')
    afterChange()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function remove(item: AllergyItem) {
  try {
    await ElMessageBox.confirm(`删除「${item.allergenName}」？如为误录可删除；如已脱敏建议改为「已消失」保留记录。`, '删除过敏条目', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  try {
    await api(`${base.value}/${item.id}`, { method: 'DELETE' })
    ElMessage.success('已删除')
    afterChange()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function setStatus(status: 'NKA' | 'UNKNOWN', opts?: { silent?: boolean }): Promise<boolean> {
  try {
    const res = await api<{ data: AllergySummary }>(`${base.value}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status }),
    })
    if (!opts?.silent) {
      ElMessage.success(status === 'NKA' ? '已记录：无已知过敏' : '已清除「无已知过敏」')
    }
    afterChange(res.data)
    return true
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
    return false
  }
}

function reactionText(item: AllergyItem): string {
  const parts: string[] = []
  if (item.reactionLabels.length) parts.push(item.reactionLabels.join('、'))
  if (item.reactionNote) parts.push(item.reactionNote)
  return parts.join('；') || ''
}

function hasReaction(item: AllergyItem): boolean {
  return !!(item.reactionLabels.length || item.reactionNote)
}

function statusText(item: AllergyItem): string {
  const bits: string[] = []
  if (item.clinicalStatus === 'RESOLVED') bits.push('已消失')
  // 已确认不展示；仅提示待核实 / 已排除
  if (item.verification === 'UNCONFIRMED') bits.push('患者自述')
  if (item.verification === 'REFUTED') bits.push('已排除')
  return bits.join(' · ') || '—'
}

function tableRowClass({ row }: { row: AllergyItem }) {
  const cls: string[] = []
  if (!row.effective) cls.push('ag-row-inactive')
  if (row.highRisk && row.effective) cls.push('ag-row-risk')
  return cls.join(' ')
}

watch(() => props.peopleId, () => void load())
watch(allergyTick, (v, old) => {
  // 其他入口改动后同步；本组件自身改动已在 afterChange 刷新
  if (skipTickReload || v === old || loading.value || dialogVisible.value) return
  void load()
})
onMounted(() => void load())
</script>

<template>
  <div v-loading="loading" class="ag-block">
    <div class="ag-head">
      <el-radio-group
        :model-value="statusUi"
        class="ag-status"
        @change="onStatusUi"
      >
        <el-radio value="none">无已知过敏</el-radio>
        <el-radio value="has">有过敏</el-radio>
      </el-radio-group>
      <el-tag v-if="summary?.pendingCount" size="small" type="warning" effect="light">
        {{ summary.pendingCount }} 条患者自述待确认
      </el-tag>
    </div>
    <p v-if="summary?.status === 'UNKNOWN' && !forceHas" class="ag-status-hint">
      尚未采集。多数患者无过敏，可先选「无已知过敏」；若确认有过敏再选「有过敏」并逐项添加。
    </p>

    <div v-if="showBody" class="ag-body">
      <section v-for="sec in categorySections" :key="sec.code" class="ag-section">
        <div class="ag-section-head">
          <h4 class="ag-section-title">{{ sec.label }}</h4>
        </div>
        <el-table
          v-if="sec.rows.length"
          :data="sec.rows"
          size="small"
          stripe
          class="ag-table"
          :row-class-name="tableRowClass"
        >
          <el-table-column label="过敏原" min-width="120">
            <template #default="{ row }">
              <div class="ag-allergen">
                <span class="ag-name" :class="{ inactive: !row.effective }">{{ row.allergenName }}</span>
                <el-tag v-if="row.highRisk" size="small" type="danger" effect="dark">高危</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="88">
            <template #default="{ row }">
              {{ row.allergyType === 'INTOLERANCE' ? '不耐受' : '过敏' }}
            </template>
          </el-table-column>
          <el-table-column label="反应表现" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="hasReaction(row)">{{ reactionText(row) }}</span>
              <button
                v-else
                type="button"
                class="ag-reaction-missing"
                @click="openEdit(row)"
              >
                待补充反应
              </button>
            </template>
          </el-table-column>
          <el-table-column label="程度" width="88">
            <template #default="{ row }">
              {{ row.severity === 'UNKNOWN' ? '—' : row.severityLabel }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span
                :class="{
                  'ag-status-warn': row.verification === 'UNCONFIRMED',
                  'ag-status-muted': row.verification === 'REFUTED' || row.clinicalStatus === 'RESOLVED',
                }"
              >
                {{ statusText(row) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="168" fixed="right">
            <template #default="{ row }">
              <div class="ag-ops">
                <template v-if="row.verification === 'UNCONFIRMED'">
                  <el-button link type="success" @click="verify(row, 'CONFIRMED')">确认</el-button>
                  <el-button link type="warning" @click="verify(row, 'REFUTED')">排除</el-button>
                </template>
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="remove(row)">删除</el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <button
          type="button"
          class="ag-add-dash"
          @click="openCreate(sec.code)"
        >
          + 添加{{ sec.label }}过敏项
        </button>
      </section>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑过敏' : '添加过敏'"
      width="640px"
      append-to-body
      destroy-on-close
    >
      <el-form label-width="88px" class="ag-form">
        <el-form-item label="类别" required>
          <el-radio-group :model-value="form.category" @change="onCategoryPick">
            <el-radio-button v-for="c in catalog?.categories ?? []" :key="c.code" :value="c.code">
              {{ c.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="过敏原" required>
          <el-select
            v-model="allergenPick"
            filterable
            allow-create
            default-first-option
            clearable
            placeholder="选择常见过敏原，或直接输入名称"
            style="width: 100%"
            @change="onAllergenPick"
          >
            <el-option v-for="a in allergenOptions" :key="a.code" :label="a.label" :value="a.code">
              <span>{{ a.label }}</span>
              <span v-if="a.aliases.length" class="ag-opt-alias">{{ a.aliases.slice(0, 4).join('、') }}</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="form.allergyType">
            <el-radio v-for="t in catalog?.types ?? []" :key="t.code" :value="t.code">{{ t.label }}</el-radio>
          </el-radio-group>
          <span class="ag-hint">不耐受（如乳糖不耐受）只做提醒，不按过敏拦截</span>
        </el-form-item>
        <el-form-item label="反应表现" required>
          <el-checkbox-group v-model="form.reactions" class="ag-reactions">
            <el-checkbox
              v-for="r in catalog?.reactions ?? []"
              :key="r.code"
              :value="r.code"
              :class="{ 'ag-risk-option': r.highRisk }"
            >
              {{ r.label }}
            </el-checkbox>
          </el-checkbox-group>
          <p class="ag-field-hint">请至少勾选一项（如皮疹、腹痛腹泻、呼吸困难）；不清楚可勾「其他」并在下方补充。</p>
        </el-form-item>
        <el-form-item label="反应补充">
          <el-input
            v-model="form.reactionNote"
            maxlength="128"
            placeholder="可选：补充发作经过，如用药后 10 分钟全身红疹"
          />
        </el-form-item>
        <el-form-item label="严重程度">
          <el-radio-group v-model="form.severity">
            <el-radio v-for="s in catalog?.severities ?? []" :key="s.code" :value="s.code">{{ s.label }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-alert
          v-if="willBeHighRisk"
          type="error"
          :closable="false"
          show-icon
          title="将标记为高危过敏，患者头部会醒目提示"
          class="ag-risk-alert"
        />
        <el-form-item label="首次发生">
          <el-input v-model="form.onsetText" maxlength="32" placeholder="如：2015 年、儿时" style="width: 220px" />
          <span class="ag-inline-label">最近发作</span>
          <el-date-picker
            v-model="form.lastOccurrence"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择日期"
            :disabled-date="disabledLastOccurrenceDate"
            style="width: 180px"
          />
        </el-form-item>
        <el-form-item label="当前状态">
          <el-radio-group v-model="form.clinicalStatus">
            <el-radio value="ACTIVE">仍过敏</el-radio>
            <el-radio value="RESOLVED">已消失（如已脱敏）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="核实">
          <el-radio-group v-model="form.verification">
            <el-radio value="CONFIRMED">已确认</el-radio>
            <el-radio value="UNCONFIRMED">患者自述</el-radio>
            <el-radio value="REFUTED">已排除</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ag-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 16px;
  min-height: 32px;
}

.ag-status {
  flex-shrink: 0;
  height: 32px;
  align-items: center;
}

.ag-status :deep(.el-radio) {
  height: 32px;
  margin-right: 16px;
  align-items: center;
}

.ag-status-hint {
  margin: 8px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--admin-muted, #64748b);
}

.ag-body {
  min-height: 40px;
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.ag-section-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.ag-section-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-text);
}

.ag-table {
  width: 100%;
  margin-bottom: 8px;
  --el-table-header-bg-color: #f8fafc;
}

.ag-add-dash {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 40px;
  padding: 8px 12px;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  color: #2563eb;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.12s ease, background 0.12s ease, color 0.12s ease;
}

.ag-add-dash:hover {
  border-color: #93c5fd;
  background: #eff6ff;
  color: #1d4ed8;
}

.ag-reaction-missing {
  border: 0;
  padding: 0;
  background: transparent;
  color: #b45309;
  font-size: 12px;
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 2px;
  cursor: pointer;
}

.ag-field-hint {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.45;
  color: var(--admin-muted, #64748b);
}

.ag-table :deep(.ag-row-risk) {
  --el-table-tr-bg-color: #fef2f2;
}

.ag-table :deep(.ag-row-inactive) {
  opacity: 0.55;
}

.ag-allergen {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.ag-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-text);
}

.ag-name.inactive {
  text-decoration: line-through;
  font-weight: 500;
}

.ag-status-warn {
  color: #b45309;
}

.ag-status-muted {
  color: var(--admin-muted);
}

.ag-ops {
  display: flex;
  flex-wrap: wrap;
  gap: 0;
}

.ag-opt-alias {
  float: right;
  margin-left: 12px;
  font-size: 12px;
  color: var(--admin-muted);
}

.ag-hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--admin-muted);
}

.ag-reactions {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  column-gap: 8px;
}

.ag-risk-option :deep(.el-checkbox__label) {
  color: #dc2626;
}

.ag-risk-alert {
  margin: -6px 0 14px 88px;
  width: auto;
}

.ag-inline-label {
  margin: 0 8px 0 16px;
  font-size: 14px;
  color: var(--el-text-color-regular);
}
</style>
