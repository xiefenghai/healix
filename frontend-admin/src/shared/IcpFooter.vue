<script setup lang="ts">
/** 备案号由构建变量注入：VITE_ICP_NO（如 粤ICP备2026000000号-1）、VITE_PSB_NO（公安备案号，可选） */
const icp = import.meta.env.VITE_ICP_NO as string | undefined
const psb = import.meta.env.VITE_PSB_NO as string | undefined
const psbCode = psb?.match(/\d+/)?.[0]
</script>

<template>
  <div v-if="icp || psb" class="icp">
    <a v-if="icp" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">{{ icp }}</a>
    <a
      v-if="psb"
      :href="`https://beian.mps.gov.cn/#/query/webSearch?code=${psbCode}`"
      target="_blank"
      rel="noopener"
    >{{ psb }}</a>
  </div>
</template>

<style scoped>
.icp { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 12px; }
.icp a { color: inherit; text-decoration: none; }
.icp a:hover { text-decoration: underline; }
</style>
