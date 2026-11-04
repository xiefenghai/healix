<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../shared/http'
import { BACKFILL_ALERT, KUDOS_PRESETS, type RewardStaffView } from '../../shared/rewards'

const props = defineProps<{ peopleId: string }>()

const view = ref<RewardStaffView | null>(null)
const loading = ref(false)
const kudosOpen = ref(false)
const kudosText = ref('')
const sending = ref(false)
const toggling = ref(false)
const showLedger = ref(false)

const s = computed(() => view.value?.summary)
const earned = computed(() => (view.value?.badges || []).filter((b) => b.earned))

async function load() {
  if (!props.peopleId) return
  loading.value = true
  try {
    const res = await api<{ data: RewardStaffView }>(`/api/b/v1/patients/${props.peopleId}/rewards`)
    view.value = res.data
  } catch {
    view.value = null
  } finally {
    loading.value = false
  }
}

async function sendKudos() {
  sending.value = true
  try {
    const res = await api<{ data: { sent: boolean; message: string } }>(
      `/api/b/v1/patients/${props.peopleId}/rewards/kudos`,
      { method: 'POST', body: JSON.stringify({ message: kudosText.value.trim() || null }) },
    )
    ElMessage({ type: res.data.sent ? 'success' : 'warning', message: res.data.message })
    kudosOpen.value = false
    kudosText.value = ''
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败')
  } finally {
    sending.value = false
  }
}

async function toggle(enabled: boolean) {
  if (!enabled) {
    try {
      await ElMessageBox.confirm(
        '关闭后该患者不再获得健康豆、火苗与徽章，C 端也不再展示成就入口。已有记录保留，可随时重新开启。',
        '关闭该患者的激励？',
        { type: 'warning', confirmButtonText: '关闭激励', cancelButtonText: '取消' },
      )
    } catch {
      return
    }
  }
  toggling.value = true
  try {
    const res = await api<{ data: RewardStaffView }>(`/api/b/v1/patients/${props.peopleId}/rewards/enabled`, {
      method: 'PUT',
      body: JSON.stringify({ enabled }),
    })
    view.value = res.data
    ElMessage.success(enabled ? '已开启激励' : '已关闭激励')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  } finally {
    toggling.value = false
  }
}

function shortDate(d?: string | null) {
  return d ? String(d).slice(5, 10).replace('-', '/') : ''
}

watch(() => props.peopleId, load, { immediate: true })
defineExpose({ load })
</script>

