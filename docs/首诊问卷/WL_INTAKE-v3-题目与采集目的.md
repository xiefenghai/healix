# 首诊问卷 WL_INTAKE v3（全部题目）

来源：`WL_INTAKE.v3` + 各模块 JSON + `field-catalog`。题目以模块 JSON 为准，下面表格序号仍是调整前的 146 题底稿。

Excel/筛选请用同目录 CSV。

本轮已落到 v2/v3 模块（C 端标准档同步），下面表格与模块 JSON 一致：

- 晚餐通常几点吃：`basic.diet.dinnerTime`，在饮食行为。
- 平时大概几点下班：`basic.schedule.offWork`。作息选规律白班或不固定时才出现。倒班或夜班改填 `basic.schedule.offWorkNote`（自由文本，可写多个时段）。两题都在睡眠与烟酒，紧挨作息题。
- 这些药和吃饭怎么配合：`medication.mealRelation`（多选，不同药可以不同时机），在用药明细下一行。
- 早上起来觉得解乏吗：`basic.sleep.refreshed`，在睡眠时长下一行。
- 第 26 题 `goal.constraints` 改为多选：应酬多 / 经常外卖 / 总是觉得饿 / 家人不太支持 / 没时间运动。
- 口味、主食、烹饪收成一道「日常饮食更接近哪种」；主食和烹饪细问只留给健管师面诊。
- 历史最高血压、成年最重/最轻体重只留给健管师面诊。身材满意、身材焦虑不再出现在首诊。
- 「平时作息」在睡眠与烟酒模块第一题，不在基础信息。

## 基础信息（BASIC_PROFILE）
模块加载：始终

平时作息不在本模块，见「睡眠与烟酒」第一题，并与下班时间联动。

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 1 | 姓名 | `profile.displayName` | TEXT | 自由文本 | 必填 | 识别客户、称呼与档案展示 |
| 2 | 性别 | `profile.gender` | ENUM | MALE=男｜FEMALE=女 | 必填 | 分性方案、女性健康显隐、腰围标准 |
| 3 | 出生日期 | `profile.birthday` | DATE | 选日期 | 必填 | 计算年龄，控制未成年/高龄干预强度；方案影响：干预强度取保守值 |
| 4 | 手机号 | `profile.mobile` | TEXT | 自由文本 | 必填 | 联系随访与填写链接手机号核验 |
| 5 | 微信号 | `profile.wechatId` | TEXT | 自由文本 | 可后补 | 微信随访备用联系方式 |
| 6 | 职业 | `profile.occupation` | ENUM | OFFICE=行政 / 文职｜TECH=技术 / IT｜TEACHER=教师 / 教育｜MEDICAL=医护｜SALES=销售 / 商务｜SERVICE=服务业｜WORKER=工人 / 技工｜DRIVER=司机 / 物流｜FARM=农林牧渔｜STUDENT=学生｜HOME=全职家务｜RETIRED=退休｜UNEMPLOYED=待业 / 无业｜FREELANCE=自由职业｜OTHER=其他 | 首诊应收 | 判断久坐/体力劳动，辅助运动饮食处方 |
| 7 | 婚姻状况 | `profile.maritalStatus` | ENUM | UNMARRIED=未婚｜MARRIED=已婚｜DIVORCED=离异｜WIDOWED=丧偶｜OTHER=其他 | 可后补 | 了解家庭与社会支持背景。未满 18 岁不显示（`showIf.ageGte=18`） |

