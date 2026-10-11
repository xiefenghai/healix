<script setup lang="ts">
import { ref } from 'vue'
import { PRIVACY, TERMS, type LegalDoc } from './legal'
import LegalDocument from './LegalDocument.vue'

/** 两项均勾选才算同意：协议 + 敏感个人信息单独同意（个保法第 29 条） */
const terms = defineModel<boolean>('terms', { default: false })
const sensitive = defineModel<boolean>('sensitive', { default: false })

const viewing = ref<LegalDoc | null>(null)
</script>

<template>
  <div class="consent">
    <van-checkbox v-model="terms" shape="square" icon-size="16px">
      我已阅读并同意
      <a @click.stop.prevent="viewing = TERMS">《用户协议》</a>和<a @click.stop.prevent="viewing = PRIVACY">《隐私政策》</a>
    </van-checkbox>
    <van-checkbox v-model="sensitive" shape="square" icon-size="16px">
      同意处理我的健康信息、身份证号等敏感个人信息，用于提供健康管理服务
    </van-checkbox>
    <p class="note">本服务为健康管理工具，不提供医疗诊断。</p>

    <van-popup
      :show="!!viewing"
      position="bottom"
      round
      closeable
      teleport="body"
      :style="{ height: '85%' }"
      @update:show="(v: boolean) => !v && (viewing = null)"
    >
      <div class="sheet">
        <LegalDocument v-if="viewing" :doc="viewing" />
      </div>
    </van-popup>
  </div>
</template>

<style scoped>
.consent { margin: 14px 0 16px; display: flex; flex-direction: column; gap: 10px; }
.consent :deep(.van-checkbox) { align-items: flex-start; }
.consent :deep(.van-checkbox__label) { font-size: 12px; line-height: 1.6; color: var(--hx-muted); }
.consent :deep(.van-checkbox__icon) { margin-top: 2px; }
a { color: var(--hx-teal); }
.note { margin: 0; font-size: 12px; color: var(--hx-muted); }
.sheet { height: 100%; overflow-y: auto; -webkit-overflow-scrolling: touch; }
</style>
