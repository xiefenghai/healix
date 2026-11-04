<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api } from '../api/http'
import { currentPatientIdFromToken } from '../shared/patient-context'

interface QuickAction {
  label: string
  path: string
}

interface ConfirmCard {
  id: string
  cardType: string
  summary: string
  metric: 'BP' | 'GLUCOSE' | 'WEIGHT'
  bpContext?: string
  mealContext?: string
  busy?: boolean
  closed?: boolean
}

interface Turn {
  role: 'user' | 'assistant'
  text: string
  actions?: QuickAction[]
  cards?: ConfirmCard[]
}

const GREETING =
  '你好，我是你的健康管家。说「血压 120/80」或「血糖 7.2」，确认后我才会记下来。'
const MAX_STORED_TURNS = 80

const router = useRouter()
const message = ref('')
const loading = ref(false)
const turns = ref<Turn[]>(loadTurns())
const listRef = ref<HTMLElement | null>(null)

function keepActions(userText: string, actions: QuickAction[]) {
  const askPlan = /方案|打卡|今日任务|今天要做|还有什么要做|还没做|未完成|提醒/.test(userText)
  const askMed = /吃药|服药|用药|药物|漏服|停药/.test(userText)
  return actions.filter((action) => {
    if (action.label === '去打卡') return askPlan
    if (action.label === '去服药打卡') return askMed || askPlan
    if (isCheckinLabel(action.label)) return false
    return true
  })
}

function isCheckinLabel(label?: string) {
  return label === '去打卡' || label === '去服药打卡' || label === '查看方案' || label === '管理用药'
}

function storageKey() {
  return `healix_c_agent_turns:${currentPatientIdFromToken() || 'anon'}`
}

function greetingTurns(): Turn[] {
  return [{ role: 'assistant', text: GREETING }]
}

function loadTurns(): Turn[] {
  try {
    const raw = sessionStorage.getItem(storageKey())
    if (!raw) return greetingTurns()
    const parsed = JSON.parse(raw) as Turn[]
    if (!Array.isArray(parsed) || parsed.length === 0) return greetingTurns()
    return parsed.slice(-MAX_STORED_TURNS).map((turn) => ({
      role: turn.role === 'user' ? 'user' : 'assistant',
      text: typeof turn.text === 'string' ? turn.text : '',
      actions: Array.isArray(turn.actions) ? turn.actions.filter((action) => !isCheckinLabel(action?.label)) : undefined,
      cards: Array.isArray(turn.cards)
        ? turn.cards.map((card) => ({ ...card, busy: false }))
        : undefined,
    }))
  } catch {
    return greetingTurns()
  }
}

function saveTurns() {
  try {
    const slim = turns.value.slice(-MAX_STORED_TURNS).map((turn) => ({
      role: turn.role,
      text: turn.text,
      actions: turn.actions,
      cards: turn.cards?.map((card) => ({
        id: card.id,
        cardType: card.cardType,
        summary: card.summary,
        metric: card.metric,
        bpContext: card.bpContext,
        mealContext: card.mealContext,
        closed: card.closed,
      })),
    }))
    sessionStorage.setItem(storageKey(), JSON.stringify(slim))
  } catch {
    // 存不下时仍保留当前页的对话
  }
}

watch(turns, saveTurns, { deep: true })
onMounted(() => {
  void nextTick(scrollToBottom)
})

const presets = ['今天还有什么要做', '最近血压怎么样']
const showPrompts = computed(() => turns.value.length === 1 && turns.value[0]?.role === 'assistant')
const hasHistory = computed(() => turns.value.some((turn) => turn.role === 'user'))

function scrollToBottom() {
  listRef.value?.scrollTo({ top: listRef.value.scrollHeight, behavior: 'smooth' })
}

function sealCards() {
  for (const turn of turns.value) {
    for (const card of turn.cards ?? []) card.closed = true
  }
}

async function send(preset?: string) {
  const text = (preset ?? message.value).trim()
  if (!text || loading.value) return
  sealCards()
  turns.value.push({ role: 'user', text })
  message.value = ''
  loading.value = true
  await nextTick()
  scrollToBottom()
  try {
    const res = await api<{
      data: { reply?: string; actions?: QuickAction[]; cards?: ConfirmCard[] }
    }>('/api/c/v1/agent/chat', {
      method: 'POST',
      body: JSON.stringify({ message: text }),
    })
    turns.value.push({
      role: 'assistant',
      text: res.data.reply || '我在呢，稍后再试试～',
      actions: keepActions(text, res.data.actions ?? []),
      cards: res.data.cards ?? [],
    })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '对话失败')
    turns.value.push({
      role: 'assistant',
      text: '刚才有点走神，我们换个说法再试试好吗？',
    })
  } finally {
    loading.value = false
    await nextTick()
    scrollToBottom()
  }
}

