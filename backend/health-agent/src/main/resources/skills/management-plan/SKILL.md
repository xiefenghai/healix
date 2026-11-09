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
| 患者档案、病种、过敏、指标、用药、检验、目标、首诊摘要/高亮 | 上下文工具（只读） |
| 运动 / 饮食 / 执行 / 总结文案 | LLM 按本 Skill 生成 |
| Safety 评估、草稿落库、发布 | 业务服务（非本 Skill） |

**禁止**：编造患者未提供的检验数值；替代医生诊疗决策；推荐患者过敏食物；骨折/卧床/医嘱制动时推荐跑步、负重或剧烈有氧。

### 疾病评估结果（`assessments`）

上下文中的 `assessments` 是系统评估结论（患病风险 / 严重等级分层）与关键因素，仅用于决定干预强度与重点：

- 高 / 很高：干预更积极（监测频次、复评周期更短；若同时有减重方向再加强体重目标），`execution` 中安排对应监测任务
- `keyFactors` 中可干预的因素（吸烟、久坐、高盐、腰围超标等）应在运动 / 饮食 / 执行中逐一落实
- **方案正文是给患者看的**：`exercise` / `diet` / `execution` / `summary` / `goalSummary` 中**不得**出现「高风险」「很高危」「分层」「中危」等等级用语，也不得复述评分；用「需要重点控制血压」「建议规律监测血糖」「稳步管理体重」这类**与本次管理方向一致**的行动化表述
- **生成理由 `rationale` 仅给健管师审阅**：可以点明评估等级（如很高危），须引用档案/评估/过敏等事实，禁止编造数值；用通顺中文短句，便于健管师扫读

### 指南条目（`guidelineRecs`）

输入含 `guidelineRecs` 时，它是服务端按患者情况筛出的、可引用的指南推荐条目（`code` / `kind` / `block` / `recommendation` / `patientHint` / `constraint` / `source`）：

- **运动、饮食、执行三块各输出 `refs`**：填写本块所依据条目的 `code`，只能使用 `guidelineRecs` 里出现且 `block` 对应的 code；关键任务（如监测任务）可在任务上带 `refs`
- **`kind=SAFETY` 的 `constraint` 是硬约束**，必须满足（如单次运动时长、强度上限）；违反会被服务端拦截、无法发布
- **`kind=TARGET` 的 `constraint` 默认满足**（如每周中等强度分钟数、监测任务）；确需个体化偏离时，在该块 `rationale` 写明原因
- 建议内容以 `recommendation` 为准，不自行编造指南结论；面向患者的正文参考 `patientHint` 写成行动化表述，**不写指南名称与条目编码**
- **`rationale` / `rationale.overview` 禁止出现条目编码**（如 `WL.ENERGY_DEFICIT`、`DM.DIET.CARB`、`HTN.DIET.SALT`）；编码**只放**对应块的 `refs`。依据用中文要点，例：「按减重热量与每周约 0.8kg 速度（与 6 个月减约 20kg 目标自洽）控制在约 1600 千卡；控精制糖与精白米面、主食粗细搭配；糖尿病示例餐不用粥类；限盐&lt;6g；落实少油少糖与足量蔬果。鸡蛋中度过敏，示例餐与推荐均避开并给平替；降压药与第一口饭同服已写入执行提醒。」
- 未出现在 `guidelineRecs` 的建议可以写，但不得声称有指南依据；**勿编造**档案未写明的糖尿病分型（如擅自写「T1」）

无 `guidelineRecs` 时不输出 `refs`，按下文原则生成。

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

