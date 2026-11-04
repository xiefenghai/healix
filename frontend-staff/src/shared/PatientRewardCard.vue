<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { showConfirmDialog, showToast } from 'vant'
import { api } from './http'

interface StaffView {
  tenantEnabled: boolean
  patientEnabled: boolean
  summary: {
    points: number
    weekPoints: number
    todayPoints: number
    streak: number
    freezeCards: number
    todayActive: boolean
    level: { level: number; title: string } | null
  }
  badges: { code: string; name: string; icon: string; kind: string; earned: boolean }[]
  kudosSentToday: boolean
  backfills7d: number
  challenge?: {
    title: string
    target: number
    progress: number
    status: string
  } | null
}

const BACKFILL_ALERT = 5

const PRESETS = ['坚持得很好，继续保持！', '这周进步很明显 👏', '指标控制得很稳，继续加油']

const props = defineProps<{ peopleId: string }>()
const view = ref<StaffView | null>(null)
const sheetOpen = ref(false)
const message = ref('')
const sending = ref(false)

const earned = computed(() => (view.value?.badges || []).filter((b) => b.earned))

async function load() {
  try {
    const res = await api<{ data: StaffView }>(`/api/b/v1/patients/${props.peopleId}/rewards`)
    view.value = res.data
  } catch {
    view.value = null
  }
}

async function send() {
  sending.value = true
  try {
    const res = await api<{ data: { sent: boolean; message: string } }>(
      `/api/b/v1/patients/${props.peopleId}/rewards/kudos`,
      { method: 'POST', body: JSON.stringify({ message: message.value.trim() || null }) },
    )
    showToast(res.data.message)
    sheetOpen.value = false
    message.value = ''
    await load()
  } catch (e) {
    showToast(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function toggle() {
  const enable = !view.value?.patientEnabled
  if (!enable) {
    try {
      await showConfirmDialog({
        title: '关闭该患者的激励？',
        message: '关闭后不再获得健康豆、火苗与徽章，C 端不再展示成就入口。可随时重新开启。',
      })
    } catch {
      return
    }
  }
  try {
    const res = await api<{ data: StaffView }>(`/api/b/v1/patients/${props.peopleId}/rewards/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled: enable }),
    })
    view.value = res.data
  } catch (e) {
    showToast(e instanceof Error ? e.message : '操作失败')
  }
}

watch(() => props.peopleId, load, { immediate: true })
</script>

<template>
  <section v-if="view?.tenantEnabled" class="reward hx-surface">
    <div class="head">
      <h3><span>健康激励</span></h3>
      <button type="button" class="toggle" @click="toggle">{{ view.patientEnabled ? '关闭' : '开启' }}</button>
    </div>
    <p v-if="!view.patientEnabled" class="off">已为该患者关闭激励</p>
    <template v-else>
      <p v-if="view.backfills7d >= BACKFILL_ALERT" class="alert">
        ⚠️ 近 7 天补录 {{ view.backfills7d }} 条，建议核实数据真实性
      </p>
      <div class="stats">
        <div class="flame">
          <strong>🔥{{ view.summary.streak }}</strong>
          <span>连续天数</span>
        </div>
        <div>
          <strong :class="{ dim: !view.summary.todayActive }">{{ view.summary.todayActive ? '已打卡' : '未打卡' }}</strong>
          <span>今日</span>
        </div>
        <div>
          <strong>{{ view.summary.points }}</strong>
          <span>健康豆</span>
        </div>
        <div>
          <strong>Lv{{ view.summary.level?.level ?? 1 }}</strong>
          <span>{{ view.summary.level?.title }}</span>
        </div>
      </div>
      <p v-if="view.challenge" class="challenge">
        🎯 {{ view.challenge.title }}
        <em>{{ view.challenge.progress }}/{{ view.challenge.target }}</em>
      </p>
      <div v-if="earned.length" class="badges">
        <span v-for="b in earned.slice(0, 6)" :key="b.code" class="badge">{{ b.icon }} {{ b.name }}</span>
      </div>
      <button type="button" class="kudos" :disabled="view.kudosSentToday" @click="sheetOpen = true">
        {{ view.kudosSentToday ? '今日已点赞' : '👍 点赞鼓励' }}
      </button>
    </template>

    <van-popup v-model:show="sheetOpen" position="bottom" round closeable>
      <div class="sheet">
        <h4>给患者点个赞</h4>
        <div class="presets">
          <button v-for="p in PRESETS" :key="p" type="button" class="preset" @click="message = p">{{ p }}</button>
        </div>
        <van-field v-model="message" type="textarea" rows="2" maxlength="100" show-word-limit placeholder="可选：一句鼓励的话" />
        <van-button type="primary" block round :loading="sending" @click="send">发送给患者</van-button>
      </div>
    </van-popup>
  </section>
</template>

<style scoped>
.reward {
  margin: 0 16px 12px;
  padding: 14px;
  background: linear-gradient(135deg, #fff6ea, #ffffff 70%);
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 800;
}
.head h3 span {
  background: linear-gradient(transparent 60%, #ffe58a 60%);
}
.toggle {
  border: 0;
  background: transparent;
  color: var(--hx-muted, #8a96a3);
  font-size: 12px;
}
.alert {
  margin: 8px 0 0;
  padding: 6px 10px;
  border-radius: 10px;
  background: #fff3da;
  color: #8a5c00;
  font-size: 12px;
  font-weight: 600;
}
.off {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--hx-muted, #8a96a3);
}
.stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 10px;
  text-align: center;
}
.stats strong {
  display: block;
  font-size: 17px;
  font-weight: 800;
  color: var(--hx-ink, #1f2d3a);
}
.stats .flame strong {
  color: #e0632a;
}
.stats strong.dim {
  color: #b8c2cc;
}
.stats span {
  font-size: 11px;
  color: var(--hx-muted, #8a96a3);
}
.challenge {
  margin: 10px 0 0;
  font-size: 13px;
  font-weight: 700;
  color: #5b6b7a;
}
.challenge em {
  margin-left: 6px;
  font-style: normal;
  color: #e0632a;
}
.badges {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: #fff4e0;
  color: #8a5a2b;
  font-size: 11px;
}
.kudos {
  width: 100%;
  margin-top: 12px;
  height: 36px;
  border: 0;
  border-radius: 999px;
  background: linear-gradient(90deg, #ffb057, #ff7a45);
  color: #fff;
  font-weight: 700;
  font-size: 14px;
}
.kudos:disabled {
  background: #f1f3f5;
  color: #a0aab4;
}
.sheet {
  padding: 16px 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.sheet h4 {
  margin: 0;
  text-align: center;
  font-size: 15px;
}
.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.preset {
  border: 1px solid #f6d9b8;
  background: #fff8ef;
  color: #8a5a2b;
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 12px;
}
</style>
