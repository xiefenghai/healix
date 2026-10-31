<script setup lang="ts">
/**
 * 首诊问卷过敏明细：与过敏面板同字段，按食物 / 药物分类编辑列表。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  emptyAllergyForm,
  loadAllergyCatalog,
  type AllergyCatalog,
  type AllergyCategory,
  type AllergyForm,
} from '../../shared/allergy'

export type IntakeAllergyDraft = AllergyForm & { id?: string }

const props = defineProps<{
  modelValue: unknown
  /** FOOD | DRUG */
  category: AllergyCategory
  disabled?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: IntakeAllergyDraft[]): void
}>()

const catalog = ref<AllergyCatalog | null>(null)
const items = reactive<IntakeAllergyDraft[]>([])
const legacyHint = ref('')

const allergenOptions = computed(() =>
  (catalog.value?.allergens ?? []).filter((a) => a.category === props.category),
)

const highRiskReactions = computed(
  () => new Set((catalog.value?.reactions ?? []).filter((r) => r.highRisk).map((r) => r.code)),
)

function blankItem(): IntakeAllergyDraft {
  return { ...emptyAllergyForm(), category: props.category, id: undefined }
}

function normalizeItem(raw: unknown): IntakeAllergyDraft | null {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null
  const o = raw as Record<string, unknown>
  const base = emptyAllergyForm()
  base.category = props.category
  base.allergenCode = o.allergenCode ? String(o.allergenCode) : ''
  base.allergenName = String(o.allergenName ?? o.name ?? '')
  base.allergyType = String(o.allergyType || 'ALLERGY')
  base.reactions = Array.isArray(o.reactions) ? o.reactions.map(String) : []
  base.reactionNote = o.reactionNote ? String(o.reactionNote) : ''
  base.severity = String(o.severity || 'UNKNOWN')
  base.clinicalStatus = String(o.clinicalStatus || 'ACTIVE')
  base.verification = (o.verification as AllergyForm['verification']) || 'CONFIRMED'
  base.onsetText = o.onsetText ? String(o.onsetText) : ''
  base.lastOccurrence = o.lastOccurrence ? String(o.lastOccurrence) : ''
  base.note = o.note ? String(o.note) : ''
  return {
    ...base,
    id: o.id ? String(o.id) : undefined,
  }
}

function parseList(v: unknown): IntakeAllergyDraft[] {
  legacyHint.value = ''
  if (v == null || v === '') return []
  if (typeof v === 'string') {
    const t = v.trim()
    if (!t) return []
    try {
      return parseList(JSON.parse(t))
    } catch {
      legacyHint.value = '原文本记录请按结构化重新录入'
      return []
    }
  }
  if (Array.isArray(v)) {
    return v.map(normalizeItem).filter((x): x is IntakeAllergyDraft => !!x)
  }
  const one = normalizeItem(v)
  return one ? [one] : []
}

function syncFromProp() {
  const next = parseList(props.modelValue).map((x) => ({ ...blankItem(), ...x, category: props.category }))
  items.splice(0, items.length, ...next)
}

watch(
  () => props.modelValue,
  () => {
    const cur = JSON.stringify(items)
    const next = JSON.stringify(parseList(props.modelValue))
    if (cur !== next) syncFromProp()
  },
  { immediate: true, deep: true },
)

function emitList() {
  emit(
    'update:modelValue',
    items.map((row) => ({
      ...(row.id ? { id: row.id } : {}),
      category: props.category,
      allergenCode: row.allergenCode || '',
      allergenName: row.allergenName.trim(),
      allergyType: row.allergyType || 'ALLERGY',
      reactions: [...(row.reactions || [])],
      reactionNote: row.reactionNote?.trim() || '',
      severity: row.severity || 'UNKNOWN',
      clinicalStatus: row.clinicalStatus || 'ACTIVE',
      verification: row.verification || 'CONFIRMED',
      onsetText: row.onsetText?.trim() || '',
      lastOccurrence: row.lastOccurrence || '',
      note: row.note?.trim() || '',
    })),
  )
}

function addItem() {
  if (props.disabled) return
  items.push(blankItem())
  emitList()
}

function removeItem(idx: number) {
  if (props.disabled) return
  items.splice(idx, 1)
  emitList()
}

function onAllergenPick(row: IntakeAllergyDraft, val: string) {
  const hit = catalog.value?.allergens.find((a) => a.code === val)
  if (hit) {
    row.allergenCode = hit.code
    row.allergenName = hit.label
    row.allergyType = hit.defaultType || row.allergyType
  } else {
    row.allergenCode = ''
    row.allergenName = (val ?? '').trim()
  }
  emitList()
}

function willBeHighRisk(row: IntakeAllergyDraft) {
  return row.severity === 'SEVERE' || row.reactions.some((r) => highRiskReactions.value.has(r))
}

