# Healix 个人健康管理平台 — 业务与技术架构方案

| 项 | 内容 |
|---|---|
| 版本 | **v0.3.1**（患者模型跟进：`people_*` 租户隔离） |
| 日期 | 2026-08-27 |
| 状态 | 架构口径与落地 DDL 对齐中；详表见 `数据模型.md` v0.2 |
| 范围 | C 端患者 / B 端健管师·医生 / Ops 系统运维；SaaS 租户-机构模型 |
| 依据 | Grill 决策 Q1–Q35；机构工作台专项 Grill（people 模型） |

---

## 0. 决策摘要（执行口径）

本节是全文最高优先级约束；下文细节不得与之冲突。

| 主题 | 定案 |
|---|---|
| 患者身份 | **租户内** `people_profile`（people = 患者）；C/B **账号分离**；一人多证 / 可无证 |
| 租户归属 | 患者行自带 `tenant_id`；跨租户同一真人 = **独立两条 people**（废止 `home_tenant_id`） |
| 多机构 | 同租户允许多机构 membership；**禁止跨租户**入组（天然：people 属单租户） |
| 数据隔离 | **体征/报告 = 租户共享**；**随访/工单 = 机构隔离**；**Patient Agent 私聊 = 仅患者** |
| 入组可见性 | 入组即可见该患者本租户历史体征/报告（不含私聊） |
| 退出机构 | 该机构立即不可见；协作记录保留只读 |
| 主管关系 | 首次入组机构 = 主管机构；邀请人 = 默认主管健管师（可改） |
| C 端视图 | **不区分机构**，个人聚合；未入组仅：注册/资料/邀请码（注册须带 `tenantId`） |
| B 端工作机构 | Staff 多机构时**必须切换当前工作机构** |
| 建机构 | **租户管理员自建**；Ops 负责开租户 |
| 邀请 | P1：机构级邀请码 + 有效期可刷新；代录手机号 → P2 |
| 双 Agent | Patient Agent ⊥ Care Copilot，记忆隔离；机构不可见患者私聊 |
| Staff 模型 | 统一 Staff + RBAC；`DOCTOR` 角色 P1 预留、流程不做 |
| 前端 | C 独立（目标小程序）；B+Ops 同壳分权限 |
| P1 登录 | **三端账号密码**；短信/微信后置（需厂商资质） |
| Ops 与 PHI | **可搜明文（A1）**；强制访问审计 + 超管数量严控 |
| P1 范围 | 开租户/建机构邀员/邀请入组/体征/薄 Agent/Ops 检索+审计 |
| P1 推迟 | 报告、预警工单、随访、Care Copilot、医生流程、短信登录 |
| 基础设施 | P1 即部署 MySQL + Redis + PGVector + Influx + One-API（功能可薄） |

**刻意接受的风险**

1. Ops 上帝视角 —— 靠审计与账号数量兜底，非最小权限。
2. 同租户机构对体征/报告不隔离 —— 机构主要是任务与统计边界。
3. 基础设施先行 —— 环境成本换集成少返工。

---

## 1. 背景与目标

### 1.1 产品定位

Healix 是面向医疗机构 / 健管机构的 **SaaS 化个人健康管理平台**。平台以患者持续健康管理为核心，在体征、报告、预警之上引入 AI Agent（患者侧私人管家 + B 端协作副驾），同时为健管师 / 医生提供规模化随访与干预能力。

### 1.2 建设目标

1. **三角色入口**：C / B / Ops 分入口、分认证、分 Token 受众。
2. **SaaS 多租户**：租户 → 机构层级；患者同期单租户；行级 `tenant_id` 隔离。
3. **可运营**：Ops 开通租户与配额；租户管理员自建机构与邀员。
4. **AI 原生**：双 Agent 边界清晰；工具调用带租户/患者上下文；安全阀门可演进。
5. **可演进**：Modulith 单体起步，模块边界清晰。

### 1.3 非目标（本期 / P1 不做）

- 医保结算、HIS 深度对接
- 跨租户数据共享 / 患者同时挂多租户
- 短信 / 微信一键登录（接口预留）
- 医生独立临床流程（仅角色预留）
- 报告上传、预警工单、随访任务、Care Copilot（P2+）
- 等保专用部署 / 独立库 Schema（按需二期）

---

## 2. 用户角色与入口

### 2.1 角色总览

| 端 | 角色 | 说明 | P1 |
|---|---|---|---|
| **C** | 患者 | 档案、入组、体征、Patient Agent | ✅ |
| **B** | 租户管理员 | 建机构、邀员工、租户内配置 | ✅ |
| **B** | 机构管理员 | 本机构成员与邀请码 | ✅ |
| **B** | 健管师 | 患者列表、查看租户内共享健康数据 | ✅ |
| **B** | 医生 | 临床复核等 | 角色预留，流程 ❌ |
| **Ops** | 平台超管 / 运营 | 开租户、配额、审计、患者明文检索 | ✅（检索需审计） |

