package com.healix.core.metadata.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * 平台级 metadata_code 注册表（Java 侧 SSOT，供查询/规则/种子数据引用）。
 * <p>
 * 运行期 schema（label、widget、optionRef）仍以 {@code sys_dict} 为准；
 * {@code sys_dict.content.metadataCode} 应与枚举一致，租户可覆盖编码（需自行承担检索兼容）。
 */
public enum MetaDataCodeEnum {

    // --- 基础档案顶层 ---
    BASIC_PRESENT_ILLNESS("basic.presentIllness"),
    BASIC_PRESENT_ILLNESS_OTHER("basic.presentIllnessOther"),
    BASIC_FAMILY_HISTORY("basic.familyHistory"),
    BASIC_PAST_HISTORY("basic.pastHistory"),
    BASIC_PAST_HISTORY_ITEMS("basic.pastHistoryItems"),
    BASIC_EARLY_CV_FAMILY_HISTORY("basic.earlyCvFamilyHistory"),
    BASIC_DIET("basic.diet"),
    BASIC_EXERCISE("basic.exercise"),
    BASIC_SLEEP("basic.sleep"),
    BASIC_LIFESTYLE("basic.lifestyle"),

    // --- 基础档案 COMPOSITE 子路径（FLAT） ---
    BASIC_DIET_APPETITE("basic.diet.appetite"),
    BASIC_DIET_HABIT("basic.diet.habit"),
    BASIC_DIET_TYPE("basic.diet.type"),
    BASIC_DIET_PREFERENCE("basic.diet.preference"),
    BASIC_DIET_NOTE("basic.diet.note"),
    BASIC_EXERCISE_FREQUENCY("basic.exercise.frequency"),
    BASIC_EXERCISE_INTENSITY("basic.exercise.intensity"),
    BASIC_EXERCISE_DURATION_MIN("basic.exercise.durationMin"),
    BASIC_EXERCISE_TYPE("basic.exercise.type"),
    BASIC_EXERCISE_NOTE("basic.exercise.note"),
    BASIC_SLEEP_QUALITY("basic.sleep.quality"),
    BASIC_SLEEP_HOURS("basic.sleep.hours"),
    BASIC_SLEEP_DISORDER("basic.sleep.disorder"),
    BASIC_SLEEP_NOTE("basic.sleep.note"),
    BASIC_LIFESTYLE_SMOKING("basic.lifestyle.smoking"),
    BASIC_LIFESTYLE_SMOKING_STATUS("basic.lifestyle.smoking.status"),
    BASIC_LIFESTYLE_SMOKING_CIGARETTES_PER_DAY("basic.lifestyle.smoking.cigarettesPerDay"),
    BASIC_LIFESTYLE_SMOKING_YEARS("basic.lifestyle.smoking.years"),
    BASIC_LIFESTYLE_SMOKING_QUIT_YEAR("basic.lifestyle.smoking.quitYear"),
    BASIC_LIFESTYLE_SMOKING_NOTE("basic.lifestyle.smoking.note"),
    BASIC_LIFESTYLE_DRINKING("basic.lifestyle.drinking"),
    BASIC_LIFESTYLE_DRINKING_STATUS("basic.lifestyle.drinking.status"),
    BASIC_LIFESTYLE_DRINKING_FREQUENCY("basic.lifestyle.drinking.frequency"),
    BASIC_LIFESTYLE_DRINKING_TYPE("basic.lifestyle.drinking.type"),
    BASIC_LIFESTYLE_DRINKING_AMOUNT("basic.lifestyle.drinking.amountPerDay"),
    BASIC_LIFESTYLE_DRINKING_NOTE("basic.lifestyle.drinking.note"),
    BASIC_LIFESTYLE_NOTE("basic.lifestyle.note"),

    // --- 病种档案 ---
    DISEASE_DIABETES_DIAGNOSIS_DATE("disease.diabetes.diagnosisDate"),
    DISEASE_DIABETES_TYPE("disease.diabetes.diabetesType"),
    DISEASE_DIABETES_SYMPTOMS("disease.diabetes.symptoms"),
    DISEASE_DIABETES_SYMPTOMS_OTHER("disease.diabetes.symptomsOther"),
    DISEASE_DIABETES_EMERGENCY_COMPLICATIONS("disease.diabetes.emergencyComplications"),
    DISEASE_DIABETES_HYPOGLYCEMIA_REACTION("disease.diabetes.hypoglycemiaReaction"),
    DISEASE_DIABETES_HYPOGLYCEMIA_COUNT("disease.diabetes.hypoglycemiaCountLastMonth"),
    DISEASE_DIABETES_HYPOGLYCEMIA_HANDLING("disease.diabetes.hypoglycemiaHandling"),
    DISEASE_DIABETES_REMARK("disease.diabetes.remark"),
    DISEASE_HYPERTENSION_DIAGNOSIS_DATE("disease.hypertension.diagnosisDate"),
    DISEASE_HYPERTENSION_TYPE("disease.hypertension.hypertensionType"),
    DISEASE_HYPERTENSION_GRADE("disease.hypertension.hypertensionGrade"),
    DISEASE_HYPERTENSION_CV_RISK("disease.hypertension.cvRiskStratification"),
    DISEASE_HYPERTENSION_HIGHEST_SYS("disease.hypertension.highestSystolic"),
    DISEASE_HYPERTENSION_HIGHEST_DIA("disease.hypertension.highestDiastolic"),
    DISEASE_HYPERTENSION_SYMPTOMS("disease.hypertension.symptoms"),
    DISEASE_HYPERTENSION_SYMPTOMS_OTHER("disease.hypertension.symptomsOther"),
    DISEASE_HYPERTENSION_EMERGENCY_COMPLICATIONS("disease.hypertension.emergencyComplications"),
    DISEASE_HYPERTENSION_EMERGENCY_COMPLICATIONS_OTHER(
            "disease.hypertension.emergencyComplicationsOther");

    public static final String BASIC_PREFIX = "basic.";
    public static final String DISEASE_PREFIX = "disease.";

    private final String code;

    MetaDataCodeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 基础档案顶层：{@code basic.{fieldCode}} */
    public static String basicField(String fieldCode) {
        return BASIC_PREFIX + fieldCode;
    }

    /** COMPOSITE 子字段：{@code basic.{composite}.{subCode}} */
    public static String basicCompositeSub(String compositeCode, String subCode) {
        return BASIC_PREFIX + compositeCode + "." + subCode;
    }

    /** 病种字段：{@code disease.{diseaseCode}.{fieldCode}} */
    public static String diseaseField(String diseaseCode, String fieldCode) {
        return DISEASE_PREFIX + diseaseCode + "." + fieldCode;
    }

    public static Optional<MetaDataCodeEnum> findByCode(String metadataCode) {
        if (metadataCode == null || metadataCode.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(v -> v.code.equals(metadataCode)).findFirst();
    }
}
