<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ALLERGY_CATEGORY_OPTIONS,
  COMMON_VACCINES,
  SURGERY_TYPE_OPTIONS,
  clonePastHistory,
  formatAllergyEntry,
  formatDiseaseEntry,
  formatSurgeryEntry,
  formatTransfusionEntry,
  formatVaccinationEntry,
  type AllergyCategory,
  type PastHistoryAllergyEntry,
  type PastHistoryDiseaseEntry,
  type PastHistoryStatus,
  type PastHistoryStructured,
  type PastHistorySurgeryEntry,
  type PastHistoryTransfusionEntry,
  type PastHistoryVaccinationEntry,
  type SurgeryType,
} from '../../shared/past-history-options'

interface IllnessOption {
  dictCode: string
  dictCodeDesc: string
}

type ListSectionKey = 'diseases' | 'surgeries' | 'allergies' | 'transfusions' | 'vaccinations'
type CategoryKey = ListSectionKey | 'medication'

const CATEGORIES: Array<{ key: CategoryKey; label: string; hint: string; warning?: boolean }> = [
  { key: 'diseases', label: '疾病史', hint: '曾患急慢性、传染病等' },
  { key: 'surgeries', label: '手术及外伤', hint: '手术、外伤、意外事故' },
  { key: 'allergies', label: '过敏史', hint: '药物、食物、环境过敏', warning: true },
  { key: 'transfusions', label: '输血史', hint: '输血及输血反应' },
  { key: 'vaccinations', label: '预防接种', hint: '疫苗接种记录' },
  { key: 'medication', label: '用药史', hint: '已停用或既往长用药说明' },
]

interface DisplayTag {
  section: CategoryKey
  index: number
  label: string
  warning?: boolean
}

const model = defineModel<PastHistoryStructured>({ required: true })
const props = defineProps<{
  illnessOptions: IllnessOption[]
}>()

const dialogVisible = ref(false)
const activeCategory = ref<CategoryKey>('diseases')

const diseaseDraft = reactive({
  code: '',
  name: '',
  year: '',
  note: '',
})

const surgeryDraft = reactive({
  type: 'SURGERY' as SurgeryType,
  name: '',
  date: '',
  hospital: '',
  note: '',
})

const allergyDraft = reactive({
  category: 'DRUG' as AllergyCategory,
  allergen: '',
  reaction: '',
})

const transfusionDraft = reactive({
  date: '',
  hasReaction: false,
  note: '',
})

const vaccinationDraft = reactive({
  vaccine: '',
  customVaccine: '',
  date: '',
  note: '',
})

const medicationDraft = reactive({
  note: '',
})

const selectableIllness = computed(() =>
  props.illnessOptions.filter((o) => o.dictCode !== 'NONE'),
)

const displayTags = computed((): DisplayTag[] => {
  const tags: DisplayTag[] = []
  const pushList = (key: ListSectionKey, warning?: boolean) => {
    model.value[key].forEach((entry, index) => {
      const formatters = {
        diseases: formatDiseaseEntry,
        surgeries: formatSurgeryEntry,
        allergies: formatAllergyEntry,
        transfusions: formatTransfusionEntry,
        vaccinations: formatVaccinationEntry,
      }
      tags.push({
        section: key,
        index,
        label: formatters[key](entry as never),
        warning,
      })
    })
  }
  pushList('diseases')
  pushList('surgeries')
  pushList('allergies', true)
  pushList('transfusions')
  pushList('vaccinations')
  if (model.value.medicationNote?.trim()) {
    tags.push({
      section: 'medication',
      index: 0,
      label: `用药史：${model.value.medicationNote.trim()}`,
    })
  }
  return tags
})

const activeCategoryMeta = computed(
  () => CATEGORIES.find((c) => c.key === activeCategory.value) ?? CATEGORIES[0],
)

function replaceModel(next: PastHistoryStructured) {
  model.value = next
}

function onStatusChange(val: string | number | boolean | undefined) {
  const status: PastHistoryStatus = val === 'none' || val === 'has' ? val : ''
  replaceModel({ ...clonePastHistory(model.value), status })
}

