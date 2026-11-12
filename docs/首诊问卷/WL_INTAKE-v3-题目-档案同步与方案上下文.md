# 首诊问卷 WL_INTAKE v3：逐题对照（档案同步 × 方案生成上下文）

| 项 | 内容 |
|---|---|
| 问卷 | `WL_INTAKE` **v3**（`backend/health-core/src/main/resources/questionnaires/questionnaires/WL_INTAKE.v3.json`） |
| 题目底稿 | [`WL_INTAKE-v3-题目与采集目的.csv`](./WL_INTAKE-v3-题目与采集目的.csv) |
| 本表 CSV | [`WL_INTAKE-v3-题目-档案同步与方案上下文.csv`](./WL_INTAKE-v3-题目-档案同步与方案上下文.csv) |
| 整理日期 | 2026-11-12 |

## 判读口径

### 同步去向（提交首诊后写回）

依据 `QuestionnaireResponseService` 提交写回：

| 字段前缀 | 写回位置 |
|---|---|
| `profile.*` | `people_profile` |
| `metric.*` | `vital_record`（来源 INTAKE） |
| `basic.*` | `people_basic_archive.content_json` |
| `disease.diabetes.*` / `disease.hypertension.*` | `people_field`（病种临床字段） |
| `allergy.*` | 过敏表 |
| `medication.current` | `people_medication` |
| `goal.*` | `people_health_goal` |
| `consent.*` / `staff.*` / 部分时机字段 | 主要留在 `questionnaire_response.answers_json` |

所有题目答案都会进入 **`questionnaire_response.answers_json`**。

### 是否进方案生成上下文

生成方案时 `CarePlanContextService` → `patientContext`，首诊相关入口包括：