## 诉求与目标（GOALS_PREFERENCE）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 9 | 这次最想解决什么问题？（可多选） | `goal.primaryAppeal` | MULTI | GLUCOSE=控糖（糖尿病）｜BP=控压（高血压）｜LIPID=控血脂｜FAT_LOSS=减脂减重｜BODY_SHAPE=塑形增肌｜METABOLIC=三高 / 代谢综合｜SLEEP=改善睡眠｜GUT=改善肠胃｜CONSULT=先了解看看｜OTHER=其他 | 必填 | 锁定核心诉求，推导管理专线与题目分支 |
| 10 | 本次纳入哪些管理方向？ | `goal.careTracks` | MULTI | WEIGHT_LOSS=减重管理｜DIABETES=糖尿病管理｜HYPERTENSION=高血压管理｜NONE=暂不加入项目 | 仅健管师 | 确定入组方向，控制专属模块显隐与入组 |
| 11 | 用一句话说说你的期望（选填） | `goal.appealText` | TEXT | 自由文本 | 首诊应收 | 记录客户原话期望，便于个性化沟通 |
| 12 | 是医生建议做这次管理的吗？ | `goal.doctorAdvised` | ENUM | YES=是｜NO=否 | 可后补 | 区分医嘱驱动与自驱，影响依从与转诊 |
| 13 | 本次主目标类型 | `goal.mode` | ENUM | LOSS=减重｜BODY_COMPOSITION=改善体成分｜MAINTAIN=维持｜GLUCOSE_CONTROL=控糖｜BP_CONTROL=控压 | 首诊应收 | 明确主目标类型（减重/体成分/控糖/控压等） |
| 14 | 希望体重到多少？ | `goal.targetWeightKg` | NUMBER | 单位 kg，范围 25–300 | 首诊应收 | 量化减重目标并校验目标 BMI 安全；方案影响：按当前身高计算，目标体重对应 BMI < 18.5，请先补身高或上调目标体重 |
| 15 | 希望多久达到体重目标？ | `goal.targetDate` | ENUM | M1=1 个月｜M3=3 个月｜M6=6 个月｜M12=约 1 年｜FLEXIBLE=不着急，先稳住 | 首诊应收 | 评估目标节奏，避免过快减重 |
| 16 | 腰围目标（健管师选填，可不问客户） | `goal.targetWaistCm` | NUMBER | 单位 cm，范围 40–200 | 仅健管师 | 腰围目标，关注中心性肥胖 |
| 17 | 体脂率目标（健管师选填） | `goal.targetBodyFat` | NUMBER | 单位 %，范围 3–70 | 仅健管师 | 体成分改善目标（健管师） |
| 18 | 糖化血红蛋白目标（%）（健管师按指南选填） | `goal.targetHba1c` | NUMBER | 单位 %，范围 4–15 | 仅健管师 | 糖尿病糖化目标 |
| 19 | 空腹血糖目标（mmol/L）（健管师选填） | `goal.targetFpg` | NUMBER | 单位 mmol/L，范围 3–20 | 仅健管师 | 糖尿病空腹血糖目标 |
| 20 | 收缩压目标（高压）（健管师选填） | `goal.targetBpSys` | NUMBER | 单位 mmHg，范围 80–200 | 仅健管师 | 高血压收缩压目标 |
| 21 | 舒张压目标（低压）（健管师选填） | `goal.targetBpDia` | NUMBER | 单位 mmHg，范围 40–130 | 仅健管师 | 高血压舒张压目标 |
| 22 | 每天大概能拿出多少时间活动或运动？ | `goal.exerciseMinutesPerDay` | NUMBER | 单位 分钟，范围 0–240 | 首诊应收 | 可执行运动时间，定运动处方 |
| 23 | 饮食上能做多大调整？ | `goal.dietEffort` | ENUM | COOK=愿意自己做｜ADJUST_ORDER=只能调整点餐｜MINIMAL=尽量少改变 | 首诊应收 | 饮食改变意愿，定饮食干预力度 |
| 24 | 希望怎么跟进？ | `goal.followupChannel` | MULTI | WECHAT=微信｜PHONE=电话｜ONSITE=到店 | 首诊应收 | 约定随访触达方式 |
| 25 | 希望多久沟通一次？ | `goal.followupFrequency` | ENUM | DAILY=每天｜WEEKLY_2_3=每周 2–3 次｜WEEKLY_1=每周 1 次 | 首诊应收 | 约定随访频次 |
| 26 | 执行健康计划时，您觉得最大的阻力是什么？（可多选） | `goal.constraints` | MULTI | SOCIAL=应酬多，推不掉｜TAKEOUT=经常要点外卖｜HUNGRY=总是觉得饿｜FAMILY=家人不太支持｜NO_TIME=没时间运动｜NONE=暂时没什么特别的阻力（与其他项互斥） | 首诊应收 | 把不可执行的点写进方案：应酬少饮酒低盐点餐、外卖给能点到的替换、容易饿则不把热量压太低 |

## 体测（BODY_MEASURE）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 27 | 身高 | `metric.HEIGHT` | NUMBER | 单位 cm，范围 100–230 | 必填 | 算 BMI、理想体重与热量需求 |
| 28 | 体重 | `metric.WEIGHT` | NUMBER | 单位 kg，范围 25–300 | 必填 | 体重基线，对比与目标设定；方案影响：偏瘦：不设减重目标，优先营养与转介评估 |
| 29 | 腰围（肚脐上方一横指水平绕一圈） | `metric.WAIST` | NUMBER | 单位 cm，范围 40–200 | 首诊应收 | 中心性肥胖与代谢风险初筛 |
| 30 | 臀围（臀部最突出处水平一圈） | `metric.HIP` | NUMBER | 单位 cm，范围 50–200 | 可后补 | 算腰臀比，评估体脂分布 |
| 31 | 体脂率（体脂秤或体成分仪） | `metric.BODY_FAT` | NUMBER | 单位 %，范围 3–70 | 可后补 | 体成分基线，区分减重与减脂 |
| 32 | 血压：高压（收缩压） | `metric.BLOOD_PRESSURE_SYS` | NUMBER | 单位 mmHg，范围 60–260 | 首诊应收 | 血压基线与运动安全；方案影响：血压很高，建议先就医，暂缓中高强度运动 |
| 33 | 血压：低压（舒张压） | `metric.BLOOD_PRESSURE_DIA` | NUMBER | 单位 mmHg，范围 30–160 | 首诊应收 | 血压基线与运动安全；方案影响：同上 |
| 34 | 静息心率 | `metric.HEART_RATE` | NUMBER | 单位 次/分，范围 30–200 | 可后补 | 静息心率，运动安全参考；方案影响：运动前建议先就医评估 |
| 35 | 这些数据怎么来的？ | `measure.method` | ENUM | ONSITE=现场测量｜DEVICE=家用设备｜SELF_REPORT=客户自述 | 首诊应收 | 标注测量来源可信度 |