function resetDrafts() {
  diseaseDraft.code = ''
  diseaseDraft.name = ''
  diseaseDraft.year = ''
  diseaseDraft.note = ''
  surgeryDraft.type = 'SURGERY'
  surgeryDraft.name = ''
  surgeryDraft.date = ''
  surgeryDraft.hospital = ''
  surgeryDraft.note = ''
  allergyDraft.category = 'DRUG'
  allergyDraft.allergen = ''
  allergyDraft.reaction = ''
  transfusionDraft.date = ''
  transfusionDraft.hasReaction = false
  transfusionDraft.note = ''
  vaccinationDraft.vaccine = ''
  vaccinationDraft.customVaccine = ''
  vaccinationDraft.date = ''
  vaccinationDraft.note = ''
  medicationDraft.note = model.value.medicationNote ?? ''
}

function openDialog() {
  activeCategory.value = 'diseases'
  resetDrafts()
  dialogVisible.value = true
}

function selectCategory(key: CategoryKey) {
  activeCategory.value = key
  if (key === 'medication') {
    medicationDraft.note = model.value.medicationNote ?? ''
  }
}

function onDiseaseCodeChange(code: string) {
  const opt = selectableIllness.value.find((o) => o.dictCode === code)
  if (opt) diseaseDraft.name = opt.dictCodeDesc
}

function removeTag(tag: DisplayTag) {
  if (tag.section === 'medication') {
    replaceModel({ ...clonePastHistory(model.value), medicationNote: '' })
    return
  }
  const next = clonePastHistory(model.value)
  next[tag.section].splice(tag.index, 1)
  replaceModel(next)
}

function confirmDialog() {
  if (activeCategory.value === 'diseases') {
    const name = diseaseDraft.name.trim()
    if (!name) {
      ElMessage.warning('请填写或选择疾病')
      return
    }
    const entry: PastHistoryDiseaseEntry = {
      code: diseaseDraft.code || undefined,
      name,
      year: (diseaseDraft.year || '').trim() || undefined,
      note: diseaseDraft.note.trim() || undefined,
    }
    const next = clonePastHistory(model.value)
    next.status = 'has'
    next.diseases = [...next.diseases, entry]
    replaceModel(next)
  } else if (activeCategory.value === 'surgeries') {
    if (!surgeryDraft.name.trim()) {
      ElMessage.warning('请填写手术/外伤名称')
      return
    }
    const entry: PastHistorySurgeryEntry = {
      type: surgeryDraft.type,
      name: surgeryDraft.name.trim(),
      date: (surgeryDraft.date || '').trim() || undefined,
      hospital: surgeryDraft.hospital.trim() || undefined,
      note: surgeryDraft.note.trim() || undefined,
    }
    const next = clonePastHistory(model.value)
    next.status = 'has'
    next.surgeries = [...next.surgeries, entry]
    replaceModel(next)
  } else if (activeCategory.value === 'allergies') {
    if (!allergyDraft.allergen.trim()) {
      ElMessage.warning('请填写过敏原')
      return
    }
    const entry: PastHistoryAllergyEntry = {
      category: allergyDraft.category,
      allergen: allergyDraft.allergen.trim(),
      reaction: allergyDraft.reaction.trim() || undefined,
    }
    const next = clonePastHistory(model.value)
    next.status = 'has'
    next.allergies = [...next.allergies, entry]
    replaceModel(next)
  } else if (activeCategory.value === 'transfusions') {
    const entry: PastHistoryTransfusionEntry = {
      date: (transfusionDraft.date || '').trim() || undefined,
      hasReaction: transfusionDraft.hasReaction,
      note: transfusionDraft.note.trim() || undefined,
    }
    const next = clonePastHistory(model.value)
    next.status = 'has'
    next.transfusions = [...next.transfusions, entry]
    replaceModel(next)
  } else if (activeCategory.value === 'vaccinations') {
    const vaccine = (vaccinationDraft.vaccine === '__OTHER__'
      ? vaccinationDraft.customVaccine
      : vaccinationDraft.vaccine
    ).trim()
    if (!vaccine) {
      ElMessage.warning('请选择或填写疫苗名称')
      return
    }
    const entry: PastHistoryVaccinationEntry = {
      vaccine,
      date: (vaccinationDraft.date || '').trim() || undefined,
      note: vaccinationDraft.note.trim() || undefined,
    }
    const next = clonePastHistory(model.value)
    next.status = 'has'
    next.vaccinations = [...next.vaccinations, entry]
    replaceModel(next)
  } else {
    const next = clonePastHistory(model.value)
    next.status = 'has'
    next.medicationNote = medicationDraft.note.trim()
    replaceModel(next)
  }
  dialogVisible.value = false
}
</script>

