package com.healix.core.field;

/** 字段值存在哪：由编码前缀决定，不单独配置。 */
public enum FieldStore {
    /** people_profile 列 */
    PROFILE("profile."),
    /** people_metadata_info：基础档案类 */
    BASIC("basic."),
    /** people_metadata_info：病种档案类 */
    DISEASE("disease."),
    /** 指标表（时间序列） */
    METRIC("metric."),
    /** 检验报告，只读 */
    LAB("lab."),
    ALLERGY("allergy."),
    MEDICATION("medication."),
    GOAL("goal."),
    CONSENT("consent."),
    /** 只存在作答里（健管师补充、测量方式） */
    RESPONSE_STAFF("staff."),
    RESPONSE_MEASURE("measure.");

    private final String prefix;

    FieldStore(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public boolean metadata() {
        return this == BASIC || this == DISEASE;
    }

    public boolean responseOnly() {
        return this == RESPONSE_STAFF || this == RESPONSE_MEASURE;
    }

    public static FieldStore of(String code) {
        if (code != null) {
            for (FieldStore s : values()) {
                if (code.startsWith(s.prefix)) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException("未知字段前缀: " + code);
    }

    /** {@code disease.hypertension.highestSystolic} → {@code hypertension} */
    public static String diseaseOf(String code) {
        String rest = code.substring(DISEASE.prefix.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