## 病史与用药（MEDICAL_HISTORY）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 36 | 医生说过您有哪些疾病？ | `basic.presentIllness` | MULTI | 字典 presentIllness（运行时从 sys_dict 加载） | 首诊应收 | 已知疾病，方案禁忌、监测与转诊；方案影响：确认在用药与监测习惯；可提示一并纳入对应慢病管理；干预前需医生同意；留意情绪与进食、依从性 |
| 37 | 其他疾病 | `basic.presentIllnessOther` | TEXT | 自由文本 | 可后补 | 补充未列疾病 |
| 38 | 目前在吃哪类药？ | `basic.medicationFlags` | MULTI | NONE=都没有｜HYPOGLYCEMIC=降糖药或胰岛素｜ANTIHYPERTENSIVE=降压药｜STATIN=降脂药｜PSYCH=抗抑郁 / 抗精神病药｜STEROID=长期激素｜THYROID=甲状腺药｜WEIGHT_LOSS_DRUG=减重药（如司美格鲁肽）｜OTHER=其他 | 首诊应收 | 在用药类别，评估低血糖/药物影响体重等；方案影响：调整饮食或运动前防低血糖，与医生沟通；留意直立性低血压；力量训练与高强度运动需谨慎；核实药名、剂量与来源；部分药物影响食欲、体重或血糖 |
| 39 | 在用药物明细（药名 / 剂量） | `medication.current` | LIST | 列表明细 | 首诊应收 | 在用药物明细，写回用药清单 |
|  | 这些药一般和吃饭怎么配合？（可多选） | `medication.mealRelation` | MULTI | BEFORE=饭前吃｜WITH_MEAL=跟第一口饭一起吃｜AFTER=饭后吃｜BEDTIME=睡前吃｜IRREGULAR=没固定，想到才吃 | 首诊应收 | 用药时机。阿卡波糖等须随第一口饭服。仅当「目前在吃哪类药」不是「都没有」时显示。多选是因为不同药可以不同时机 |
| 40 | 有没有在用代餐、保健品或非处方调理产品？ | `basic.supplements` | MULTI | NONE=都没有｜MEAL_REPLACEMENT=代餐｜PROTEIN=蛋白粉｜DIET_TEA=减肥茶 / 非处方减肥产品｜FAT_BURNER=燃脂类｜VITAMIN=维生素 / 矿物质｜OTHER=其他 | 可后补 | 保健品/补充剂，避免与方案冲突；方案影响：了解成分来源，建议停用并咨询医生 |
| 41 | 近两年有没有做过手术或住院？ | `basic.medical.recentHospitalization` | ENUM | NONE=没有｜SURGERY=有手术｜HOSPITAL=有住院｜BOTH=都有｜UNKNOWN=不清楚 | 可后补 | 近期住院提示病情不稳定，方案需谨慎；方案影响：了解时间与恢复情况，恢复期内不安排中高强度运动 |
| 42 | 做过什么手术或因何住院？大概什么时候？ | `basic.medical.recentHospitalizationNote` | TEXT | 自由文本 | 可后补 | 住院原因补充 |
| 43 | 目前身体最不舒服的地方是？ | `basic.medical.mainDiscomfort` | TEXT | 自由文本 | 首诊应收 | 当前主诉不适，优先关注点 |
| 44 | 有没有医生说过不能运动或要限制运动？ | `basic.medical.exerciseRestriction` | MULTI | NONE=没有｜DOCTOR_LIMIT=医生明确限制｜CARDIAC=运动时胸痛 / 心悸 / 晕厥｜BP_UNCONTROLLED=血压控制不好｜RECENT_SURGERY=近期手术未恢复｜OTHER=其他 | 首诊应收 | 运动医学限制，处方避雷；方案影响：先就医评估再开始运动；优先稳压，暂缓高强度；设运动强度上限，避开禁忌 |
| 45 | 医生具体怎么说的？ | `basic.medical.exerciseRestrictionNote` | TEXT | 自由文本 | 可后补 | 运动限制细节 |
| 46 | 有没有关节问题或运动损伤？ | `basic.medical.jointIssues` | MULTI | NONE=没有｜KNEE=膝｜ANKLE=踝｜HIP=髋｜LOWER_BACK=腰｜SHOULDER=肩颈｜OTHER=其他 | 首诊应收 | 关节问题，影响运动类型选择；方案影响：以低冲击运动为主（快走、骑车、游泳），避开受伤部位 |
| 47 | 是否曾因使用激素类药物出现过血糖升高？ | `basic.medical.steroidHyperglycemia` | ENUM | YES=是｜NO=否｜UNKNOWN=不清楚 | 可后补 | 激素相关高血糖风险线索 |
| 48 | 颈后、腋下等处是否有皮肤发黑、增厚？ | `basic.medical.acanthosis` | ENUM | YES=是｜NO=否｜UNKNOWN=不清楚 | 可后补 | 黑棘皮等胰岛素抵抗体征线索 |

## 女性健康（WOMEN_HEALTH）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 49 | 目前有没有在备孕、怀孕或者哺乳？ | `basic.women.reproductiveStatus` | ENUM | NONE=都没有｜PREPARING=备孕中｜PREGNANT=怀孕中｜LACTATING=哺乳期｜UNSURE=不确定 | 首诊应收 | 备孕/孕期/哺乳禁忌与保守策略；方案影响：不设热量缺口与减重目标，只给均衡营养与适度活动；缺口与速度取保守值；建议先确认 |
| 50 | 月经情况 | `basic.women.menstrualStatus` | ENUM | REGULAR=规律｜IRREGULAR=不规律｜AMENORRHEA=停经（非绝经）｜PERIMENOPAUSE=围绝经期｜POSTMENOPAUSE=已绝经 | 可后补 | 月经/围绝经线索，内分泌与节食相关；方案影响：可能与过度节食或内分泌问题有关，建议就医明确 |
| 51 | 是否在 50 岁之前绝经？ | `basic.women.earlyMenopause` | ENUM | YES=是｜NO=否｜UNKNOWN=不清楚 | 可后补 | 早绝经相关心血管与代谢风险 |
| 52 | 怀孕期间是否得过妊娠期糖尿病？ | `basic.women.gdmHistory` | ENUM | YES=是｜NO=否｜UNKNOWN=不清楚 | 首诊应收 | 妊娠糖尿病史，远期糖尿病风险；方案影响：远期糖尿病风险升高，控糖与体重管理宜更积极监测 |

