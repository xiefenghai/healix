<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api } from '../shared/http'
import {
  formatAssessTime,
  sourceLabel,
  tierTone,
  toneChip,
  type AssessmentGroup,
  type AssessmentMissing,
  type AssessmentOverview,
  type AssessmentRuleSheet,
  type AssessmentSnapshot,
  type QuestionnaireActivity,
} from '../shared/assessment'

const route = useRoute()
const router = useRouter()
const peopleId = computed(() => String(route.params.peopleId || ''))

const loading = ref(false)
const running = ref(false)
const sending = ref(false)
const overview = ref<AssessmentOverview | null>(null)
const activities = ref<QuestionnaireActivity[]>([])
const expanded = ref('')

const rulesOpen = ref(false)
const rules = ref<AssessmentRuleSheet[]>([])
const rulesEngine = ref('')
const ruleSheet = computed(
  () => rules.value.find((r) => r.engineCode === rulesEngine.value) || rules.value[0] || null,
)

const historyOpen = ref(false)
const historyItems = ref<AssessmentSnapshot[]>([])
const historyDetail = ref<AssessmentSnapshot | null>(null)

const riskGroups = computed(() => (overview.value?.groups || []).filter((g) => g.category === 'RISK'))
const severityGroups = computed(() =>
  (overview.value?.groups || []).filter((g) => g.category === 'SEVERITY'),
)
const pendingActivity = computed(
  () => activities.value.find((a) => a.kind === 'REQUEST' && a.status === 'PENDING') || null,
)

const METRIC_FIELDS = new Set(['BLOOD_PRESSURE', 'HEART_RATE', 'GLUCOSE'])
const LAB_FIELDS = new Set(['HBA1C', 'LIPIDS', 'URIC_ACID', 'RENAL', 'LIVER'])