1. **`intakeBrief.keyAnswers`**：白名单字段（`CarePlanPromptContextSupport.INTAKE_FIELD_LABELS`）
2. **`intakeBrief.aiSummary` / highlights / highlightGuidance`**
3. **经档案/指标/用药/过敏/目标表再读入**：`basicArchive`、`latestMetrics`、`activeMedications`、`allergens`、`goals`、`diabetesClinical`、`hypertensionClinical`、`lifestyleProfile` 等

> 注意：家族史会写基础档案；`summarizeBasicArchive` 已拷贝 `familyHistoryStatus` / `familyHistoryItems` / `familyHistorySummary`（及早发心血管、一级亲属糖尿病标记）进方案 Prompt。

本题库共 **149** 题（含条件显隐题；实际作答因 `careTracks`/性别等分支而异）。

## 基础信息（BASIC_PROFILE）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 姓名 | 识别客户、称呼与档案展示 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 否（档案展示/联系用；方案 Prompt 一般不注入姓名手机） |
| 性别 | 分性方案、女性健康显隐、腰围标准 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 是 → 间接：指南匹配 GuidelineFacts（年龄/性别）；不在 patientContext 明文展示 |
| 出生日期 | 计算年龄，控制未成年/高龄干预强度；方案影响：干预强度取保守值 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 是 → 间接：指南匹配 GuidelineFacts（年龄/性别）；不在 patientContext 明文展示 |
| 手机号 | 联系随访与填写链接手机号核验 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 否（档案展示/联系用；方案 Prompt 一般不注入姓名手机） |
| 微信号 | 微信随访备用联系方式 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 否（档案展示/联系用；方案 Prompt 一般不注入姓名手机） |
| 职业 | 判断久坐/体力劳动，辅助运动饮食处方 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 否（档案展示/联系用；方案 Prompt 一般不注入姓名手机） |
| 婚姻状况 | 了解家庭与社会支持背景 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 否（档案展示/联系用；方案 Prompt 一般不注入姓名手机） |

## 诉求与目标（GOALS_PREFERENCE）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 这次最想解决什么问题？（可多选） | 锁定核心诉求，推导管理专线与题目分支 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 本次纳入哪些管理方向？ | 确定入组方向，控制专属模块显隐与入组 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 用一句话说说你的期望（选填） | 记录客户原话期望，便于个性化沟通 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 是医生建议做这次管理的吗？ | 区分医嘱驱动与自驱，影响依从与转诊 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 本次主目标类型 | 明确主目标类型（减重/体成分/控糖/控压等） | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 希望体重到多少？ | 量化减重目标并校验目标 BMI 安全；方案影响：按当前身高计算，目标体重对应 BMI < 18.5，请先补身高或上调目标体重 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 希望多久达到体重目标？ | 评估目标节奏，避免过快减重 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 腰围目标（健管师选填，可不问客户） | 腰围目标，关注中心性肥胖 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 体脂率目标（健管师选填） | 体成分改善目标（健管师） | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 糖化血红蛋白目标（%）（健管师按指南选填） | 糖尿病糖化目标 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 空腹血糖目标（mmol/L）（健管师选填） | 糖尿病空腹血糖目标 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 收缩压目标（高压）（健管师选填） | 高血压收缩压目标 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 舒张压目标（低压）（健管师选填） | 高血压舒张压目标 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |
| 每天大概能拿出多少时间活动或运动？ | 可执行运动时间，定运动处方 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 饮食上能做多大调整？ | 饮食改变意愿，定饮食干预力度 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 希望怎么跟进？ | 约定随访触达方式 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 希望多久沟通一次？ | 约定随访频次 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → patientContext.goals（及 careFocus） |
| 执行健康计划时，您觉得最大的阻力是什么？（可多选） | 把不可执行的点写进方案：应酬、外卖、容易饿、家人不支持、没时间运动 | 健康目标 people_health_goal；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.goals（及 careFocus） |

## 体测（BODY_MEASURE）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 身高 | 算 BMI、理想体重与热量需求 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 体重 | 体重基线，对比与目标设定；方案影响：偏瘦：不设减重目标，优先营养与转介评估 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 腰围（肚脐上方一横指水平绕一圈） | 中心性肥胖与代谢风险初筛 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 臀围（臀部最突出处水平一圈） | 算腰臀比，评估体脂分布 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 体脂率（体脂秤或体成分仪） | 体成分基线，区分减重与减脂 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 血压：高压（收缩压） | 血压基线与运动安全；方案影响：血压很高，建议先就医，暂缓中高强度运动 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 血压：低压（舒张压） | 血压基线与运动安全；方案影响：同上 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 静息心率 | 静息心率，运动安全参考；方案影响：运动前建议先就医评估 | 指标表 vital_record（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.latestMetrics（经指标表） |
| 这些数据怎么来的？ | 标注测量来源可信度 | 答卷 answersJson（测量来源标注）；答卷 questionnaire_response.answers_json | 否（测量可信度标注，不进方案 Prompt） |

## 病史与用药（MEDICAL_HISTORY）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 医生说过您有哪些疾病？ | 已知疾病，方案禁忌、监测与转诊；方案影响：确认在用药与监测习惯；可提示一并纳入对应慢病管理；干预前需医生同意；留意情绪与进食、依从性 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案）；可影响 diseaseLabels / 指南匹配病种标签（间接） |
| 其他疾病 | 补充未列疾病 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案）；可影响 diseaseLabels / 指南匹配病种标签（间接） |
| 目前在吃哪类药？ | 在用药类别，评估低血糖/药物影响体重等；方案影响：调整饮食或运动前防低血糖，与医生沟通；留意直立性低血压；力量训练与高强度运动需谨慎；核实药名、剂量与来源；部分药物影响食欲、体重或血糖 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 在用药物明细（药名 / 剂量） | 在用药物明细，写回用药清单 | 用药清单 people_medication（source=INTAKE）；答卷 questionnaire_response.answers_json | 是 → patientContext.activeMedications / activeMedicationDetails（经用药表） |
| 这些药一般和吃饭怎么配合？（可多选） | 用药时机。阿卡波糖等须随第一口饭服。不同药可以不同时机，所以是多选 | 答卷 answersJson；方案 keyAnswers 直读（不单独建表）；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；intakeBrief.keyAnswers（用药时机） |
| 有没有在用代餐、保健品或非处方调理产品？ | 保健品/补充剂，避免与方案冲突；方案影响：了解成分来源，建议停用并咨询医生 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 近两年有没有做过手术或住院？ | 近期住院提示病情不稳定，方案需谨慎；方案影响：了解时间与恢复情况，恢复期内不安排中高强度运动 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 做过什么手术或因何住院？大概什么时候？ | 住院原因补充 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 目前身体最不舒服的地方是？ | 当前主诉不适，优先关注点 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 有没有医生说过不能运动或要限制运动？ | 运动医学限制，处方避雷；方案影响：先就医评估再开始运动；优先稳压，暂缓高强度；设运动强度上限，避开禁忌 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 医生具体怎么说的？ | 运动限制细节 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 有没有关节问题或运动损伤？ | 关节问题，影响运动类型选择；方案影响：以低冲击运动为主（快走、骑车、游泳），避开受伤部位 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 是否曾因使用激素类药物出现过血糖升高？ | 激素相关高血糖风险线索 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 颈后、腋下等处是否有皮肤发黑、增厚？ | 黑棘皮等胰岛素抵抗体征线索 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 女性健康（WOMEN_HEALTH）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 目前有没有在备孕、怀孕或者哺乳？ | 备孕/孕期/哺乳禁忌与保守策略；方案影响：不设热量缺口与减重目标，只给均衡营养与适度活动；缺口与速度取保守值；建议先确认 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 月经情况 | 月经/围绝经线索，内分泌与节食相关；方案影响：可能与过度节食或内分泌问题有关，建议就医明确 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 是否在 50 岁之前绝经？ | 早绝经相关心血管与代谢风险 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 怀孕期间是否得过妊娠期糖尿病？ | 妊娠糖尿病史，远期糖尿病风险；方案影响：远期糖尿病风险升高，控糖与体重管理宜更积极监测 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 过敏与忌口（ALLERGY_DIET_RESTRICTION）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 有没有食物或药物过敏、不耐受？ | 有无过敏，饮食与用药安全入口 | 过敏档案 people_allergy / people_allergy_profile；答卷 questionnaire_response.answers_json | 是 → patientContext.allergens / allergyStatus / foodAllergyGuard（经过敏表） |
| 对哪些食物过敏或不耐受？ | 食物过敏/不耐受避雷 | 过敏档案 people_allergy / people_allergy_profile；答卷 questionnaire_response.answers_json | 是 → patientContext.allergens / allergyStatus / foodAllergyGuard（经过敏表） |
| 药物过敏 | 药物过敏避雷 | 过敏档案 people_allergy / people_allergy_profile；答卷 questionnaire_response.answers_json | 是 → patientContext.allergens / allergyStatus / foodAllergyGuard（经过敏表） |
| 饮食上有什么忌口？ | 宗教/个人忌口，保证饮食可执行 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 其他忌口 | 其他忌口补充 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 家族史（FAMILY_HISTORY）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 父母、兄弟姐妹或子女有没有慢性病？ | 有无家族慢病史入口 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive.familyHistoryStatus / familyHistorySummary |
| 哪位亲属、什么病？ | 一级亲属慢病明细，遗传与早发风险 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive.familyHistoryItems / earlyCvFamilyHistory / firstDegreeDiabetesFamilyHistory |

## 糖尿病管理（DM_CLINICAL）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 糖尿病是哪一型？ | 糖尿病分型，决定管理路径 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 大概哪一年得知患病的？（选年份） | 病程长短，并发症与监测强度 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 有没有医生说过的慢性并发症？ | 慢性并发症，运动与转诊决策 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → patientContext.diabetesClinical（及 keyAnswers） |
| 有没有出现过低血糖？ | 低血糖史，饮食运动安全边界；方案影响：热量缺口与运动前注意防低血糖，调整前与医生沟通 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 平时测血糖频次？ | 血糖监测习惯，方案可执行性 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 最近一次糖化血红蛋白？ | 近期控糖水平基线；方案影响：控糖优先；若同时减重，节奏宜稳，避免激进缺口 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 最近空腹血糖？ | 近期空腹血糖基线 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 最近餐后 2 小时血糖？ | 近期餐后血糖基线 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 降糖药 / 胰岛素吃得规律吗？ | 降糖药依从，干预重点；方案影响：先提醒按时用药，方案不强推大幅饮食变动 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.diabetesClinical（及 keyAnswers） |
| 最近一次眼底检查大概什么时候？ | 眼底检查时效，并发症筛查提醒 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → patientContext.diabetesClinical（及 keyAnswers） |

## 高血压管理（HTN_CLINICAL）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 大概哪一年得知患高血压？（选年份） | 高血压病程 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.hypertensionClinical（及 keyAnswers） |
| 在家有测血压吗？ | 家庭血压监测习惯 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.hypertensionClinical（及 keyAnswers） |
| 近期收缩压大概多少？ | 近期收缩压；方案影响：血压偏高时力量训练与高强度运动需谨慎，优先稳压 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.hypertensionClinical（及 keyAnswers） |
| 近期舒张压大概多少？ | 近期舒张压 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.hypertensionClinical（及 keyAnswers） |
| 有没有晨起血压明显偏高？ | 晨峰血压线索 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.hypertensionClinical（及 keyAnswers） |
| 起立后有没有头晕或眼前发黑？ | 体位性低血压，运动/用药安全；方案影响：注意直立性低血压；起身宜慢，运动前后勿突然起立 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.hypertensionClinical（及 keyAnswers） |
| 降压药吃得规律吗？ | 降压药依从；方案影响：先提醒按时用药，再谈限盐与运动 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.hypertensionClinical（及 keyAnswers） |
| 以前测到过的最高收缩压？ | 历史最高收缩压，严重程度 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → patientContext.hypertensionClinical（及 keyAnswers） |
| 以前测到过的最高舒张压？ | 历史最高舒张压，严重程度 | 病种临床字段 people_field（disease.* 前缀）；可驱动专病模块/评估补数；答卷 questionnaire_response.answers_json | 是 → patientContext.hypertensionClinical（及 keyAnswers） |

## 减重史（WL_WEIGHT_HISTORY）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 以前减过重吗？ | 是否有减重经历，匹配干预策略；方案影响：体重反复的客户节奏更保守，重点防反弹 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 以前用过哪些方法？ | 既往减重方法，避免重复失败路径；方案影响：术后营养需求特殊，需了解术式与时间 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 最多一次减了多少公斤？ | 曾减幅度，评估可达成性 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 之前没坚持下来或反弹的主要原因？ | 失败原因，方案需针对性破解 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 成年后最重大概多少公斤？ | 历史最高体重，反弹与目标参考 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 成年后最轻大概多少公斤？ | 成年最低体重，目标安全边界 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 最近 3–6 个月体重有变化吗？ | 近期体重趋势 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 大概变了多少公斤？ | 近期体重变化幅度 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 减重风险筛查（WL_RISK_SCREEN）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 以前有没有出现过暴食、催吐，或者长时间刻意不吃饭的情况？ | 进食障碍史筛查，必要时转诊；方案影响：不设热量缺口；称重改为每周；方案中提示转介 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 有没有因为这个找过医生或心理咨询？ | 是否寻求过专业帮助 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 希望多快看到减重效果？ | 期望速度，纠正过快减重预期；方案影响：预期沟通：稳健节奏更可持续，避免过快减重 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 饮食行为（DIET_BEHAVIOR）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 一日三餐规律吗？ | 餐次规律性，设计饮食节奏 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 晚餐通常几点吃？ | 排晚餐与运动间隔。晚上 9 点以后吃晚饭时，不安排刚吃完就运动 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 早餐吃吗？ | 早餐习惯 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 点外卖或在外面吃的频率？ | 外卖/外食频率，方案可执行性 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 吃零食或夜宵的频率？ | 夜宵习惯，控糖控重关键点 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 奶茶、可乐、果汁这类含糖饮料喝多少？ | 含糖饮料摄入；方案影响：控糖 / 减重方案优先减含糖饮料 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 您的日常饮食更接近哪种？（可多选） | C 端只问这一道。咸→限盐；油炸→少油替换；精米白面→控糖时逐步粗细搭配；甜食→优先减 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 主食一般吃什么？（面诊可再细问） | 健管师面诊细问主食结构，C 端不问 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 蔬菜水果吃得多吗？ | 蔬果摄入水平 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 家里做菜常用哪些方式？（面诊可再细问） | 健管师按烹饪方式给替换。仅当已纳入减重、糖尿病或高血压时显示 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 吃一顿饭大概多久？ | 进食速度，饱腹与热量控制 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 每天大概喝多少水？ | 日饮水量 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 平时主要谁做饭？ | 是否自己做饭，干预可行性 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 是一个人住吗？ | 独居与否，影响做饭与依从支持 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 以前记录过饮食吗？ | 是否记过饮食，数字工具接受度 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 饮食备注 | 饮食补充说明 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 运动行为（EXERCISE_BEHAVIOR）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 平时运动（每次约 30 分钟以上）的频率？ | 运动频率基线 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 一般做什么运动？ | 偏好运动类型 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 运动强度大概怎样？ | 强度耐受 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 每次运动多长时间？ | 单次时长 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 做过力量训练吗？ | 力量训练经验 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 平时一天大概走多少步？ | 日常步数水平 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 一天大概坐多久？ | 久坐时长，活动处方切入点 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 一般什么时候有空运动？ | 可运动时段 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 在哪运动方便？ | 运动场所条件 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 家里有什么器械？ | 可用器械 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 运动备注 | 运动补充 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 睡眠与烟酒（SLEEP_SUBSTANCE）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 平时作息是哪种？ | 按班次安排餐次、用药提醒与运动时段。选倒班或夜班后不出现固定下班时间，改填上班时段 | 患者档案 people_profile；答卷 questionnaire_response.answers_json | 是 → patientContext.lifestyleProfile（经 people_profile） |
| 平时大概几点下班？ | 排运动时段。晚上 8 点及以后下班：不要安排晚饭前运动 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 倒班或夜班时，大概哪些时段在上班？ | 方案按实际班次排运动，不写死傍晚 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 工作和日常活动量属于哪种？ | 整体活动水平分层 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 每天大概睡几个小时？ | 睡眠时长，代谢与依从相关；方案影响：睡眠不足影响食欲、血压与控糖，方案可加入睡眠改善 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 早上起来觉得解乏吗？ | 肥胖或高血压再叠加极度疲劳时，方案提示睡眠呼吸监测 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → intakeBrief.keyAnswers；patientContext.basicArchive 摘要（经基础档案） |
| 一般几点睡？ | 入睡时间，作息评估 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 一般几点起？ | 起床时间，作息评估 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 睡眠质量 | 睡眠质量 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 睡眠问题 | 睡眠障碍/鼾症等线索 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 多久熬一次夜（凌晨以后睡）？ | 熬夜习惯 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 睡眠备注 | 睡眠补充 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 吸烟吗？ | 吸烟状态，心血管风险 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 每天几支？ | 吸烟量 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 吸了多少年？ | 烟龄 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 哪年戒的？ | 戒烟年份 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 家里或单位经常有人在身边吸烟吗？ | 二手烟暴露 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 喝酒吗？ | 饮酒状态，热量与肝代谢 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 多久喝一次？ | 饮酒频率 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 常喝什么酒？ | 酒类偏好 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 一次喝多少？ | 饮酒量 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 心理与情绪（PSYCH_BASE）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 现在想改变生活习惯的程度？ | 改变准备度，匹配干预节奏；方案影响：首版方案只设 1–2 个小目标 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 最近压力大吗？ | 压力水平，依从与情绪进食相关 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 压力大的时候吃东西会变吗？ | 压力性进食 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 心情不好或无聊时会想吃东西吗？ | 情绪性进食；方案影响：以行为替代为主，避免严格限制 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 家里人支持你这次健康管理吗？ | 家庭支持度 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |
| 愿意记录饮食、体重、血压或血糖吗？ | 是否愿意打卡记录，工具依从预期 | 基础档案 people_basic_archive.content_json；答卷 questionnaire_response.answers_json | 是 → patientContext.basicArchive 摘要（经基础档案） |

## 健管师补充（STAFF_NOTE）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 重点跟进事项 | 健管师待跟进要点留痕 | 健管师备注（答卷 answersJson，不进档案主表）；答卷 questionnaire_response.answers_json | 否（合规/内部备注，不进方案 Prompt） |
| 转介建议 | 转诊建议留痕 | 健管师备注（答卷 answersJson，不进档案主表）；答卷 questionnaire_response.answers_json | 否（合规/内部备注，不进方案 Prompt） |
| 备注 | 健管师综合备注 | 健管师备注（答卷 answersJson，不进档案主表）；答卷 questionnaire_response.answers_json | 否（合规/内部备注，不进方案 Prompt） |

## 隐私授权（CONSENT）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 已向客户说明并取得敏感健康信息处理同意 | 敏感健康信息处理授权（合规） | 授权留痕（答卷/合规字段）；答卷 questionnaire_response.answers_json | 否（合规/内部备注，不进方案 Prompt） |
| 授权方式 | 授权方式留痕 | 授权留痕（答卷/合规字段）；答卷 questionnaire_response.answers_json | 否（合规/内部备注，不进方案 Prompt） |

## AI 总结（INTAKE_AI_SUMMARY）

| 题目 | 目的 | 同步去向 | 是否进方案生成上下文 |
|---|---|---|---|
| 首诊 AI 总结 | 健管师确认的 AI/人工总结 | 首诊答卷 AI 总结字段（questionnaire_response）；健管师备注（答卷 answersJson，不进档案主表）；答卷 questionnaire_response.answers_json | 是 → intakeBrief.aiSummary |