## 过敏与忌口（ALLERGY_DIET_RESTRICTION）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 53 | 有没有食物或药物过敏、不耐受？ | `allergy.status` | ENUM | NKA=没有已知过敏｜HAS=有 | 首诊应收 | 有无过敏，饮食与用药安全入口 |
| 54 | 对哪些食物过敏或不耐受？ | `allergy.food` | LIST | 列表明细 | 首诊应收 | 食物过敏/不耐受避雷 |
| 55 | 药物过敏 | `allergy.drug` | LIST | 列表明细 | 首诊应收 | 药物过敏避雷 |
| 56 | 饮食上有什么忌口？ | `basic.diet.restriction` | MULTI | NONE=没有｜VEGETARIAN=蛋奶素｜VEGAN=纯素｜HALAL=清真｜NO_SPICY=不吃辣｜NO_BEEF_LAMB=不吃牛羊肉｜NO_SEAFOOD=不吃海鲜｜OTHER=其他 | 首诊应收 | 宗教/个人忌口，保证饮食可执行 |
| 57 | 其他忌口 | `basic.diet.restrictionOther` | TEXT | 自由文本 | 可后补 | 其他忌口补充 |

## 家族史（FAMILY_HISTORY）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 58 | 父母、兄弟姐妹或子女有没有慢性病？ | `basic.familyHistoryStatus` | ENUM | none=没有｜has=有 | 首诊应收 | 有无家族慢病史入口 |
| 59 | 哪位亲属、什么病？ | `basic.familyHistoryItems` | LIST | 列表明细 | 首诊应收 | 一级亲属慢病明细，遗传与早发风险 |

## 糖尿病管理（DM_CLINICAL）
模块加载：{"field":"goal.careTracks","intersects":["DIABETES"]}

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 60 | 糖尿病是哪一型？ | `disease.diabetes.type` | ENUM | T2=2 型糖尿病｜T1=1 型糖尿病｜OTHER=其他 / 未分型｜UNKNOWN=不清楚 | 首诊应收 | 糖尿病分型，决定管理路径 |
| 61 | 大概哪一年得知患病的？（选年份） | `disease.diabetes.diagnosedYear` | YEAR |  | 首诊应收 | 病程长短，并发症与监测强度 |
| 62 | 有没有医生说过的慢性并发症？ | `disease.diabetes.chronicComplications` | MULTI | NEPHROPATHY=糖尿病肾病｜RETINOPATHY=糖尿病视网膜病变｜NEUROPATHY=周围神经病变｜DIABETIC_FOOT=糖尿病足｜NONE=都没有 | 首诊应收 | 慢性并发症，运动与转诊决策 |
| 63 | 有没有出现过低血糖？ | `disease.diabetes.hypoglycemia` | ENUM | NONE=没有｜MILD=偶尔轻微｜SEVERE=有过较重或需他人帮助｜UNKNOWN=不清楚 | 首诊应收 | 低血糖史，饮食运动安全边界；方案影响：热量缺口与运动前注意防低血糖，调整前与医生沟通 |
| 64 | 平时测血糖频次？ | `disease.diabetes.monitorFreq` | ENUM | NONE=基本不测｜OCCASIONAL=偶尔｜WEEKLY=每周几次｜DAILY=几乎每天｜UNKNOWN=不清楚 | 首诊应收 | 血糖监测习惯，方案可执行性 |
| 65 | 最近一次糖化血红蛋白？ | `disease.diabetes.latestHba1c` | NUMBER | 单位 %，范围 3–20 | 首诊应收 | 近期控糖水平基线；方案影响：控糖优先；若同时减重，节奏宜稳，避免激进缺口 |
| 66 | 最近空腹血糖？ | `disease.diabetes.latestFpg` | NUMBER | 单位 mmol/L，范围 1–40 | 首诊应收 | 近期空腹血糖基线 |
| 67 | 最近餐后 2 小时血糖？ | `disease.diabetes.latestPpg` | NUMBER | 单位 mmol/L，范围 1–40 | 首诊应收 | 近期餐后血糖基线 |
| 68 | 降糖药 / 胰岛素吃得规律吗？ | `disease.diabetes.medAdherence` | ENUM | ALWAYS=基本按时用药｜MOSTLY=大多按时偶有漏服｜IRREGULAR=经常漏服或自行加减｜STOPPED=已自行停药｜UNKNOWN=不清楚 | 首诊应收 | 降糖药依从，干预重点；方案影响：先提醒按时用药，方案不强推大幅饮食变动 |
| 69 | 最近一次眼底检查大概什么时候？ | `disease.diabetes.eyeCheck` | ENUM | WITHIN_1Y=近 1 年内查过｜1_TO_2Y=1–2 年前｜OVER_2Y=超过 2 年 / 没查过｜UNKNOWN=不清楚 | 可后补 | 眼底检查时效，并发症筛查提醒 |

