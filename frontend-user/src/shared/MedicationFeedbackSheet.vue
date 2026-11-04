<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { showToast } from 'vant'
import { api } from '../api/http'
import {
  MED_SEVERITY_LABELS,
  MED_STOP_REASONS,
  MED_SYMPTOMS,
  type FeedbackMode,
  type MedicationView,
} from './medication-labels'

const props = defineProps<{ show: boolean; mode: FeedbackMode; med: MedicationView | null }>()
const emit = defineEmits<{ 'update:show': [boolean]; done: [FeedbackMode] }>()

const current = ref<FeedbackMode>('ADVERSE')
const saving = ref(false)
const form = reactive({
  symptoms: [] as string[],
  severity: '',
  content: '',
  reason: '',
  note: '',
})

watch(
  () => props.show,
  (v) => {
    if (!v) return
    current.value = props.mode
    form.symptoms = []
    form.severity = ''
    form.content = ''
    form.reason = ''
    form.note = ''
  },
)

const title = computed(() => {
  const name = props.med?.drugName || ''
  if (current.value === 'STOP') return `停用 ${name}`
  if (current.value === 'ADJUST') return `申请调整 ${name}`
  return `${name} 用药不适`
})

function toggleSymptom(code: string) {
  form.symptoms = form.symptoms.includes(code) ? form.symptoms.filter((x) => x !== code) : [...form.symptoms, code]
}

async function submit() {
  if (!props.med) return
  const base = `/api/c/v1/me/medications/${props.med.id}`
  if (current.value === 'STOP') {
    if (!form.reason) return showToast('请选择停药原因')
  } else if (current.value === 'ADVERSE') {
    if (!form.symptoms.length) return showToast('请选择不适症状')
    if (!form.severity) return showToast('请选择严重程度')
  } else if (!form.content.trim()) {
    return showToast('请说明想调整什么')
  }
  saving.value = true
  try {
    if (current.value === 'STOP') {
      await api(`${base}/stop`, {
        method: 'POST',
        body: JSON.stringify({ reason: form.reason, note: form.note.trim() || null }),
      })
      showToast({ type: 'success', message: '已停用，提醒已停止' })
      emit('done', 'STOP')
      if (form.reason === 'SIDE_EFFECT') {
        current.value = 'ADVERSE'
        form.content = form.note
        return
      }
    } else {
      await api(`${base}/feedback`, {
        method: 'POST',
        body: JSON.stringify({
          kind: current.value,
          symptoms: current.value === 'ADVERSE' ? form.symptoms : null,
          severity: current.value === 'ADVERSE' ? form.severity : null,
          content: form.content.trim() || null,
        }),
      })
      showToast({ type: 'success', message: '已告知健管师' })
      emit('done', current.value)
    }
    emit('update:show', false)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '提交失败')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <van-popup
    :show="show"
    position="bottom"
    round
    :style="{ maxHeight: '88%' }"
    teleport="body"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div class="sheet">
      <div class="sheet-head">
        <strong>{{ title }}</strong>
        <button type="button" class="sheet-close" @click="emit('update:show', false)">关闭</button>
      </div>

      <div class="sheet-body">
        <template v-if="current === 'STOP'">
          <div class="block">
            <div class="label required">停药原因</div>
            <div class="chips">
              <button
                v-for="r in MED_STOP_REASONS"
                :key="r.code"
                type="button"
                class="chip"
                :class="{ active: form.reason === r.code }"
                @click="form.reason = r.code"
              >
                {{ r.label }}
              </button>
            </div>
            <p v-if="form.reason === 'SIDE_EFFECT'" class="hint">提交后请接着告诉我们出现了哪些不适。</p>
          </div>
          <div class="block">
            <div class="label">补充说明</div>
            <van-field v-model="form.note" rows="2" autosize type="textarea" maxlength="200" placeholder="选填" />
          </div>
          <p class="hint pad">停用后不再提醒，健管师会收到通知。</p>
        </template>

        <template v-else-if="current === 'ADVERSE'">
          <div class="block">
            <div class="label required">出现了哪些不适</div>
            <div class="chips">
              <button
                v-for="s in MED_SYMPTOMS"
                :key="s.code"
                type="button"
                class="chip"
                :class="{ active: form.symptoms.includes(s.code) }"
                @click="toggleSymptom(s.code)"
              >
                {{ s.label }}
              </button>
            </div>
          </div>
          <div class="block">
            <div class="label required">严重程度</div>
            <div class="chips">
              <button
                v-for="(label, code) in MED_SEVERITY_LABELS"
                :key="code"
                type="button"
                class="chip"
                :class="{ active: form.severity === code, danger: code === 'SEVERE' && form.severity === code }"
                @click="form.severity = code"
              >
                {{ label }}
              </button>
            </div>
            <p v-if="form.severity === 'SEVERE'" class="urgent">
              症状较重请尽快就医；出现呼吸困难、胸痛、意识不清等，请立即拨打 120。
            </p>
          </div>
          <div class="block">
            <div class="label">描述</div>
            <van-field
              v-model="form.content"
              rows="3"
              autosize
              type="textarea"
              maxlength="500"
              placeholder="选填：什么时候开始、持续多久"
            />
          </div>
        </template>

        <template v-else>
          <div class="block">
            <div class="label required">想调整什么</div>
            <van-field
              v-model="form.content"
              rows="4"
              autosize
              type="textarea"
              maxlength="500"
              placeholder="如：早上的药总忘，想改到晚上吃；剂量想咨询是否可以减少"
            />
          </div>
          <p class="hint pad">健管师会联系你或在这里回复，未经医生同意请不要自行改剂量。</p>
        </template>
      </div>

      <div class="sheet-footer">
        <van-button
          round
          block
          :type="current === 'STOP' ? 'danger' : 'primary'"
          :loading="saving"
          @click="submit"
        >
          {{ current === 'STOP' ? '确认停药' : '提交' }}
        </van-button>
      </div>
    </div>
  </van-popup>
</template>

<style scoped>
.sheet {
  display: flex;
  flex-direction: column;
  max-height: 88vh;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 16px 8px;
}
.sheet-head strong {
  font-size: 16px;
  color: var(--hx-text);
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
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: #fff;
}
.block {
  padding: 10px 8px;
}
.label {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--hx-muted);
  font-weight: 600;
}
.label.required::after {
  content: ' *';
  color: var(--hx-danger);
}
.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
}
.hint.pad {
  padding: 0 8px;
}
.urgent {
  margin: 10px 0 0;
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--hx-danger-bg);
  color: var(--hx-danger-text);
  font-size: 13px;
  font-weight: 600;
  line-height: 1.6;
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
}
.chip.active {
  border-color: transparent;
  background: var(--hx-teal-light);
  color: var(--hx-teal);
  font-weight: 600;
}
.chip.danger {
  background: var(--hx-danger-bg);
  color: var(--hx-danger-text);
}
.block :deep(.van-field) {
  padding: 8px 0;
  background: transparent;
}
:deep(.van-button--primary) {
  background: var(--hx-grad);
  border: 0;
}
</style>
