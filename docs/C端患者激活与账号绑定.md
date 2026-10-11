# C 端患者激活与账号绑定

| 项 | 内容 |
|---|---|
| 版本 | **v0.3** |
| 日期 | 2026-09-07 |
| 状态 | **Phase 1 已落地**（表结构 + B 发码/关联态 + C 注册/就诊人/激活）；Phase 2（短信 OTP 等）未做 |
| 依据 | `SaaS架构.md`、`数据模型.md`、`机构工作台-健管组与患者建档.md`；Grill 会话结论 |
| 范围 | 家庭账号模型；账号→就诊人卡片→`people` 三层；患者激活码；自助添加就诊人（证件实名） |
| 非目标 | 短信 OTP 对齐（Phase 2）、患者合并 S2、换绑/注销纠纷处理、B 端解绑明细、跨租户挂靠、角色权限拆分 |

**相对 v0.1 的破坏性变更**：废弃「1 账号 ↔ 1 `people`（`people_profile.account_id`）」；改为家庭账号 + 就诊人卡片。

---

## 1. 背景与问题

### 1.1 业务背景

典型线下流程：**健管师先建档 → 患者/家属后装 App**。档案、管理方案、用药等 PHI 均挂在 B 端（或对齐后的）`people_id` 上。

C 端真实用法更接近：**一个登录账号管理多名就诊人**（本人、父母、子女），而非一人一号。

### 1.2 当前实现问题

| 路径 | 行为 |
|---|---|
| **B 端建档** | 创建 `people_profile`，`account_id = NULL` |
| **C 端注册** | 创建 `people_account` + **新的** `people_profile`，并写入 `account_id` |
| **机构邀请码** | 对 JWT 中单一 `patientId` 写 membership |

结果：B 管理的患者 A 与 C 注册出的 B 常为两条 `people`；且无法表达「一账号多名就诊人 / 多账号共管同一自然人」。

### 1.3 目标

| # | 目标 |
|---|---|
| G1 | 租户内 **同一自然人 = 同一 `people_id`**（证件唯一） |
| G2 | C **账号** 与 **就诊人卡片** 1:N；卡片指向 `people_id` |
| G3 | 主路径：**B 建档 → 激活码 → 账号下增加已授权就诊人卡片** |
| G4 | 辅路径：自助添加就诊人（证件未命中时建卡片+people）；入组靠机构邀请码 |
| G5 | 机构邀请码 ≠ 患者激活码 |
| G6 | Phase 1 **无短信**；证件命中已有 people **不得**静默授权 |
| G7 | 多账号可挂同一 `people`（各发各的激活码） |

---

## 2. 核心模型（三层）

```text
people_account              C 登录账号（注册只建这个，可空壳）
account_patient             【新增】就诊人卡片（账号私有）
people_profile              自然人主档（临床数据挂这里）
people_identity             真实证件（ID_CARD 等）；禁止用 identity 存业务 FK
people_org_membership       机构入组（挂 people）
people_activation_invite    【新增】患者专属激活码
```

### 2.1 语义

| 实体 | 含义 |
|---|---|
| `people_account` | 谁在登录（子女/本人） |
| `account_patient` | 账号下的「就诊人卡片」；UI 切换单位 |
| `people_profile` | 租户内自然人；B/C 临床数据主体 |

**绑定真相**：`account_patient.people_id` → `people_profile.id`。  
**停用**：`people_profile.account_id` 不再承担「已开通 App」语义（列可保留但 C 路径不再写入；开发库可不迁旧数据）。

### 2.2 `account_patient`（就诊人卡片）

| 字段 | 说明 |
|---|---|
| id | 业务主键（JWT `activePatientCardId`） |
| tenant_id | 租户 |
| account_id | 所属 C 账号 |
| people_id | **非空**；指向自然人（Phase 1 可选卡片皆已对齐 people） |
| display_name | 卡片展示名（可与 profile 同步） |
| relation | 可选：SELF / PARENT / CHILD / SPOUSE / OTHER |
| identity 摘要 | 类型 + mask（完整证件在 `people_identity`） |
| mobile | 可空；Phase 1 **只存不验**；Phase 2 短信对齐用 |
| gmt_* / is_deleted | 标准软删 |

