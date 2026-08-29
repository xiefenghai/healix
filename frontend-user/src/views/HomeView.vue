<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { showToast } from 'vant'
import { useRouter } from 'vue-router'
import { api } from '../api/http'

const router = useRouter()
const displayName = ref('朋友')
const homeTenantId = ref<number | null>(null)
const steps = ref(6420)
const sleepHours = ref(7.2)
const waterCups = ref(5)
const agentTip = ref('今天走路步数已达标一半啦，午后散散步，轻松完成目标 🎉')

const recordType = ref<'diet' | 'weight' | 'glucose' | 'bp' | null>(null)
const recordValue = ref('')
const showRecord = computed({
  get: () => recordType.value != null,
  set: (v: boolean) => {
    if (!v) recordType.value = null
  },
})

const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 11) return '早安'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const stepPct = computed(() => Math.min(100, Math.round((steps.value / 8000) * 100)))
const sleepPct = computed(() => Math.min(100, Math.round((sleepHours.value / 8) * 100)))
const waterPct = computed(() => Math.min(100, Math.round((waterCups.value / 8) * 100)))

const recordTitle = computed(() => {
  switch (recordType.value) {
    case 'diet':
      return '记录饮食'
    case 'weight':
      return '记录体重'
    case 'glucose':
      return '记录血糖'
    case 'bp':
      return '记录血压'
    default:
      return '快捷记录'
  }
})

const recordPlaceholder = computed(() => {
  switch (recordType.value) {
    case 'diet':
      return '例如：燕麦粥 + 水煮蛋'
    case 'weight':
      return '单位 kg，如 62.5'
    case 'glucose':
      return '单位 mmol/L，如 5.8'
    case 'bp':
      return '如 120/80'
    default:
      return ''
  }
})

onMounted(async () => {
  try {
    const res = await api<{ data: { displayName?: string; homeTenantId?: number | null } }>(
      '/api/c/v1/profile',
    )
    displayName.value = res.data.displayName || '朋友'
    homeTenantId.value = res.data.homeTenantId ?? null
    if (homeTenantId.value) {
      agentTip.value = '今天状态不错哦，继续温柔地照顾自己。需要时随时找我聊聊 💪'
      await loadSteps()
    } else {
      agentTip.value = '先完善资料并用邀请码入组，就能开始记录体征、和健康助手聊天啦～'
    }
  } catch {
    /* keep defaults */
  }
})

async function loadSteps() {
  try {
    const to = new Date().toISOString()
    const from = new Date()
    from.setHours(0, 0, 0, 0)
    const res = await api<{ data: Array<{ value: number }> }>(
      `/api/c/v1/vitals?metricType=STEPS&from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to)}`,
    )
    const list = res.data ?? []
    if (list.length) {
      steps.value = Number(list[0].value) || steps.value
    }
  } catch {
    /* demo numbers remain */
  }
}

function openRecord(type: 'diet' | 'weight' | 'glucose' | 'bp') {
  recordType.value = type
  recordValue.value = ''
}

async function submitRecord() {
  if (!recordValue.value.trim()) {
    showToast('请先填写内容')
    return
  }
  if (!homeTenantId.value) {
    showToast('入组后即可同步到健康档案')
    showRecord.value = false
    router.push('/join')
    return
  }

  try {
    if (recordType.value === 'glucose') {
      await api('/api/c/v1/vitals', {
        method: 'POST',
        body: JSON.stringify({
          metricType: 'BLOOD_GLUCOSE',
          value: Number(recordValue.value),
          unit: 'mmol/L',
        }),
      })
    } else if (recordType.value === 'weight') {
      await api('/api/c/v1/vitals', {
        method: 'POST',
        body: JSON.stringify({
          metricType: 'WEIGHT',
          value: Number(recordValue.value),
          unit: 'kg',
        }),
      })
    }
    showToast({ message: '已记录，真棒！', type: 'success' })
  } catch (e) {
    showToast(e instanceof Error ? e.message : '记录失败')
  } finally {
    showRecord.value = false
  }
}

function goDetail(kind: string) {
  showToast(`查看${kind}详情`)
  router.push('/health')
}
</script>

