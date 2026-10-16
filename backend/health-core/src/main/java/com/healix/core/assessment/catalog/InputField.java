package com.healix.core.assessment.catalog;

import java.math.BigDecimal;
import java.util.List;

/**
 * 评估输入字段注册表：引擎声明用到哪些字段，问卷按「适用引擎字段 − 已有值」动态出题。
 *
 * <p>{@link Audience#NONE} 的字段不进问卷，由 {@link #fillHint} 引导到对应录入入口。
 */
public enum InputField {
    GENDER("性别", ValueType.ENUM, Audience.NONE, WriteBack.NONE, null, "请在基本信息补充性别"),
    BIRTHDAY("出生日期", ValueType.ENUM, Audience.NONE, WriteBack.NONE, null, "请在基本信息补充出生日期"),
    HEIGHT("身高", ValueType.NUMBER, Audience.PATIENT, WriteBack.METRIC, Question.number("您的身高", "cm", "100", "230"), null),
    WEIGHT("体重", ValueType.NUMBER, Audience.PATIENT, WriteBack.METRIC, Question.number("您的体重", "kg", "25", "300"), null),
    WAIST("腰围", ValueType.NUMBER, Audience.PATIENT, WriteBack.METRIC,
            Question.number("您的腰围（肚脐上方一横指水平绕一圈）", "cm", "40", "200"), null),
    BODY_FAT("体脂率", ValueType.NUMBER, Audience.PATIENT, WriteBack.FACT,
            Question.number("体脂率（体脂秤测量，可不填）", "%", "3", "70"), null),
    BLOOD_PRESSURE("血压", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null,
            "请在「健康数据」记录血压，近 4 周至少测 2 次"),
    HEART_RATE("静息心率", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null, "请在「健康数据」记录心率"),
    GLUCOSE("血糖", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null, "请在「健康数据」记录空腹或餐后血糖"),
    HBA1C("糖化血红蛋白", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null, "请在「检验报告」录入糖化血红蛋白"),
    LIPIDS("血脂", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null, "请在「检验报告」录入血脂四项"),
    URIC_ACID("尿酸", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null, "请在「检验报告」录入尿酸"),
    RENAL("肾功能", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null, "请在「检验报告」录入肌酐 / eGFR / 尿白蛋白肌酐比"),
    LIVER("肝功能", ValueType.NUMBER, Audience.NONE, WriteBack.NONE, null, "请在「检验报告」录入转氨酶"),

    SMOKING("吸烟", ValueType.ENUM, Audience.PATIENT, WriteBack.ARCHIVE,
            Question.choice("您目前吸烟吗？", List.of(
                    opt("NEVER", "从不吸烟"), opt("CURRENT", "目前吸烟"), opt("OCCASIONAL", "偶尔吸烟"), opt("FORMER", "已戒烟"))),
            null),
    PASSIVE_SMOKING("被动吸烟", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("家中或工作场所是否经常有人在您身边吸烟？"), null),
    DRINKING("饮酒", ValueType.ENUM, Audience.PATIENT, WriteBack.ARCHIVE,
            Question.choice("您喝酒的频率？", List.of(
                    opt("NEVER", "不喝酒"), opt("MONTHLY", "偶尔（每月几次）"), opt("WEEKLY_1_2", "每周 1–2 次"),
                    opt("WEEKLY_3", "每周 3 次及以上"), opt("DAILY", "每天"))),
            null),
    EXERCISE("运动频率", ValueType.ENUM, Audience.PATIENT, WriteBack.ARCHIVE,
            Question.choice("您平时运动（快走、跑步、骑车等，每次 30 分钟以上）的频率？", List.of(
                    opt("NONE", "基本不运动"), opt("WEEKLY_1", "每周 1–2 次"), opt("WEEKLY_3", "每周 3 次及以上"), opt("DAILY", "每天"))),
            null),
    HIGH_SALT("高盐饮食", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("您口味偏咸，或经常吃咸菜、腌制食品吗？"), null),
    CHRONIC_STRESS("长期精神紧张", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("您是否长期感到精神紧张、压力很大？"), null),

