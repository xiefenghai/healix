<script setup lang="ts">
import { useRouter } from 'vue-router'
import { showToast } from 'vant'

const router = useRouter()
</script>

<template>
  <div class="page">
    <header class="head">
      <button type="button" class="back" @click="router.back()">‹ 返回</button>
      <h1>AI健康管家</h1>
      <p>AI问答功能近期开放</p>
    </header>
    <div class="hero">
      <div class="orb" aria-hidden="true">AI</div>
      <h2>AI健康管家</h2>
      <p class="desc">可以帮你记指标、看今日进度、<br />讲解已发布方案与报告</p>
      <span class="soon">AI问答功能近期开放</span>
      <van-button round type="primary" class="try" @click="showToast('AI问答功能近期开放')">
        试试问问
      </van-button>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 18px 16px 28px;
  background: var(--hx-bg-flat);
}
.back {
  display: inline-flex;
  align-items: center;
  margin: 0 0 8px;
  padding: 0;
  border: 0;
  background: none;
  font-size: 14px;
  font-weight: 700;
  color: var(--hx-teal-700);
  font-family: inherit;
  cursor: pointer;
}
.head h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 900;
}
.head p {
  margin: 6px 0 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
}
.hero {
  margin-top: 48px;
  text-align: center;
  padding: 12px 8px;
}
.orb {
  width: 88px;
  height: 88px;
  margin: 0 auto 18px;
  border-radius: 28px;
  display: grid;
  place-items: center;
  color: #fff;
  font-size: 28px;
  font-weight: 900;
  background: linear-gradient(145deg, var(--hx-teal-300), var(--hx-teal-700));
  box-shadow: var(--hx-elev-3);
}
.hero h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 900;
}
.desc {
  margin: 10px auto 0;
  max-width: 28ch;
  font-size: 13px;
  font-weight: 600;
  color: var(--hx-muted);
  line-height: 1.55;
}
.soon {
  display: inline-block;
  margin-top: 18px;
  padding: 8px 14px;
  border-radius: 999px;
  background: var(--hx-warn-bg);
  color: var(--hx-warn-text);
  font-size: 12px;
  font-weight: 800;
}
.try {
  display: block;
  width: 200px;
  margin: 20px auto 0;
}
</style>
