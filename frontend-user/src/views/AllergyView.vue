<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../api/http'

type Category = 'DRUG' | 'FOOD' | 'ENVIRONMENT' | 'OTHER'

interface AllergyItem {
  id: string
  category: Category
  categoryLabel: string
  allergenCode: string | null
  allergenName: string
  allergyType: 'ALLERGY' | 'INTOLERANCE'
  reactions: string[]
  reactionLabels: string[]
  reactionNote: string | null
  severity: string
  severityLabel: string
  highRisk: boolean
  clinicalStatus: 'ACTIVE' | 'RESOLVED'
  verification: 'UNCONFIRMED' | 'CONFIRMED' | 'REFUTED'
  verificationLabel: string
  onsetText: string | null
  note: string | null
  effective: boolean
  editableByPatient: boolean
}

interface Summary {
  status: 'UNKNOWN' | 'NKA' | 'HAS'
  statusLabel: string
  items: AllergyItem[]
}

interface Catalog {
  categories: Array<{ code: Category; label: string }>
  allergens: Array<{ code: string; category: Category; label: string; defaultType: string }>
  reactions: Array<{ code: string; label: string; highRisk: boolean }>
  severities: Array<{ code: string; label: string }>
}

const router = useRouter()
const loading = ref(true)
const saving = ref(false)
const summary = ref<Summary | null>(null)
const catalog = ref<Catalog | null>(null)
const showForm = ref(false)
const editingId = ref<string | null>(null)

const form = reactive({
  category: 'DRUG' as Category,
  allergenCode: '',
  customName: '',
  allergyType: 'ALLERGY',
  reactions: [] as string[],
  reactionNote: '',
  severity: 'UNKNOWN',
  clinicalStatus: 'ACTIVE',
  onsetText: '',
  note: '',
})

const items = computed(() => summary.value?.items ?? [])
const hasEffective = computed(() => items.value.some((i) => i.effective))
const commonAllergens = computed(() =>
  (catalog.value?.allergens ?? []).filter((a) => a.category === form.category),
)
const willBeHighRisk = computed(
  () =>
    form.severity === 'SEVERE' ||
    form.reactions.some((r) => catalog.value?.reactions.find((x) => x.code === r)?.highRisk),
)

onMounted(load)

