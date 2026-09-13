<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showToast } from 'vant'
import { api, clearToken } from '../api/http'

const router = useRouter()
const displayName = ref('')
const gender = ref('')
const homeTenantId = ref<number | null>(null)
const saving = ref(false)

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
      <div>
        <h1>{{ displayName || '我的档案' }}</h1>
        <p>{{ homeTenantId ? '已入组 · 核心能力已开启' : '空态 · 可完善资料与入组' }}</p>
      </div>
    </header>

    <section class="card">
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

    <section class="card menu">
      <van-cell title="就诊人管理" is-link to="/patient-cards" />
      <van-cell title="健康档案" is-link to="/archive" />
      <van-cell title="健康数据" is-link to="/health-data?tab=vitals" />
      <van-cell title="管理方案" is-link to="/care-plan" />
      <van-cell title="用药管理" is-link to="/medications" />
      <van-cell title="随访记录" is-link to="/followups" />
      <van-cell title="管理报告" is-link to="/management-reports" />
      <van-cell title="消息中心" is-link to="/notifications" />
      <van-cell title="联系健管师团队" is-link to="/care-chat?contact=1" />
      <van-cell title="激活码添加档案" is-link to="/activate" />
      <van-cell title="邀请码入组" is-link to="/join" />
      <van-cell title="健康助手" is-link to="/discover" />
      <van-cell title="退出登录" is-link @click="logout" />
    </section>
  </div>
</template>

<style scoped>
.page { padding: 18px 16px 24px; }
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
  background: linear-gradient(145deg, #ffd080, #f5a623);
  color: #fff;
  font-size: 22px;
  font-weight: 700;
}
h1 { margin: 0; font-size: 20px; }
.profile-head p { margin: 4px 0 0; color: var(--hx-muted); font-size: 13px; }
.card {
  background: #fff;
  border-radius: 18px;
  margin-bottom: 14px;
  overflow: hidden;
  box-shadow: var(--hx-shadow);
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
  color: var(--van-field-label-color, #646566);
  font-size: 14px;
}
.pad { padding: 8px 12px 14px; }
.menu :deep(.van-cell) { font-size: 15px; }
:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
</style>
