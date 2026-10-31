package com.healix.core.assessment.catalog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 评估引擎声明 / 缺项补填用的字段编码（替代已删除的 {@code InputField}）。
 *
 * <p>编码与 {@code fields/field-catalog.json} 对齐；组合项（血压、血脂、肾功能等）在
 * {@link com.healix.core.assessment.support.AssessmentContext#has(String)} 里按「组已采集」判定。
 */
public final class AssessmentFields {

    public static final String GENDER = "profile.gender";
    public static final String BIRTHDAY = "profile.birthday";
    public static final String HEIGHT = "metric.HEIGHT";
    public static final String WEIGHT = "metric.WEIGHT";
    public static final String WAIST = "metric.WAIST";
    public static final String BODY_FAT = "metric.BODY_FAT";
    public static final String BLOOD_PRESSURE_SYS = "metric.BLOOD_PRESSURE_SYS";
    public static final String BLOOD_PRESSURE_DIA = "metric.BLOOD_PRESSURE_DIA";
    public static final String HEART_RATE = "metric.HEART_RATE";
    public static final String GLUCOSE = "metric.BLOOD_GLUCOSE";
    public static final String HBA1C = "lab.HBA1C";
    public static final String TC = "lab.TC";
    public static final String TG = "lab.TG";
    public static final String LDL_C = "lab.LDL_C";
    public static final String HDL_C = "lab.HDL_C";
    public static final String URIC_ACID = "lab.UA";
    public static final String CR = "lab.CR";
    public static final String EGFR = "lab.EGFR";
    public static final String UACR = "lab.UACR";
    public static final String ALT = "lab.ALT";
    public static final String AST = "lab.AST";

    public static final String SMOKING = "basic.lifestyle.smoking.status";
    public static final String PASSIVE_SMOKING = "basic.lifestyle.smoking.passive";
    public static final String DRINKING_STATUS = "basic.lifestyle.drinking.status";
    public static final String DRINKING_FREQUENCY = "basic.lifestyle.drinking.frequency";
    /** 饮酒：引擎声明 / 缺项用 status 作代表编码。 */
    public static final String DRINKING = DRINKING_STATUS;
    public static final String EXERCISE = "basic.exercise.frequency";
    public static final String HIGH_SALT = "basic.diet.taste";
    public static final String CHRONIC_STRESS = "basic.psych.stressLevel";
    public static final String FAMILY_HISTORY_STATUS = "basic.familyHistoryStatus";
    public static final String FAMILY_HISTORY_ITEMS = "basic.familyHistoryItems";
    /** 一级亲属虚拟字段（问卷出题 / 引擎声明）。 */
    public static final String FAMILY_HISTORY = "basic.familyHistory.firstDegree";
    public static final String PRESENT_ILLNESS = "basic.presentIllness";
    public static final String GDM_HISTORY = "basic.women.gdmHistory";
    public static final String STEROID_DIABETES = "basic.medical.steroidHyperglycemia";
    public static final String ACANTHOSIS = "basic.medical.acanthosis";
    public static final String MEDICATION_FLAGS = "basic.medicationFlags";
    public static final String EARLY_MENOPAUSE = "basic.women.earlyMenopause";
    public static final String HTN_MAX_SBP = "disease.hypertension.highestSystolic";
    public static final String HTN_MAX_DBP = "disease.hypertension.highestDiastolic";
    public static final String DM_COMPLICATIONS = "disease.diabetes.chronicComplications";
    public static final String LVH = "basic.clinical.lvh";
    public static final String CAROTID_PLAQUE = "basic.clinical.carotidPlaque";
    public static final String PWV_HIGH = "basic.clinical.pwvHigh";
    public static final String ABI_LOW = "basic.clinical.abiLow";
    public static final String HCY_HIGH = "basic.clinical.hcyHigh";
    public static final String PERIPHERAL_ARTERY_DISEASE = "basic.clinical.peripheralArteryDisease";
    public static final String ADVANCED_RETINOPATHY = "basic.clinical.advancedRetinopathy";

    /** 现有疾病选项：糖尿病前期 / 多囊 / 睡眠呼吸暂停（并入 presentIllness）。 */
    public static final String ILLNESS_PREDIABETES = "PREDIABETES";
    public static final String ILLNESS_PCOS = "PCOS";
    public static final String ILLNESS_OSA = "OSA";
    public static final String MED_ANTIHYPERTENSIVE = "ANTIHYPERTENSIVE";
    public static final String MED_STATIN = "STATIN";
    public static final String MED_PSYCH = "PSYCH";

    public static final List<String> BLOOD_PRESSURE = List.of(BLOOD_PRESSURE_SYS, BLOOD_PRESSURE_DIA);
    public static final List<String> LIPIDS = List.of(TC, TG, LDL_C, HDL_C);
    public static final List<String> RENAL = List.of(CR, EGFR, UACR);
    public static final List<String> LIVER = List.of(ALT, AST);

    public enum Audience {
        PATIENT,
        STAFF,
        NONE
    }

    public record Spec(
            String code,
            String label,
            String fillHint,
            Audience audience,
            String section,
            boolean femaleOnly) {

        public boolean inQuestionnaire() {
            return audience != Audience.NONE;
        }
    }

    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    static {
        spec(GENDER, "性别", "请在基本信息补充性别", Audience.NONE, null, false);
        spec(BIRTHDAY, "出生日期", "请在基本信息补充出生日期", Audience.NONE, null, false);
        spec(HEIGHT, "身高", null, Audience.PATIENT, "body", false);
        spec(WEIGHT, "体重", null, Audience.PATIENT, "body", false);
        spec(WAIST, "腰围", null, Audience.PATIENT, "body", false);
        spec(BODY_FAT, "体脂率", null, Audience.PATIENT, "body", false);
        spec(BLOOD_PRESSURE_SYS, "血压", "请在「健康数据」记录血压，近 4 周至少测 2 次", Audience.NONE, null, false);
        spec(BLOOD_PRESSURE_DIA, "血压（舒张压）", "请在「健康数据」记录血压，近 4 周至少测 2 次", Audience.NONE, null, false);
        spec(HEART_RATE, "静息心率", "请在「健康数据」记录心率", Audience.NONE, null, false);
        spec(GLUCOSE, "血糖", "请在「健康数据」记录空腹或餐后血糖", Audience.NONE, null, false);
        spec(HBA1C, "糖化血红蛋白", "请在「检验报告」录入糖化血红蛋白", Audience.NONE, null, false);
        spec(TC, "血脂", "请在「检验报告」录入血脂四项", Audience.NONE, null, false);
        spec(TG, "甘油三酯", "请在「检验报告」录入血脂四项", Audience.NONE, null, false);
        spec(LDL_C, "低密度脂蛋白", "请在「检验报告」录入血脂四项", Audience.NONE, null, false);
        spec(HDL_C, "高密度脂蛋白", "请在「检验报告」录入血脂四项", Audience.NONE, null, false);
        spec(URIC_ACID, "尿酸", "请在「检验报告」录入尿酸", Audience.NONE, null, false);
        spec(CR, "肾功能", "请在「检验报告」录入肌酐 / eGFR / 尿白蛋白肌酐比", Audience.NONE, null, false);
        spec(EGFR, "eGFR", "请在「检验报告」录入肌酐 / eGFR / 尿白蛋白肌酐比", Audience.NONE, null, false);
        spec(UACR, "尿白蛋白肌酐比", "请在「检验报告」录入肌酐 / eGFR / 尿白蛋白肌酐比", Audience.NONE, null, false);
        spec(ALT, "肝功能", "请在「检验报告」录入转氨酶", Audience.NONE, null, false);
        spec(AST, "AST", "请在「检验报告」录入转氨酶", Audience.NONE, null, false);
        spec(SMOKING, "吸烟", null, Audience.PATIENT, "lifestyle", false);
        spec(PASSIVE_SMOKING, "被动吸烟", null, Audience.PATIENT, "lifestyle", false);
        spec(DRINKING, "饮酒", null, Audience.NONE, null, false);
        spec(DRINKING_FREQUENCY, "饮酒频率", null, Audience.PATIENT, "lifestyle", false);
        spec(EXERCISE, "运动频率", null, Audience.PATIENT, "lifestyle", false);
        spec(HIGH_SALT, "口味", null, Audience.PATIENT, "lifestyle", false);
        spec(CHRONIC_STRESS, "压力水平", null, Audience.PATIENT, "lifestyle", false);
        spec(FAMILY_HISTORY, "一级亲属家族史", null, Audience.PATIENT, "history", false);
        spec(FAMILY_HISTORY_STATUS, "家族史状态", null, Audience.NONE, null, false);
        spec(FAMILY_HISTORY_ITEMS, "家族史条目", null, Audience.NONE, null, false);
        spec(PRESENT_ILLNESS, "现有疾病", null, Audience.PATIENT, "history", false);
        spec(GDM_HISTORY, "妊娠期糖尿病史", null, Audience.PATIENT, "history", true);
        spec(STEROID_DIABETES, "类固醇糖尿病史", null, Audience.PATIENT, "history", false);
        spec(ACANTHOSIS, "黑棘皮症", null, Audience.PATIENT, "history", false);
        spec(MEDICATION_FLAGS, "在用药物类别", null, Audience.PATIENT, "history", false);
        spec(EARLY_MENOPAUSE, "早发绝经", null, Audience.PATIENT, "history", true);
        spec(HTN_MAX_SBP, "既往最高收缩压", null, Audience.PATIENT, "history", false);
        spec(HTN_MAX_DBP, "既往最高舒张压", null, Audience.PATIENT, "history", false);
        spec(DM_COMPLICATIONS, "糖尿病并发症", null, Audience.PATIENT, "history", false);
        spec(LVH, "左心室肥厚", null, Audience.STAFF, "professional", false);
        spec(CAROTID_PLAQUE, "颈动脉内中膜增厚或斑块", null, Audience.STAFF, "professional", false);
        spec(PWV_HIGH, "脉搏波传导速度增快", null, Audience.STAFF, "professional", false);
        spec(ABI_LOW, "踝臂指数降低", null, Audience.STAFF, "professional", false);
        spec(HCY_HIGH, "高同型半胱氨酸", null, Audience.STAFF, "professional", false);
        spec(PERIPHERAL_ARTERY_DISEASE, "外周血管疾病", null, Audience.STAFF, "professional", false);
        spec(ADVANCED_RETINOPATHY, "视网膜病变（出血/渗出/视乳头水肿）", null, Audience.STAFF, "professional", false);
    }

    private AssessmentFields() {}

    private static void spec(
            String code, String label, String fillHint, Audience audience, String section, boolean femaleOnly) {
        SPECS.put(code, new Spec(code, label, fillHint, audience, section, femaleOnly));
    }

    public static Optional<Spec> find(String code) {
        return Optional.ofNullable(code == null ? null : SPECS.get(code));
    }

    public static Spec require(String code) {
        return find(code).orElseThrow(() -> new IllegalArgumentException("未知评估字段: " + code));
    }

    public static Collection<Spec> all() {
        return SPECS.values();
    }

    public static boolean known(String code) {
        return SPECS.containsKey(code);
    }

    public static String labelOf(String code) {
        Spec s = SPECS.get(code);
        return s == null ? code : s.label();
    }

    public static String fillHintOf(String code) {
        Spec s = SPECS.get(code);
        return s == null ? null : s.fillHint();
    }

    public static boolean inQuestionnaire(String code) {
        Spec s = SPECS.get(code);
        return s != null && s.inQuestionnaire();
    }
}
