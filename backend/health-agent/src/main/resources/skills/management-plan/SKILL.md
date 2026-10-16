---
name: management-plan
description: 为患者生成阶段健康管理方案草稿（运动、饮食、执行计划与总结）。仅 CARE_COPILOT；生成结果写入草稿，须人工审阅后发布。
display_name: 管理方案生成
---

# 健康管理方案生成 Skill

为单患者生成**运动 / 饮食 / 执行计划**三块内容，并输出**方案总结**。患者事实来自上下文工具；文案由本 Skill 指导的 LLM 生成；**禁止自动发布**。

## 职责边界

| 内容 | 负责方 |
|------|--------|
| 患者档案、病种、过敏、指标、用药、检验 | 上下文工具（只读） |
| 运动 / 饮食 / 执行 / 总结文案 | LLM 按本 Skill 生成 |
| Safety 评估、草稿落库、发布 | 业务服务（非本 Skill） |

**禁止**：编造患者未提供的检验数值；替代医生诊疗决策；推荐患者过敏食物；骨折/卧床/医嘱制动时推荐跑步、负重或剧烈有氧。

### 疾病评估结果（`assessments`）

上下文中的 `assessments` 是系统评估结论（患病风险 / 严重等级分层）与关键因素，仅用于决定干预强度与重点：

- 高 / 很高：干预更积极（如减重目标、监测频次、复评周期更短），`execution` 中安排对应监测任务
- `keyFactors` 中可干预的因素（吸烟、久坐、高盐、腰围超标等）应在运动 / 饮食 / 执行中逐一落实
- **方案正文是给患者看的**：`exercise` / `diet` / `execution` / `summary` / `goalSummary` 中**不得**出现「高风险」「很高危」「分层」「中危」等等级用语，也不得复述评分；用「需要重点控制血压」「建议加强体重管理」这类行动化表述
- **生成理由 `rationale` 仅给健管师审阅**：可以点明评估等级（如很高危），须引用档案/评估/过敏等事实，禁止编造数值

## 方案类型（templateKey）

| 键 | 含义 |
|---|---|
| `COMPREHENSIVE` | 综合管理；**任何人可用**（未患病也可手选） |
| `DIABETES` | 糖尿病管理（叙事以控糖为主） |
| `HYPERTENSION` | 高血压管理 |
| `OBESITY` | 肥胖管理 |
| `WELLNESS` | 健康管理（未确诊糖/压/肥时的生活方式管理，**不是**「营养方案」） |

监测任务按档案病种标签叠加（有糖加血糖、有压加血压、有肥加每日体重），与是否选综合无关。

## 编排步骤

1. 拉取 `CarePlanContext`（档案、病种、过敏、用药、最近指标与检验）
2. 使用服务端给定的模板键（五类之一）
3. 结合 `instruction` 生成整包 JSON（含 rationale）；若为**修订模式**，在 `currentDraft` 上按 `revisionInstruction` 局部调整后仍输出完整 JSON
4. 服务端 Safety 校验 → 写入 / 更新 `care_plan_draft`

### 修订模式（REVISE）

当用户反馈类似「把运动改成步行」「饮食少盐一点」「最近骨折了」「经常拉肚子」时：

- 输入含 `currentDraft`（exercise/diet/execution/goalSummary）、`revisionInstruction`，以及可选的 `mustPreserveConstraints`
- **未提及的块尽量保持原意与结构**，仅改被点名的部分；仍输出完整 `exercise → diet → execution → summary → goalSummary`
- **安全约束累积保留（硬性）**：
  - 若当前草稿或 `mustPreserveConstraints` 已含骨折 / 石膏 / 卧床 / 医嘱制动 / 禁止负重等限制，后续修订**必须继续保留**在 `exercise.goal`、`contraindications`、`precautions` 与 execution 运动任务中
  - 仅当修订意见**明确解除**（如「骨折已愈合」「医生允许负重」「可以恢复步行」）才可去掉上述限制
  - 饮食/消化类意见（火锅、拉肚子、少油少辣等）**优先只改 diet**（原则、推荐/限制食物、示例日、notes），**不得**把运动从制动/床旁活动改回常规有氧或站立训练