**约束**：

| 规则 | 说明 |
|---|---|
| 账号内证件唯一 | 同一 `account_id` 不得两张同证件卡片 |
| 跨账号可同 people | 儿子、女儿各一张卡，`people_id` 相同 |
| 单账号上限 | **10** 张卡片 |
| 移除 | 仅解关联（软删卡片）；**不删** `people` |

### 2.3 证件与命中

- 租户内 `people_identity`：`(tenant_id, identity_type, identity_value_hash)` 唯一 → **一人一证**。
- **不**新增 `VISITANT_ID` 类 identity；卡片→people 用 `account_patient.people_id`。

---

## 3. 流程设计

### 3.1 注册（只建账号）

```text
C 注册(tenant + username + password)
  → insert people_account
  → 不创建 people / 不创建 account_patient
  → JWT：accountId，activePatientCardId = null
  → 空态页：引导「添加就诊人」或「输入激活码」
```

### 3.2 主路径：B 建档 → 激活码 → 就诊人卡片

```text
B 建档 people=A（可无 C）
  → 发 people_activation_invite（绑 A + org）
  → 患者/家属：
      未登录：激活码 + 设账号密码 + 校验档案姓名
        → 建 account（若无）+ 建 account_patient(people_id=A) + 补 membership
      已登录：激活码 + 校验姓名
        → 仅建卡片 + 补 membership（账号内证件不冲突）
  → JWT activePatientCardId = 新卡片；上下文 people_id = A
```

**同 people 多家属**：健管师对同一 `people_id` **可多次发码**；每码一次性、由一个账号消费一次。

### 3.3 自助添加就诊人（Phase 1，无短信）

```text
已登录账号 → 添加就诊人（姓名 + 证件 + 可选手机/关系）
  → 账号内同证件？ → 409
  → 已达 10 人？ → 409
  → 租户内证件命中已有 people？
       是 → **不建卡**；提示「请使用机构发放的激活码添加」
       否 → 创建 people + people_identity + account_patient(people_id=新people)
  → 该 people 暂无 membership → 仅开放档案/自填体征等；机构能力灰掉
```

**禁止**：证件命中 = 自动挂靠机构 PHI（无激活码、无短信时尤其危险）。

### 3.4 机构邀请码（入组）

| 类型 | 绑定 | 作用 |
|---|---|---|
| **机构邀请码** `org_invite_code` | 仅 `org_id` | 对 **当前就诊人卡片对应的 people** 写 membership |
| **患者激活码** `people_activation_invite` | `people_id` + `org_id` | 授权建卡片并指向该 people，并可自动入组 |

C→B 可见：患者对某就诊人使用机构码入组后，B 患者列表可见。  
`attachPeopleId` 可保留技术能力，**不作为产品主入口**。

### 3.5 Phase 2（预留）：短信对齐

| 场景 | 规则 |
|---|---|
| 新建无人档 | 可验就诊人手机 OTP 后建卡+people |
| 证件命中已有 B 档 | 须验 **档上预留手机** OTP，或走激活码；档上无手机 → 只能激活码 |
| 任意手机收码 | **不算**对已建档 people 的授权 |

---

## 4. 数据模型增量

### 4.1 `account_patient`

见 §2.2。建议 UK：

- `(account_id, id)` 业务主键  
- `(account_id, identity_type, identity_value_hash, gmt_deleted)` 账号内证件唯一（或等价哈希字段）  
- `KEY (people_id)`、`KEY (account_id)`

### 4.2 `people_activation_invite`

| 字段 | 说明 |
|---|---|
| id | PK |
| tenant_id / org_id / people_id | 待激活自然人 + 目标机构 |
| code | 展示码；**建议全局唯一**（便于反查 tenant；现网可先配置 tenant） |
| enabled / expire_at | |
| used_at / used_by_account_id | 消费账号 |
| created_by_staff_id | 发码人 |
| gmt_* / is_deleted | |

**发码规则**：`people` 存在即可（不再要求 `account_id` 为空）；发码人须有该机构患者权限；新码生效后建议作废同 people 未使用旧码（可配置）；已激活（已有任意 C 卡片）仍允许再发码给其他家属。

