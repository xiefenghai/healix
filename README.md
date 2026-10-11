# Healix

个人健康管理平台（C / B / Ops）。仓库为 monorepo：后端 + 双前端。

## 目录

```text
healix/
├── backend/                 # Spring Boot 多模块
│   ├── health-common/
│   ├── health-core/
│   ├── health-agent/
│   ├── health-app/          # 统一 API：/api/c|b|ops/v1
│   └── pom.xml
├── frontend-user/           # C 端 H5（P1；后续可迁小程序）
├── frontend-admin/          # B + Ops 同壳 PC 后台
├── docs/
│   ├── SaaS架构.md
│   ├── 数据模型.md
│   ├── 租户管理-超级管理员权限.md
│   └── 租户管理员.md
└── README.md
```

## 启动

### 后端

```bash
cd backend
# 按需配置环境变量，参考仓库根 .env.example
export JAVA_HOME=.../jdk-21.jdk/Contents/Home
mvn -pl health-app -am spring-boot:run
```

默认端口 `8080`。Ops 启动账号见 `healix.bootstrap.*`（默认 `opsadmin` / `OpsAdmin123!`）。

### C 端

```bash
cd frontend-user
npm install
npm run dev     # http://localhost:5173  → 代理 /api → 8080
```

### 管理端（B / Ops）

```bash
cd frontend-admin
npm install
npm run dev     # http://localhost:5174
```

登录页先选入口：**平台超管** / **租户管理** / **机构工作台**；Token `aud` 与角色互不串用。

- 平台超管：Ops（默认 `opsadmin` / `OpsAdmin123!`）
- 租户管理：B 端 `TENANT_ADMIN`
- 机构工作台：健管师/医生等，先选机构再管患者

## 文档

- `docs/SaaS架构.md` — 业务与技术架构 v0.3
- `docs/数据模型.md` — 数据模型
- `docs/Healix-整体技术实现方案.md` — 整体架构 / 数据库 / Agent 实现（当前落地）
- `docs/Healix-整体技术实现方案.html` — 同上，浏览器可读版
- `docs/健管智能体-演示脚本.md` — 领导内部演示口径
- `docs/组内技术分享-Healix健管智能体.md` — 组内研发分享（演示 + 技术）
- `docs/Healix-产品与技术介绍.html` — 产品与技术投影介绍页
- `docs/租户管理-超级管理员权限.md` — Ops 超管租户/账号方案 v0.2
- `docs/租户管理员.md` — B 端租户管理员方案 v0.2（已定稿）
- `docs/部署手册.md` — 线上部署（ECS + RDS + Redis + Nginx，B + C）
