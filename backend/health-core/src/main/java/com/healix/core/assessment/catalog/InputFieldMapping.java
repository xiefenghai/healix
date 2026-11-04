package com.healix.core.assessment.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旧 {@code InputField} 枚举名 → 字段目录编码（见 {@code docs/首诊问卷/InputField-迁移映射表.csv}）。
 *
 * <p>用于 M1 校验、请求 fields_json 存量转换、事实表迁移；{@code InputField} 删除后仍保留本表作历史对照。
 */
public final class InputFieldMapping {

    private static final Map<String, List<String>> CODES = new LinkedHashMap<>();

    static {
        put("GENDER", AssessmentFields.GENDER);
        put("BIRTHDAY", AssessmentFields.BIRTHDAY);
        put("HEIGHT", AssessmentFields.HEIGHT);
        put("WEIGHT", AssessmentFields.WEIGHT);
        put("WAIST", AssessmentFields.WAIST);
        put("BODY_FAT", AssessmentFields.BODY_FAT);
        put("BLOOD_PRESSURE", AssessmentFields.BLOOD_PRESSURE_SYS, AssessmentFields.BLOOD_PRESSURE_DIA);
        put("HEART_RATE", AssessmentFields.HEART_RATE);
        put("GLUCOSE", AssessmentFields.GLUCOSE);
        put("HBA1C", AssessmentFields.HBA1C);
        put("LIPIDS", AssessmentFields.TC, AssessmentFields.TG, AssessmentFields.LDL_C, AssessmentFields.HDL_C);
        put("URIC_ACID", AssessmentFields.URIC_ACID);
        put("RENAL", AssessmentFields.CR, AssessmentFields.EGFR, AssessmentFields.UACR);
        put("LIVER", AssessmentFields.ALT, AssessmentFields.AST);
        put("SMOKING", AssessmentFields.SMOKING);
        put("PASSIVE_SMOKING", AssessmentFields.PASSIVE_SMOKING);
        put("DRINKING", AssessmentFields.DRINKING_STATUS, AssessmentFields.DRINKING_FREQUENCY);
        put("EXERCISE", AssessmentFields.EXERCISE);
        put("HIGH_SALT", AssessmentFields.HIGH_SALT);
        put("CHRONIC_STRESS", AssessmentFields.CHRONIC_STRESS);
        put(
                "FAMILY_HISTORY",
                AssessmentFields.FAMILY_HISTORY,
                AssessmentFields.FAMILY_HISTORY_STATUS,
                AssessmentFields.FAMILY_HISTORY_ITEMS);
        put("PRESENT_ILLNESS", AssessmentFields.PRESENT_ILLNESS);
        put("PREDIABETES", AssessmentFields.PRESENT_ILLNESS);
        put("GDM_HISTORY", AssessmentFields.GDM_HISTORY);
        put("STEROID_DIABETES", AssessmentFields.STEROID_DIABETES);
        put("PCOS", AssessmentFields.PRESENT_ILLNESS);
        put("ACANTHOSIS", AssessmentFields.ACANTHOSIS);
        put("PSYCH_DRUGS", AssessmentFields.MEDICATION_FLAGS);
        put("ANTIHYPERTENSIVE", AssessmentFields.MEDICATION_FLAGS);
        put("STATIN", AssessmentFields.MEDICATION_FLAGS);
        put("EARLY_MENOPAUSE", AssessmentFields.EARLY_MENOPAUSE);
        put("OSA", AssessmentFields.PRESENT_ILLNESS);
        put("HTN_MAX_SBP", AssessmentFields.HTN_MAX_SBP);
        put("HTN_MAX_DBP", AssessmentFields.HTN_MAX_DBP);
        put("DM_COMPLICATIONS", AssessmentFields.DM_COMPLICATIONS);
        put("LVH", AssessmentFields.LVH);
        put("CAROTID_PLAQUE", AssessmentFields.CAROTID_PLAQUE);
        put("PWV_HIGH", AssessmentFields.PWV_HIGH);
        put("ABI_LOW", AssessmentFields.ABI_LOW);
        put("HCY_HIGH", AssessmentFields.HCY_HIGH);
        put("PERIPHERAL_ARTERY_DISEASE", AssessmentFields.PERIPHERAL_ARTERY_DISEASE);
        put("ADVANCED_RETINOPATHY", AssessmentFields.ADVANCED_RETINOPATHY);
    }

    private InputFieldMapping() {}

    private static void put(String legacy, String... codes) {
        CODES.put(legacy, List.of(codes));
    }

    public static Set<String> legacyNames() {
        return CODES.keySet();
    }

    public static List<String> codesOf(String legacyName) {
        List<String> codes = CODES.get(legacyName);
        if (codes == null) {
            throw new IllegalArgumentException("未知 InputField: " + legacyName);
        }
        return codes;
    }

    /** 代表编码（组合项取第一个）；用于请求 fields_json 单值列表。 */
    public static String primaryCode(String legacyName) {
        return codesOf(legacyName).get(0);
    }

    /** 枚举名或已是字段编码 → 字段编码列表。 */
    public static List<String> resolve(String token) {
        if (token == null || token.isBlank()) {
            return List.of();
        }
        String t = token.trim();
        if (CODES.containsKey(t)) {
            return codesOf(t);
        }
        return List.of(t);
    }

    /** 将请求 fields_json 里的旧枚举名批量换成编码（已是编码的保留）。 */
    public static List<String> convertFieldList(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String token : raw) {
            out.addAll(resolve(token));
        }
        return List.copyOf(out);
    }
}