## 高血压管理（HTN_CLINICAL）
模块加载：{"field":"goal.careTracks","intersects":["HYPERTENSION"]}

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 70 | 大概哪一年得知患高血压？（选年份） | `disease.hypertension.diagnosedYear` | YEAR |  | 首诊应收 | 高血压病程 |
| 71 | 在家有测血压吗？ | `disease.hypertension.homeMonitor` | ENUM | YES=有在家测｜NO=基本不测｜OCCASIONAL=偶尔测｜UNKNOWN=不清楚 | 首诊应收 | 家庭血压监测习惯 |
| 72 | 近期收缩压大概多少？ | `disease.hypertension.latestSystolic` | NUMBER | 单位 mmHg，范围 60–280 | 首诊应收 | 近期收缩压；方案影响：血压偏高时力量训练与高强度运动需谨慎，优先稳压 |
| 73 | 近期舒张压大概多少？ | `disease.hypertension.latestDiastolic` | NUMBER | 单位 mmHg，范围 30–180 | 首诊应收 | 近期舒张压 |
| 74 | 有没有晨起血压明显偏高？ | `disease.hypertension.morningPeak` | ENUM | YES=有｜NO=没有｜UNKNOWN=不清楚 | 首诊应收 | 晨峰血压线索 |
| 75 | 起立后有没有头晕或眼前发黑？ | `disease.hypertension.orthostatic` | ENUM | YES=有｜NO=没有｜UNSURE=不清楚 | 首诊应收 | 体位性低血压，运动/用药安全；方案影响：注意直立性低血压；起身宜慢，运动前后勿突然起立 |
| 76 | 降压药吃得规律吗？ | `disease.hypertension.medAdherence` | ENUM | ALWAYS=基本按时用药｜MOSTLY=大多按时偶有漏服｜IRREGULAR=经常漏服或自行加减｜STOPPED=已自行停药｜UNKNOWN=不清楚 | 首诊应收 | 降压药依从；方案影响：先提醒按时用药，再谈限盐与运动 |
| 77 | 以前测到过的最高收缩压？ | `disease.hypertension.highestSystolic` | NUMBER | 单位 mmHg，范围 80–280 | 仅健管师 | 历史最高收缩压，面诊由健管师补，不让客户自己回忆 |
| 78 | 以前测到过的最高舒张压？ | `disease.hypertension.highestDiastolic` | NUMBER | 单位 mmHg，范围 40–160 | 仅健管师 | 历史最高舒张压，面诊由健管师补 |

## 减重史（WL_WEIGHT_HISTORY）
模块加载：{"field":"goal.careTracks","intersects":["WEIGHT_LOSS"]}

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 79 | 以前减过重吗？ | `basic.weightHistory.triedBefore` | ENUM | NEVER=没有｜ONCE=1 次｜MULTIPLE=多次 | 首诊应收 | 是否有减重经历，匹配干预策略；方案影响：体重反复的客户节奏更保守，重点防反弹 |
| 80 | 以前用过哪些方法？ | `basic.weightHistory.methods` | MULTI | DIET_CONTROL=控制饮食｜LOW_CARB=低碳 / 生酮｜MEAL_REPLACEMENT=代餐｜FASTING=断食 / 轻断食｜EXERCISE=运动｜DRUG=药物｜SURGERY=减重手术｜OTHER=其他 | 首诊应收 | 既往减重方法，避免重复失败路径；方案影响：术后营养需求特殊，需了解术式与时间 |
| 81 | 最多一次减了多少公斤？ | `basic.weightHistory.maxLossKg` | NUMBER | 单位 kg，范围 0–100 | 可后补 | 曾减幅度，评估可达成性 |
| 82 | 之前没坚持下来或反弹的主要原因？ | `basic.weightHistory.failReasons` | MULTI | REBOUND=反弹了｜HUNGRY=太饿坚持不了｜NO_TIME=没时间｜NO_RESULT=没效果｜SOCIAL=应酬多｜INJURY=受伤｜OTHER=其他 | 首诊应收 | 失败原因，方案需针对性破解 |
| 83 | 成年后最重大概多少公斤？ | `basic.weightHistory.maxWeightKg` | NUMBER | 单位 kg，范围 25–300 | 仅健管师 | 历史最高体重，面诊由健管师补，避免客户回忆偏差 |
| 84 | 成年后最轻大概多少公斤？ | `basic.weightHistory.minAdultWeightKg` | NUMBER | 单位 kg，范围 25–300 | 仅健管师 | 成年最低体重，面诊由健管师补 |
| 85 | 最近 3–6 个月体重有变化吗？ | `basic.weightHistory.recentTrend` | ENUM | STABLE=基本稳定｜UP=涨了｜DOWN_INTENDED=有意减下来了｜DOWN_UNINTENDED=没刻意减却瘦了 | 首诊应收 | 近期体重趋势 |
| 86 | 大概变了多少公斤？ | `basic.weightHistory.recentChangeKg` | NUMBER | 单位 kg，范围 0–50 | 可后补 | 近期体重变化幅度 |

## 减重风险筛查（WL_RISK_SCREEN）
模块加载：{"field":"goal.careTracks","intersects":["WEIGHT_LOSS"]}

