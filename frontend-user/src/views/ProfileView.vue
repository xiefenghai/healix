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

onMounted(async () => {
  try {
    const res = await api<{ data: any }>('/api/c/v1/profile')
    displayName.value = res.data.displayName ?? ''
    gender.value = res.data.gender ?? ''
    homeTenantId.value = res.data.homeTenantId ?? null
  } catch (e) {
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
      <van-field v-model="gender" label="性别" placeholder="如 MALE / FEMALE" />
      <div class="pad">
        <van-button round block type="primary" :loading="saving" @click="save">保存资料</van-button>
      </div>
    </section>

    <section class="card menu">
      <van-cell title="邀请码入组" is-link to="/join" />
      <van-cell title="健康数据" is-link to="/health" />
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
.pad { padding: 8px 12px 14px; }
.menu :deep(.van-cell) { font-size: 15px; }
:deep(.van-button--primary) {
  background: linear-gradient(90deg, #5cb8b8, #2b9e9e);
  border: 0;
}
</style>