1. 拉取 `CarePlanContext`（档案生活方式摘要、病种、过敏、用药、指标与检验、目标 `goals`、首诊 `intakeBrief` / `highlights`）
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
  "exercise": { "rationale": "块级生成依据…", "refs": ["GEN.PA.AEROBIC"] },
  "diet": { "rationale": "块级生成依据…", "refs": ["HTN.DIET.SALT"] },
  "execution": {
    "rationale": "块级生成依据…",
    "refs": ["HTN.MON.HBPM"],
    "tasks": [{ "code": "…", "title": "…", "rationale": "该任务依据…", "refs": ["HTN.MON.HBPM"] }]
  },
  "summary": "200字以内方案总结：阶段目标、核心干预、随访要点（勿写等级用语）",
  "goalSummary": "具体目标句：须含周期与可核对数字（如 12 周减重约 5kg、血压&lt;130/80、空腹血糖约 6mmol/L）；减重时周期/总量/每周速度须数学自洽；勿与标题同义复读",
  "rationale": {
    "templateKey": "COMPREHENSIVE",
    "overview": "为何选此类型、依据哪些档案/评估事实",
    "sources": ["diseaseArchive", "assessment", "allergens", "meds", "vitals", "goals", "intake"]
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
| `notes` | 患者可见的**执行提醒**（健康建议短句，用中文分号分隔）。禁止写「医嘱」；禁止把「过敏史未采集」等内部质检话术写入 notes |

**热量**（制定方案时由服务端按档案/饮食目标自动写入 `energyTarget`，健管师可随后调整；模型只写文案）：

- `patientContext.energyTarget.available=true`：`calorieHint` 必须使用其中的 `energyKcal` 与碳水 / 蛋白质 / 脂肪克数，可补充目标类型（减重速度）与执行要点。**每周速度必须与 `goalSummary` 自洽**：若目标写「6 个月减重 20kg」，则写「每周约 0.8kg」（≈20/(6×4.3)），禁止照抄无关的 0.5kg。例：「每日约 1650 千卡（碳水 206g / 蛋白质 74g / 脂肪 50g），按每周约减 0.8 kg；三餐定时定量，避免极端节食」。`source=ESTIMATED` 表示尚无饮食目标、按档案估算，文案可写「按身高体重估算」
- `available=false`：**不写任何热量数值**，`calorieHint` 提示完善 `missing` 中的档案项后可计算热量目标
- **不要输出 `energyTarget` 字段**（数字由服务端写入，模型自填会被丢弃或改写）
- `sampleDay` 与 `sampleWeek` 的份量与热量目标大致匹配，**餐文不写千卡数字**，但**主食/加餐须写可执行份量**（见下方「份量」）
- `swapHint` 写具体可执行替换（如「米饭可换成杂粮饭」），不要写成空泛「清淡为主」；无合适替换则省略或 `null`
- **有食物过敏时（`foodAllergyGuard` type=ALLERGY 非空）**：示例餐正文**必须直接避开**过敏原（硬约束，见下条），**不要**再写「鸡蛋过敏：不选蒸蛋…」这类占位平替条——餐已干净就不必每餐挂提示。`swapHint` 仅用于同餐可换的**安全菜品轮换**（如「清蒸鱼可换成豆腐」），且 hint 本身也不得含过敏关键词；服务端会删掉仍含过敏词或「本餐已无过敏却写回避说教」的 hint
- `sampleWeek` 必须输出完整七天。每天至少含早、午、晚；过敏约束同 `sampleDay`；菜品按天轮换，不要七天完全相同
- **份量（食物交换份，硬约束）**：
  - 禁止仅用模糊量词收尾（如单独写「1碗」「半碗」「2块」「一小把」而不给参照）
  - 主食优先写熟重克数或「约一手掌/拳头」参照，例：`杂粮饭约150g（熟重）`、`荞麦面约120g（熟重）`、`全麦馒头约70g（1个）`、`玉米约180g（中等1根）`、`芋头约100g`
  - 蛋白写可执行份量：`水煮蛋1个`、`清蒸鱼约80g`、`豆腐约100g`
  - 加餐写清楚：`无糖酸奶约100g（小盒）`、`柚子约80g（2～3瓣）`、`草莓约100g`
- **鸡蛋频次（硬约束）**：
  - `sampleWeek` 早餐中鸡蛋类（水煮蛋/煎蛋/蒸蛋/蛋羹等）**合计不超过 3 天**
  - 其余早餐用无糖豆浆、无糖酸奶、豆腐/豆干、瘦肉末等轮换；全周鸡蛋总出现也宜 ≤3～4 次
  - 有血脂/超重/肥胖叙事时更应控制鸡蛋频次，优先豆制品与鱼虾

**饮食规则**：

- `recommended` 与 `allergensAvoid` 的 `code` 集合**不得相交**（服务端 ERROR）
- `foodAllergyGuard[].keywords`（type=ALLERGY）中的任一词**不得出现**在 `recommended`、`sampleDay` 与 `sampleWeek` 的正文及 `swapHint`（如鸡蛋过敏时不写「鸡蛋」「蒸蛋」，改用瘦肉/豆浆等替代）；服务端会按关键词全文检查并自动删除命中内容
- **过敏史未采集**（`foodAllergyGuard` 空且上下文提示未采集）：`recommended` **不要默认**写虾蟹贝类、花生、坚果等常见高致敏蛋白；优先瘦肉/禽肉/豆腐/鱼（淡水鱼相对稳妥）；示例餐同理少写「虾等水产」作主推
- 有食物过敏时，`notes` 可写「外食技巧：外出就餐主动询问菜品成分」；不耐受写入 `intolerances`，`notes` 提示注意食用量、可少量但不作为主推
- **`notes` 合规（硬约束）**：
  - 面向患者的生活方式提醒，短句分条（中文分号分隔），例如「进餐顺序：…」「服药配合：…」「外食技巧：…」
  - **禁止**「医嘱 / 处方 / 诊断」等诊疗用语；服药相关用「若您正在使用需随餐服用的药物，请记得与第一口饭同服」「请按医生建议按时服用」
  - **禁止**把「过敏史未采集 / 过敏忌口尚未采集 / 请尽快补全」等**健管师内部质检**写入 `notes`（过敏风险由服务端警告栏处理）
- 有 `guidelineRecs` 时饮食原则以 `block=diet` 的条目为准；无条目时：
  - 糖尿病：控精制糖、规律三餐、增加膳食纤维
  - 高血压：低盐、限制腌制食品
- **糖尿病主食禁/慎粥（硬约束）**：
  - 管理方向含 `DIABETES`，或 `diabetesClinical` 提示 T1/T2/用胰岛素/降糖药时：`sampleDay` / `sampleWeek` **禁止**以粥类作主食（小米粥、杂粮粥、白米粥、南瓜粥、燕麦粥等稀流质）
  - 尤其晚餐与睡前加餐：**不得**写粥；改用「杂粮饭约150g（熟重）」「荞麦面约120g（熟重）」「全麦馒头约70g」「芋头约100g」等相对低 GI、形态偏固态的主食
  - `limited` **必须**含粥类限制项，文案须区分形态，例：`{code:"PORRIDGE", label:"熬煮软烂的粥类（相对杂粮饭升糖更快：小米粥/杂粮粥/白米粥等）"}`——**固态杂粮饭可推荐，软烂粥需限制**，勿让健管师误以为「凡杂粮都限」
  - `principles` 或 `notes` 点明「少选软烂粥、主食写清克数」
  - 理由：粥煮得越烂 GI 越高，青年超重 T1 + 胰岛素场景晚餐喝粥风险极高
- 合并症：取保守合并原则
- **作息与做饭人**（见 `lifestyleProfile` / `basicArchive.diet`）：
  - `lifestyleProfile.workSchedule`：`SHIFT`/`NIGHT` → `notes` 写错峰餐次与用药提醒；示例餐用「班前/班后」而非固定 7/12/18 点；`IRREGULAR` → 给时间窗
  - `basicArchive.diet.cook=FAMILY` → `notes` **必须**加一句家属提示（如「请家人少放油盐，主食换成杂粮」）
  - `cook=EAT_OUT`/`CANTEEN` → 示例餐与 `swapHint` 偏外卖/食堂可执行（少油少糖点餐话术），不要默认「自己蒸煮」
- **药-餐配合**：优先读 `activeMedicationDetails[]`（`name`/`timingNote`/`frequency`）；`diet.notes` 用温和「服药配合」短句写饭前/饭后/与餐同服（有 timingNote 则改写为患者可执行提醒，勿写成替医生下指令）；勿编造未在清单中的药名

---

## 三、方案总结（summary / goalSummary）

| 字段 | 说明 |
|------|------|
| `summary` | 面向健管师的阶段总结：控制目标导向、运动饮食要点、执行重点、2 周复评建议；**不含监测设备操作细节** |
| `goalSummary` | 写入方案头的**具体目标句**（1～2 句，约 40～80 字） |

**标题 vs 目标摘要（禁止重复）**：

- 方案 **标题**由系统按类型生成短名（如「综合管理方案」「糖尿病管理方案」），模型**不必**、也**不要**输出与标题同义的长句。
- `goalSummary` **必须比标题更具体**：优先写入周期与数字目标（体重 kg、血压 mmHg、空腹血糖 mmol/L、糖化 %、腰围 cm 等，来自 `patientContext.goals.items` / 档案实测）；没有明确数字时写「约 X 周把…稳住在合理区间」并点出监测频率，**禁止**只复读「稳步减重、控好血糖与血压」这类口号。
- **减重数字自洽（硬约束）**：若写「N 个月（或 N 周）减重 M kg」或「N 个月内从 A kg 降至 B kg」，则同一句或 `calorieHint` 中的每周速度必须 ≈ `总减重量 / 周数`（月×4.3≈周，可四舍五入到 0.1kg）。例：6 个月从 80kg 降至 60kg（减 20kg）→ 每周约 **0.8kg**；12 周减 5kg → 每周约 **0.4kg**。**禁止**同时出现「6 个月内从 80kg 降至 60kg」与「每周约 0.5kg」这类互相矛盾的数字；`energyTarget.rateKgPerWeek` 若与目标推算明显不符，文案以目标周期与起止体重为准。
- **结局指标为主、运动量级为辅**：体重/血压/血糖/腰围等平铺；每周运动分钟、步数等写入 `exercise`，勿与结局句并列抢戏。若必须带一句运动，用括号附在句末，如「…靠近（每周运动目标：150分钟中等强度）」。
- **禁止**把 `goalSummary` 整段复制成「某某方案 · {goalSummary}」式标题文案；标题短、摘要详，各司其职。

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
| `code` | 稳定编码，如 `EX_WALK_30`、`DIET_LOG`、`MED_REMINDER`、`WATER_LOG` |
| `title` | 展示标题 |
| `category` | `EXERCISE` / `DIET` / `WATER` / `MEDICATION` / `SLEEP` / `MOOD` / `OTHER` |
| `frequency` | `QD`（每日1次）/ `BID` / `TID` / `TIW` / `QW` / `PRN` / **`HABIT`（全天微习惯，当日勾选一次即可）** |
| `timeSlot` | `MORNING` / `AFTERNOON` / `EVENING` / `AFTER_DINNER` / `BEDTIME` / `ALL` |
| `relatedRef` | 可选，关联运动类型或指标，如 `WALKING`、`BLOOD_GLUCOSE`、`SEDENTARY_BREAK` |
| `targetMl` | **仅 `WATER`**：每日饮水目标毫升（整数） |
| `enabled` | 是否启用；关掉后患者端不展示 |
| `rationale` | 可选，灰色「临床依据」短句（患者/健管师可见） |

**编制原则**：

1. **运动任务**：从 `weeklyPlan` 提炼 1–2 条高频任务，`category=EXERCISE`。标题要点名动作（如「餐后步行 30 分钟」「靠墙静蹲与坐姿抬腿 20 分钟」），不要只写「抗阻训练」「有氧运动」「拉伸训练」这类患者不知道做什么的大类
    - 有 `basicArchive.exercise.availableSlots` / `availableSlotsLabels` 时：`timeSlot` **必须**对齐可运动时段（`MORNING`→`MORNING`，`EVENING`/`NIGHT`→`EVENING` 或 `AFTER_DINNER`，`WEEKEND`→标题写「周末」且 frequency 用 `QW`/`TIW` 而非默认每日）
    - 有 `basicArchive.schedule.offWork` / `offWorkLabel` 时：晚上 8 点及以后下班，不要把运动写在晚饭前
    - 有 `basicArchive.schedule.offWorkNote`（倒班或夜班的上班时段，自由文本）或 `lifestyleProfile.workSchedule=SHIFT|NIGHT` 时：按客户写的班次排运动，不要写死傍晚，也避开「刚下夜班」
    - 有 `basicArchive.diet.dinnerTime` / `dinnerTimeLabel` 为晚上 9 点以后时：不安排刚吃完晚饭就运动
2. **饮食任务**：至少 1 条「记录三餐与加餐」，`code=DIET_LOG`，`category=DIET`（**不要**再把饮水写进 DIET）
    - **禁止**把服药写进饮食任务标题（错误例：「记录三餐与加餐，服药与第一口饭同服」）。饮食打卡只记吃了什么；服药另开任务
    - 有 `goals.preferences.constraints` / `constraintsDisplay` 或 `basicArchive.weightHistory.failReasons` 时：执行标题/总结点出对应阻力破解（应酬多→少饮酒、低盐点餐；经常外卖→给能点到的替换；总是觉得饿→正餐留足蛋白质和蔬菜，不把热量压太低；家人不太支持→给家属一句可执行的话；没时间运动→把活动嵌进已有空档）
2b. **用药提醒（硬拆分）**：`activeMedicationDetails` 或问卷 `medication.mealRelation` 含随餐/第一口饭同服时，**必须**另编 1 条：
    - `code=MED_REMINDER`，`category=MEDICATION`
    - 标题例：`服药提醒：阿卡波糖与第一口饭同服`（有药名写药名；无药名写「随餐药物与第一口饭同服」）
    - 随三餐药：`frequency=TID`，`timeSlot=ALL`；一日一次药：`QD` + 对应时段
    - `rationale` 写清：具体服药在患者端「用药」页按时段打卡；本条是方案级提醒，勿与饮食记录混写
    - 药-餐配合短句仍可写在 `diet.notes`，但**不要**塞进 `DIET_LOG` 标题
3. **饮水任务**：无限水禁忌时编入 1 条 `code=WATER_LOG`，`category=WATER`，`frequency=QD`，`timeSlot=ALL`，填写 `targetMl`
    - 有体重：`targetMl ≈ round(kg×30 / 100)×100`，夹在 **1200–3000**（例：82kg→2500）；无体重默认 1600
    - 标题：「足量饮水，每日约 {targetMl} ml」
    - `rationale` 用大白话，例：「根据您的体重约 82 kg 计算，建议每天喝 2500 毫升水。少量多次，不要等渴了再喝。」**禁止**写「落在 1200-2000ml 上限」这类不通顺/与数字打架的话
3b. **久坐起身**：标题含「久坐 / 每小时起身」时，`frequency` **必须**用 `HABIT`（全天微习惯），`timeSlot=ALL`，`relatedRef=SEDENTARY_BREAK`；**禁止** `QD`（每日1次会让患者以为动一次就够、系统也不再提醒）
4. **限水**：上下文出现心衰、慢性肾病、医嘱限水等 → **不要生成** `WATER` 任务（由健管师决定是否手加）
5. **糖尿病标签**：增加「按时监测血糖」`relatedRef=BLOOD_GLUCOSE`
6. **高血压标签**：增加「家庭血压测量」`relatedRef=BLOOD_PRESSURE`
6b. **肥胖 / 减重方向**：增加「记录体重」`code=WEIGHT_LOG`，`relatedRef=WEIGHT`
    - 默认 `frequency=QD`（每日）
    - 若 `patientContext.goals.weighFrequency=WEEKLY`，或 `highlightGuidance` / 高亮命中进食障碍、身材焦虑 → **必须** `frequency=QW`（每周1次），标题写成「每周固定一天晨起空腹称体重」；**禁止**标题写每周一次却把 `frequency` 标成 `TIW`/`QD`
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
      "rationale": "建议每天喝 1600 毫升水。少量多次，不要等渴了再喝。",
      "enabled": true
    },
    {
      "code": "MED_REMINDER",
      "title": "服药提醒：阿卡波糖与第一口饭同服",
      "category": "MEDICATION",
      "frequency": "TID",
      "timeSlot": "ALL",
      "rationale": "具体服药请在「用药」页按时段打卡；本条为方案级提醒",
      "enabled": true
    }
  ]
}
```

---

## 五、上下文使用说明

生成时必须参考上下文中的：

- 病种与合并症 → 决定模板倾向与安全提示
- **`patientContext.goals.careTracks` / `careTracksLabels`（本次管理方向）** → 叙事主轴：
  - 含 `DIABETES`：以控糖为主（监测、餐后活动、防低血糖），体重仅为辅助除非同时含减重
  - 含 `HYPERTENSION`：以控压为主（限盐、家庭血压、避免憋气用力）
  - 含 `WEIGHT_LOSS`：才强化减重热量、称重与体成分叙事
  - 多方向并存：综合模板下分清主次，勿把纯慢病方案写成减肥方案
- `patientContext.goals`（`mode`/`modeLabel`、`primaryAppeal`/`primaryAppealLabels`（可多选；兼容旧字段 `primaryAppealLabel`）、目标 `items` 含体重/糖化/空腹血糖/血压、`weighFrequency` / `pace` / `lossBlocked`）
  - `lossBlocked=true` 时不得以快速减重为主叙事
  - 有 `HBA1C`/`FPG`/`BP_SYS`/`BP_DIA` 目标时，在 `goalSummary` 与监测任务中体现
  - 无减重方向时不要默认「每日称重 / 快速减脂」话术
- `patientContext.diabetesClinical` / `hypertensionClinical`（首诊慢病专属要点：分型、低血糖、监测频率、用药规律、家庭自测、晨峰等）→ 运动强度、监测任务与饮食限盐/控糖
- `patientContext.basicArchive` 生活方式摘要（饮食含 `taste`/`dinnerTime`/`sugaryDrinks`/`cook`、`schedule.offWork`/`offWorkNote`、运动习惯与 `availableSlots`、睡眠含 `hours`/`refreshed`、烟酒、心理与改变意愿、孕哺、运动禁忌/关节问题、减重史 `failReasons` 等，含 `*Label`）→ 个体化强度、示例餐与执行时段
  - `schedule.offWork` / `offWorkLabel`：规律白班或不固定时的下班时间。晚上 8 点及以后下班，运动改到睡前轻度活动，不要排晚饭前
  - `schedule.offWorkNote`：倒班或夜班时客户自己写的上班时段（可多个）。运动和餐次按这段文字排，不要套用固定傍晚
  - `diet.dinnerTime` / `dinnerTimeLabel`：晚餐时间。晚上 9 点以后吃晚饭时，不安排刚吃完就运动
  - `diet.taste` / `tasteLabels`：日常饮食更接近哪种（偏咸、偏油或油炸、精米白面、甜食）。按命中项给替换，主食和烹饪细问只在健管师补了 `staple`/`cooking` 时才用
  - `sleep.refreshed` / `refreshedLabel`：早上是否解乏。**打鼾（`disorder=SNORING`）+ 晨起疲惫（`PARTIAL`/`NO`）+ 超重/肥胖或糖/压慢病** 时，属阻塞性睡眠呼吸暂停（OSA）高危信号：`diet.notes` **必须**写强提醒「建议尽快前往呼吸内科/耳鼻喉科做睡眠呼吸监测（多导睡眠图）；打鼾严重会影响血压和血糖控制」——禁止只写「关注睡眠呼吸情况」；**不要写成已确诊 OSA**
- `patientContext.lifestyleProfile`（`workSchedule` / `workScheduleLabel` / `scheduleHint`）→ 餐次、用药提醒与运动时段对齐班次；与上面的下班时间、倒班时段一起看
- `patientContext.goals.preferences.constraints` 与 `constraintsDisplay` → 执行阻力多选（应酬多、经常外卖、总是觉得饿、家人不太支持、没时间运动）。写入执行与总结的可执行破解，勿空喊「要坚持」
- `patientContext.intakeBrief.keyAnswers` 里的 `medication.mealRelation`（`display` 为中文）→ 吃药和吃饭怎么配合。含「跟第一口饭一起吃」时写进用餐提醒。与 `activeMedicationDetails.timingNote` 一并使用
- `patientContext.activeMedicationDetails`（药名 + `timingNote`）→ 单药备注进 `diet.notes`；兼容旧字段 `activeMedications` 字符串列表
- `patientContext.intakeBrief`（最近首诊/待补）与高亮：
  - `keyAnswers`：核心诉求、管理方向、糖/压/体重目标与临床关键答（带 `label`/`display`），优先落实，**勿整段照抄**到患者正文
  - 有 `aiSummary` 时吸收要点、风险与下一步
  - **`highlightGuidance`（优先）**：首诊「高亮追问 / 方案影响」的模型可读版；每条含 `severityLabel`（谨慎/转介/阻断）、`mustApply`（须写入运动/饮食/执行的约束）、可选 `clinicianNote`（追问话术，只吸收约束、**勿照抄**进患者正文）、`source`（题干）
    - `mustApply` 为硬约束：对应写进 `exercise`（强度/禁忌）、`diet`（限盐/控糖等）、`execution`（监测/用药提醒）与 `goalSummary`
    - `severityLabel=转介/阻断` → 节奏更保守；阻断减重时不得以快速减重为主叙事
  - 兼容原始 `highlights`（`field`/`severity`/`planEffect`/`followUp`）；进食障碍 / 身材焦虑 / 运动限制 / 孕哺等 → 称重勿过度强调每日、运动避禁忌
  - `rationale` 可写「依据首诊高亮…」，患者正文仍用行动化表述
- 过敏原 → 写入 `allergensAvoid`，且不得出现在 `recommended`、`sampleDay` 与 `sampleWeek`（含 `swapHint`；以 `foodAllergyGuard` 为准）
- 限水相关病情 → 不要生成 `WATER` 任务
- 最近指标（血糖、血压、体重等）→ 调整运动强度与总结表述
- `energyTarget` → 饮食 `calorieHint` 的热量数字（见「热量」）；**非减重方向**可弱化热量缺口表述，改为均衡与限盐/控糖
- 在用药物 → `diet.notes` 与执行计划中谨慎表述
- `guidelineRecs` → 建议内容、量化约束与各块 `refs`（见「指南条目」）
- `contextIncomplete=true` → 文案中可提示信息有限，但不拒绝生成
- `rationale.sources` 可含 `intake`、`goals`、`diseaseArchive`、`assessment`、`allergens`、`meds`、`vitals`

## 六、禁止事项

1. 输出 markdown 代码块包裹 JSON
2. 编造检验数值或诊断
3. 自动发布或声称「已生效」
4. 过敏食物出现在推荐列表或示例食谱
5. 卧床/骨折场景推荐剧烈运动；多轮修订时因饮食意见而丢掉既有骨折/制动限制
6. 在总结中承诺疗效或替代就医
6b. 引用 `guidelineRecs` 以外的条目编码；在患者可见正文或 **`rationale` 文案**中写指南名称、条目编码（如 `HTN.DIET.SALT`）、「专家认证」「医生制定」等表述——编码只属于 `refs`
7. 面向患者的文案（标题、总结、建议、任务名）出现「处方」「诊断」「确诊」「医嘱」「治疗方案」等医疗用语：用「运动计划」「按时服药」「按时监测」「已患」等健康管理表述