### 4.3 `people_profile.account_id`

- Phase 1：C 路径 **停止写入**；B「App 状态」改为：是否存在 `account_patient` 指向该 people（及关联账号数）。  
- 开发环境：**不强制迁移**旧绑定（可清空库）。

---

## 5. API 设计（草案）

### 5.1 B 端

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/b/v1/patients/{peopleId}/activation-invites` | 发码（`validDays` 可选） |
| GET | `/api/b/v1/patients/{peopleId}/activation-invites` | 列表 |
| DELETE | `/api/b/v1/patients/{peopleId}/activation-invites/{inviteId}` | 作废未使用码 |

患者详情展示：**C 端关联：是/否（卡片数/账号数）**；Phase 1 不展示账号明细、不解绑。

### 5.2 C 端 Auth / 就诊人

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/c/v1/auth/register` | **只建 account** |
| POST | `/api/c/v1/auth/login` | 登录；可带回默认 `activePatientCardId` |
| POST | `/api/c/v1/auth/activate` | 激活码：冷启动可建账号；热启动只加卡片 |
| GET | `/api/c/v1/patient-cards` | 就诊人列表 |
| POST | `/api/c/v1/patient-cards` | 自助添加（证件命中则 409 引导激活） |
| POST | `/api/c/v1/patient-cards/{cardId}/select` | 切换当前卡片（重签 JWT） |
| DELETE | `/api/c/v1/patient-cards/{cardId}` | 解关联 |
| POST | `/api/c/v1/membership/join` | 机构码：对 **当前卡片的 people** 入组 |

**激活请求示例**：

```json
{
  "tenantId": "...",
  "activationCode": "A3K9-7X2M",
  "username": "child1",
  "password": "********",
  "displayName": "张父",
  "relation": "PARENT"
}
```

已登录时可省略 username/password。

**激活步骤**：

1. 校验码（未过期、未使用）→ `peopleId`、`orgId`  
2. 校验提交姓名与档案 `display_name` 一致（Phase 1 P0）  
3. 若无账号则创建 `people_account`  
4. 账号内证件/people 冲突检查；未超 10 人  
5. `insert account_patient(people_id=A, …)`  
6. 无 ACTIVE membership 则 insert  
7. 更新 invite `used_*`；作废策略按 §4.2  
8. JWT：`accountId` + `activePatientCardId`  
9. 审计：`PATIENT_CARD_ACTIVATE`（建议）  
10. **禁止**为激活再 insert 多余的「第二个」同证件 `people_profile`

已关联同一 people 的账号再次用新码：幂等成功（补 membership），不 409。

### 5.3 请求上下文（拦截器）

```text
JWT: aud=c, sub=accountId, activePatientCardId?, homeTenantId
```

业务 `/api/c/v1/me/**`（及依赖患者上下文的接口）：

1. 解析 `activePatientCardId`；空 → 400/引导空态（非白名单接口）  
2. 卡片存在、未删、`account_id` = 当前账号  
3. 上下文 `peopleId = card.people_id`  
4. 后续读写一律用该 `peopleId`

Phase 1 **不**在拦截器里「现场按证件再查一遍授权」：可选卡片在创建时已保证指向合法 people。

---

## 6. 安全与校验

| 级别 | 项 | 阶段 |
|---|---|---|
| P0 | 激活码 + 未过期 + 未使用 + 姓名一致 | Phase 1 |
| P0 | 自助添加：证件命中已有 people → 拒绝建卡 | Phase 1 |
| P0 | 卡片归属校验 | Phase 1 |
| P1 | 档上手机 OTP 对齐已有 people | Phase 2 |
| P2 | 生产短信通道 / 风控 | 生产 |

**错误文案（中文）**：

| 场景 | HTTP | 文案 |
|---|---|---|
| 码无效/过期 | 400 | 激活码无效或已过期 |
| 姓名不符 | 400 | 姓名与档案不一致 |
| 证件已在机构建档 | 409 | 该就诊人已在机构建档，请使用激活码添加 |
| 账号内证件重复 | 409 | 该证件已在您的就诊人列表中 |
| 超过 10 人 | 409 | 就诊人数量已达上限 |
| username 占用 | 409 | 用户名已存在 |
| 未选择就诊人 | 400 | 请先添加或选择就诊人 |

