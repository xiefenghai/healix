package com.healix.core.questionnaire;

import java.util.Set;

/**
 * C 端「标准」档（{@link IntakeDepth#QUICK}）可见字段白名单。
 *
 * <p>对齐 {@code WL_INTAKE} v4 瘦身：去掉随访运营题、冗余生活方式与极低频体征；
 * 保留方向/体测/红线/阻力/作息饮食关键枚举。v5 起答题顺序为：基础 → 体测 → 诉求。
 */
public final class PatientCQuickFields {

    private PatientCQuickFields() {}

    public static final Set<String> FIELDS = Set.of(
            // 基础（未预填时）
            "profile.displayName",
            "profile.gender",
            "profile.birthday",
            "profile.mobile",
            "profile.workSchedule",
            // 体测
            "metric.HEIGHT",
            "metric.WEIGHT",
            "metric.WAIST",
            "metric.BLOOD_PRESSURE_SYS",
            "metric.BLOOD_PRESSURE_DIA",
            // 诉求与目标（排在体测之后）
            "goal.primaryAppeal",
            "goal.appealText",
            "goal.mode",
            "goal.targetWeightKg",
            "goal.targetDate",
            "goal.constraints",
            // 病史（不含用药明细 LIST）
            "basic.presentIllness",
            "basic.medicationFlags",
            "medication.mealRelation",
            "basic.medical.mainDiscomfort",
            "basic.medical.exerciseRestriction",
            "basic.medical.jointIssues",
            // 女性高危
            "basic.women.reproductiveStatus",
            "basic.women.gdmHistory",
            // 过敏忌口（Tag；明细 LIST 留给 B）
            "allergy.status",
            "allergy.foodTags",
            "allergy.drugTags",
            "basic.diet.restriction",
            // 家族史入口（明细 LIST 留给 B）
            "basic.familyHistoryStatus",
            // 糖尿病精简
            "disease.diabetes.type",
            "disease.diabetes.hypoglycemia",
            "disease.diabetes.latestHba1c",
            "disease.diabetes.monitorFreq",
            // 高血压精简
            "disease.hypertension.homeMonitor",
            "disease.hypertension.latestSystolic",
            "disease.hypertension.latestDiastolic",
            "disease.hypertension.orthostatic",
            // 减重精简
            "basic.weightHistory.triedBefore",
            "basic.weightHistory.failReasons",
            "basic.weightHistory.expectedPace",
            "basic.psych.eatingDisorderHistory",
            // 饮食 / 运动 / 睡眠 / 心理抓手
            "basic.diet.mealRegularity",
            "basic.diet.snackNight",
            "basic.diet.sugaryDrinks",
            "basic.diet.taste",
            "basic.diet.dinnerTime",
            "basic.diet.cook",
            "basic.exercise.frequency",
            "basic.exercise.types",
            "basic.schedule.offWork",
            "basic.schedule.offWorkNote",
            "basic.exercise.availableSlots",
            "basic.lifestyle.activityLevel",
            "basic.sleep.hours",
            "basic.sleep.refreshed",
            "basic.sleep.disorder",
            "basic.psych.stressLevel",
            "basic.psych.emotionalEating",
            "basic.psych.readiness",
            "basic.psych.willingToRecord");

    public static boolean includes(String field) {
        return field != null && FIELDS.contains(field);
    }
}
