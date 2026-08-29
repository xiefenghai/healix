<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const entries = [
  {
    key: 'ops',
    title: '平台超管',
    desc: '开通租户、患者明文检索与审计',
    path: '/login/ops',
    tag: 'Ops',
    accent: '#6366f1',
  },
  {
    key: 'tenant',
    title: '租户管理',
    desc: '建机构、生成患者邀请码',
    path: '/login/tenant',
    tag: '租户管理员',
    accent: '#0d9488',
  },
  {
    key: 'workspace',
    title: '机构工作台',
    desc: '选择机构后高效管理患者',
    path: '/login/workspace',
    tag: '健管 / 医护',
    accent: '#0891b2',
  },
] as const
</script>

<template>
  <div class="page">
    <header class="top">
      <div class="brand">
        <span class="brand-mark" />
        <span>Healix 健康管理后台</span>
      </div>
      <el-tag effect="plain" class="env-tag">管理端</el-tag>
    </header>

    <section class="hero">
      <h1>选择工作入口</h1>
      <p>专业、高效、可信赖 · 三端账号与 Token 互不串用</p>
    </section>

    <div class="grid">
      <button
        v-for="item in entries"
        :key="item.key"
        type="button"
        class="card"
        :style="{ '--card-accent': item.accent }"
        @click="router.push(item.path)"
      >
        <span class="card-bar" />
        <el-tag size="small" effect="plain" class="card-tag">{{ item.tag }}</el-tag>
        <h2>{{ item.title }}</h2>
        <p>{{ item.desc }}</p>
        <span class="cta">进入登录 →</span>
      </button>
    </div>

    <footer class="footer">Healix · 智慧健康管理平台</footer>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 28px 40px 40px;
  background:
    radial-gradient(ellipse 80% 50% at 50% -20%, rgba(13, 148, 136, 0.08), transparent),
    var(--admin-bg);
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 960px;
  margin: 0 auto 40px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 16px;
  font-weight: 600;
  color: var(--admin-text);
}

.brand-mark {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--admin-primary), #0891b2);
  box-shadow: 0 4px 12px rgba(13, 148, 136, 0.25);
}

.env-tag {
  border-color: var(--admin-border) !important;
  color: var(--admin-muted) !important;
  background: var(--admin-card) !important;
}

.hero {
  max-width: 960px;
  margin: 0 auto 28px;
  text-align: center;
}

.hero h1 {
  margin: 0 0 10px;
  font-size: 26px;
  font-weight: 600;
  color: var(--admin-text);
  letter-spacing: -0.02em;
}

.hero p {
  margin: 0;
  color: var(--admin-muted);
  font-size: 14px;
}

.grid {
  max-width: 960px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 20px;
}

.card {
  position: relative;
  overflow: hidden;
  text-align: left;
  padding: 22px 22px 20px;
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius);
  background: var(--admin-card);
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
  box-shadow: var(--admin-shadow);
}

.card-bar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--card-accent, var(--admin-primary));
  opacity: 0.85;
}

.card:hover {
  transform: translateY(-4px);
  box-shadow: var(--admin-shadow-md);
  border-color: #cbd5e1;
}

.card-tag {
  margin-top: 4px;
  border-color: var(--admin-border) !important;
  color: var(--admin-text-secondary) !important;
}

.card h2 {
  margin: 14px 0 8px;
  font-size: 17px;
  font-weight: 600;
  color: var(--admin-text);
}

.card p {
  margin: 0;
  color: var(--admin-muted);
  font-size: 13px;
  line-height: 1.55;
  min-height: 40px;
}

.cta {
  display: inline-block;
  margin-top: 18px;
  color: var(--admin-primary);
  font-size: 13px;
  font-weight: 600;
}

.footer {
  max-width: 960px;
  margin: 48px auto 0;
  text-align: center;
  font-size: 12px;
  color: var(--admin-muted);
}

@media (max-width: 960px) {
  .grid {
    grid-template-columns: 1fr;
  }
  .page {
    padding: 16px;
  }
}
</style>
