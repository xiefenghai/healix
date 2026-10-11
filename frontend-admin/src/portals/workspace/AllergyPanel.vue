<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import {
  SOURCE_LABELS,
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

const base = computed(() => `/api/b/v1/patients/${props.peopleId}/allergies`)

const items = computed(() => summary.value?.items ?? [])
const hasEffective = computed(() => items.value.some((i) => i.effective))

const allergenOptions = computed(() =>
  (catalog.value?.allergens ?? []).filter((a) => a.category === form.category),
)

const highRiskReactions = computed(
  () => new Set((catalog.value?.reactions ?? []).filter((r) => r.highRisk).map((r) => r.code)),
)

const willBeHighRisk = computed(
  () => form.severity === 'SEVERE' || form.reactions.some((r) => highRiskReactions.value.has(r)),
)

async function load() {
  if (!props.peopleId) return
  loading.value = true
  try {
    const [s, c] = await Promise.all([fetchAllergySummary(props.peopleId), loadAllergyCatalog()])
    summary.value = s
    catalog.value = c
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '过敏史加载失败')
  } finally {
    loading.value = false
  }
}

function afterChange(next?: AllergySummary) {
  allergyTick.value++
  if (next) summary.value = next
  else void load()
}

function openCreate() {
  editingId.value = null
  Object.assign(form, emptyAllergyForm())
  allergenPick.value = ''
  dialogVisible.value = true
}

function openEdit(item: AllergyItem) {
  editingId.value = item.id
  Object.assign(form, formFromItem(item))
  allergenPick.value = item.allergenCode ?? item.allergenName
  dialogVisible.value = true
}