<template>
  <section v-if="view?.tenantEnabled" v-loading="loading" class="reward-panel">
    <header class="rp-head">
      <h3><span>健康激励</span></h3>
      <span class="rp-hint">患者 C 端自助完成的打卡才计入，代录不计</span>
      <div class="rp-actions">
        <template v-if="view.patientEnabled">
          <el-popover v-model:visible="kudosOpen" placement="bottom-end" :width="300" trigger="click">
            <template #reference>
              <el-button type="warning" plain size="small" :disabled="view.kudosSentToday && !kudosOpen">
                {{ view.kudosSentToday ? '今日已点赞' : '👍 点赞鼓励' }}
              </el-button>
            </template>
            <div class="kudos-pop">
              <div class="presets">
                <el-tag
                  v-for="p in KUDOS_PRESETS"
                  :key="p"
                  class="preset"
                  effect="plain"
                  @click="kudosText = p"
                >{{ p }}</el-tag>
              </div>
              <el-input
                v-model="kudosText"
                type="textarea"
                :rows="2"
                maxlength="100"
                show-word-limit
                placeholder="可选：一句鼓励的话"
              />
              <el-button type="primary" size="small" class="send" :loading="sending" @click="sendKudos">
                发送给患者
              </el-button>
            </div>
          </el-popover>
        </template>
        <el-switch
          :model-value="view.patientEnabled"
          :loading="toggling"
          inline-prompt
          active-text="开"
          inactive-text="关"
          @update:model-value="(v: string | number | boolean) => toggle(!!v)"
        />
      </div>
    </header>

    <div v-if="!view.patientEnabled" class="rp-off">已为该患者关闭激励</div>

    <template v-else-if="s">
      <el-alert
        v-if="view.backfills7d >= BACKFILL_ALERT"
        type="warning"
        :closable="false"
        show-icon
        class="rp-alert"
        :title="`近 7 天补录 ${view.backfills7d} 条（隔天录入，或测量 12 小时后才录入），建议核实数据真实性`"
      />
      <div class="rp-stats">
        <div class="stat flame">
          <span class="lbl">连续打卡</span>
          <strong>🔥 {{ s.streak }}<em>天</em></strong>
          <span class="sub">最长 {{ s.bestStreak }} 天 · 保护卡 {{ s.freezeCards }}/2</span>
        </div>
        <div class="stat">
          <span class="lbl">今日</span>
          <strong :class="{ muted: !s.todayActive }">{{ s.todayActive ? '已打卡' : '未打卡' }}</strong>
          <span class="sub">+{{ s.todayPoints }} 豆 · {{ s.todayActions }} 项</span>
        </div>
        <div class="stat">
          <span class="lbl">健康豆</span>
          <strong>{{ s.points }}</strong>
          <span class="sub">本周 +{{ s.weekPoints }}</span>
        </div>
        <div class="stat">
          <span class="lbl">等级</span>
          <strong>Lv{{ s.level?.level ?? 1 }}</strong>
          <span class="sub">{{ s.level?.title }}</span>
        </div>
      </div>

      <div v-if="view.challenge" class="rp-row">
        <span class="row-lbl">本周挑战</span>
        <div class="challenge">
          <strong>🎯 {{ view.challenge.title }}</strong>
          <span>
            {{ view.challenge.progress }}/{{ view.challenge.target }}
            <template v-if="view.challenge.status === 'DONE'"> · 已达成</template>
          </span>
        </div>
      </div>

      <div class="rp-row">
        <span class="row-lbl">徽章 {{ earned.length }}</span>
        <div class="badges">
          <span v-for="b in earned" :key="b.code" class="badge" :class="{ outcome: b.kind === 'OUTCOME' }" :title="`${shortDate(b.earnedDate)} 获得`">
            {{ b.icon }} {{ b.name }}
          </span>
          <span v-if="!earned.length" class="muted">暂无</span>
        </div>
      </div>

      <div v-if="view.kudos.length" class="rp-row">
        <span class="row-lbl">最近点赞</span>
        <div class="kudos-list">
          <span v-for="(k, i) in view.kudos.slice(0, 3)" :key="i" class="kudos">
            {{ k.staffName || '健管师' }} · {{ shortDate(k.createdAt) }}<template v-if="k.message">：{{ k.message }}</template>
          </span>
        </div>
      </div>

      <div class="rp-row">
        <el-button link type="primary" @click="showLedger = !showLedger">
          {{ showLedger ? '收起健康豆明细' : '查看近 14 天健康豆明细' }}
        </el-button>
      </div>
      <div v-if="showLedger" class="ledger">
        <div v-if="!view.ledger.length" class="muted">近 14 天暂无记录</div>
        <div v-for="d in view.ledger" :key="d.date" class="ledger-day">
          <div class="ld-head">
            <span>{{ d.date }}</span>
            <strong>{{ d.total >= 0 ? '+' : '' }}{{ d.total }}</strong>
          </div>
          <div v-for="(it, i) in d.items" :key="i" class="ld-item">
            <span>{{ it.title }}</span>
            <span :class="{ neg: it.points < 0 }">{{ it.points > 0 ? '+' : '' }}{{ it.points }}</span>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.reward-panel {
  margin-bottom: 16px;
  padding: 16px 18px;
  border-radius: 14px;
  background: linear-gradient(135deg, #fff7ee 0%, #ffffff 60%);
  border: 1px solid #f6e3cf;
}
.rp-head {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.rp-head h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
}
.rp-head h3 span {
  background: linear-gradient(transparent 60%, #ffe58a 60%);
}
.rp-hint {
  font-size: 12px;
  color: #909399;
}
.rp-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
}
.rp-alert {
  margin-top: 12px;
}
.rp-off {
  margin-top: 12px;
  font-size: 13px;
  color: #909399;
}
.rp-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 10px;
  margin-top: 14px;
}
.stat {
  padding: 10px 12px;
  border-radius: 12px;
  background: #fff;
  border: 1px solid #f1ebe3;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat .lbl {
  font-size: 12px;
  color: #909399;
}
.stat strong {
  font-size: 22px;
  font-weight: 800;
  color: #303133;
  font-variant-numeric: tabular-nums;
}
.stat strong em {
  font-style: normal;
  font-size: 12px;
  margin-left: 2px;
  color: #909399;
}
.stat.flame strong {
  color: #e0632a;
}
.stat strong.muted {
  color: #c0c4cc;
}
.stat .sub {
  font-size: 12px;
  color: #909399;
}
.challenge {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 13px;
  color: #606266;
}
.challenge strong {
  color: #303133;
  font-weight: 700;
}
.rp-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 12px;
}
.row-lbl {
  flex-shrink: 0;
  width: 64px;
  font-size: 12px;
  color: #909399;
  line-height: 24px;
}
.badges,
.kudos-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.badge {
  padding: 2px 10px;
  border-radius: 999px;
  background: #fff4e0;
  color: #8a5a2b;
  font-size: 12px;
  line-height: 20px;
}
.badge.outcome {
  background: #e9f8f1;
  color: #2a7a5a;
}
.kudos {
  font-size: 12px;
  color: #606266;
  line-height: 24px;
}
.kudos + .kudos::before {
  content: '·';
  margin-right: 6px;
  color: #c0c4cc;
}
.muted {
  color: #c0c4cc;
  font-size: 12px;
}
.ledger {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
  margin-top: 6px;
}
.ledger-day {
  padding: 8px 10px;
  border-radius: 10px;
  background: #fff;
  border: 1px solid #f1ebe3;
}
.ld-head {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  font-weight: 700;
  margin-bottom: 4px;
}
.ld-head strong {
  color: #e0632a;
}
.ld-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  color: #606266;
  line-height: 1.8;
}
.ld-item span:first-child {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.neg {
  color: #c0c4cc;
}
.kudos-pop {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.preset {
  cursor: pointer;
}
.send {
  align-self: flex-end;
}
</style>