async function startNew() {
  if (loading.value || !hasHistory.value) return
  loading.value = true
  try {
    await api('/api/c/v1/agent/session/new', { method: 'POST' })
    message.value = ''
    turns.value = greetingTurns()
    await nextTick()
    scrollToBottom()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '没能新开一段对话')
  } finally {
    loading.value = false
  }
}

function go(action: QuickAction) {
  router.push(action.path)
}

function setBp(card: ConfirmCard, value: string) {
  if (card.closed || card.busy || card.bpContext === value) return
  card.bpContext = value
  void changeContext(card)
}

function setMeal(card: ConfirmCard, value: string) {
  if (card.closed || card.busy || card.mealContext === value) return
  card.mealContext = value
  void changeContext(card)
}

async function changeContext(card: ConfirmCard) {
  if (card.closed || card.busy) return
  card.busy = true
  try {
    const res = await api<{ data: ConfirmCard }>(`/api/c/v1/agent/cards/${card.id}`, {
      method: 'PATCH',
      body: JSON.stringify({ bpContext: card.bpContext, mealContext: card.mealContext }),
    })
    card.summary = res.data.summary
    card.bpContext = res.data.bpContext
    card.mealContext = res.data.mealContext
  } catch (e) {
    showToast(e instanceof Error ? e.message : '修改失败')
  } finally {
    card.busy = false
  }
}

async function decideCard(card: ConfirmCard, confirm: boolean) {
  if (card.closed || card.busy) return
  card.busy = true
  try {
    const res = await api<{ data: { reply?: string } }>(
      `/api/c/v1/agent/cards/${card.id}/${confirm ? 'confirm' : 'cancel'}`,
      { method: 'POST' },
    )
    card.closed = true
    turns.value.push({ role: 'assistant', text: res.data.reply || (confirm ? '已记下。' : '已取消，没有记入。') })
    await nextTick()
    scrollToBottom()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  } finally {
    card.busy = false
  }
}
</script>