async function load() {
  loading.value = true
  try {
    const [ov, act] = await Promise.all([
      api<{ data: AssessmentOverview }>(`/api/b/v1/patients/${peopleId.value}/assessments`),
      api<{ data: QuestionnaireActivity[] }>(
        `/api/b/v1/patients/${peopleId.value}/assessments/questionnaire-activity?limit=20`,
      ).catch(() => ({ data: [] as QuestionnaireActivity[] })),
    ])
    overview.value = ov.data
    activities.value = act.data || []
    if (!expanded.value) {
      const alert = [...severityGroups.value, ...riskGroups.value].find(
        (g) => g.latest?.suspicion || g.latest?.tone === 'danger',
      )
      expanded.value = alert?.diseaseCode || ''
    }
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function runManual() {
  running.value = true
  try {
    const res = await api<{ data: AssessmentOverview }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/run`,
      { method: 'POST' },
    )
    overview.value = res.data
    showToast('已重新评估')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '评估失败')
  } finally {
    running.value = false
  }
}

async function sendRequest() {
  const resend = !!pendingActivity.value
  try {
    await showConfirmDialog({
      title: resend ? '重新发送问卷' : '发送评估问卷',
      message: resend
        ? '患者还有一份未填写的评估问卷，重新发送会作废旧问卷并重新计时 7 天。'
        : '将向患者推送评估问卷，请其补充缺失的健康信息。7 天内有效，48 小时未填会再提醒一次。',
      confirmButtonText: '发送',
    })
  } catch {
    return
  }
  sending.value = true
  try {
    await api(`/api/b/v1/patients/${peopleId.value}/assessments/questionnaire-requests`, {
      method: 'POST',
      body: JSON.stringify({}),
    })
    showToast('已发送给患者')
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function createArchive(g: AssessmentGroup) {
  const disease = g.latest?.suspicion?.diseaseLabel || g.diseaseLabel
  try {
    await showConfirmDialog({
      title: `建立${disease}档案`,
      message: `确认患者已确诊${disease}？建档后将切换为「${disease}严重等级分层」评估，并关闭疑似患病任务。`,
      confirmButtonText: '确认建档',
    })
  } catch {
    return
  }
  try {
    const res = await api<{ data: AssessmentOverview }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/disease-archives/${g.diseaseCode}`,
      { method: 'POST' },
    )
    overview.value = res.data
    showToast(`已建立${disease}档案`)
  } catch (e) {
    showToast(e instanceof Error ? e.message : '建档失败')
  }
}

function openQuestionnaire(field = '') {
  router.push({
    path: `/patients/${peopleId.value}/assessment-questionnaire`,
    query: field ? { field } : {},
  })
}

function fillMissing(m: AssessmentMissing) {
  if (m.questionnaire) openQuestionnaire(m.field)
  else if (METRIC_FIELDS.has(m.field)) router.push(`/patients/${peopleId.value}/metrics`)
  else if (LAB_FIELDS.has(m.field)) router.push(`/patients/${peopleId.value}/labs`)
  else if (m.fillHint) showToast(m.fillHint)
}

async function openRules(engineCode: string) {
  rulesEngine.value = engineCode
  rulesOpen.value = true
  if (rules.value.length) return
  try {
    const res = await api<{ data: AssessmentRuleSheet[] }>('/api/b/v1/assessment-rules')
    rules.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载规则失败')
  }
}

async function openHistory(engineCode: string) {
  historyOpen.value = true
  historyItems.value = []
  try {
    const q = new URLSearchParams({ page: '1', pageSize: '30', engineCode })
    const res = await api<{ data: { items: AssessmentSnapshot[] } }>(
      `/api/b/v1/patients/${peopleId.value}/assessments/history?${q}`,
    )
    historyItems.value = res.data.items || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载历史失败')
  }
}

function cardStatus(g: AssessmentGroup): { text: string; chip: string } {
  if (!g.applicable) return { text: '不适用', chip: '' }
  const s = g.latest
  if (!s) return { text: '未评估', chip: '' }
  if (s.status !== 'COMPLETE' || !s.levelLabel) return { text: '待补充数据', chip: '' }
  return { text: s.levelLabel, chip: toneChip(s.tone) }
}

function activityChip(a: QuestionnaireActivity): { text: string; chip: string } {
  if (a.kind === 'SUBMISSION') return { text: '已提交', chip: 'ok' }
  if (a.status === 'PENDING') return { text: '待填写', chip: 'warn' }
  if (a.status === 'EXPIRED') return { text: '已过期', chip: 'danger' }
  if (a.status === 'SUBMITTED') return { text: '已填写', chip: 'ok' }
  return { text: a.status || '', chip: '' }
}

function activityTime(a: QuestionnaireActivity) {
  return formatAssessTime(a.submittedAt || a.createdAt)
}

function toggle(code: string) {
  expanded.value = expanded.value === code ? '' : code
}

onMounted(load)
</script>

<template>
  <div class="hx-page">
    <van-nav-bar title="疾病评估" left-arrow @click-left="router.back()">
      <template #right>
        <button type="button" class="nav-link" :disabled="running" @click="runManual">
          {{ running ? '评估中' : '重算' }}
        </button>
      </template>
    </van-nav-bar>

    <van-loading v-if="loading && !overview" class="hx-loading" vertical>加载中</van-loading>

    <div v-else class="page-body">
      <p class="lead hx-muted">
        未确诊看「患病风险」，已确诊看「严重分层」。结果仅供健康管理参考，患者端不展示。
      </p>

      <!-- 患病风险评估 -->
      <section class="sec sec-risk">
        <header class="sec-head">
          <div class="sec-badge risk">风险</div>
          <div>
            <h2>患病风险评估</h2>
            <p>尚未确诊的病种：评估是否易患 / 高危人群</p>
          </div>
        </header>

        <div v-if="!riskGroups.length" class="empty hx-surface">当前没有适用的患病风险评估</div>
        <article
          v-for="g in riskGroups"
          :key="'r-' + g.diseaseCode"
          class="card hx-surface"
          :class="{ open: expanded === g.diseaseCode, muted: !g.applicable }"
        >
          <button type="button" class="card-top" @click="toggle(g.diseaseCode)">
            <div class="card-titles">
              <strong>{{ g.diseaseLabel }}</strong>
              <span class="engine">{{ g.engineLabel }}</span>
            </div>
            <div class="chips">
              <span v-if="g.latest?.suspicion" class="hx-chip danger">疑似已患病</span>
              <span class="hx-chip" :class="cardStatus(g).chip">{{ cardStatus(g).text }}</span>
              <van-icon :name="expanded === g.diseaseCode ? 'arrow-up' : 'arrow-down'" />
            </div>
          </button>

          <p v-if="!g.applicable" class="sub hx-muted">{{ g.notApplicableReason }}</p>
          <p v-else-if="g.latest?.advice && expanded !== g.diseaseCode" class="advice clamp">
            {{ g.latest.advice }}
          </p>

          <template v-if="expanded === g.diseaseCode && g.applicable && g.latest">
            <div v-if="g.latest.suspicion" class="suspect">
              <p>{{ g.latest.suspicion.reason }}</p>
              <van-button size="small" type="danger" plain round @click="createArchive(g)">
                已确诊，建立{{ g.latest.suspicion.diseaseLabel }}档案
              </van-button>
            </div>

            <div v-if="g.latest.missing.length" class="block">
              <div class="block-title">缺少数据，补齐后自动重新评估</div>
              <div v-for="m in g.latest.missing" :key="m.field" class="row" @click="fillMissing(m)">
                <span>{{ m.label }}</span>
                <em class="link">{{ m.questionnaire ? '问卷补充' : '去录入' }}</em>
              </div>
            </div>

            <div v-if="g.latest.dimensions.length" class="dims">
              <div v-for="d in g.latest.dimensions" :key="d.code" class="dim" :class="tierTone(d.tier)">
                <span class="hx-muted">{{ d.label }}</span>
                <b>{{ d.value || '—' }}</b>
              </div>
            </div>

            <div v-if="g.latest.items.length" class="block">
              <div class="block-title">
                评估数据
                <span v-if="g.latest.score != null" class="hx-muted"> · 合计 {{ g.latest.score }} 分</span>
              </div>
              <div v-for="it in g.latest.items" :key="it.code" class="row">
                <span>{{ it.label }}</span>
                <em>
                  {{ it.value || '—'
                  }}<template v-if="it.points != null"> · {{ it.points }}分</template>
                </em>
              </div>
            </div>

            <div
              v-for="fg in g.latest.factorGroups.filter((x) => x.hits.length || x.unchecked.length)"
              :key="fg.code"
              class="block"
            >
              <div class="block-title">
                {{ fg.title }}<span class="hx-muted"> · 命中 {{ fg.hits.length }} 项</span>
              </div>
              <div v-for="h in fg.hits" :key="h.code" class="row">
                <span>{{ h.label }}<template v-if="h.detail">（{{ h.detail }}）</template></span>
                <em v-if="h.source" class="hx-muted">{{ sourceLabel(h.source) }}</em>
              </div>
              <p v-if="fg.unchecked.length" class="unchecked hx-muted">
                未核实：
                <span
                  v-for="u in fg.unchecked"
                  :key="u.field + u.label"
                  :class="{ link: u.questionnaire }"
                  @click="u.questionnaire && openQuestionnaire(u.field)"
                  >{{ u.label }}</span
                >
              </p>
            </div>

            <div v-if="g.latest.comparisons.length" class="block">
              <div class="block-title">与档案比对</div>
              <div v-for="c in g.latest.comparisons" :key="c.label" class="row">
                <span>{{ c.label }}</span>
                <em :class="{ warn: !c.consistent }">
                  档案 {{ c.value || '—' }} / 计算 {{ c.computed || '—' }}
                </em>
              </div>
            </div>

            <ul v-if="g.latest.notes.length" class="notes hx-muted">
              <li v-for="n in g.latest.notes" :key="n">{{ n }}</li>
            </ul>

            <p class="meta hx-muted">
              <span v-if="g.latest.provisional" class="hx-chip warn">暂定口径</span>
              {{ g.latest.triggerLabel || '' }} · {{ formatAssessTime(g.latest.assessedAt) }}
            </p>
            <div class="links">
              <span class="link" @click="openRules(g.engineCode)">规则说明</span>
              <span class="link" @click="openHistory(g.engineCode)">历史记录</span>
            </div>
          </template>
        </article>
      </section>

      <!-- 疾病严重等级分层 -->
      <section class="sec sec-sev">
        <header class="sec-head">
          <div class="sec-badge sev">分层</div>
          <div>
            <h2>疾病严重等级分层</h2>
            <p>已确诊的病种：评估当前管理分层与关注重点</p>
          </div>
        </header>

        <div v-if="!severityGroups.length" class="empty hx-surface">
          暂无已确诊病种，建立病种档案后会出现在这里
        </div>
        <article
          v-for="g in severityGroups"
          :key="'s-' + g.diseaseCode"
          class="card hx-surface sev-card"
          :class="{ open: expanded === g.diseaseCode, muted: !g.applicable }"
        >
          <button type="button" class="card-top" @click="toggle(g.diseaseCode)">
            <div class="card-titles">
              <strong>{{ g.diseaseLabel }}</strong>
              <span class="engine">
                {{ g.engineLabel }}
                <template v-if="g.diagnosedReason"> · {{ g.diagnosedReason }}</template>
              </span>
            </div>
            <div class="chips">
              <span class="hx-chip" :class="cardStatus(g).chip">{{ cardStatus(g).text }}</span>
              <van-icon :name="expanded === g.diseaseCode ? 'arrow-up' : 'arrow-down'" />
            </div>
          </button>

          <p v-if="!g.applicable" class="sub hx-muted">{{ g.notApplicableReason }}</p>
          <p v-else-if="g.latest?.advice && expanded !== g.diseaseCode" class="advice clamp">
            {{ g.latest.advice }}
          </p>

          <template v-if="expanded === g.diseaseCode && g.applicable && g.latest">
            <div v-if="g.latest.missing.length" class="block">
              <div class="block-title">缺少数据，补齐后自动重新评估</div>
              <div v-for="m in g.latest.missing" :key="m.field" class="row" @click="fillMissing(m)">
                <span>{{ m.label }}</span>
                <em class="link">{{ m.questionnaire ? '问卷补充' : '去录入' }}</em>
              </div>
            </div>

            <div v-if="g.latest.dimensions.length" class="dims">
              <div v-for="d in g.latest.dimensions" :key="d.code" class="dim" :class="tierTone(d.tier)">
                <span class="hx-muted">{{ d.label }}</span>
                <b>{{ d.value || '—' }}</b>
              </div>
            </div>

            <div v-if="g.latest.items.length" class="block">
              <div class="block-title">
                评估数据
                <span v-if="g.latest.score != null" class="hx-muted"> · 合计 {{ g.latest.score }} 分</span>
              </div>
              <div v-for="it in g.latest.items" :key="it.code" class="row">
                <span>{{ it.label }}</span>
                <em>
                  {{ it.value || '—'
                  }}<template v-if="it.points != null"> · {{ it.points }}分</template>
                </em>
              </div>
            </div>

            <div
              v-for="fg in g.latest.factorGroups.filter((x) => x.hits.length || x.unchecked.length)"
              :key="fg.code"
              class="block"
            >
              <div class="block-title">
                {{ fg.title }}<span class="hx-muted"> · 命中 {{ fg.hits.length }} 项</span>
              </div>
              <div v-for="h in fg.hits" :key="h.code" class="row">
                <span>{{ h.label }}<template v-if="h.detail">（{{ h.detail }}）</template></span>
                <em v-if="h.source" class="hx-muted">{{ sourceLabel(h.source) }}</em>
              </div>
              <p v-if="fg.unchecked.length" class="unchecked hx-muted">
                未核实：
                <span
                  v-for="u in fg.unchecked"
                  :key="u.field + u.label"
                  :class="{ link: u.questionnaire }"
                  @click="u.questionnaire && openQuestionnaire(u.field)"
                  >{{ u.label }}</span
                >
              </p>
            </div>

            <div v-if="g.latest.comparisons.length" class="block">
              <div class="block-title">与档案比对</div>
              <div v-for="c in g.latest.comparisons" :key="c.label" class="row">
                <span>{{ c.label }}</span>
                <em :class="{ warn: !c.consistent }">
                  档案 {{ c.value || '—' }} / 计算 {{ c.computed || '—' }}
                </em>
              </div>
            </div>

            <ul v-if="g.latest.notes.length" class="notes hx-muted">
              <li v-for="n in g.latest.notes" :key="n">{{ n }}</li>
            </ul>

            <p class="meta hx-muted">
              <span v-if="g.latest.provisional" class="hx-chip warn">暂定口径</span>
              {{ g.latest.triggerLabel || '' }} · {{ formatAssessTime(g.latest.assessedAt) }}
            </p>
            <div class="links">
              <span class="link" @click="openRules(g.engineCode)">规则说明</span>
              <span class="link" @click="openHistory(g.engineCode)">历史记录</span>
            </div>
          </template>
        </article>
      </section>

      <!-- 评估问卷 -->
      <section class="sec sec-q">
        <header class="sec-head">
          <div class="sec-badge q">问卷</div>
          <div>
            <h2>评估问卷</h2>
            <p>补齐评估缺项；可自行填写，也可发给患者</p>
          </div>
        </header>

        <div class="q-actions hx-surface">
          <van-button size="small" round type="primary" @click="openQuestionnaire()">
            填写问卷
          </van-button>
          <van-button size="small" round plain :loading="sending" @click="sendRequest">
            {{ pendingActivity ? '重新发送给患者' : '发给患者' }}
          </van-button>
        </div>

        <div v-if="!activities.length" class="empty hx-surface">暂无问卷记录</div>
        <ul v-else class="q-list hx-surface">
          <li v-for="a in activities" :key="a.kind + a.id" class="q-item">
            <div class="q-main">
              <strong>{{ a.title }}</strong>
              <p class="hx-muted">{{ a.summary }}</p>
              <p class="q-time hx-muted">{{ activityTime(a) }}</p>
            </div>
            <span class="status-chip" :class="activityChip(a).chip">{{ activityChip(a).text }}</span>
          </li>
        </ul>
      </section>

      <p v-if="overview?.disclaimer" class="foot hx-muted">{{ overview.disclaimer }}</p>
    </div>

    <van-popup v-model:show="rulesOpen" position="bottom" round closeable :style="{ height: '80%' }">
      <div v-if="ruleSheet" class="sheet">
        <h3>{{ ruleSheet.engineLabel }}</h3>
        <p class="hx-muted">
          {{ ruleSheet.categoryLabel }} · {{ ruleSheet.guidelineName || '' }} · 规则包
          {{ ruleSheet.rulePackVersion }}
        </p>
        <p v-if="ruleSheet.provisional" class="banner warn">暂定口径：待医学团队校准</p>
        <p v-if="ruleSheet.sheet.summary" class="advice">{{ ruleSheet.sheet.summary }}</p>
        <div class="block">
          <div class="block-title">分级</div>
          <div v-for="l in ruleSheet.levels" :key="l.code" class="row">
            <span>{{ l.label }}</span>
            <em v-if="l.tierLabel" class="hx-muted">{{ l.tierLabel }}</em>
          </div>
        </div>
        <div v-for="sec in ruleSheet.sheet.sections" :key="sec.title" class="block">
          <div class="block-title">{{ sec.title }}</div>
          <div v-if="sec.rows?.length" class="table-wrap">
            <table>
              <thead v-if="sec.headers?.length">
                <tr>
                  <th v-for="h in sec.headers" :key="h">{{ h }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(r, i) in sec.rows" :key="i">
                  <td v-for="(c, j) in r" :key="j">{{ c }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <ul v-if="sec.bullets?.length" class="notes">
            <li v-for="b in sec.bullets" :key="b">{{ b }}</li>
          </ul>
        </div>
        <ul v-if="ruleSheet.sheet.notes?.length" class="notes hx-muted">
          <li v-for="n in ruleSheet.sheet.notes" :key="n">{{ n }}</li>
        </ul>
      </div>
      <van-loading v-else class="hx-loading" vertical>加载中</van-loading>
    </van-popup>

    <van-popup v-model:show="historyOpen" position="bottom" round closeable :style="{ height: '70%' }">
      <div class="sheet">
        <h3>评估历史</h3>
        <van-empty v-if="!historyItems.length" description="暂无记录" />
        <div
          v-for="h in historyItems"
          :key="h.id"
          class="row history"
          @click="historyDetail = historyDetail?.id === h.id ? null : h"
        >
          <span>{{ formatAssessTime(h.assessedAt) }} · {{ h.triggerLabel }}</span>
          <span class="hx-chip" :class="toneChip(h.tone)">{{ h.levelLabel || '数据不足' }}</span>
          <div v-if="historyDetail?.id === h.id" class="history-detail">
            <p v-if="h.advice" class="advice">{{ h.advice }}</p>
            <div v-for="it in h.items" :key="it.code" class="row">
              <span>{{ it.label }}</span>
              <em>
                {{ it.value || '—'
                }}<template v-if="it.points != null"> · {{ it.points }}分</template>
              </em>
            </div>
          </div>
        </div>
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.nav-link {
  border: none;
  background: transparent;
  color: var(--hx-teal);
  font-size: 14px;
  padding: 0 4px;
}
.page-body {
  padding: 8px 14px 36px;
}
.lead {
  margin: 0 2px 14px;
  font-size: 12px;
  line-height: 1.5;
}
.sec {
  margin-bottom: 18px;
}
.sec-head {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin: 0 2px 10px;
}
.sec-head h2 {
  margin: 0;
  font-size: 16px;
  color: var(--hx-ink);
}
.sec-head p {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  line-height: 1.45;
}
.sec-badge {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}
.sec-badge.risk {
  background: #e8f4ff;
  color: #1d5f9c;
}
.sec-badge.sev {
  background: #fff1e6;
  color: #b35a12;
}
.sec-badge.q {
  background: var(--hx-teal-light, #e8f6f6);
  color: var(--hx-teal-deep, #166f6f);
}
.sec-risk .card {
  border-left: 3px solid #5b8def;
}
.sec-sev .card {
  border-left: 3px solid #e89a1a;
}
.card {
  padding: 0;
  overflow: hidden;
  margin-bottom: 10px;
}
.card.muted {
  opacity: 0.78;
}
.card-top {
  width: 100%;
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
  padding: 14px;
  border: none;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.card-titles {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.card-titles strong {
  font-size: 15px;
  color: var(--hx-ink);
}
.engine {
  font-size: 12px;
  color: var(--hx-muted);
}
.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
  align-items: center;
  flex-shrink: 0;
}
.sub {
  margin: 0;
  padding: 0 14px 12px;
  font-size: 12px;
}
.advice {
  margin: 0;
  padding: 0 14px 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--hx-text);
}
.advice.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card.open .advice.clamp {
  display: none;
}
.suspect {
  margin: 0 14px 12px;
  padding: 10px 12px;
  border-radius: 12px;
  background: #fdecec;
  font-size: 13px;
  color: var(--hx-danger);
}
.suspect p {
  margin: 0 0 8px;
  line-height: 1.5;
}
.block {
  margin: 0 14px;
  border-top: 1px solid var(--hx-line);
  padding: 8px 0;
}
.block-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-ink);
  margin-bottom: 4px;
}
.row {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 6px 0;
  font-size: 12px;
  color: var(--hx-text);
}
.row em {
  font-style: normal;
  color: var(--hx-ink);
  font-weight: 600;
  text-align: right;
}
.row em.warn {
  color: #9a6508;
}
.dims {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  margin: 0 14px 8px;
}
.dim {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--hx-bg);
  font-size: 12px;
}
.dim b {
  font-size: 13px;
  color: var(--hx-ink);
}
.dim.danger {
  background: #fdecec;
}
.dim.warning {
  background: var(--hx-orange-soft);
}
.unchecked {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.7;
}
.unchecked span + span::before {
  content: '、';
  color: var(--hx-muted);
}
.link {
  color: var(--hx-teal);
  font-style: normal;
  cursor: pointer;
}
.notes {
  margin: 0 14px 10px;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.6;
}
.meta {
  margin: 0 14px 6px;
  font-size: 12px;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.links {
  display: flex;
  gap: 16px;
  margin: 0 14px 14px;
  font-size: 13px;
}
.empty {
  padding: 16px;
  font-size: 13px;
  color: var(--hx-muted);
  text-align: center;
}
.q-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding: 12px 14px;
  margin-bottom: 10px;
}
.q-list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
}
.q-item {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
  padding: 12px 14px;
  border-bottom: 1px solid var(--hx-line);
}
.q-item:last-child {
  border-bottom: none;
}
.q-main {
  min-width: 0;
  flex: 1;
}
.q-main strong {
  font-size: 14px;
  color: var(--hx-ink);
}
.q-main p {
  margin: 3px 0 0;
  font-size: 12px;
  line-height: 1.45;
}
.q-time {
  margin-top: 4px !important;
}
.status-chip {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  background: var(--hx-bg);
  color: var(--hx-muted);
}
.status-chip.warn {
  background: var(--hx-orange-soft);
  color: #9a6508;
}
.status-chip.danger {
  background: #fdecec;
  color: var(--hx-danger);
}
.status-chip.ok {
  background: var(--hx-teal-light, #e8f6f6);
  color: var(--hx-teal-deep, #166f6f);
}
.foot {
  margin: 8px 2px 0;
  font-size: 11px;
  line-height: 1.55;
}
.sheet {
  padding: 18px 16px 30px;
}
.sheet h3 {
  margin: 0 0 4px;
  font-size: 16px;
}
.sheet > p {
  margin: 4px 0;
  font-size: 12px;
}
.banner.warn {
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--hx-orange-soft);
  color: #9a6508;
}
.table-wrap {
  overflow-x: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
th,
td {
  border: 1px solid var(--hx-line);
  padding: 5px 6px;
  text-align: left;
  vertical-align: top;
}
th {
  background: var(--hx-bg);
}
.history {
  border-bottom: 1px solid var(--hx-line);
}
.history-detail {
  width: 100%;
}
</style>