身材满意（`basic.psych.bodySatisfaction`）、身材焦虑（`basic.psych.bodyAnxiety`）已从首诊删除。

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 87 | 以前有没有出现过暴食、催吐，或者长时间刻意不吃饭的情况？ | `basic.psych.eatingDisorderHistory` | ENUM | NO=没有｜YES=有过｜UNSURE=说不清｜DECLINE=不想回答 | 首诊应收 | 进食障碍史筛查，必要时转诊；方案影响：不设热量缺口；称重改为每周；方案中提示转介 |
| 88 | 有没有因为这个找过医生或心理咨询？ | `basic.psych.soughtHelp` | ENUM | YES=找过｜NO=没有 | 可后补 | 是否寻求过专业帮助 |
| 91 | 希望多快看到减重效果？ | `basic.weightHistory.expectedPace` | ENUM | STEADY=稳一点就好｜MODERATE=适中｜ASAP=越快越好 | 首诊应收 | 期望速度，纠正过快减重预期；方案影响：预期沟通：稳健节奏更可持续，避免过快减重 |

## 饮食行为（DIET_BEHAVIOR）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 92 | 一日三餐规律吗？ | `basic.diet.mealRegularity` | ENUM | REGULAR=基本规律｜SOMETIMES=偶尔不规律｜IRREGULAR=经常不规律 | 首诊应收 | 餐次规律性，设计饮食节奏 |
|  | 晚餐通常几点吃？ | `basic.diet.dinnerTime` | ENUM | BEFORE_18=下午 6 点前｜H18_19=下午 6–7 点｜H19_20=晚上 7–8 点｜H20_21=晚上 8–9 点｜AFTER_21=晚上 9 点以后｜IRREGULAR=不固定 | 首诊应收 | 排晚餐与运动间隔。晚上 9 点以后吃晚饭时，不安排刚吃完就运动 |
| 93 | 早餐吃吗？ | `basic.diet.breakfast` | ENUM | DAILY=每天吃｜SOMETIMES=有时吃｜RARELY=基本不吃 | 可后补 | 早餐习惯 |
| 94 | 点外卖或在外面吃的频率？ | `basic.diet.takeoutFrequency` | ENUM | RARELY=很少｜WEEKLY_1_3=每周 1–3 次｜WEEKLY_4_7=每周 4–7 次｜DAILY_MULTI=每天不止一次 | 首诊应收 | 外卖/外食频率，方案可执行性 |
| 95 | 吃零食或夜宵的频率？ | `basic.diet.snackNight` | ENUM | RARELY=很少｜WEEKLY_1_3=每周 1–3 次｜MOST_DAYS=大多数天 | 首诊应收 | 夜宵习惯，控糖控重关键点 |
| 96 | 奶茶、可乐、果汁这类含糖饮料喝多少？ | `basic.diet.sugaryDrinks` | ENUM | NONE=基本不喝｜WEEKLY_1_3=每周 1–3 杯｜DAILY_1=每天 1 杯｜DAILY_2PLUS=每天 2 杯以上 | 首诊应收 | 含糖饮料摄入；方案影响：控糖 / 减重方案优先减含糖饮料 |
| 97 | 您的日常饮食更接近哪种？（可多选） | `basic.diet.taste` | MULTI | SALTY=偏咸、口味重｜OILY=偏油腻，或爱吃油炸｜REFINED=主食主要是白米饭、白馒头、面条｜SWEET=常吃甜食或含糖饮料｜NONE=以上都不大符合（与其他项互斥） | 首诊应收 | C 端只问这一道。咸→限盐；油炸→少油替换；精米白面→控糖时逐步粗细搭配；甜食→优先减 |
| 98 | 主食一般吃什么？（面诊可再细问） | `basic.diet.staple` | ENUM | REFINED=以白米白面为主｜MIXED=粗细搭配｜LOW=主食吃得很少 | 仅健管师 | 健管师面诊细问主食结构，C 端不问 |
| 99 | 蔬菜水果吃得多吗？ | `basic.diet.vegFruit` | ENUM | ENOUGH=每天都有不少｜SOME=有但不多｜RARE=很少吃 | 可后补 | 蔬果摄入水平 |
| 100 | 家里做菜常用哪些方式？（面诊可再细问） | `basic.diet.cooking` | MULTI | STEAM_BOIL=蒸煮｜STIR_FRY=炒｜DEEP_FRY=油炸｜BRAISE=红烧 / 卤 | 仅健管师 | 健管师按烹饪方式给替换（如少红烧、少油炸）。仅当已纳入减重、糖尿病或高血压时显示 |
| 101 | 吃一顿饭大概多久？ | `basic.diet.eatingSpeed` | ENUM | FAST=10 分钟以内｜NORMAL=10–20 分钟｜SLOW=20 分钟以上 | 可后补 | 进食速度，饱腹与热量控制 |
| 102 | 每天大概喝多少水？ | `basic.diet.waterMl` | NUMBER | 单位 ml，范围 0–6000 | 可后补 | 日饮水量 |
| 103 | 平时主要谁做饭？ | `basic.diet.cook` | ENUM | SELF=自己做｜FAMILY=家人做｜CANTEEN=单位食堂｜EAT_OUT=外卖 / 外食为主 | 首诊应收 | 是否自己做饭，干预可行性 |
| 104 | 是一个人住吗？ | `basic.diet.livingAlone` | ENUM | YES=是｜NO=否 | 可后补 | 独居与否，影响做饭与依从支持 |
| 105 | 以前记录过饮食吗？ | `basic.diet.trackedBefore` | ENUM | NEVER=没有｜TRIED=试过没坚持｜ONGOING=一直在记 | 可后补 | 是否记过饮食，数字工具接受度 |
| 106 | 饮食备注 | `basic.diet.note` | TEXT | 自由文本 | 可后补 | 饮食补充说明 |

