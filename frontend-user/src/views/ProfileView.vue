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
const homeTenantId = ref<number | null>(null)
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
    homeTenantId.value = res.data.tenantId ? 1 : null
    void loadRewardSummary().then((s) => (rewardsOn.value = !!s))
  } catch (e) {
    const msg = e instanceof Error ? e.message : ''
    if (msg.includes('就诊人')) {
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
    <header class="profile-head">
      <div class="avatar">{{ (displayName || '我').slice(0, 1) }}</div>
      <div class="meta">
        <h1>{{ displayName || '我的档案' }}</h1>
        <p>{{ homeTenantId ? '已入组 · 核心能力已开启' : '空态 · 可完善资料与入组' }}</p>
        <button type="button" class="edit" @click="editOpen = !editOpen">
          {{ editOpen ? '收起资料' : '编辑资料' }}
        </button>
      </div>
      <button type="button" class="switch" @click="router.push('/patient-cards')">切换 ›</button>
    </header>

    <section v-if="editOpen" class="card">
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
      <div class="group-title">激励</div>
      <van-cell title="我的成就" is-link to="/achievements" />
    </section>

    <section class="card menu">
      <div class="group-title">管理服务</div>
      <van-cell title="管理方案" is-link to="/care-plan" />
      <van-cell title="用药管理" is-link to="/medications" />
      <van-cell title="随访记录" is-link to="/followups" />
      <van-cell title="管理报告" is-link to="/management-reports" />
      <van-cell title="初步评估" is-link to="/intake" />
      <van-cell title="评估" is-link to="/assessment-questionnaire" />
    </section>

    <section class="card menu">
      <div class="group-title">智能助手</div>
      <van-cell title="AI管家" label="近期开放" is-link to="/ai" />
    </section>

    <section class="card menu">
      <div class="group-title">档案与数据</div>
      <van-cell title="就诊人管理" is-link to="/patient-cards" />
      <van-cell title="健康档案" is-link to="/archive" />
      <van-cell title="健康数据" is-link to="/health-data?tab=vitals" />
    </section>

    <section class="card menu">
      <div class="group-title">账户</div>
      <van-cell title="激活码添加档案" is-link to="/activate" />
      <van-cell title="邀请码入组" is-link to="/join" />
      <van-cell title="用户协议" is-link to="/legal/terms" />
      <van-cell title="隐私政策" is-link to="/legal/privacy" />
      <van-cell title="退出登录" is-link @click="logout" />
    </section>
    <IcpFooter class="profile-icp" />
  </div>
</template>

<style scoped>
.page { padding: 18px 16px 24px; }
.profile-icp { justify-content: center; margin-top: 16px; color: var(--hx-muted); }
.profile-head {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}
.avatar {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  display: grid;
  place-items: center;
  background: linear-gradient(145deg, #ffd080, var(--hx-warn));
  color: #fff;
  font-size: 22px;
  font-weight: 700;
  flex: none;
}
.meta { flex: 1; min-width: 0; }
h1 { margin: 0; font-size: 20px; }
.profile-head p { margin: 4px 0 0; color: var(--hx-muted); font-size: 13px; }
.edit {
  margin-top: 6px;
  border: 0;
  background: transparent;
  color: var(--hx-teal-700);
  font-size: 13px;
  font-weight: 800;
  padding: 0;
  font-family: inherit;
  cursor: pointer;
}
.switch {
  border: 0;
  background: var(--hx-n-100);
  color: var(--hx-n-700);
  border-radius: 999px;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: 800;
  font-family: inherit;
  flex: none;
}
.card {
  background: #fff;
  border-radius: var(--hx-r-lg);
  margin-bottom: 14px;
  overflow: hidden;
  box-shadow: var(--hx-elev-2);
}
.group-title {
  padding: 12px 16px 2px;
  font-size: 11px;
  font-weight: 800;
  color: var(--hx-muted);
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
  color: var(--van-field-label-color, var(--hx-n-700));
  font-size: 14px;
}
.pad { padding: 8px 12px 14px; }
.menu :deep(.van-cell) { padding-top: 13px; padding-bottom: 13px; }
</style>
