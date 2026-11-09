package com.healix.core.questionnaire;

import java.util.Set;

/**
 * C 端「极简」档（{@link IntakeDepth#MINIMAL} / C_FAST）可见字段白名单。
 *
 * <p>只留「基础代谢 + 安全红线 + 粗粒度生活方式」约 15–20 题，让患者 1–2 分钟填完；
 * 腰围/血压、详细病史与习惯留给 B 端面诊或 C 端「完整补充」。
 */
public final class PatientCFastFields {

    private PatientCFastFields() {}

    public static final Set<String> FIELDS = Set.of(
            // 基础信息（Magic Link / 建档已有则可跳过）
            "profile.displayName",
            "profile.gender",
            "profile.birthday",
            "profile.mobile",
            // 体测基础（腰围/血压不进极简）
            "metric.HEIGHT",
            "metric.WEIGHT",
            "measure.method",
            // 核心诉求
            "goal.primaryAppeal",
            // 高危禁忌排查
            "basic.presentIllness",
            "basic.medical.exerciseRestriction",
            "allergy.status",
            "allergy.foodTags",
            "allergy.drugTags",
            "basic.medicationFlags",
            // 基本生活方式
            "profile.workSchedule",
            "basic.lifestyle.activityLevel",
            "basic.diet.mealRegularity",
            "basic.diet.takeoutFrequency");

    public static boolean includes(String field) {
        return field != null && FIELDS.contains(field);
    }
}