## 运动行为（EXERCISE_BEHAVIOR）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 107 | 平时运动（每次约 30 分钟以上）的频率？ | `basic.exercise.frequency` | ENUM | 字典 exerciseFrequency（运行时从 sys_dict 加载） | 首诊应收 | 运动频率基线 |
| 108 | 一般做什么运动？ | `basic.exercise.types` | MULTI | WALK=快走｜RUN=跑步｜CYCLE=骑行｜SWIM=游泳｜STRENGTH=力量训练｜BALL=球类｜YOGA=瑜伽 / 普拉提｜DANCE=跳操 / 广场舞｜OTHER=其他 | 首诊应收 | 偏好运动类型 |
| 109 | 运动强度大概怎样？ | `basic.exercise.intensity` | ENUM | 字典 exerciseIntensity（运行时从 sys_dict 加载） | 可后补 | 强度耐受 |
| 110 | 每次运动多长时间？ | `basic.exercise.durationMin` | NUMBER | 单位 分钟，范围 0–300 | 可后补 | 单次时长 |
| 111 | 做过力量训练吗？ | `basic.exercise.strengthExperience` | ENUM | NONE=没练过｜BEGINNER=偶尔练｜REGULAR=规律练 | 可后补 | 力量训练经验 |
| 112 | 平时一天大概走多少步？ | `basic.exercise.dailySteps` | ENUM | LT3000=3000 步以下｜S3000_6000=3000–6000 步｜S6000_10000=6000–10000 步｜GT10000=1 万步以上｜UNKNOWN=不清楚 | 首诊应收 | 日常步数水平 |
| 113 | 一天大概坐多久？ | `basic.exercise.sedentaryHours` | NUMBER | 单位 小时，范围 0–20 | 可后补 | 久坐时长，活动处方切入点 |
| 114 | 一般什么时候有空运动？ | `basic.exercise.availableSlots` | MULTI | MORNING=早上｜NOON=中午｜EVENING=傍晚｜NIGHT=晚上｜WEEKEND=只有周末 | 首诊应收 | 可运动时段 |
| 115 | 在哪运动方便？ | `basic.exercise.venue` | MULTI | HOME=家里｜OUTDOOR=户外｜GYM=健身房｜OFFICE=单位 | 可后补 | 运动场所条件 |
| 116 | 家里有什么器械？ | `basic.exercise.equipment` | TEXT | 自由文本 | 可后补 | 可用器械 |
| 117 | 运动备注 | `basic.exercise.note` | TEXT | 自由文本 | 可后补 | 运动补充 |

## 睡眠与烟酒（SLEEP_SUBSTANCE）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 8 | 平时作息是哪种？ | `profile.workSchedule` | ENUM | DAY=规律白班｜SHIFT=倒班｜NIGHT=夜班为主｜IRREGULAR=不固定 | 首诊应收 | 按班次安排餐次、用药提醒与运动时段。选倒班或夜班后，不出现固定下班时间，改填下一题 |
|  | 平时大概几点下班？ | `basic.schedule.offWork` | ENUM | BEFORE_18=下午 6 点前｜H18_19=下午 6–7 点｜H19_20=晚上 7–8 点｜H20_21=晚上 8–9 点｜AFTER_21=晚上 9 点以后｜IRREGULAR=不固定｜NOT_WORKING=不上班 | 首诊应收 | 排运动时段。仅当作息为规律白班或不固定时显示。晚上 8 点及以后下班：不要安排晚饭前运动 |
|  | 倒班或夜班时，大概哪些时段在上班？ | `basic.schedule.offWorkNote` | TEXT | 自由文本，可写多个时段。例如：白班 8–16 点；夜班 20 点到次日 8 点 | 首诊应收 | 仅当作息为倒班或夜班时显示。方案按实际班次排运动，不写死傍晚 |
| 118 | 工作和日常活动量属于哪种？ | `basic.lifestyle.activityLevel` | ENUM | SEDENTARY=久坐为主｜LIGHT=轻体力（站立走动较多）｜MODERATE=中等体力｜HEAVY=重体力 | 首诊应收 | 整体活动水平分层 |
| 119 | 每天大概睡几个小时？ | `basic.sleep.hours` | NUMBER | 单位 小时，范围 0–16 | 首诊应收 | 睡眠时长，代谢与依从相关；方案影响：睡眠不足影响食欲、血压与控糖，方案可加入睡眠改善 |
|  | 早上起来觉得解乏吗？ | `basic.sleep.refreshed` | ENUM | YES=完全解乏｜PARTIAL=有些疲惫｜NO=极度疲劳 | 首诊应收 | 肥胖或高血压再叠加极度疲劳时，方案提示睡眠呼吸监测 |
| 120 | 一般几点睡？ | `basic.sleep.bedTime` | TIME |  | 可后补 | 入睡时间，作息评估 |
| 121 | 一般几点起？ | `basic.sleep.wakeTime` | TIME |  | 可后补 | 起床时间，作息评估 |
| 122 | 睡眠质量 | `basic.sleep.quality` | ENUM | 字典 sleepQuality（运行时从 sys_dict 加载） | 可后补 | 睡眠质量 |
| 123 | 睡眠问题 | `basic.sleep.disorder` | ENUM | 字典 sleepDisorder（运行时从 sys_dict 加载） | 可后补 | 睡眠障碍/鼾症等线索 |
| 124 | 多久熬一次夜（凌晨以后睡）？ | `basic.sleep.lateNight` | ENUM | RARELY=很少｜WEEKLY_1_3=每周 1–3 次｜MOST_DAYS=大多数天 | 可后补 | 熬夜习惯 |
| 125 | 睡眠备注 | `basic.sleep.note` | TEXT | 自由文本 | 可后补 | 睡眠补充 |
| 126 | 吸烟吗？ | `basic.lifestyle.smoking.status` | ENUM | 字典 smoking（运行时从 sys_dict 加载） | 首诊应收 | 吸烟状态，心血管风险 |
| 127 | 每天几支？ | `basic.lifestyle.smoking.cigarettesPerDay` | NUMBER | 单位 支，范围 0–100 | 可后补 | 吸烟量 |
| 128 | 吸了多少年？ | `basic.lifestyle.smoking.years` | NUMBER | 单位 年，范围 0–80 | 可后补 | 烟龄 |
| 129 | 哪年戒的？ | `basic.lifestyle.smoking.quitYear` | TEXT | 自由文本 | 可后补 | 戒烟年份 |
| 130 | 家里或单位经常有人在身边吸烟吗？ | `basic.lifestyle.smoking.passive` | ENUM | YES=是｜NO=否｜UNKNOWN=不清楚 | 可后补 | 二手烟暴露 |
| 131 | 喝酒吗？ | `basic.lifestyle.drinking.status` | ENUM | 字典 drinking（运行时从 sys_dict 加载） | 首诊应收 | 饮酒状态，热量与肝代谢 |
| 132 | 多久喝一次？ | `basic.lifestyle.drinking.frequency` | ENUM | 字典 drinkingFrequency（运行时从 sys_dict 加载） | 可后补 | 饮酒频率 |
| 133 | 常喝什么酒？ | `basic.lifestyle.drinking.type` | TEXT | 自由文本 | 可后补 | 酒类偏好 |
| 134 | 一次喝多少？ | `basic.lifestyle.drinking.amountPerDay` | TEXT | 自由文本 | 可后补 | 饮酒量 |

