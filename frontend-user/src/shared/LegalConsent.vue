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
    <label class="row">
      <input v-model="terms" class="ck-input" type="checkbox" />
      <span class="ck-slot" aria-hidden="true">
        <span class="ck">
          <svg class="ck-mark" viewBox="0 0 24 24" fill="none">
            <path
              d="M6 12.5l4 4L18 8"
              stroke="#fff"
              stroke-width="2.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </span>
      </span>
      <span class="label">
        我已阅读并同意
        <a @click.stop.prevent="viewing = TERMS">《用户协议》</a>和<a @click.stop.prevent="viewing = PRIVACY">《隐私政策》</a>
      </span>
    </label>

    <label class="row">
      <input v-model="sensitive" class="ck-input" type="checkbox" />
      <span class="ck-slot" aria-hidden="true">
        <span class="ck">
          <svg class="ck-mark" viewBox="0 0 24 24" fill="none">
            <path
              d="M6 12.5l4 4L18 8"
              stroke="#fff"
              stroke-width="2.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </span>
      </span>
      <span class="label">
        同意处理我的健康信息、身份证号等敏感个人信息，用于提供健康管理服务
      </span>
    </label>

    <p class="note">
      <svg class="note-ico" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M12 3.5l7 3v5.2c0 4.4-2.9 8.2-7 9.8-4.1-1.6-7-5.4-7-9.8V6.5l7-3z" />
      </svg>
      <span>本服务为健康管理工具，不提供医疗诊断。</span>
    </p>

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
.consent {
  margin: 14px 0 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.row {
  display: flex;
  align-items: flex-start; /* 禁止 center：多行时圆圈会漂到整段中间 */
  gap: 8px;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.ck-input {
  position: absolute;
  width: 1px;
  height: 1px;
  margin: -1px;
  padding: 0;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
  opacity: 0;
}
/* 首行高度槽：高度 = 文字 line-height，圆圈在槽内垂直居中 → 钉死第一行 */
.ck-slot {
  flex: none;
  flex-shrink: 0;
  width: 20px;
  height: 18px; /* 12px * 1.5 */
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: 0;
}
.ck {
  flex: none;
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  min-width: 20px;
  min-height: 20px;
  border-radius: 50%;
  border: 1.5px solid #D1D5DB;
  background: transparent;
  box-sizing: border-box;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin: 0;
  transition: background 0.15s ease, border-color 0.15s ease;
}
.ck-mark {
  width: 11px;
  height: 11px;
  display: block;
  opacity: 0;
  transform: scale(0.6);
  transition: opacity 0.15s ease, transform 0.15s ease;
  pointer-events: none;
}
.ck-input:checked + .ck-slot .ck {
  border-color: #10B981;
  background: #10B981;
  width: 20px;
  height: 20px;
}
.ck-input:checked + .ck-slot .ck-mark {
  opacity: 1;
  transform: scale(1);
}
.ck-input:focus-visible + .ck-slot .ck {
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.22);
}
.label {
  flex: 1;
  min-width: 0;
  padding: 0;
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: #4B5563;
}
a {
  color: #10B981;
  font-weight: 600;
}
.note {
  margin: 2px 0 0;
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #9CA3AF;
}
.note-ico {
  flex: none;
  width: 14px;
  height: 14px;
  margin-top: 1px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.sheet {
  height: 100%;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
}
</style>
