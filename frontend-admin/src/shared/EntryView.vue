<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const entries = [
  {
    key: 'ops',
    title: 'Ops 平台超管',
    desc: '全局租户、机构、用户与权限管理，平台级数据看板与系统配置。',
    path: '/login/ops',
    tag: 'PLATFORM',
    meta: '仅限平台管理员',
    metaExtra: '14 项核心能力',
    tone: 'ops',
  },
  {
    key: 'tenant',
    title: '租户管理中心',
    desc: '机构入驻审核、品牌配置、计费规则、子机构管理。',
    path: '/login/tenant',
    tag: 'TENANT',
    meta: '租户管理员',
    metaExtra: '9 项核心能力',
    tone: 'tenant',
  },
  {
    key: 'workspace',
    title: '机构工作台',
    desc: '健管师 / 医生日常工作：患者管理、智能驾驶舱、健康方案、依从性看板。',
    path: '/login/workspace',
    tag: 'WORKSPACE',
    meta: '健管师 · 医生 · 机构管理员',
    metaExtra: '28 项核心能力',
    tone: 'workspace',
    recommend: true,
  },
] as const
</script>

<template>
  <div class="page">
    <section class="brand">
      <div class="brand-inner">
        <div class="brand-header">
          <div class="brand-logo">
            <span class="logo-mark">H</span>
            <span class="logo-text">Healix</span>
          </div>
          <div class="brand-meta">
            <span class="dot" />
            <span>管理端 · 生产环境</span>
          </div>
        </div>

        <div class="brand-hero">
          <div class="brand-tag">
            <span class="pulse" />
            AI · 医疗 · 全周期健康管理
          </div>
          <h1>
            让每一次<br />
            <span>健康干预</span><br />
            都精准及时
          </h1>
          <p>
            基于多病种知识图谱与 AI 智能体，为健管师、医生、机构提供从风险识别、方案制定到随访依从的全流程工作平台。
          </p>

          <div class="brand-stats">
            <div class="stat">
              <div class="stat-v">智能驾驶舱</div>
              <div class="stat-k">今日优先 · 对话驱动</div>
            </div>
            <div class="stat">
              <div class="stat-v">方案起草</div>
              <div class="stat-k">AI 出草稿 · 人确认</div>
            </div>
            <div class="stat">
              <div class="stat-v">单据 OCR</div>
              <div class="stat-k">预填核对 · 再入库</div>
            </div>
          </div>
        </div>

        <div class="brand-foot">
          <div>© 2026 Healix Health Tech. All rights reserved.</div>
        </div>
      </div>
    </section>

    <section class="choose">
      <div class="choose-inner">
        <div class="choose-head">
          <h2>选择登录入口</h2>
          <p>请根据你的身份选择对应的工作台入口 · 三端账号与 Token 互不串用</p>
          <div class="seg" aria-hidden="true">
            <span class="on">三种身份</span>
            <span>单点登录</span>
            <span>企业 SSO</span>
          </div>
        </div>

        <div class="entrance-list">
          <button
            v-for="item in entries"
            :key="item.key"
            type="button"
            class="entrance"
            :class="[`e-${item.tone}`]"
            @click="router.push(item.path)"
          >
            <span v-if="item.recommend" class="rec">推荐入口</span>
            <span class="ico" aria-hidden="true">
              <svg v-if="item.tone === 'ops'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 2L2 7l10 5 10-5-10-5z" />
                <path d="M2 17l10 5 10-5" />
                <path d="M2 12l10 5 10-5" />
              </svg>
              <svg v-else-if="item.tone === 'tenant'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M3 21h18M5 21V7l8-4v18M19 21V11l-6-4" />
                <circle cx="9" cy="9" r="1.5" fill="currentColor" />
                <circle cx="9" cy="14" r="1.5" fill="currentColor" />
              </svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
                <path d="M16 3.13a4 4 0 0 1 0 7.75" stroke-dasharray="2 2" />
              </svg>
            </span>
            <div class="body">
              <h3>
                {{ item.title }}
                <span class="badge">{{ item.tag }}</span>
              </h3>
              <p>{{ item.desc }}</p>
              <div class="meta">
                <span>{{ item.meta }}</span>
                <span class="sep">·</span>
                <span>{{ item.metaExtra }}</span>
              </div>
            </div>
            <span class="go" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M5 12h14M13 5l7 7-7 7" />
              </svg>
            </span>
          </button>
        </div>

        <div class="choose-foot">
          <div>
            忘记账号？
            <span class="foot-em">联系企业管理员</span>
          </div>
          <div class="links">
            <span>帮助中心</span>
            <span>三端隔离</span>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  min-height: 100vh;
  background: var(--ink-50);
}

.brand {
  position: relative;
  padding: 48px 56px;
  color: #fff;
  overflow: hidden;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 45%, #1e3a8a 100%);
}

.brand::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(900px 600px at 80% 10%, rgba(44, 126, 248, 0.45), transparent 60%),
    radial-gradient(700px 500px at 10% 90%, rgba(0, 184, 169, 0.35), transparent 60%);
  pointer-events: none;
}

.brand::after {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.04) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse at center, #000 30%, transparent 80%);
  -webkit-mask-image: radial-gradient(ellipse at center, #000 30%, transparent 80%);
  pointer-events: none;
}

.brand-inner {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: calc(100vh - 96px);
}

.brand-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-mark {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: linear-gradient(135deg, var(--brand-500), var(--teal-500));
  display: grid;
  place-items: center;
  font-weight: 800;
  font-size: 18px;
  box-shadow: 0 6px 16px rgba(44, 126, 248, 0.45);
}

