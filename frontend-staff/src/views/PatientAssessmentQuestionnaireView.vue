<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../shared/http'
import AssessmentQuestionForm from '../shared/AssessmentQuestionForm.vue'
import { formatAssessTime, type QuestionnaireSubmitResult, type QuestionnaireView } from '../shared/assessment'

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))
const focusField = computed(() => String(route.query.field || ''))

const loading = ref(false)
const submitting = ref(false)
const view = ref<QuestionnaireView | null>(null)
const showAll = ref(false)
const formRef = ref<InstanceType<typeof AssessmentQuestionForm> | null>(null)

const touchedCount = computed(() => formRef.value?.touchedCount ?? 0)

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: QuestionnaireView }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/questionnaire`,
    )
    view.value = res.data
    showAll.value = res.data.missingCount === 0
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载问卷失败')
  } finally {
    loading.value = false
  }
}

async function submit(confirmOverrides: string[] = []) {
  const form = formRef.value
  if (!form) return
  const { answers, error } = form.payload()
  if (error) {
    showToast(error)
    return
  }
  if (!Object.keys(answers).length) {
    showToast('请至少回答一题')
    return
  }
  submitting.value = true
  try {
    const res = await api<{ data: QuestionnaireSubmitResult }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/questionnaire`,
      { method: 'POST', body: JSON.stringify({ answers, confirmOverrides }) },
    )
    if (res.data.status === 'CONFLICT') {
      submitting.value = false
      const rows = res.data.conflicts
        .map((c) => `· ${c.label}：档案「${c.currentText || '—'}」→ 问卷「${c.newText || '—'}」`)
        .join('\n')
      try {
        await showConfirmDialog({
          title: '信息不一致',
          message: `以下信息与档案记录不一致，确认后将以问卷为准更新档案：\n${rows}`,
          messageAlign: 'left',
          confirmButtonText: '以问卷为准',
          cancelButtonText: '返回修改',
        })
      } catch {
        return
      }
      await submit([...confirmOverrides, ...res.data.conflicts.map((c) => c.field)])
      return
    }
    showToast('已保存并重新评估')
    router.back()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '提交失败')
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="评估问卷" left-arrow @click-left="router.back()" />
    <van-loading v-if="loading" class="hx-loading" vertical>加载中</van-loading>
    <div v-else-if="view" class="wrap">
      <div class="head hx-surface">
        <div>
          共 {{ view.totalCount }} 题，缺失 <b>{{ view.missingCount }}</b> 题
          <p v-if="view.lastSubmittedAt" class="hx-muted">上次提交 {{ formatAssessTime(view.lastSubmittedAt) }}</p>
        </div>
        <van-switch v-model="showAll" size="18px" />
        <span class="hx-muted switch-label">查看全部</span>
      </div>
      <div v-if="view.hints.length" class="hints hx-surface">
        <p class="hints-title">以下数据需在对应入口录入，问卷不收集</p>
        <p v-for="h in view.hints" :key="h.field" class="hx-muted">· {{ h.label }}：{{ h.hint }}</p>
      </div>
      <AssessmentQuestionForm ref="formRef" :view="view" :show-all="showAll" :focus-field="focusField" />
      <div class="submit-bar">
        <van-button
          type="primary"
          block
          round
          :loading="submitting"
          :disabled="!touchedCount"
          @click="submit()"
        >
          保存并重新评估{{ touchedCount ? `（${touchedCount} 项）` : '' }}
        </van-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wrap {
  padding: 12px 14px 90px;
}
.head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  font-size: 13px;
  color: var(--hx-text);
}
.head > div {
  flex: 1;
}
.head p {
  margin: 4px 0 0;
  font-size: 12px;
}
.switch-label {
  font-size: 12px;
}
.hints {
  margin-top: 10px;
  padding: 10px 14px;
  font-size: 12px;
  line-height: 1.6;
}
.hints p {
  margin: 0;
}
.hints-title {
  color: var(--hx-text);
  font-weight: 600;
}
.submit-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: rgba(255, 255, 255, 0.95);
  border-top: 1px solid var(--hx-line);
}
</style>