<template>
  <div class="page">
    <header>
      <div class="avatar" aria-hidden="true">管</div>
      <div class="who">
        <h1>健康管家</h1>
        <p>私密对话，机构不可见</p>
      </div>
      <button v-if="hasHistory" type="button" class="new-chat" :disabled="loading" @click="startNew">新会话</button>
    </header>
    <div ref="listRef" class="chat">
      <template v-for="(t, i) in turns" :key="i">
        <div class="bubble" :class="t.role">{{ t.text }}</div>
        <div v-if="t.cards?.length" class="cards">
          <div v-for="card in t.cards" :key="card.id" class="confirm-card" :class="{ closed: card.closed }">
            <p>{{ card.summary }}</p>
            <div v-if="card.metric === 'BP' && !card.closed" class="seg">
              <button type="button" :class="{ on: card.bpContext === 'HOME' }" @click="setBp(card, 'HOME')">家庭</button>
              <button type="button" :class="{ on: card.bpContext === 'CLINIC' }" @click="setBp(card, 'CLINIC')">诊室</button>
            </div>
            <div v-else-if="card.metric === 'GLUCOSE' && !card.closed" class="seg">
              <button type="button" :class="{ on: card.mealContext === 'RANDOM' }" @click="setMeal(card, 'RANDOM')">随机</button>
              <button type="button" :class="{ on: card.mealContext === 'FASTING' }" @click="setMeal(card, 'FASTING')">空腹</button>
              <button type="button" :class="{ on: card.mealContext === 'POSTPRANDIAL' }" @click="setMeal(card, 'POSTPRANDIAL')">餐后</button>
            </div>
            <div v-if="!card.closed" class="card-actions">
              <button type="button" class="btn-ok" :disabled="card.busy" @click="decideCard(card, true)">确认</button>
              <button type="button" class="btn-no" :disabled="card.busy" @click="decideCard(card, false)">取消</button>
            </div>
          </div>
        </div>
        <div v-if="t.actions?.length" class="actions">
          <button v-for="a in t.actions" :key="a.path + a.label" type="button" class="action-chip" @click="go(a)">
            {{ a.label }}
          </button>
        </div>
      </template>
      <div v-if="showPrompts" class="prompts">
        <button v-for="p in presets" :key="p" type="button" :disabled="loading" @click="send(p)">{{ p }}</button>
      </div>
      <div v-if="loading" class="bubble assistant typing" aria-label="正在回复"><i /><i /><i /></div>
    </div>
    <form class="composer" @submit.prevent="send()">
      <textarea
        v-model="message"
        rows="1"
        placeholder="和管家说点什么"
        @keydown.enter.exact.prevent="send()"
      />
      <button type="submit" class="send" :disabled="loading || !message.trim()">发送</button>
    </form>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  height: calc(100dvh - 50px - env(safe-area-inset-bottom, 0px));
  padding: 12px 16px 16px;
  min-height: 0;
}
header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 4px 0 12px;
}
.who { flex: 1; min-width: 0; }
.new-chat {
  flex: none;
  border: 0;
  background: #fff;
  color: var(--hx-teal-700);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 13px;
}
.new-chat:disabled { opacity: 0.45; }
.avatar {
  width: 40px;
  height: 40px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  background: var(--hx-teal-100);
  color: var(--hx-teal-700);
  font-weight: 800;
  flex: none;
}
header h1 { margin: 0; font-size: 18px; line-height: 1.2; }
header p { margin: 2px 0 0; color: var(--hx-muted); font-size: 12px; }
.chat {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 8px 0 16px;
}
.bubble {
  max-width: 84%;
  padding: 11px 14px;
  border-radius: 18px;
  font-size: 15px;
  line-height: 1.55;
  white-space: pre-wrap;
}
.bubble.assistant {
  align-self: flex-start;
  background: #fff;
  color: var(--hx-text);
  border-bottom-left-radius: 6px;
}
.bubble.user {
  align-self: flex-end;
  background: var(--hx-teal-600);
  color: #fff;
  border-bottom-right-radius: 6px;
}
.typing {
  display: flex;
  gap: 4px;
  align-items: center;
  min-width: 52px;
}
.typing i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--hx-teal-400);
  animation: blink 1s infinite;
}
.typing i:nth-child(2) { animation-delay: 0.15s; }
.typing i:nth-child(3) { animation-delay: 0.3s; }
@keyframes blink {
  0%, 80%, 100% { opacity: 0.25; }
  40% { opacity: 1; }
}
.actions, .cards {
  align-self: flex-start;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  max-width: 92%;
}
.cards { flex-direction: column; }
.action-chip, .btn-no {
  border: 0;
  background: #fff;
  color: var(--hx-teal-700);
  border-radius: 999px;
  padding: 6px 12px;
  font-size: 13px;
  line-height: 1.3;
}
.confirm-card {
  background: #fff;
  border-radius: 16px;
  padding: 12px 12px 10px;
  border: 1px solid var(--hx-teal-100);
}
.confirm-card p { margin: 0 0 10px; font-size: 14px; line-height: 1.5; }
.seg {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
}
.seg button {
  flex: 1;
  border: 0;
  background: var(--hx-n-100);
  color: var(--hx-n-700);
  border-radius: 999px;
  padding: 6px 0;
  font-size: 13px;
}
.seg button.on {
  background: var(--hx-teal-100);
  color: var(--hx-teal-800);
  font-weight: 700;
}
.card-actions { display: flex; gap: 8px; }
.btn-ok, .send {
  border: 0;
  background: var(--hx-teal-600);
  color: #fff;
  border-radius: 999px;
  padding: 8px 16px;
  font-size: 14px;
  font-weight: 700;
}
.btn-ok:disabled, .send:disabled, .prompts button:disabled { opacity: 0.45; }
.confirm-card.closed { opacity: 0.55; }
.prompts {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 4px;
}
.prompts button {
  align-self: flex-start;
  border: 0;
  background: #fff;
  color: var(--hx-teal-800);
  border-radius: 14px;
  padding: 12px 14px;
  font-size: 14px;
  line-height: 1.4;
  text-align: left;
}
.composer {
  display: flex;
  gap: 8px;
  align-items: flex-end;
  background: #fff;
  border-radius: 22px;
  padding: 6px 6px 6px 14px;
  margin-top: 4px;
}
.composer textarea {
  flex: 1;
  border: 0;
  resize: none;
  max-height: 96px;
  padding: 8px 0;
  font: inherit;
  font-size: 15px;
  line-height: 1.4;
  background: transparent;
  color: var(--hx-text);
}
.composer textarea:focus { outline: none; }
.send { flex: none; min-width: 64px; }
</style>