### 2.2 入口与前端

```text
C 端（独立应用，目标微信小程序；P1 可用账号密码 Web/H5 过渡）
B 端业务 + Ops（同一后台壳，菜单按 aud/角色分离）
```

| 入口 | 路径前缀 | 认证 |
|---|---|---|
| C | `/api/c/v1/**` | `aud=c`，账号密码 |
| B | `/api/b/v1/**` | `aud=b`，账号密码 |
| Ops | `/api/ops/v1/**` | `aud=ops`，账号密码 |
| 公开 | `/api/public/v1/**` | 极少 |

### 2.3 身份隔离

- C 账号与 B 账号即使同一手机号也**不关联**（当两个人）。
- Token 禁止跨入口串用。
- 同一自然人若需两边能力：分别注册，产品不自动打通。

---

## 3. SaaS 组织与数据模型（逻辑）

### 3.1 层级

```text
Platform
 └── Tenant                          # 签约与 PHI 共享边界
      ├── Organization               # 任务/协作/统计边界
      │    ├── Staff（多机构可挂，工作需切换当前 org）
      │    ├── CareTeam / CareTeamMember
      │    └── PeopleOrgMembership
      └── PeopleProfile（租户隔离；people = 患者）
           ├── PeopleAccount?        # C 登录，可空
           ├── PeopleIdentity*       # 多证件 / 可无证
           └── PeopleCareAssignment
```

### 3.2 患者生命周期（C）

```text
注册（须指定 tenantId；空态）
  → 仅：登录、完善资料、输入邀请码
  → 禁止：体征、报告、Agent、消息

使用邀请码加入机构（同租户）
  → 写 membership
  → 首次入组：primary_org = 该机构；primary_care_manager = 邀请人（可改）
  → 开放核心能力

同租户再加入其他机构
  → 允许；C 端仍不切换机构视图

退出某机构
  → 该机构侧不可见该患者（在管列表移除）
  → 协作记录保留只读
```

### 3.3 数据可见性矩阵

| 数据类 | 存储键 | C 端 | 同租户机构 Staff | 其他租户 | Ops |
|---|---|---|---|---|---|
| 患者主档案 | tenant_id + people_id | 本人 | 有 membership 的机构可见 | 否 | 可查（审计） |
| 体征 / 报告 | tenant_id + people_id | 本人聚合 | 同租户凡有该患者 membership 的机构均可见 | 否 | 可查（审计） |
| 随访 / 工单 | tenant_id + org_id | 按产品需要只读相关项（P2） | **仅本机构** | 否 | 可查（审计） |
| Patient Agent 私聊 | people 私域 | 本人 | **否** | 否 | 可查（审计，A1） |
| Care Copilot | staff/org 域（P2） | 否 | 本侧 | 否 | 可查 |

> 正确口径：**租户隔离 PHI，机构隔离协作**。患者不再平台唯一。

### 3.4 请求上下文

```text
RequestContext {
  portal: C | B | OPS
  accountId: Long
  peopleId?: Long               // C（原 patientId）
  staffId?: Long                // B
  tenantId?: Long               // B 必填；C 必填（账号属租户）；OPS 操作目标租户可指定
  currentOrgId?: Long           // B 当前工作机构
  roles: Set<String>
}
```

---

## 4. 业务能力与分期

### 4.1 Phase 1（骨架可演示）

| 域 | 能力 |
|---|---|
| Ops | 开通/停用租户、创建租户管理员、简陋患者明文检索 + **访问审计**、超管账号数量控制 |
| B | 租户管理员建机构；邀员工；切换工作机构；生成/刷新邀请码；患者列表；查看体征 |
| C | 注册登录、完善资料、邀请码入组、体征录入与趋势、Patient Agent（可薄/可接 One-API） |
| 平台 | JWT 分 aud、租户拦截器（含 people_*）、基础审计日志 |
| 基础设施 | MySQL、Redis、PGVector、InfluxDB、One-API **先装好** |

### 4.2 Phase 2+

报告上传与解析、预警规则与工单、随访任务与干预、Care Copilot、医生流程、短信/微信登录、套餐计量深化、MFA、大客户独立 Schema。

### 4.3 Agent 边界

| Agent | 使用者 | 记忆 | 可读数据 |
|---|---|---|---|
| Patient Agent | 患者 | 私有，不与 B 共享 | 本人档案/体征等（入组后） |
| Care Copilot | 健管师/医生（P2） | 独立 | 仅 B 端授权可见的业务数据，**不读**患者私聊 |

