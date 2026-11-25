<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, clearToken } from '../api/http'
import IcpFooter from '../shared/IcpFooter.vue'
import { loadRewardSummary } from '../shared/rewards'

const router = useRouter()
const rewardsOn = ref(false)
const displayName = ref('')
const gender = ref('')
const inCare = ref(false)
const saving = ref(false)
const editOpen = ref(false)

const genderOptions = [
  { text: '男', value: 'MALE' },
  { text: '女', value: 'FEMALE' },
  { text: '其他', value: 'OTHER' },
]

onMounted(async () => {
  try {
    const res = await api<{ data: any }>('/api/c/v1/profile')
    displayName.value = res.data.displayName ?? ''
    gender.value = res.data.gender ?? ''
    try {
      const m = await api<{ data: { inCare?: boolean } }>('/api/c/v1/membership/status')
      inCare.value = !!m.data?.inCare
    } catch {
      inCare.value = false
    }
    void loadRewardSummary().then((s) => (rewardsOn.value = !!s))
  } catch (e) {
    const msg = e instanceof Error ? e.message : ''
    if (msg.includes('健康档案') || msg.includes('就诊人')) {
      await router.replace('/patient-cards')
      return
    }
    showToast(e instanceof Error ? e.message : '加载失败')
  }
})

async function save() {
  saving.value = true
  try {
    await api('/api/c/v1/profile', {
      method: 'PUT',
      body: JSON.stringify({
        displayName: displayName.value,
        gender: gender.value || null,
      }),
    })
    showToast({ type: 'success', message: '已保存' })
    editOpen.value = false
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function logout() {
  clearToken()
  router.replace('/login')
}
</script>

<template>
  <div class="page">
    <header class="hero-card">
      <div class="avatar" aria-hidden="true">{{ (displayName || '我').slice(0, 1) }}</div>
      <div class="meta">
        <button type="button" class="name-edit" :aria-label="editOpen ? '收起资料' : '编辑资料'" @click="editOpen = !editOpen">
          <h1>{{ displayName || '我的档案' }}</h1>
          <svg class="edit-ico" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M14.2 5.4l4.4 4.4" />
            <path d="M5 15.6L14.5 6.1a1.7 1.7 0 012.4 0l1 1a1.7 1.7 0 010 2.4L8.4 19H5v-3.4z" />
          </svg>
        </button>
        <p>
          <span class="dot" :class="{ care: inCare }" />
          {{ inCare ? '已入组 · 专属服务已开启' : '自用态 · 可完善资料与入组' }}
        </p>
      </div>
      <button type="button" class="switch" @click="router.push('/patient-cards')">切换 ›</button>
    </header>

    <section v-if="editOpen" class="card edit-card">
      <van-field v-model="displayName" label="显示姓名" placeholder="怎么称呼你" />
      <div class="gender">
        <div class="gender-label">性别</div>
        <van-radio-group v-model="gender" direction="horizontal">
          <van-radio v-for="o in genderOptions" :key="o.value" :name="o.value">{{ o.text }}</van-radio>
        </van-radio-group>
      </div>
      <div class="pad">
        <van-button round block type="primary" :loading="saving" @click="save">保存资料</van-button>
      </div>
    </section>

    <section v-if="rewardsOn" class="card menu">
      <div class="group-title">
        <svg class="gico" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M8 10V7a4 4 0 018 0v3" />
          <rect x="5" y="10" width="14" height="10" rx="2.5" />
          <path d="M12 14v3" />
        </svg>
        激励
      </div>
      <van-cell title="我的成就" is-link to="/achievements" />
    </section>

    <section class="card menu">
      <div class="group-title">
        <svg class="gico" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M12 4v16M4 12h16" />
          <circle cx="12" cy="12" r="8.5" />
        </svg>
        管理服务
      </div>
      <van-cell title="管理方案" is-link to="/care-plan" />
      <van-cell title="用药管理" is-link to="/medications" />
      <van-cell title="随访记录" is-link to="/followups" />
      <van-cell title="管理报告" is-link to="/management-reports" />
      <van-cell title="健康评估" is-link to="/assessment-questionnaire" />
    </section>

    <section class="card menu">
      <div class="group-title">
        <svg class="gico" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 7h14a2 2 0 012 2v7a2 2 0 01-2 2h-6l-4 3v-3H5a2 2 0 01-2-2V9a2 2 0 012-2z" />
        </svg>
        智能助手
      </div>
      <van-cell title="AI 健康助手" label="问答与健康沟通" is-link to="/chat" />
    </section>

    <section class="card menu">
      <div class="group-title">
        <svg class="gico" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M4 7a2 2 0 012-2h5l2 2h5a2 2 0 012 2v9a2 2 0 01-2 2H6a2 2 0 01-2-2V7z" />
        </svg>
        档案与数据
      </div>
      <van-cell title="健康档案" is-link to="/archive" />
      <van-cell title="健康数据" is-link to="/health-data?tab=labs" />
    </section>

    <section class="card menu">
      <div class="group-title">
        <svg class="gico" viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="8" r="3.2" />
          <path d="M5 19c1.4-3.2 3.8-4.8 7-4.8s5.6 1.6 7 4.8" />
        </svg>
        账户
      </div>
      <van-cell title="激活码添加档案" is-link to="/activate" />
      <van-cell title="邀请码入组" is-link to="/join" />
      <van-cell title="用户协议" is-link to="/legal/terms" />
      <van-cell title="隐私政策" is-link to="/legal/privacy" />
      <van-cell title="退出登录" is-link @click="logout" />
    </section>
    <div class="profile-icp">
      <IcpFooter />
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 0;
  padding: 16px 16px 24px;
  background: #f5f7fb;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.profile-icp {
  margin-top: 4px;
  padding-bottom: 8px;
}
.profile-icp :deep(.icp) {
  padding: 16px 0 0;
  color: #94a3b8;
  font-size: 12px;
}
.hero-card {
  display: flex;
  gap: 14px;
  align-items: center;
  padding: 18px 16px;
  border-radius: 24px;
  border: 0;
  background: linear-gradient(135deg, #e8faf3 0%, #eaf3ff 55%, #f5f9ff 100%);
  box-shadow: 0 8px 24px rgba(22, 119, 255, 0.06);
}
.avatar {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: linear-gradient(145deg, #40a9ff 0%, #1677ff 48%, #10b981 100%);
  color: #fff;
  font-size: 24px;
  font-weight: 800;
  flex: none;
  border: 3px solid #fff;
  box-shadow: 0 6px 16px rgba(22, 119, 255, 0.22);
}
.meta {
  flex: 1;
  min-width: 0;
}
.name-edit {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}
h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.02em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.edit-ico {
  width: 15px;
  height: 15px;
  flex: none;
  stroke: #94a3b8;
  fill: none;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.meta p {
  margin: 6px 0 0;
  color: #94a3b8;
  font-size: 12px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #94a3b8;
  flex: none;
}
.dot.care {
  background: #10b981;
  box-shadow: 0 0 4px rgba(16, 185, 129, 0.5);
}
.switch {
  flex: none;
  border-radius: 999px;
  padding: 6px 10px;
  background: #fff;
  color: #64748b;
  font-size: 11px;
  font-weight: 650;
  font-family: inherit;
  border: 1px solid #e8eef4;
  cursor: pointer;
}
.card {
  background: #fff;
  border-radius: 20px;
  overflow: hidden;
  border: 0;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.02);
}
.edit-card {
  margin: 0;
}
.group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 16px 2px;
  font-size: 11px;
  font-weight: 700;
  color: #94a3b8;
  letter-spacing: 0.04em;
}
.gico {
  width: 14px;
  height: 14px;
  flex: none;
  stroke: currentColor;
  fill: none;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
  opacity: 0.9;
}
.gender {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
}
.gender-label {
  width: 6.2em;
  flex-shrink: 0;
  color: #475569;
  font-size: 14px;
}
.pad {
  padding: 8px 12px 14px;
}
.menu :deep(.van-cell) {
  padding: 16px;
  align-items: center;
  background: transparent;
}
.menu :deep(.van-cell::after) {
  border-bottom-color: #f1f5f9;
  left: 16px;
  right: 16px;
}
.menu :deep(.van-cell__title) {
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
}
.menu :deep(.van-cell__label) {
  margin-top: 4px;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.35;
}
.menu :deep(.van-cell__right-icon) {
  color: #cbd5e1;
  font-size: 16px;
  line-height: 16px;
  width: 16px;
  height: 16px;
  margin-top: 0 !important;
  align-self: center;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.menu :deep(.van-cell__right-icon .van-icon),
.menu :deep(.van-cell__right-icon svg) {
  width: 16px;
  height: 16px;
  font-size: 16px;
  color: #cbd5e1;
}
.menu :deep(.van-cell:active) {
  background: #f8fafc;
}
</style>