<template>
  <div class="ph-editor">
    <el-radio-group :model-value="model.status" class="ph-status" @update:model-value="onStatusChange">
      <el-radio value="none">无既往史</el-radio>
      <el-radio value="has">有既往史</el-radio>
    </el-radio-group>

    <p v-if="model.status === 'none' && displayTags.length" class="ph-unsaved-hint">
      已选「无既往史」，尚未保存；切换回「有既往史」可恢复已填内容
    </p>

    <div v-if="model.status === 'has'" class="ph-body">
      <div class="ph-tags">
        <el-tag
          v-for="(tag, i) in displayTags"
          :key="`${tag.section}-${tag.index}-${i}`"
          closable
          :type="tag.warning ? 'warning' : 'info'"
          effect="plain"
          class="ph-tag"
          @close="removeTag(tag)"
        >
          {{ tag.label }}
        </el-tag>
        <el-button class="ph-add" circle type="primary" plain @click="openDialog">+</el-button>
      </div>
      <p v-if="!displayTags.length" class="ph-hint">点击 + 添加既往史条目</p>
    </div>

    <el-dialog v-model="dialogVisible" title="添加既往史" width="760px" destroy-on-close append-to-body>
      <div class="ph-dialog-split">
        <div class="ph-dialog-col">
          <div class="ph-col-title">记录类型</div>
          <div class="ph-type-list">
            <button
              v-for="cat in CATEGORIES"
              :key="cat.key"
              type="button"
              class="ph-type-btn"
              :class="{ active: activeCategory === cat.key, warning: cat.warning }"
              @click="selectCategory(cat.key)"
            >
              <span class="ph-type-label">{{ cat.label }}</span>
              <span class="ph-type-hint">{{ cat.hint }}</span>
            </button>
          </div>
        </div>

        <div class="ph-dialog-col ph-dialog-col-form">
          <div class="ph-col-title">{{ activeCategoryMeta.label }}</div>
          <p class="ph-form-hint">{{ activeCategoryMeta.hint }}</p>

          <el-form v-if="activeCategory === 'diseases'" label-width="88px" class="ph-form">
            <el-form-item label="常见疾病">
              <el-select
                v-model="diseaseDraft.code"
                clearable
                filterable
                placeholder="从目录选择"
                style="width: 100%"
                @change="onDiseaseCodeChange"
              >
                <el-option
                  v-for="o in selectableIllness"
                  :key="o.dictCode"
                  :label="o.dictCodeDesc"
                  :value="o.dictCode"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="疾病名称" required>
              <el-input v-model="diseaseDraft.name" maxlength="64" placeholder="可手输或由上项带出" />
            </el-form-item>
            <el-form-item label="发病/诊断年">
              <el-date-picker
                v-model="diseaseDraft.year"
                type="year"
                value-format="YYYY"
                format="YYYY"
                placeholder="选择年份"
                clearable
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="diseaseDraft.note" type="textarea" :rows="2" maxlength="120" show-word-limit />
            </el-form-item>
          </el-form>

          <el-form v-else-if="activeCategory === 'surgeries'" label-width="88px" class="ph-form">
            <el-form-item label="类型">
              <el-select v-model="surgeryDraft.type" style="width: 100%">
                <el-option v-for="o in SURGERY_TYPE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="名称" required>
              <el-input v-model="surgeryDraft.name" maxlength="64" placeholder="如：阑尾切除术" />
            </el-form-item>
            <el-form-item label="日期">
              <el-date-picker
                v-model="surgeryDraft.date"
                type="month"
                value-format="YYYY-MM"
                format="YYYY-MM"
                placeholder="选择年月"
                clearable
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="医院">
              <el-input v-model="surgeryDraft.hospital" maxlength="64" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="surgeryDraft.note" type="textarea" :rows="2" maxlength="120" show-word-limit />
            </el-form-item>
          </el-form>

          <el-form v-else-if="activeCategory === 'allergies'" label-width="88px" class="ph-form">
            <el-form-item label="类别">
              <el-select v-model="allergyDraft.category" style="width: 100%">
                <el-option v-for="o in ALLERGY_CATEGORY_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="过敏原" required>
              <el-input v-model="allergyDraft.allergen" maxlength="64" placeholder="如：青霉素、海鲜、花粉" />
            </el-form-item>
            <el-form-item label="反应">
              <el-input v-model="allergyDraft.reaction" maxlength="64" placeholder="如：皮疹、呼吸困难" />
            </el-form-item>
          </el-form>

          <el-form v-else-if="activeCategory === 'transfusions'" label-width="88px" class="ph-form">
            <el-form-item label="输血日期">
              <el-date-picker
                v-model="transfusionDraft.date"
                type="month"
                value-format="YYYY-MM"
                format="YYYY-MM"
                placeholder="选择年月"
                clearable
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="输血反应">
              <el-switch v-model="transfusionDraft.hasReaction" active-text="有" inactive-text="无" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="transfusionDraft.note" type="textarea" :rows="2" maxlength="120" show-word-limit />
            </el-form-item>
          </el-form>

          <el-form v-else-if="activeCategory === 'vaccinations'" label-width="88px" class="ph-form">
            <el-form-item label="疫苗">
              <el-select v-model="vaccinationDraft.vaccine" filterable allow-create default-first-option style="width: 100%">
                <el-option v-for="v in COMMON_VACCINES" :key="v" :label="v" :value="v" />
                <el-option label="其他（手输）" value="__OTHER__" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="vaccinationDraft.vaccine === '__OTHER__'" label="疫苗名称">
              <el-input v-model="vaccinationDraft.customVaccine" maxlength="64" />
            </el-form-item>
            <el-form-item label="接种日期">
              <el-date-picker
                v-model="vaccinationDraft.date"
                type="month"
                value-format="YYYY-MM"
                format="YYYY-MM"
                placeholder="选择年月"
                clearable
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="vaccinationDraft.note" type="textarea" :rows="2" maxlength="120" show-word-limit />
            </el-form-item>
          </el-form>

          <div v-else class="ph-med-block">
            <el-alert
              type="info"
              :closable="false"
              show-icon
              title="当前在用药请在「用药管理」中维护；此处可补充已停用或仅作既往记录的长用药说明。"
              class="ph-med-alert"
            />
            <el-input
              v-model="medicationDraft.note"
              type="textarea"
              :rows="4"
              maxlength="300"
              show-word-limit
              placeholder="如：曾长期服用二甲双胍（已停）"
            />
          </div>
        </div>
      </div>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmDialog">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ph-editor {
  width: 100%;
}