---

## 5. 逻辑架构与模块映射

```text
frontend-user (C H5)     frontend-admin (B+Ops 同壳)
        └──── JWT (aud) ────┘
                 │
         backend/health-app（路由/安全/装配）
        /api/c|b|ops/v1
                 │
     ┌───────────┴───────────┐
 health-core              health-agent
 租户/机构/账号/入组/体征    Patient Agent /（P2 Care Copilot）
     │                         │
 MySQL · Redis · PGVector · Influx · One-API · OSS
```

仓库目录：`backend/`（Maven 多模块）+ `frontend-user/` + `frontend-admin/` + `docs/`。

| 模块 | 职责 |
|---|---|
| `health-common` | 错误码、RequestContext、常量 |
| `health-core` | 租户/机构/账号/Staff/患者/membership/体征/审计 |
| `health-agent` | Agent 编排、工具、安全阀门、交互日志 |
| `health-app` | 启动、Security、三端 Controller |
| `frontend-user` | C 端 H5（目标小程序） |
| `frontend-admin` | B+Ops 同壳，菜单按 aud/角色分离 |

ORM：**MyBatis** + 租户行级拦截（平台表白名单）。

---

## 6. 认证授权

### 6.1 认证（P1）

| 入口 | 方式 | Token |
|---|---|---|
| C | 账号 + 密码 | `aud=c`, accountId, peopleId, tenantId |
| B | 账号 + 密码 | `aud=b`, accountId, staffId, tenantId, currentOrgId, roles |
| Ops | 账号 + 密码 | `aud=ops`, accountId, platformRoles |

短信（阿里云/腾讯云等）与微信手机号：预留 SPI，有企业资质再接入。

### 6.2 授权要点

1. C 只能操作本人资源。
2. B 查看患者：同租户 + 存在有效 membership（不要求当前 org 等于 membership.org 才能看体征；协作任务仍按 currentOrg 过滤）。
3. 跨租户：people 行本身带 `tenant_id`，不存在「换租户挂靠」；邀请码仅能入本租户机构。
4. Ops PHI 访问：每条查询写审计（操作者、时间、患者、接口）。

### 6.3 B 端预置角色

| 角色 | 数据/能力 |
|---|---|
| TENANT_ADMIN | 租户治理：建机构、管账号、授角色、设机构权限；**隐式可访问租户下全部机构** |
| CARE_MANAGER | 功能角色；机构工作台；可见范围由其 `staff_org_binding` 决定 |
| DOCTOR | 功能角色；机构工作台；范围同 binding |
| TENANT_OPERATOR | 运营分析（用户使用/履约统计）— **预留，本期不做** |

说明：

- 功能角色与机构解耦（`staff_role_binding.org_id` 恒为 NULL）。
- ~~ORG_ADMIN~~ 已废弃；邀请码：管理员可对任意本租户机构发放，健管师/医生仅限已授权机构。
- 详见 `租户管理员.md` v0.2。

---

## 7. 核心领域对象（逻辑，非最终 DDL）

```text
# 平台 / 租户
Tenant, TenantPlan, FeatureFlag, QuotaUsage
Organization
OrgInviteCode                 # code, org_id, expire_at, enabled

# 身份（分端账号）
PeopleAccount                 # C 登录（租户隔离）
StaffAccount                  # B 登录
OpsAccount                    # Ops 登录
PeopleProfile                 # tenant_id, 档案字段
PeopleIdentity                # 多证件
PeopleBasicArchive            # 按建档机构
StaffProfile                  # tenant_id, 资质扩展预留
StaffOrgBinding               # staff 多机构
StaffRoleBinding
PeopleOrgMembership
PeopleCareAssignment          # primary_org_id, primary_care_manager_id
CareTeam / CareTeamMember

# 健康（租户共享）
VitalRecord                   # tenant_id, people_id, ...

# 协作（机构隔离）— P2
FollowUpTask, AlertTicket, InterventionRecord, HealthReport

# Agent
AgentSession / AgentMessage   # agent_type: PATIENT | CARE_COPILOT；people_id
AgentInteractionLog

# 审计
AuditLog                      # 含 Ops PHI 访问；people_id
```

建议公共字段：`id, created_at, updated_at, created_by, updated_by, deleted`；业务表另加 `tenant_id`（`ops_account` 等平台表除外）。

---

## 8. 技术选型

| 层级 | 选型 |
|---|---|
| JDK / 框架 | 21 + Spring Boot 3.3.x |
| ORM | MyBatis |
| 业务库 | MySQL 8 |
| 缓存 | Redis |
| 向量 | PostgreSQL + pgvector |
| 时序 | InfluxDB 2.x |
| AI 网关 | One-API + LangChain4j / Spring AI |
| 安全 | Spring Security + JWT（分 aud） |
| 可观测 | Micrometer + Zipkin + 结构化日志（带 tenantId） |