.logo-text {
  font-size: 20px;
  font-weight: 700;
  letter-spacing: -0.3px;
}

.brand-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
}

.dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--teal-500);
  box-shadow: 0 0 0 4px rgba(0, 184, 169, 0.18);
}

.brand-hero {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 48px 0;
  max-width: 520px;
}

.brand-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0.3px;
  color: #cbd5e1;
  width: fit-content;
  backdrop-filter: blur(8px);
}

.pulse {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--teal-500);
  animation: pulse 1.6s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.4;
  }
}

.brand-hero h1 {
  margin: 20px 0 0;
  font-size: 44px;
  line-height: 1.1;
  font-weight: 800;
  letter-spacing: -1.2px;
}

.brand-hero h1 span {
  background: linear-gradient(135deg, #60a5fa, var(--teal-500));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.brand-hero p {
  margin: 20px 0 0;
  font-size: 15px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.7);
  max-width: 460px;
}

.brand-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-top: 40px;
}

.stat {
  padding: 18px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(12px);
}

.stat-v {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.2px;
  line-height: 1.35;
}

.stat-k {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
  margin-top: 8px;
  letter-spacing: 0.2px;
  line-height: 1.4;
}

.brand-foot {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
}

.choose {
  padding: 48px 56px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  background: var(--ink-50);
}

.choose-inner {
  max-width: 560px;
  width: 100%;
  margin: 0 auto;
}

.choose-head h2 {
  margin: 0;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.5px;
  color: var(--ink-900);
}

.choose-head p {
  margin: 8px 0 0;
  color: var(--ink-600);
  font-size: 14px;
  line-height: 1.5;
}

.seg {
  display: inline-flex;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 8px;
  padding: 3px;
  margin-top: 18px;
  font-size: 12px;
}

.seg span {
  padding: 6px 14px;
  border-radius: 6px;
  color: var(--ink-600);
}

.seg .on {
  background: var(--brand-500);
  color: #fff;
  font-weight: 500;
}

.entrance-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-top: 32px;
}

.entrance {
  position: relative;
  display: flex;
  align-items: stretch;
  padding: 20px 22px;
  background: #fff;
  border: 1px solid var(--ink-200);
  border-radius: 16px;
  cursor: pointer;
  text-align: left;
  transition: all 0.25s ease;
  overflow: hidden;
  font: inherit;
  color: inherit;
}

.entrance:hover {
  transform: translateY(-2px);
  box-shadow: 0 20px 40px -12px rgba(15, 23, 42, 0.12), 0 8px 16px -4px rgba(15, 23, 42, 0.06);
  border-color: transparent;
}

.entrance::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: transparent;
  transition: width 0.25s;
}

.entrance:hover::before {
  width: 4px;
}

.ico {
  width: 52px;
  height: 52px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  margin-right: 18px;
  color: #fff;
}

.ico svg {
  width: 26px;
  height: 26px;
}

.body {
  flex: 1;
  min-width: 0;
}

.body h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.2px;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--ink-900);
}

.badge {
  font-size: 10px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 6px;
  letter-spacing: 0.3px;
}

.body p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--ink-600);
  line-height: 1.6;
}

.meta {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-top: 10px;
  font-size: 11px;
  color: var(--ink-400);
}

.meta .sep {
  opacity: 0.6;
}

.go {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: var(--ink-400);
  background: var(--ink-100);
  transition: 0.25s;
  align-self: center;
  flex-shrink: 0;
  margin-left: 16px;
}

.entrance:hover .go {
  background: var(--brand-500);
  color: #fff;
  transform: translateX(4px);
}

.rec {
  position: absolute;
  top: 14px;
  right: 14px;
  font-size: 10px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  background: linear-gradient(135deg, var(--amber-500), var(--rose-500));
  color: #fff;
  letter-spacing: 0.5px;
  z-index: 1;
}

.e-ops .ico {
  background: linear-gradient(135deg, #1e3a8a, #3b82f6);
}
.e-ops:hover::before {
  background: #3b82f6;
}
.e-ops .badge {
  background: #dbeafe;
  color: #1d4ed8;
}

.e-tenant .ico {
  background: linear-gradient(135deg, var(--violet-500), #a78bfa);
}
.e-tenant:hover::before {
  background: var(--violet-500);
}
.e-tenant .badge {
  background: #ede9fe;
  color: #7c3aed;
}

.e-workspace .ico {
  background: linear-gradient(135deg, var(--teal-500), #2dd4bf);
}
.e-workspace:hover::before {
  background: var(--teal-500);
}
.e-workspace .badge {
  background: #ccfbf1;
  color: #0f766e;
}

.choose-foot {
  margin-top: 36px;
  padding-top: 24px;
  border-top: 1px solid var(--ink-200);
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: var(--ink-600);
}

.foot-em {
  color: var(--brand-500);
  font-weight: 500;
}

.choose-foot .links {
  display: flex;
  gap: 18px;
  color: var(--ink-600);
}

@media (max-width: 980px) {
  .page {
    grid-template-columns: 1fr;
  }
  .brand {
    padding: 40px 32px;
    min-height: 400px;
  }
  .brand-hero {
    padding: 24px 0;
  }
  .brand-hero h1 {
    font-size: 34px;
  }
  .brand-stats {
    gap: 10px;
  }
  .stat {
    padding: 14px;
  }
  .stat-v {
    font-size: 14px;
  }
  .choose {
    padding: 40px 32px;
  }
}
</style>