<template>
  <div class="home">
    <header class="hero">
      <div class="greet">
        <div class="avatar">{{ displayName.slice(0, 1) }}</div>
        <div>
          <p class="hello">{{ greeting }}，{{ displayName }}！</p>
          <p class="sub">今天状态不错哦 💪</p>
        </div>
      </div>
      <button class="bell" type="button" aria-label="通知" @click="showToast('暂无新通知')">
        <van-icon name="bell" size="20" />
      </button>
    </header>

    <section class="card metrics">
      <div class="section-head">
        <h2>今日核心数据</h2>
        <button type="button" class="link" @click="router.push('/health')">详情</button>
      </div>
      <div class="rings">
        <button type="button" class="ring-item" @click="goDetail('步数')">
          <div class="ring teal" :style="{ '--p': stepPct + '%' }">
            <div class="ring-inner">
              <strong>{{ steps }}</strong>
              <span>步</span>
            </div>
          </div>
          <p>步数 · 目标 8000</p>
        </button>
        <button type="button" class="ring-item" @click="goDetail('睡眠')">
          <div class="ring mint" :style="{ '--p': sleepPct + '%' }">
            <div class="ring-inner">
              <strong>{{ sleepHours }}</strong>
              <span>小时</span>
            </div>
          </div>
          <p>睡眠 · 目标 8h</p>
        </button>
        <button type="button" class="ring-item" @click="goDetail('饮水')">
          <div class="ring orange" :style="{ '--p': waterPct + '%' }">
            <div class="ring-inner">
              <strong>{{ waterCups }}</strong>
              <span>杯</span>
            </div>
          </div>
          <p>饮水 · 目标 8 杯</p>
        </button>
      </div>
    </section>

    <section class="card">
      <h2>快捷记录</h2>
      <div class="quick">
        <button type="button" @click="openRecord('diet')">
          <span class="qicon diet"><van-icon name="shop-o" /></span>
          <em>记录饮食</em>
        </button>
        <button type="button" @click="openRecord('weight')">
          <span class="qicon weight"><van-icon name="balance-o" /></span>
          <em>记录体重</em>
        </button>
        <button type="button" @click="openRecord('glucose')">
          <span class="qicon glucose"><van-icon name="fire-o" /></span>
          <em>记录血糖</em>
        </button>
        <button type="button" @click="openRecord('bp')">
          <span class="qicon bp"><van-icon name="medal-o" /></span>
          <em>记录血压</em>
        </button>
      </div>
    </section>

    <section class="tip-card" @click="router.push('/discover')">
      <div class="tip-bar" />
      <div class="tip-body">
        <div class="tip-title">
          <van-icon name="smile-o" />
          <span>智能建议</span>
        </div>
        <p>{{ agentTip }}</p>
      </div>
    </section>

    <section v-if="!homeTenantId" class="join-card">
      <p>还没入组？输入邀请码开启完整健康服务</p>
      <van-button round type="primary" block @click="router.push('/join')">去入组</van-button>
    </section>

    <van-popup v-model:show="showRecord" position="bottom" round :style="{ padding: '20px 16px 28px' }">
      <h3 class="popup-title">{{ recordTitle }}</h3>
      <van-field v-model="recordValue" rows="2" autosize type="textarea" :placeholder="recordPlaceholder" />
      <van-button round block type="primary" style="margin-top: 12px" @click="submitRecord">保存</van-button>
    </van-popup>
  </div>
</template>

<style scoped>
.home {
  padding: 18px 16px 24px;
}
.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.greet {
  display: flex;
  gap: 12px;
  align-items: center;
}
.avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: linear-gradient(145deg, #5cb8b8, #2b9e9e);
  color: #fff;
  font-weight: 700;
  font-size: 18px;
  box-shadow: 0 8px 16px rgba(43, 158, 158, 0.25);
}
.hello {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: var(--hx-text);
}
.sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--hx-muted);
}
.bell {
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: #fff;
  color: var(--hx-teal);
  box-shadow: var(--hx-shadow);
}
.card {
  background: var(--hx-card);
  border-radius: var(--hx-radius);
  padding: 16px;
  margin-bottom: 14px;
  box-shadow: var(--hx-shadow);
}
.section-head,
.card > h2 {
  margin: 0 0 14px;
}
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.section-head h2,
.card > h2 {
  font-size: 16px;
  font-weight: 700;
}
.link {
  border: 0;
  background: transparent;
  color: var(--hx-teal);
  font-size: 13px;
}
.rings {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}
.ring-item {
  border: 0;
  background: transparent;
  padding: 0;
}
.ring {
  --p: 50%;
  width: 92px;
  height: 92px;
  margin: 0 auto;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: conic-gradient(var(--ring) var(--p), #eef3f6 0);
}
.ring.teal { --ring: #2b9e9e; }
.ring.mint { --ring: #5cb8b8; }
.ring.orange { --ring: #f5a623; }
.ring-inner {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: #fff;
  display: grid;
  place-content: center;
  gap: 0;
}
.ring-inner strong {
  font-size: 16px;
  line-height: 1.1;
  color: var(--hx-text);
}
.ring-inner span {
  font-size: 11px;
  color: var(--hx-muted);
  text-align: center;
}
.ring-item p {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--hx-muted);
  text-align: center;
}
.quick {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}
.quick button {
  border: 0;
  background: transparent;
  display: grid;
  gap: 8px;
  justify-items: center;
  padding: 0;
}
.qicon {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  font-size: 22px;
  color: #fff;
}
.qicon.diet { background: linear-gradient(145deg, #7bcfcf, #2b9e9e); }
.qicon.weight { background: linear-gradient(145deg, #ffd080, #f5a623); }
.qicon.glucose { background: linear-gradient(145deg, #9ad8d8, #5cb8b8); }
.qicon.bp { background: linear-gradient(145deg, #ffc4a0, #f08a5d); }
.quick em {
  font-style: normal;
  font-size: 12px;
  color: var(--hx-text);
}
.tip-card {
  display: flex;
  background: linear-gradient(90deg, #eefaf8, #fff8ec);
  border-radius: var(--hx-radius);
  overflow: hidden;
  box-shadow: var(--hx-shadow);
  margin-bottom: 14px;
}
.tip-bar {
  width: 5px;
  background: linear-gradient(180deg, #2b9e9e, #f5a623);
}
.tip-body {
  padding: 14px 16px;
}
.tip-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--hx-teal);
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.tip-body p {
  margin: 0;
  font-size: 14px;
  line-height: 1.55;
  color: var(--hx-text);
}
.join-card {
  background: #fff;
  border-radius: var(--hx-radius);
  padding: 16px;
  box-shadow: var(--hx-shadow);
}
.join-card p {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--hx-muted);
}
.popup-title {
  margin: 0 0 12px;
  font-size: 18px;
  text-align: center;
}
</style>