function onStatusPick(v: string | number | boolean | undefined) {
  if (v === 'NKA' || v === 'UNKNOWN') void setStatus(v)
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

async function setStatus(status: 'NKA' | 'UNKNOWN') {
  try {
    const res = await api<{ data: AllergySummary }>(`${base.value}/status`, {
      method: 'PUT',
      body: JSON.stringify({ status }),
    })
    ElMessage.success(status === 'NKA' ? '已记录：无已知过敏' : '已改为未询问')
    afterChange(res.data)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

async function review() {
  try {
    const res = await api<{ data: AllergySummary }>(`${base.value}/review`, { method: 'POST' })
    ElMessage.success('已记录核对')
    afterChange(res.data)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

function detailLine(item: AllergyItem): string {
  const parts: string[] = []
  if (item.reactionLabels.length) parts.push(item.reactionLabels.join('、'))
  if (item.reactionNote) parts.push(item.reactionNote)
  if (item.severity !== 'UNKNOWN') parts.push(item.severityLabel)
  if (item.onsetText) parts.push(`首次 ${item.onsetText}`)
  if (item.lastOccurrence) parts.push(`最近 ${item.lastOccurrence}`)
  if (item.note) parts.push(item.note)
  return parts.join(' · ') || '未填写反应'
}

function fmtTime(iso: string | null) {
  if (!iso) return ''
  return iso.replace('T', ' ').slice(0, 16)
}

watch(() => props.peopleId, load)
watch(allergyTick, (v, old) => {
  // 其他入口（如头部）改动后同步；本组件自身改动已在 afterChange 刷新
  if (v !== old && !loading.value && !dialogVisible.value) void load()
})
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="ag-block">
    <div class="ag-head">
      <div class="ag-title">
        <el-tag
          v-if="summary"
          size="small"
          effect="plain"
          :type="summary.status === 'HAS' ? 'danger' : summary.status === 'NKA' ? 'success' : 'info'"
        >
          {{ summary.statusLabel }}
        </el-tag>
        <el-tag v-if="summary?.pendingCount" size="small" type="warning" effect="light">
          {{ summary.pendingCount }} 条患者自述待确认
        </el-tag>
        <span class="ag-reviewed">
          {{ summary?.reviewedAt ? `核对于 ${fmtTime(summary.reviewedAt)}` : '尚未核对' }}
        </span>
        <span class="ag-instant">即时保存，无需点「保存基础档案」</span>
      </div>
      <div class="ag-actions">
        <el-button size="small" @click="review">核对无误</el-button>
        <el-button size="small" type="primary" @click="openCreate">添加过敏</el-button>
      </div>
    </div>

    <el-alert
      v-if="summary?.status === 'UNKNOWN' && !items.length"
      type="warning"
      :closable="false"
      show-icon
      title="尚未采集过敏史，请询问患者是否有药物、食物或其他过敏"
      class="ag-alert"
    />

    <div v-if="!hasEffective" class="ag-status-row">
      <span class="ag-status-label">没有过敏条目时：</span>
      <el-radio-group
        :model-value="summary?.status"
        size="small"
        @change="onStatusPick"
      >
        <el-radio-button value="NKA">无已知过敏</el-radio-button>
        <el-radio-button value="UNKNOWN">未询问</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="items.length" class="ag-list">
      <div
        v-for="item in items"
        :key="item.id"
        class="ag-item"
        :class="{ inactive: !item.effective, risk: item.highRisk && item.effective }"
      >
        <div class="ag-main">
          <div class="ag-name-row">
            <span class="ag-name">{{ item.allergenName }}</span>
            <el-tag size="small" effect="plain">{{ item.categoryLabel }}</el-tag>
            <el-tag v-if="item.allergyType === 'INTOLERANCE'" size="small" type="info" effect="plain">不耐受</el-tag>
            <el-tag v-if="item.highRisk" size="small" type="danger" effect="dark">高危</el-tag>
            <el-tag
              size="small"
              effect="light"
              :type="item.verification === 'CONFIRMED' ? 'success' : item.verification === 'REFUTED' ? 'info' : 'warning'"
            >
              {{ item.verificationLabel }}
            </el-tag>
            <el-tag v-if="item.clinicalStatus === 'RESOLVED'" size="small" type="info">已消失</el-tag>
            <span class="ag-source">{{ SOURCE_LABELS[item.source] ?? item.source }}</span>
          </div>
          <div class="ag-detail">{{ detailLine(item) }}</div>
        </div>
        <div class="ag-ops">
          <template v-if="item.verification === 'UNCONFIRMED'">
            <el-button link type="success" @click="verify(item, 'CONFIRMED')">确认</el-button>
            <el-button link type="warning" @click="verify(item, 'REFUTED')">排除</el-button>
          </template>
          <el-button link type="primary" @click="openEdit(item)">编辑</el-button>
          <el-button link type="danger" @click="remove(item)">删除</el-button>
        </div>
      </div>
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
        <el-form-item label="反应表现">
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
        </el-form-item>
        <el-form-item label="反应补充">
          <el-input v-model="form.reactionNote" maxlength="128" placeholder="如：用药后 10 分钟全身红疹" />
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
            :disabled-date="(d: Date) => d.getTime() > Date.now()"
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
  justify-content: space-between;
  gap: 12px;
  min-height: 32px;
  margin-bottom: 8px;
}

.ag-instant {
  font-size: 12px;
  color: var(--el-color-primary);
}

.ag-title {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.ag-reviewed {
  font-size: 12px;
  color: var(--admin-muted);
}

.ag-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.ag-alert {
  margin-bottom: 12px;
}

.ag-status-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.ag-status-label {
  font-size: 13px;
  color: var(--admin-muted);
}

.ag-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.ag-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}

.ag-item.risk {
  border-color: #fca5a5;
  background: #fef2f2;
}

.ag-item.inactive {
  opacity: 0.6;
}

.ag-item.inactive .ag-name {
  text-decoration: line-through;
}

.ag-main {
  min-width: 0;
}

.ag-name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.ag-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--admin-text);
}

.ag-source {
  font-size: 12px;
  color: var(--admin-muted);
}

.ag-detail {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--admin-muted);
}

.ag-ops {
  display: flex;
  flex-shrink: 0;
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