- 禁止借修订编造新的检验数值或过敏原
- `goalSummary` 须同时覆盖仍有效的安全限制与本次新增关注点（例如：骨折制动 + 腹泻饮食调整）

## 输出格式（严格 JSON，无 markdown 代码块）

```json
{
  "exercise": { "rationale": "块级生成依据…" },
  "diet": { "rationale": "块级生成依据…" },
  "execution": {
    "rationale": "块级生成依据…",
    "tasks": [{ "code": "…", "title": "…", "rationale": "该任务依据…" }]
  },
  "summary": "200字以内方案总结：阶段目标、核心干预、随访要点（勿写等级用语）",
  "goalSummary": "一句话目标，用于方案头",
  "rationale": {
    "templateKey": "COMPREHENSIVE",
    "overview": "为何选此类型、依据哪些档案/评估事实",
    "sources": ["diseaseArchive", "assessment", "allergens", "meds", "vitals"]
  }
}
```

**流式展示约定**：必须按 `exercise → diet → execution → summary → goalSummary` 顺序输出字段，便于前端边生成边展示；`rationale` 可放在最后。

**理由粒度**：整份 overview + 三块 rationale 必填；关键任务与示例餐建议带 `rationale`；每周重复步行不必按天复制理由。

---

## 一、运动方案（exercise）

对应 `ExercisePlanSpec`：

| 字段 | 说明 |
|------|------|
| `goal` | 阶段运动总目标（中文，具体可执行） |
| `contraindications` | 字符串数组，禁忌事项 |
| `weeklyPlan` | 周计划数组，每项含 `day`（MON~SUN）、`items[]` |
| `items[]` | `type`（如 WALKING）、`durationMin`、`intensity`（LIGHT/MODERATE）、`note`。大类项目的 `note` 必须写成患者能照做的动作：抗阻/力量写 2～3 个居家动作和次数（靠墙静蹲、坐姿抬腿、弹力带向后拉，各 8～12 次、2～3 组，不憋气）；有氧写具体方式（快走、骑车或游泳）；拉伸写部位和停留秒数；平衡写扶椅动作。标题已点名步行、太极等具体运动时，`note` 只补充时机 |
| `precautions` | 注意事项数组 |
| `reviewHint` | 复评提示（如「2周后根据血糖调整」） |

**运动安全规则**：

- 低血糖风险或近期血糖偏低：强度 `LIGHT`，单次 15–25 分钟，优先餐后步行，注明运动前后监测
- 高血压：避免憋气用力、避免突然体位改变
- 骨折、石膏、医嘱卧床：仅推荐床旁/被动活动、呼吸训练、防血栓与防压疮，**禁止**跑步、跳绳、负重训练
- 糖尿病：优先餐后 1 小时步行；随身携带糖块

---

## 二、饮食方案（diet）

对应 `DietPlanSpec`：

| 字段 | 说明 |
|------|------|
| `calorieHint` | 热量建议文案，规则见下方「热量」 |
| `principles` | 饮食原则数组（如低盐、控糖、高纤维） |
| `recommended` | `{code, label}` 数组，推荐食物 |
| `limited` | `{code, label}` 数组，限制食物 |
| `allergensAvoid` | `{code, label}` 数组，**必须包含 `foodAllergyGuard` 中 type=ALLERGY 的过敏原** |
| `intolerances` | `{code, label}` 数组，`foodAllergyGuard` 中 type=INTOLERANCE 的不耐受食物 |
| `sampleDay` | `{breakfast, lunch, dinner, snacks}` 示例日；每餐可为字符串，或 `{text, swapHint?}`（**每餐至多 1 条** `swapHint` 可替换提示） |
| `sampleWeek` | **必填**。`{MON,TUE,WED,THU,FRI,SAT,SUN}` 每天同 `sampleDay` 结构，作为一周饮食示例。七天都要写，早餐、午餐、晚餐都要有具体菜品，加餐可空。七天不要复制同一套，主食、蛋白质和蔬菜轮换；周一可与 `sampleDay` 一致 |
| `notes` | 与用药、口味相关的补充说明 |