    FAMILY_HISTORY("一级亲属家族史", ValueType.MULTI, Audience.PATIENT, WriteBack.ARCHIVE,
            Question.multi("您的父母、兄弟姐妹或子女中，有人患有以下疾病吗？", List.of(
                    opt("FH_DIABETES", "糖尿病"), opt("FH_HYPERTENSION", "高血压"), opt("FH_CHD", "冠心病"),
                    opt("FH_STROKE", "脑卒中"), opt("FH_EARLY_MI", "早发心梗（男 55 岁 / 女 65 岁前发病）"),
                    opt(Question.NONE, "都没有"), opt(Question.UNKNOWN, "不清楚"))),
            null),
    PRESENT_ILLNESS("现有疾病", ValueType.MULTI, Audience.PATIENT, WriteBack.ARCHIVE,
            Question.multi("您是否被医生诊断过以下疾病？", List.of(
                    opt("CHD", "冠心病"), opt("STROKE", "脑卒中"), opt("HEART_FAILURE", "心力衰竭"),
                    opt("ATRIAL_FIBRILLATION", "房颤"), opt("CKD", "慢性肾病"), opt("HYPERLIPIDEMIA", "高脂血症"),
                    opt("NAFLD", "脂肪肝"), opt("OSTEOARTHRITIS", "骨关节炎"), opt(Question.NONE, "都没有"))),
            null),
    PREDIABETES("糖尿病前期史", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("医生是否说过您血糖偏高、糖尿病前期（空腹血糖受损或糖耐量异常）？"), null),
    GDM_HISTORY("妊娠期糖尿病史", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("怀孕期间是否得过妊娠期糖尿病？").forFemale(), null),
    STEROID_DIABETES("类固醇糖尿病史", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("是否曾因使用激素类药物出现过血糖升高？"), null),
    PCOS("多囊卵巢综合征", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("是否被诊断过多囊卵巢综合征？").forFemale(), null),
    ACANTHOSIS("黑棘皮症", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("颈后、腋下等处是否有皮肤发黑、增厚（黑棘皮）？"), null),
    PSYCH_DRUGS("长期精神类用药", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("是否长期服用抗精神病药或抗抑郁药？"), null),
    ANTIHYPERTENSIVE("降压治疗", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("目前是否在服用降压药？"), null),
    STATIN("调脂治疗", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("目前是否在服用他汀等降脂药？"), null),
    EARLY_MENOPAUSE("早发绝经", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("是否在 50 岁之前绝经？").forFemale(), null),
    OSA("睡眠呼吸暂停", ValueType.BOOL, Audience.PATIENT, WriteBack.FACT,
            Question.yesNo("是否被诊断过睡眠呼吸暂停（打鼾且夜间憋醒）？"), null),

    HTN_MAX_SBP("既往最高收缩压", ValueType.NUMBER, Audience.PATIENT, WriteBack.DISEASE_ARCHIVE,
            Question.number("未服降压药时测到过的最高血压：高压（收缩压）", "mmHg", "80", "280"), null),
    HTN_MAX_DBP("既往最高舒张压", ValueType.NUMBER, Audience.PATIENT, WriteBack.DISEASE_ARCHIVE,
            Question.number("未服降压药时测到过的最高血压：低压（舒张压）", "mmHg", "40", "160"), null),
    DM_COMPLICATIONS("糖尿病并发症", ValueType.MULTI, Audience.PATIENT, WriteBack.FACT,
            Question.multi("是否被诊断过以下糖尿病并发症？", List.of(
                    opt("NEPHROPATHY", "糖尿病肾病"), opt("RETINOPATHY", "糖尿病视网膜病变"),
                    opt("NEUROPATHY", "周围神经病变"), opt("DIABETIC_FOOT", "糖尿病足"), opt(Question.NONE, "都没有"))),
            null),