部署：本机/主机直接跑中间件即可，不依赖 Docker。

---

## 9. 多租户落地要点

1. MyBatis 拦截器：业务 SQL 自动填充/过滤 `tenant_id`；白名单含租户表、C 账号表、Ops 表等。
2. C 空态无 `tenant_id`：体征等写接口在无 membership 时直接拒绝。
3. Redis：`healix:{tenantId}:...`；患者私聊记忆用 `patientId` 且不进 B 端工具。
4. 向量 metadata 必须带 `tenantId`。
5. 邀请码：机构级、可刷新、带过期时间。

---

## 10. 关键链路（P1）

### 10.1 Ops 开通租户

创建 Tenant → 默认 Organization（可选）→ StaffAccount(TENANT_ADMIN) → 审计。

### 10.2 患者入组

C 输邀请码 → 校验有效期/机构 → 校验 people 属同租户 → 写 membership 与 care assignment → 开放体征/Agent。

### 10.3 C 体征 + Patient Agent

校验已入组 → 写入 VitalRecord(tenant_id, people_id) → Agent 只读本人数据与私有记忆 → 安全阀门 → 交互日志。

### 10.4 B 查看患者

切换 currentOrgId → 列表：本机构 membership 患者 → 点开体征：同租户共享可读 → 不返回 Patient Agent 私聊。

### 10.5 Ops 查患者

检索 → 返回明文 → **写 AuditLog**。

---

## 11. 非功能需求

| 类别 | 要求 |
|---|---|
| 安全 | TLS；日志脱敏；Ops PHI 审计；超管数量限制 |
| 隔离 | 租户行级隔离自动化；定期越权用例 |
| 性能 | 非 LLM 接口 P99 &lt; 500ms（目标） |
| 成本 | Token/存储按租户计量（配额表先建，强阻断可后置） |

---

## 12. 分期计划（修订后）

### Phase 0 — 完成

Grill 定稿（本文 v0.3）。

### Phase 1 — 骨架

- DDL：租户/机构/三端账号/membership/邀请码/体征/审计/Agent 日志
- 三端登录与路由
- 租户拦截器（people_* 行级租户）
- 入组与体征
- Patient Agent（薄实现可接 One-API）
- Ops 患者检索 + 审计
- 中间件齐全部署

### Phase 2 — 健管闭环

报告、预警工单、随访、Care Copilot、医生流程、短信/微信。

### Phase 3 — 深化

安全阀门租户化、周报、配额强阻断、多 Agent 协作。

### Phase 4 — SaaS 增强

计费白标、SSO、独立 Schema、MFA。

---

## 13. 下一步工程动作

1. 输出 `docs/数据模型.md` + 填充 `schema.sql`（按本文领域对象）。
2. 清理/重建包：`tenant` / `org` / `identity` / `patient` / `vitals` / `agent` / `ops`。
3. API 按 `/api/c|b|ops/v1` 重建。
4. 实现 `TenantContextHolder` + MyBatis 拦截器 + 越权测试。
5. 前端：`frontend-user`（C）与 `frontend-admin`（B+Ops 同壳分菜单）。

---

## 14. 已关闭的待决问题

| # | 原问题 | 结论 |
|---|---|---|
| 1 | 无机构个人空间？ | 空态可注册/资料/邀请码；核心能力必须入组 |
| 2 | 多租户/多机构？ | 同租户多机构；同时仅一租户 |
| 3 | 医生与健管师模型？ | 统一 Staff + RBAC；医生 P1 预留 |
| 4 | 谁建机构？ | 租户管理员 |
| 5 | C 端形态？ | 目标小程序；P1 账号密码可先 Web/H5 |
| 6 | 合规/独立库？ | 一期共享库行级隔离；独立库二期 |

---

## 15. 名词表

| 名词 | 定义 |
|---|---|
| Tenant | 签约与 PHI 共享边界 |
| Organization | 协作任务与统计边界 |
| people_id | 患者主键（租户内）；库表/API 统一此名 |
| Membership | 患者-机构管理关系 |
| Primary Org / Care Manager | 任务默认归属 |
| Patient Agent | 患者私域助手 |
| Care Copilot | B 端协作助手（P2） |
| aud | JWT 受众：c / b / ops |

---

**文档状态**：v0.3.1 已跟进 `people_*` 租户隔离（废止平台唯一患者 / `home_tenant_id`）。  
**详表**：`docs/数据模型.md` v0.2；机构工作台：`docs/机构工作台-健管组与患者建档.md` v0.2。