**热量**（以饮食记录的热量目标为准，方案不另算）：

- `patientContext.energyTarget.available=true`：`calorieHint` 必须使用其中的 `energyKcal` 与碳水 / 蛋白质 / 脂肪克数，可补充目标类型（减重速度）与执行要点，例：「每日约 1650 千卡（碳水 206g / 蛋白质 74g / 脂肪 50g），按每周减重 0.5 kg；三餐定时定量，避免极端节食」
- `available=false`：**不写任何热量数值**，`calorieHint` 提示完善 `missing` 中的档案项后可计算热量目标
- 不要输出 `energyTarget` 字段，由服务端按饮食目标写入；与目标不符的热量数字会被服务端改写
- `sampleDay` 与 `sampleWeek` 的份量与热量目标大致匹配，不写具体千卡数
- `swapHint` 写具体可执行替换（如「米饭可换成杂粮饭」），不要写成空泛「清淡为主」；无合适替换则省略或 `null`
- `sampleWeek` 必须输出完整七天。每天至少含早、午、晚；过敏约束同 `sampleDay`；菜品按天轮换，不要七天完全相同

**饮食规则**：

- `recommended` 与 `allergensAvoid` 的 `code` 集合**不得相交**（服务端 ERROR）
- `foodAllergyGuard[].keywords`（type=ALLERGY）中的任一词**不得出现**在 `recommended`、`sampleDay` 与 `sampleWeek` 的正文及 `swapHint`（如鸡蛋过敏时不写「鸡蛋」「蒸蛋」，改用瘦肉/豆浆等替代）；服务端会按关键词全文检查并自动删除命中内容
- 有食物过敏时，`notes` 中提醒患者「外出就餐注意询问菜品成分」；不耐受写入 `intolerances` 并在 `notes` 提示注意食用量，可少量但不作为主推
- 糖尿病：控精制糖、规律三餐、增加膳食纤维
- 高血压：低盐、限制腌制食品
- 合并症：取保守合并原则
- 结合在用药物在 `notes` 中提示（如与降糖药相关的进餐时间）

---

## 三、方案总结（summary / goalSummary）

| 字段 | 说明 |
|------|------|
| `summary` | 面向健管师的阶段总结：控制目标导向、运动饮食要点、执行重点、2 周复评建议；**不含监测设备操作细节** |
| `goalSummary` | 一句话写入方案头 `goal_summary` |

总结须与三块内容一致，不引入未在 exercise/diet/execution 中出现的任务。

---

## 四、执行计划（execution）

对应 `ExecutionPlanSpec`：将运动与饮食干预落实为**可打卡的任务清单**（本期用于发布投影与 P2 打卡铺路）。

| 字段 | 说明 |
|------|------|
| `horizonDays` | 执行周期天数，默认 14 |
| `tasks` | 任务数组，至少 1 条 `enabled: true` |

**tasks[] 字段**：

| 字段 | 说明 |
|------|------|
| `code` | 稳定编码，如 `EX_WALK_30`、`DIET_LOG`、`MED_REMINDER` |
| `title` | 展示标题 |
| `category` | `EXERCISE` / `DIET` / `WATER` / `SLEEP` / `MOOD` / `OTHER` |
| `frequency` | `QD`（每日）/ `BID` / `TIW`（每周三次）等 |
| `timeSlot` | `MORNING` / `AFTERNOON` / `EVENING` / `AFTER_DINNER` / `BEDTIME` / `ALL` |
| `relatedRef` | 可选，关联运动类型或指标，如 `WALKING`、`BLOOD_GLUCOSE` |
| `targetMl` | **仅 `WATER`**：每日饮水目标毫升（整数） |
| `enabled` | 是否启用 |

**编制原则**：

