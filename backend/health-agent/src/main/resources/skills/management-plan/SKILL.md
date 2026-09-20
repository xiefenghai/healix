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

## 编排步骤

1. 拉取 `CarePlanContext`（档案、病种、过敏、用药、最近指标与检验）
2. 解析模板键：`DIABETES` / `HYPERTENSION` / `DIABETES_HYPERTENSION` / `GENERAL`
3. 结合 `instruction`（健管师备注）生成整包 JSON
4. 服务端 Safety 校验 → 写入 `care_plan_draft`

## 输出格式（严格 JSON，无 markdown 代码块）

```json
{
  "exercise": { },
  "diet": { },
  "execution": { },
  "summary": "200字以内方案总结：阶段目标、核心干预、随访要点",
  "goalSummary": "一句话目标，用于方案头"
}
```

**流式展示约定**：必须按 `exercise → diet → execution → summary → goalSummary` 顺序输出字段，便于前端边生成边展示。

---

## 一、运动方案（exercise）

对应 `ExercisePlanSpec`：

| 字段 | 说明 |
|------|------|
| `goal` | 阶段运动总目标（中文，具体可执行） |
| `contraindications` | 字符串数组，禁忌事项 |
| `weeklyPlan` | 周计划数组，每项含 `day`（MON~SUN）、`items[]` |
| `items[]` | `type`（如 WALKING）、`durationMin`、`intensity`（LIGHT/MODERATE）、`note` |
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
| `calorieHint` | 可选，热量建议**文案**（非必填 kcal 数值） |
| `principles` | 饮食原则数组（如低盐、控糖、高纤维） |
| `recommended` | `{code, label}` 数组，推荐食物 |
| `limited` | `{code, label}` 数组，限制食物 |
| `allergensAvoid` | `{code, label}` 数组，**必须包含上下文中的过敏原** |
| `sampleDay` | `{breakfast, lunch, dinner, snacks}` 示例日 |
| `notes` | 与用药、口味相关的补充说明 |

**饮食规则**：

- `recommended` 与 `allergensAvoid` 的 `code` 集合**不得相交**（服务端 ERROR）
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
| `category` | `EXERCISE` / `DIET` / `OTHER` |
| `frequency` | `QD`（每日）/ `BID` / `TIW`（每周三次）等 |
| `timeSlot` | `MORNING` / `AFTERNOON` / `EVENING` / `AFTER_DINNER` / `BEDTIME` |
| `relatedRef` | 可选，关联运动类型或指标，如 `WALKING`、`BLOOD_GLUCOSE` |
| `enabled` | 是否启用 |

**编制原则**：

1. **运动任务**：从 `weeklyPlan` 提炼 1–2 条高频任务（如「餐后步行 30 分钟」），`category=EXERCISE`
2. **饮食任务**：至少 1 条（如「记录三餐」「足量饮水」），`category=DIET`
3. **糖尿病**：可增加「按医嘱监测血糖」`category=OTHER`，`relatedRef=BLOOD_GLUCOSE`（文案写「按医嘱」，不编造频次）
4. **高血压**：可增加「家庭血压测量」`relatedRef=BLOOD_PRESSURE`
5. 任务数量建议 3–6 条，避免过多导致依从性差
6. 低血糖风险期：运动任务标题与时长与 exercise 块一致，偏保守

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
    }
  ]
}
```

---

## 五、上下文使用说明

生成时必须参考上下文中的：

- 病种与合并症 → 决定模板倾向与安全提示
- 过敏原 → 写入 `allergensAvoid`，且不得出现在 `recommended`
- 最近指标（血糖、血压、体重等）→ 调整运动强度与总结表述
- 在用药物 → `diet.notes` 与执行计划中谨慎表述
- `contextIncomplete=true` → 文案中可提示信息有限，但不拒绝生成

## 六、禁止事项

1. 输出 markdown 代码块包裹 JSON
2. 编造检验数值或诊断
3. 自动发布或声称「已生效」
4. 过敏食物出现在推荐列表
5. 卧床/骨折场景推荐剧烈运动
6. 在总结中承诺疗效或替代就医
