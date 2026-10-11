<script setup lang="ts">
/** 备案号由构建变量注入：VITE_ICP_NO（如 粤ICP备2026000000号-1）、VITE_PSB_NO（公安备案号，可选） */
const icp = import.meta.env.VITE_ICP_NO as string | undefined
const psb = import.meta.env.VITE_PSB_NO as string | undefined
const psbCode = psb?.match(/\d+/)?.[0]
</script>

<template>
  <footer class="icp">
    <div>Healix · 个人技术演示项目</div>
    <div v-if="icp || psb" class="nos">
      <a v-if="icp" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">{{ icp }}</a>
      <a
        v-if="psb"
        :href="`https://beian.mps.gov.cn/#/query/webSearch?code=${psbCode}`"
        target="_blank"
        rel="noopener"
      >{{ psb }}</a>
    </div>
  </footer>
</template>

<style scoped>
.icp { padding: 16px 16px 24px; text-align: center; font-size: 12px; line-height: 1.8; color: var(--hx-muted); }
.nos { display: flex; justify-content: center; flex-wrap: wrap; gap: 0 12px; }
.icp a { color: inherit; }
</style>
