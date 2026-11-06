package com.healix.core.questionnaire;

import java.util.Set;

/**
 * C 端「标准」档（{@link IntakeDepth#QUICK}）可见字段白名单。
 *
 * <p>在 REQUIRED + FIRST_VISIT 基础上再收一刀，约 35–40 题量级（专线 showIf 命中时略增），
 * 把用药明细 / 家族史明细 / 大量 LATER 与次要 FIRST_VISIT 留给 B 端补录。
 * 下班时间、晚餐时间、吃药和吃饭的配合、执行阻力、早上是否解乏保留在标准档，供方案落地。
 */
public final class PatientCQuickFields {

    private PatientCQuickFields() {}

    public static final Set<String> FIELDS = Set.of(
            // 诉求与目标
            "goal.primaryAppeal",
            "goal.appealText",
            "goal.mode",
            "goal.targetWeightKg",
            "goal.targetDate",
            "goal.exerciseMinutesPerDay",
            "goal.dietEffort",
            "goal.followupChannel",
            "goal.followupFrequency",
            "goal.constraints",
            // 基础（未预填时）
            "profile.displayName",
            "profile.gender",
            "profile.birthday",
            "profile.mobile",
            "profile.occupation",
            "profile.workSchedule",
            // 体测
            "metric.HEIGHT",
            "metric.WEIGHT",
            "metric.WAIST",
            "metric.BLOOD_PRESSURE_SYS",
            "metric.BLOOD_PRESSURE_DIA",
            "measure.method",
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
            "basic.diet.takeoutFrequency",
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
            "basic.sleep.quality",
            "basic.psych.stressLevel",
            "basic.psych.stressEating",
            "basic.psych.readiness");

    public static boolean includes(String field) {
        return field != null && FIELDS.contains(field);
    }
}
