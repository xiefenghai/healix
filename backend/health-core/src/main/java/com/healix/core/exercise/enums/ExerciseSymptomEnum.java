package com.healix.core.exercise.enums;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 运动后不适症状。 */
public enum ExerciseSymptomEnum {
    DIZZY("头晕", false),
    PALPITATION("心慌", false),
    JOINT_PAIN("关节疼痛", false),
    HYPO("低血糖症状（出汗、手抖）", false),
    CHEST_PAIN("胸痛胸闷", true),
    SYNCOPE("晕厥或快晕倒", true),
    SEVERE_DYSPNEA("严重气短", true);

    private final String label;
    /** 对所有人都是 RED；HYPO 对糖尿病患者另行升级 */
    private final boolean alwaysRed;

    ExerciseSymptomEnum(String label, boolean alwaysRed) {
        this.label = label;
        this.alwaysRed = alwaysRed;
    }

    public String label() {
        return label;
    }

    public boolean alwaysRed() {
        return alwaysRed;
    }

    /** 去重、忽略未知值，保持枚举顺序。 */
    public static List<ExerciseSymptomEnum> parseAll(List<String> values) {
        Set<ExerciseSymptomEnum> out = new LinkedHashSet<>();
        if (values != null) {
            for (String v : values) {
                if (v == null || v.isBlank()) {
                    continue;
                }
                try {
                    out.add(valueOf(v.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // 前端旧版本/未知值忽略
                }
            }
        }
        return out.stream().sorted().toList();
    }
}
