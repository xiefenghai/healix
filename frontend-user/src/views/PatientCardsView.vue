<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { showConfirmDialog, showToast } from 'vant'
import { api, setToken } from '../api/http'

interface PatientCard {
  id: string
  peopleId: string
  displayName: string
  relation?: string
  identityValueMask?: string
}

const router = useRouter()
const cards = ref<PatientCard[]>([])
const loading = ref(false)
const showAdd = ref(false)
const form = ref({
  displayName: '',
  identityType: 'ID_CARD',
  identityValue: '',
  relation: 'SELF',
  mobile: '',
})

const relationOptions = [
  { text: '本人', value: 'SELF' },
  { text: '父母', value: 'PARENT' },
  { text: '子女', value: 'CHILD' },
  { text: '配偶', value: 'SPOUSE' },
  { text: '其他', value: 'OTHER' },
]

const relationLabelMap = Object.fromEntries(relationOptions.map((o) => [o.value, o.text]))

function cardLabel(c: PatientCard) {
  const relation = relationLabelMap[c.relation || ''] || c.relation || ''
  return [relation, c.identityValueMask].filter(Boolean).join(' · ')
}

function cycleRelation() {
  const order = relationOptions.map((o) => o.value)
  const idx = order.indexOf(form.value.relation)
  form.value.relation = order[(idx + 1) % order.length]
}

async function load() {
  loading.value = true
  try {
    const res = await api<{ data: PatientCard[] }>('/api/c/v1/patient-cards')
    cards.value = res.data || []
  } catch (e) {
    showToast(e instanceof Error ? e.message : '加载失败')
  } finally {
    loading.value = false
  }
}

async function selectCard(card: PatientCard) {
  try {
    const res = await api<{ data: { accessToken: string } }>(`/api/c/v1/patient-cards/${card.id}/select`, {
      method: 'POST',
    })
    setToken(res.data.accessToken)
    showToast(`已切换到 ${card.displayName}`)
    await router.replace('/home')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '切换失败')
  }
}

async function addCard() {
  try {
    const res = await api<{ data: { accessToken: string } }>('/api/c/v1/patient-cards', {
      method: 'POST',
      body: JSON.stringify(form.value),
    })
    setToken(res.data.accessToken)
    showToast('已添加就诊人')
    showAdd.value = false
    await router.replace('/home')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '添加失败')
  }
}

async function removeCard(card: PatientCard) {
  try {
    await showConfirmDialog({ title: '移除就诊人', message: `确定移除「${card.displayName}」？不会删除档案本身。` })
    const res = await api<{ data: { accessToken: string } }>(`/api/c/v1/patient-cards/${card.id}`, {
      method: 'DELETE',
    })
    setToken(res.data.accessToken)
    await load()
  } catch (e) {
    if (e !== 'cancel') showToast(e instanceof Error ? e.message : '移除失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <van-nav-bar title="就诊人" left-arrow @click-left="router.back()" />
    <div class="body">
      <van-empty v-if="!loading && !cards.length" description="还没有就诊人">
        <template #default>
          <div class="empty-actions">
            <van-button type="primary" size="small" round @click="showAdd = true">添加就诊人</van-button>
            <van-button plain size="small" round @click="router.push('/activate')">用激活码添加</van-button>
          </div>
        </template>
      </van-empty>
      <van-cell-group v-else inset>
        <van-cell
          v-for="c in cards"
          :key="c.id"
          :title="c.displayName"
          :label="cardLabel(c)"
          is-link
          @click="selectCard(c)"
        >
          <template #right-icon>
            <van-button size="mini" plain type="danger" @click.stop="removeCard(c)">移除</van-button>
          </template>
        </van-cell>
      </van-cell-group>
      <div v-if="cards.length" class="actions">
        <van-button block round type="primary" @click="showAdd = true">添加就诊人</van-button>
        <van-button block round plain style="margin-top: 10px" @click="router.push('/activate')">激活码添加机构档案</van-button>
      </div>
    </div>

    <van-popup v-model:show="showAdd" position="bottom" round :style="{ padding: '16px' }">
      <h3>添加就诊人</h3>
      <p class="tip">需填写证件。若该人已在机构建档，请改用激活码。</p>
      <van-field v-model="form.displayName" label="姓名" />
      <van-field v-model="form.identityValue" label="身份证号" />
      <van-field v-model="form.mobile" label="手机" placeholder="选填" />
      <van-field
        is-link
        readonly
        label="关系"
        :model-value="relationLabelMap[form.relation] || form.relation"
        @click="cycleRelation"
      />
      <van-button block type="primary" round style="margin-top: 12px" @click="addCard">保存</van-button>
    </van-popup>
  </div>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--hx-bg); }
.body { padding: 12px 0 24px; }
.empty-actions { display: flex; gap: 10px; justify-content: center; margin-top: 12px; }
.actions { padding: 16px; }
h3 { margin: 0 0 8px; }
.tip { margin: 0 0 12px; font-size: 12px; color: var(--hx-muted); }
</style>