## 心理与情绪（PSYCH_BASE）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 135 | 现在想改变生活习惯的程度？ | `basic.psych.readiness` | ENUM | NOT_READY=还没想好｜THINKING=想改但还没开始｜READY=准备好了｜ACTING=已经在做了 | 首诊应收 | 改变准备度，匹配干预节奏；方案影响：首版方案只设 1–2 个小目标 |
| 136 | 最近压力大吗？ | `basic.psych.stressLevel` | ENUM | LOW=不大｜MEDIUM=有一些｜HIGH=长期压力很大 | 首诊应收 | 压力水平，依从与情绪进食相关 |
| 137 | 压力大的时候吃东西会变吗？ | `basic.psych.stressEating` | ENUM | NO=没变化｜EAT_MORE=吃得更多｜EAT_LESS=吃得更少 | 可后补 | 压力性进食 |
| 138 | 心情不好或无聊时会想吃东西吗？ | `basic.psych.emotionalEating` | ENUM | NEVER=很少｜SOMETIMES=有时｜OFTEN=经常 | 可后补 | 情绪性进食；方案影响：以行为替代为主，避免严格限制 |
| 139 | 家里人支持你这次健康管理吗？ | `basic.psych.familySupport` | ENUM | SUPPORTIVE=支持｜NEUTRAL=无所谓｜UNSUPPORTIVE=不太支持 | 可后补 | 家庭支持度 |
| 140 | 愿意记录饮食、体重、血压或血糖吗？ | `basic.psych.willingToRecord` | ENUM | YES=愿意｜MAYBE=可以试试｜NO=不太愿意 | 首诊应收 | 是否愿意打卡记录，工具依从预期 |

## 健管师补充（STAFF_NOTE）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 141 | 重点跟进事项 | `staff.keyFollowups` | TEXT | 自由文本 | 仅健管师 | 健管师待跟进要点留痕 |
| 142 | 转介建议 | `staff.referral` | MULTI | NONE=无｜NUTRITION_CLINIC=营养科｜ENDOCRINE=内分泌科｜CARDIOLOGY=心内科｜PSYCH=心理科｜OBSTETRICS=产科｜OTHER=其他 | 仅健管师 | 转诊建议留痕 |
| 143 | 备注 | `staff.note` | TEXT | 自由文本 | 仅健管师 | 健管师综合备注 |

## 隐私授权（CONSENT）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 144 | 已向客户说明并取得敏感健康信息处理同意 | `consent.sensitiveHealth` | ENUM | YES=已同意 | 必填 | 敏感健康信息处理授权（合规） |
| 145 | 授权方式 | `consent.method` | ENUM | VERBAL=口头｜WRITTEN=书面｜ELECTRONIC=电子 | 必填 | 授权方式留痕 |

## AI 总结（INTAKE_AI_SUMMARY）
模块加载：始终

| 序号 | 题目 | 字段 | 题型 | 答案/选项 | 级别 | 采集目的 |
|---:|---|---|---|---|---|---|
| 146 | 首诊 AI 总结 | `staff.aiSummary` | TEXT | 自由文本 | 仅健管师 | 健管师确认的 AI/人工总结 |