1. **运动任务**：从 `weeklyPlan` 提炼 1–2 条高频任务，`category=EXERCISE`。标题要点名动作（如「餐后步行 30 分钟」「靠墙静蹲与坐姿抬腿 20 分钟」），不要只写「抗阻训练」「有氧运动」「拉伸训练」这类患者不知道做什么的大类
2. **饮食任务**：至少 1 条「记录三餐与加餐」等，`category=DIET`（**不要**再把饮水写进 DIET）
3. **饮水任务**：无限水禁忌时编入 1 条 `code=WATER_LOG`，`category=WATER`，`frequency=QD`，填写 `targetMl`（有体重用约 `kg×30` 并落在 1200–2000，否则 1600；标题可写「足量饮水，每日约 {targetMl} ml」）
4. **限水**：上下文出现心衰、慢性肾病、医嘱限水等 → **不要生成** `WATER` 任务（由健管师决定是否手加）
5. **糖尿病标签**：增加「按时监测血糖」`relatedRef=BLOOD_GLUCOSE`
6. **高血压标签**：增加「家庭血压测量」`relatedRef=BLOOD_PRESSURE`
6b. **肥胖标签**：增加「记录体重」`code=WEIGHT_LOG`，`relatedRef=WEIGHT`，`frequency=QD`
7. **睡眠任务**：编入 1 条 `code=SLEEP_LOG`，`category=SLEEP`，`frequency=QD`，标题如「记录昨夜睡眠」
8. **心情任务**：编入 1 条 `code=MOOD_LOG`，`category=MOOD`，`frequency=QD`，标题如「记录今日心情」
9. 任务数量建议 4–8 条，避免过多导致依从性差
10. 低血糖风险期：运动任务标题与时长与 exercise 块一致，偏保守
11. **不要**输出独立顶层 `lifestyle` 块（睡眠/心情走执行任务 + C 端日志）

**示例 tasks**：

```json
{
  "horizonDays": 14,
  "tasks": [
    {
      "code": "EX_WALK_30",
      "title": "餐后步行 30 分钟",
      "category": "EXERCISE",
      "frequency": "QD",
      "timeSlot": "AFTER_DINNER",
      "relatedRef": "WALKING",
      "enabled": true
    },
    {
      "code": "DIET_LOG",
      "title": "记录三餐与加餐",
      "category": "DIET",
      "frequency": "QD",
      "timeSlot": "EVENING",
      "enabled": true
    },
    {
      "code": "WATER_LOG",
      "title": "足量饮水，每日约 1600 ml",
      "category": "WATER",
      "frequency": "QD",
      "timeSlot": "ALL",
      "targetMl": 1600,
      "enabled": true
    }
  ]
}
```

---

## 五、上下文使用说明

生成时必须参考上下文中的：

- 病种与合并症 → 决定模板倾向与安全提示
- 过敏原 → 写入 `allergensAvoid`，且不得出现在 `recommended`、`sampleDay` 与 `sampleWeek`（含 `swapHint`；以 `foodAllergyGuard` 为准）
- 限水相关病情 → 不要生成 `WATER` 任务
- 最近指标（血糖、血压、体重等）→ 调整运动强度与总结表述
- `energyTarget` → 饮食 `calorieHint` 的热量数字（见「热量」）
- 在用药物 → `diet.notes` 与执行计划中谨慎表述
- `contextIncomplete=true` → 文案中可提示信息有限，但不拒绝生成

## 六、禁止事项

1. 输出 markdown 代码块包裹 JSON
2. 编造检验数值或诊断
3. 自动发布或声称「已生效」
4. 过敏食物出现在推荐列表或示例食谱
5. 卧床/骨折场景推荐剧烈运动；多轮修订时因饮食意见而丢掉既有骨折/制动限制
6. 在总结中承诺疗效或替代就医
7. 面向患者的文案（标题、总结、建议、任务名）出现「处方」「诊断」「确诊」「医嘱」「治疗方案」等医疗用语：用「运动计划」「按时服药」「按时监测」「已患」等健康管理表述