const filledCount = computed(() => items.filter((x) => x.allergenName.trim()).length)

onMounted(async () => {
  try {
    catalog.value = await loadAllergyCatalog()
  } catch {
    catalog.value = null
  }
})
</script>

<template>
  <div class="intake-allergy" :class="{ disabled }">
    <p v-if="legacyHint" class="legacy">{{ legacyHint }}</p>
    <div v-if="!items.length" class="empty">
      <p>尚未录入{{ category === 'FOOD' ? '食物' : '药物' }}过敏</p>
      <el-button type="primary" plain size="small" :disabled="disabled" @click="addItem">添加</el-button>
    </div>

    <div v-for="(row, idx) in items" :key="row.id || `new-${idx}`" class="card">
      <div class="head">
        <strong>{{ category === 'FOOD' ? '食物' : '药物' }} {{ idx + 1 }}</strong>
        <el-button link type="danger" size="small" :disabled="disabled" @click="removeItem(idx)">
          删除
        </el-button>
      </div>

      <el-form label-width="88px" class="form" @submit.prevent>
        <el-form-item label="过敏原" required>
          <el-select
            :model-value="row.allergenCode || row.allergenName"
            filterable
            allow-create
            default-first-option
            clearable
            :disabled="disabled"
            placeholder="选择或输入"
            style="width: 100%"
            @change="(v: string) => onAllergenPick(row, v)"
          >
            <el-option
              v-for="a in allergenOptions"
              :key="a.code"
              :label="a.label"
              :value="a.code"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="类型">
          <el-radio-group v-model="row.allergyType" :disabled="disabled" @change="emitList">
            <el-radio
              v-for="t in catalog?.types || []"
              :key="t.code"
              :value="t.code"
            >
              {{ t.label }}
            </el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="反应">
          <el-checkbox-group v-model="row.reactions" :disabled="disabled" @change="emitList">
            <el-checkbox v-for="r in catalog?.reactions || []" :key="r.code" :value="r.code">
              {{ r.label }}
            </el-checkbox>
          </el-checkbox-group>
        </el-form-item>

        <el-form-item label="反应说明">
          <el-input
            v-model="row.reactionNote"
            :disabled="disabled"
            maxlength="128"
            placeholder="如：用药后 10 分钟全身红疹"
            @change="emitList"
          />
        </el-form-item>

        <el-form-item label="严重程度">
          <el-radio-group v-model="row.severity" :disabled="disabled" @change="emitList">
            <el-radio v-for="s in catalog?.severities || []" :key="s.code" :value="s.code">
              {{ s.label }}
            </el-radio>
          </el-radio-group>
          <span v-if="willBeHighRisk(row)" class="risk">高危</span>
        </el-form-item>

        <el-form-item label="起病">
          <el-input
            v-model="row.onsetText"
            :disabled="disabled"
            maxlength="32"
            placeholder="如：2015 年、儿时"
            @change="emitList"
          />
        </el-form-item>

        <el-form-item label="最近发生">
          <el-date-picker
            v-model="row.lastOccurrence"
            type="date"
            value-format="YYYY-MM-DD"
            :disabled="disabled"
            placeholder="可选"
            style="width: 100%"
            @change="emitList"
          />
        </el-form-item>

        <el-form-item label="状态">
          <el-radio-group v-model="row.clinicalStatus" :disabled="disabled" @change="emitList">
            <el-radio value="ACTIVE">仍过敏</el-radio>
            <el-radio value="RESOLVED">已消失</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="row.note"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            :disabled="disabled"
            @change="emitList"
          />
        </el-form-item>
      </el-form>
    </div>

    <div v-if="items.length" class="foot">
      <el-button type="primary" plain size="small" :disabled="disabled" @click="addItem">
        再加一条
      </el-button>
      <span class="meta">已填 {{ filledCount }} 条</span>
    </div>
  </div>
</template>

<style scoped>
.intake-allergy {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.intake-allergy.disabled {
  opacity: 0.85;
}

.legacy {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fff7ed;
  color: #9a3412;
  font-size: 12.5px;
}

.empty {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border: 1px dashed var(--ink-200);
  border-radius: 10px;
  background: #fff;
}

.empty p {
  margin: 0;
  color: var(--ink-500);
  font-size: 13px;
}

.card {
  border: 1px solid var(--ink-200);
  border-radius: 10px;
  background: #fff;
  padding: 10px 12px 4px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.head strong {
  font-size: 13px;
  color: var(--ink-800);
}

.form :deep(.el-form-item) {
  margin-bottom: 12px;
}

.risk {
  margin-left: 8px;
  font-size: 12px;
  color: var(--rose-600, #e11d48);
  font-weight: 600;
}

.foot {
  display: flex;
  align-items: center;
  gap: 12px;
}

.meta {
  font-size: 12px;
  color: var(--ink-400);
}
</style>