.ph-status {
  margin-bottom: 12px;
}

.ph-body {
  min-height: 40px;
}

.ph-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.ph-tag {
  max-width: 100%;
  height: auto;
  white-space: normal;
  line-height: 1.45;
  padding-top: 6px;
  padding-bottom: 6px;
}

.ph-tag :deep(.el-tag__content) {
  white-space: normal;
}

.ph-add {
  flex-shrink: 0;
}

.ph-hint {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--admin-muted);
}

.ph-unsaved-hint {
  margin: 0 0 8px;
  padding: 8px 10px;
  font-size: 12px;
  line-height: 1.45;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
  border-radius: 6px;
}

.ph-dialog-split {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
  min-height: 320px;
}

.ph-dialog-col {
  display: flex;
  flex-direction: column;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: #fff;
}

.ph-col-title {
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--admin-text);
}

.ph-form-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--admin-muted);
}

.ph-type-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ph-type-btn {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  width: 100%;
  padding: 10px 12px;
  text-align: left;
  background: #f8fafc;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.ph-type-btn:hover {
  border-color: var(--admin-primary-light);
}

.ph-type-btn.active {
  background: var(--admin-primary-muted);
  border-color: var(--admin-primary);
}

.ph-type-btn.active.warning .ph-type-label {
  color: #b45309;
}

.ph-type-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--admin-text);
}

.ph-type-hint {
  font-size: 11px;
  line-height: 1.35;
  color: var(--admin-muted);
}

.ph-dialog-col-form {
  overflow-y: auto;
}

.ph-form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.ph-med-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.ph-med-alert {
  margin-bottom: 0;
}
</style>