async function load() {
  loading.value = true
  try {
    const [s, c] = await Promise.all([
      api<{ data: Summary }>('/api/c/v1/me/allergies'),
      catalog.value ? Promise.resolve({ data: catalog.value }) : api<{ data: Catalog }>('/api/c/v1/me/allergies/catalog'),
    ])
    summary.value = s.data
    catalog.value = c.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.category = 'DRUG'
  form.allergenCode = ''
  form.customName = ''
  form.allergyType = 'ALLERGY'
  form.reactions = []
  form.reactionNote = ''
  form.severity = 'UNKNOWN'
  form.clinicalStatus = 'ACTIVE'
  form.onsetText = ''
  form.note = ''
}

function openAdd() {
  editingId.value = null
  resetForm()
  showForm.value = true
}

function openEdit(item: AllergyItem) {
  if (!item.editableByPatient) {
    showToast('该条目已由医护确认，如需修改请联系健管师')
    return
  }
  editingId.value = item.id
  form.category = item.category
  form.allergenCode = item.allergenCode ?? ''
  form.customName = item.allergenCode ? '' : item.allergenName
  form.allergyType = item.allergyType
  form.reactions = [...item.reactions]
  form.reactionNote = item.reactionNote ?? ''
  form.severity = item.severity
  form.clinicalStatus = item.clinicalStatus
  form.onsetText = item.onsetText ?? ''
  form.note = item.note ?? ''
  showForm.value = true
}

function pickCategory(c: Category) {
  form.category = c
  form.allergenCode = ''
  form.customName = ''
}

function pickAllergen(code: string) {
  if (form.allergenCode === code) {
    form.allergenCode = ''
    return
  }
  form.allergenCode = code
  form.customName = ''
  const hit = catalog.value?.allergens.find((a) => a.code === code)
  if (hit) form.allergyType = hit.defaultType
}

function onCustomInput() {
  if (form.customName.trim()) form.allergenCode = ''
}

function toggleReaction(code: string) {
  const i = form.reactions.indexOf(code)
  if (i >= 0) form.reactions.splice(i, 1)
  else form.reactions.push(code)
}

async function save() {
  const hit = catalog.value?.allergens.find((a) => a.code === form.allergenCode)
  const name = hit ? hit.label : form.customName.trim()
  if (!name) {
    showToast('请选择或填写过敏原')
    return
  }
  saving.value = true
  try {
    const body = JSON.stringify({
      category: form.category,
      allergenCode: hit ? hit.code : null,
      allergenName: name,
      allergyType: form.allergyType,
      reactions: form.reactions,
      reactionNote: form.reactionNote.trim() || null,
      severity: form.severity,
      clinicalStatus: form.clinicalStatus,
      onsetText: form.onsetText.trim() || null,
      note: form.note.trim() || null,
    })
    if (editingId.value) {
      await api(`/api/c/v1/me/allergies/${editingId.value}`, { method: 'PUT', body })
    } else {
      await api('/api/c/v1/me/allergies', { method: 'POST', body })
    }
    showToast({ type: 'success', message: '已保存，待健管师确认' })
    showForm.value = false
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function remove() {
  if (!editingId.value) return
  try {
    await showConfirmDialog({ title: '删除过敏记录', message: '确定删除这条过敏记录吗？' })
  } catch {
    return
  }
  try {
    await api(`/api/c/v1/me/allergies/${editingId.value}`, { method: 'DELETE' })
    showToast({ type: 'success', message: '已删除' })
    showForm.value = false
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}

async function setStatus(status: 'NKA' | 'UNKNOWN') {
  try {
    const res = await api<{ data: Summary }>('/api/c/v1/me/allergies/status', {
      method: 'PUT',
      body: JSON.stringify({ status }),
    })
    summary.value = res.data
    showToast({ type: 'success', message: status === 'NKA' ? '已记录：没有过敏' : '已记录：不确定' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  }
}

function detail(item: AllergyItem) {
  const parts: string[] = []
  if (item.reactionLabels.length) parts.push(item.reactionLabels.join('、'))
  if (item.reactionNote) parts.push(item.reactionNote)
  if (item.severity !== 'UNKNOWN') parts.push(item.severityLabel)
  if (item.onsetText) parts.push(`首次 ${item.onsetText}`)
  return parts.join(' · ') || '未填写反应'
}

function verifyBadge(item: AllergyItem) {
  if (item.verification === 'CONFIRMED') return { text: '医护已确认', cls: 'ok' }
  if (item.verification === 'REFUTED') return { text: '医护已排除', cls: 'muted' }
  return { text: '待医护确认', cls: 'warn' }
}
</script>

<template>
  <div class="page">
    <van-nav-bar title="过敏史" left-arrow fixed placeholder :border="false" @click-left="router.back()" />

    <van-loading v-if="loading" vertical class="loading">加载中</van-loading>

    <template v-else-if="summary">
      <section class="card intro">
        <p>过敏信息会用于用药、饮食记录和健康助手的安全提醒。你填写的内容需要健管师确认，确认后如需修改请联系健管师。</p>
      </section>

      <section v-if="!hasEffective" class="card">
        <div class="status-title">你是否有药物、食物或其他过敏？</div>
        <div class="chips">
          <button type="button" class="chip" :class="{ active: summary.status === 'NKA' }" @click="setStatus('NKA')">
            我没有过敏
          </button>
          <button
            type="button"
            class="chip"
            :class="{ active: summary.status === 'UNKNOWN' }"
            @click="setStatus('UNKNOWN')"
          >
            不确定
          </button>
          <button type="button" class="chip" @click="openAdd">我有过敏</button>
        </div>
      </section>

      <div class="toolbar">
        <van-button round block type="primary" icon="plus" @click="openAdd">添加过敏</van-button>
      </div>

      <section
        v-for="item in items"
        :key="item.id"
        class="card item"
        :class="{ risk: item.highRisk && item.effective, inactive: !item.effective }"
        @click="openEdit(item)"
      >
        <div class="item-head">
          <strong>{{ item.allergenName }}</strong>
          <span class="badge">{{ item.categoryLabel }}</span>
          <span v-if="item.allergyType === 'INTOLERANCE'" class="badge muted">不耐受</span>
          <span v-if="item.highRisk" class="badge danger">高危</span>
          <span v-if="item.clinicalStatus === 'RESOLVED'" class="badge muted">已消失</span>
          <span class="badge" :class="verifyBadge(item).cls">{{ verifyBadge(item).text }}</span>
        </div>
        <p class="line">{{ detail(item) }}</p>
        <p v-if="!item.editableByPatient" class="meta">已由医护确认，如需修改请联系健管师</p>
      </section>
    </template>

    <van-popup v-model:show="showForm" position="bottom" round :style="{ maxHeight: '90%' }" teleport="body">
      <div class="sheet">
        <div class="sheet-head">
          <strong>{{ editingId ? '编辑过敏' : '添加过敏' }}</strong>
          <button type="button" class="sheet-close" @click="showForm = false">关闭</button>
        </div>
        <div class="sheet-body">
          <div class="block">
            <div class="label required">类别</div>
            <div class="chips">
              <button
                v-for="c in catalog?.categories ?? []"
                :key="c.code"
                type="button"
                class="chip"
                :class="{ active: form.category === c.code }"
                @click="pickCategory(c.code)"
              >
                {{ c.label }}
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label required">过敏原</div>
            <div v-if="commonAllergens.length" class="chips">
              <button
                v-for="a in commonAllergens"
                :key="a.code"
                type="button"
                class="chip"
                :class="{ active: form.allergenCode === a.code }"
                @click="pickAllergen(a.code)"
              >
                {{ a.label }}
              </button>
            </div>
            <van-field
              v-model="form.customName"
              maxlength="64"
              clearable
              placeholder="不在上面？直接输入，如：阿莫西林、菠萝"
              @update:model-value="onCustomInput"
            />
          </div>

          <div class="block">
            <div class="label">类型</div>
            <div class="chips">
              <button
                type="button"
                class="chip"
                :class="{ active: form.allergyType === 'ALLERGY' }"
                @click="form.allergyType = 'ALLERGY'"
              >
                过敏
              </button>
              <button
                type="button"
                class="chip"
                :class="{ active: form.allergyType === 'INTOLERANCE' }"
                @click="form.allergyType = 'INTOLERANCE'"
              >
                不耐受（如喝牛奶腹泻）
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">出现过什么反应（可多选）</div>
            <div class="chips">
              <button
                v-for="r in catalog?.reactions ?? []"
                :key="r.code"
                type="button"
                class="chip"
                :class="{ active: form.reactions.includes(r.code), risk: r.highRisk }"
                @click="toggleReaction(r.code)"
              >
                {{ r.label }}
              </button>
            </div>
            <van-field v-model="form.reactionNote" maxlength="128" placeholder="补充描述（选填）" />
          </div>

          <div class="block">
            <div class="label">严重程度</div>
            <div class="chips">
              <button
                v-for="s in catalog?.severities ?? []"
                :key="s.code"
                type="button"
                class="chip"
                :class="{ active: form.severity === s.code }"
                @click="form.severity = s.code"
              >
                {{ s.label }}
              </button>
            </div>
            <p v-if="willBeHighRisk" class="risk-hint">将标记为高危过敏，就诊时请主动告知医生</p>
          </div>

          <div class="block">
            <div class="label">首次发生</div>
            <van-field v-model="form.onsetText" maxlength="32" placeholder="如：2015 年、小时候（选填）" />
          </div>

          <div v-if="editingId" class="block">
            <div class="label">现在还过敏吗</div>
            <div class="chips">
              <button
                type="button"
                class="chip"
                :class="{ active: form.clinicalStatus === 'ACTIVE' }"
                @click="form.clinicalStatus = 'ACTIVE'"
              >
                仍过敏
              </button>
              <button
                type="button"
                class="chip"
                :class="{ active: form.clinicalStatus === 'RESOLVED' }"
                @click="form.clinicalStatus = 'RESOLVED'"
              >
                已不过敏
              </button>
            </div>
          </div>

          <div class="block">
            <div class="label">备注</div>
            <van-field v-model="form.note" rows="2" autosize type="textarea" maxlength="200" placeholder="选填" />
          </div>
        </div>
        <div class="sheet-footer">
          <van-button v-if="editingId" round plain class="del-btn" @click="remove">删除</van-button>
          <van-button round block type="primary" :loading="saving" @click="save">保存</van-button>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--hx-bg);
  padding-bottom: 28px;
}
.loading {
  padding: 48px 0;
}
.card {
  margin: 12px 16px 0;
  padding: 14px 16px;
  border-radius: var(--hx-r-lg);
  background: var(--hx-card);
  box-shadow: var(--hx-elev-2);
}
.intro p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--hx-muted);
}
.status-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--hx-text);
}
.toolbar {
  margin: 14px 16px 0;
}
.toolbar :deep(.van-button) {
  height: 44px;
  font-weight: 700;
}
.item.risk {
  background: var(--hx-danger-bg);
  border: 1px solid #fecaca;
}
.item.inactive {
  opacity: 0.6;
}
.item-head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.item-head strong {
  font-size: 16px;
  color: var(--hx-text);
}
.badge {
  font-size: 11px;
  font-weight: 600;
  color: var(--hx-teal);
  background: var(--hx-teal-light);
  border-radius: 999px;
  padding: 2px 8px;
}
.badge.ok {
  color: var(--hx-teal-700);
  background: rgba(11, 122, 102, 0.12);
}
.badge.warn {
  color: var(--hx-warn-text);
  background: var(--hx-warn-bg);
}
.badge.danger {
  color: #fff;
  background: var(--hx-danger);
}
.badge.muted {
  color: var(--hx-muted);
  background: var(--hx-n-100);
}
.line {
  margin: 8px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-text);
}
.meta {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.sheet {
  display: flex;
  flex-direction: column;
  max-height: 90vh;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.sheet-close {
  border: 0;
  background: transparent;
  color: var(--hx-muted);
  font-size: 13px;
}
.sheet-body {
  flex: 1;
  overflow: auto;
  padding: 4px 8px 12px;
}
.sheet-footer {
  display: flex;
  gap: 10px;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: #fff;
}
.del-btn {
  flex: 0 0 88px;
  color: var(--hx-danger);
  border-color: #fecaca;
}
.block {
  padding: 10px 8px;
}
.label {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.label.required::after {
  content: ' *';
  color: var(--hx-danger);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  border: 1px solid var(--hx-n-200);
  background: var(--hx-n-50);
  color: var(--hx-text);
  font-size: 13px;
  padding: 8px 12px;
  border-radius: 999px;
  line-height: 1.2;
}
.chip.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 600;
}
.chip.risk {
  color: var(--hx-danger);
}
.chip.risk.active {
  background: var(--hx-danger-bg);
  color: var(--hx-danger-text);
}
.risk-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-danger);
}
.block :deep(.van-field) {
  margin-top: 8px;
  padding: 8px 0;
  background: transparent;
}
</style>