---

## 7. 边界与异常

| 场景 | 处理 |
|---|---|
| 空壳账号 | 允许；`activePatientCardId` 空；空态引导 |
| B 无证建档 | 激活仍可靠码+姓名；自助添加必须有证 |
| 私有 people 证件被占用（他人先自建） | 报错证件已占用；Phase 1 人工/Ops，不做免码挂靠 |
| B 软删 people | 相关未用激活码失效；已有卡片随业务策略只读/解关联 |
| 切换就诊人 | 重签 JWT，更新 `activePatientCardId` |

---

## 8. 与 JWT

```text
aud = c
sub = accountId
activePatientCardId = account_patient.id   ← 可空
homeTenantId = tenantId
```

服务端由卡片解析 `peopleId`；**不要**只把 `peopleId` 放进 JWT 作为唯一上下文（多卡片同 people 时无法区分卡片）。

租户：开发期客户端配置/写死 `tenantId`；激活码设计为可全局唯一以便反查 tenant。

---

## 9. 实施计划

### Phase 1 — MVP ✅ 已落地

| # | 交付物 | 状态 |
|---|---|---|
| 1 | DDL：`account_patient`、`people_activation_invite` + `SchemaEnsureRunner` | ✅ |
| 2 | 改造 `register`：只建 account；停止写 `people_profile.account_id` | ✅ |
| 3 | `PatientCardService` / `PatientActivationService` | ✅ |
| 4 | C 拦截器：卡片归属 → `peopleId` 上下文 | ✅ |
| 5 | B API：发码/列表/作废；患者 C 关联状态 | ✅ |
| 6 | C API：激活、卡片 CRUD/切换、入组 | ✅ |
| 7 | C UI：空态、添加就诊人、激活、切换器 | ✅ |
| 8 | B UI：发码、复制、关联状态 | ✅ |

### Phase 2

- 短信 OTP；证件命中时的档上手机对齐  
- 激活深链 `/activate?code=...`  
- B 端关联账号明细与解绑  

### Phase 3

- 患者合并 S2、换绑/注销、审计与 Ops 查询、卡片角色权限  

---

## 10. 测试要点

| 场景 | 期望 |
|---|---|
| 注册 | 无 people、无卡片 |
| 自助添加（新证） | 1 people + 1 卡片；无 membership |
| 自助添加（证已存在） | 409，无新卡 |
| B 建档→发码→激活 | 卡片 people_id = 建档 id；可入组 |
| 第二家属再激活同 people | 第二账号新卡片，同 people_id |
| 同码二次消费 | 400 |
| 切换卡片 | JWT 变；/me 数据跟人走 |
| 机构码入组 | 当前卡片 people 出现在 B 列表 |
| 解关联 | 卡片软删；people 仍在 |
| 第 11 人 | 409 |

---

## 11. 相关文档

| 文档 | 关系 |
|---|---|
| `SaaS架构.md` §3.2 | C 生命周期需按「账号 / 就诊人 / 入组」更新表述 |
| `数据模型.md` §5 | `account_id` 语义废弃；增补 `account_patient` |
| `机构工作台-健管组与患者建档.md` | Q5「可无 account」改为「可无 C 卡片」；激活另文 |

---

## 12. 决策摘要（Grill v0.2）

| # | 结论 |
|---|---|
| D1 | 三层：account → **account_patient** → people；不用 `account_id` 绑定 |
| D2 | 主路径 B 建档 → 激活码 → 卡片；校验码+姓名 |
| D3 | 注册只建账号；自助添加须证件；命中已有 people 不建卡 |
| D4 | 新表激活码；机构码只入组 |
| D5 | 多账号共管同一 people：多次发码 |
| D6 | Phase 1 无短信；Phase 2 再短信对齐 |
| D7 | JWT：`accountId` + `activePatientCardId` |
| D8 | 账号内证件唯一；上限 10；只解关联不删 people |
| D9 | 拦截器：归属 + 卡片上 `people_id` |
| D10 | C→B：机构邀请码入组为主；`attachPeopleId` 非主入口 |
| D11 | 开发库不迁旧 `account_id` |