    LVH("左心室肥厚", ValueType.BOOL, Audience.STAFF, WriteBack.FACT,
            Question.yesNo("心电图或心脏超声提示左心室肥厚"), null),
    CAROTID_PLAQUE("颈动脉内中膜增厚或斑块", ValueType.BOOL, Audience.STAFF, WriteBack.FACT,
            Question.yesNo("颈动脉超声：内中膜厚度 ≥0.9 mm 或有斑块"), null),
    PWV_HIGH("脉搏波传导速度增快", ValueType.BOOL, Audience.STAFF, WriteBack.FACT,
            Question.yesNo("颈-股动脉脉搏波传导速度 ≥12 m/s"), null),
    ABI_LOW("踝臂指数降低", ValueType.BOOL, Audience.STAFF, WriteBack.FACT,
            Question.yesNo("踝臂指数 <0.9"), null),
    HCY_HIGH("高同型半胱氨酸", ValueType.BOOL, Audience.STAFF, WriteBack.FACT,
            Question.yesNo("血同型半胱氨酸 ≥15 μmol/L"), null),
    PERIPHERAL_ARTERY_DISEASE("外周血管疾病", ValueType.BOOL, Audience.STAFF, WriteBack.FACT,
            Question.yesNo("已诊断外周动脉疾病"), null),
    ADVANCED_RETINOPATHY("视网膜病变（出血/渗出/视乳头水肿）", ValueType.BOOL, Audience.STAFF, WriteBack.FACT,
            Question.yesNo("眼底检查见出血、渗出或视乳头水肿"), null);

    private final String label;
    private final ValueType type;
    private final Audience audience;
    private final WriteBack writeBack;
    private final Question question;
    private final String fillHint;

    InputField(String label, ValueType type, Audience audience, WriteBack writeBack, Question question, String fillHint) {
        this.label = label;
        this.type = type;
        this.audience = audience;
        this.writeBack = writeBack;
        this.question = question;
        this.fillHint = fillHint;
    }

    public String label() {
        return label;
    }

    public ValueType type() {
        return type;
    }

    public Audience audience() {
        return audience;
    }

    public WriteBack writeBack() {
        return writeBack;
    }

    public Question question() {
        return question;
    }

    public String fillHint() {
        return fillHint;
    }

    public boolean inQuestionnaire() {
        return audience != Audience.NONE && question != null;
    }

    public static InputField parse(String code) {
        try {
            return code == null ? null : valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Option opt(String code, String label) {
        return new Option(code, label);
    }

    public enum ValueType {
        NUMBER,
        BOOL,
        ENUM,
        MULTI
    }

    /** PATIENT：患者可答（B 端也可填）；STAFF：专业项仅 B 端；NONE：不进问卷。 */
    public enum Audience {
        PATIENT,
        STAFF,
        NONE
    }

    public enum WriteBack {
        METRIC,
        ARCHIVE,
        DISEASE_ARCHIVE,
        FACT,
        NONE
    }

    public record Option(String code, String label) {}

    public record Question(
            String text, String unit, BigDecimal min, BigDecimal max, List<Option> options, boolean femaleOnly) {

        public static final String YES = "YES";
        public static final String NO = "NO";
        public static final String UNKNOWN = "UNKNOWN";
        public static final String NONE = "NONE";

        static Question number(String text, String unit, String min, String max) {
            return new Question(text, unit, new BigDecimal(min), new BigDecimal(max), List.of(), false);
        }

        static Question choice(String text, List<Option> options) {
            return new Question(text, null, null, null, options, false);
        }

        static Question multi(String text, List<Option> options) {
            return new Question(text, null, null, null, options, false);
        }

        static Question yesNo(String text) {
            return new Question(
                    text, null, null, null, List.of(new Option(YES, "是"), new Option(NO, "否"), new Option(UNKNOWN, "不知道")), false);
        }

        Question forFemale() {
            return new Question(text, unit, min, max, options, true);
        }
    }
}
