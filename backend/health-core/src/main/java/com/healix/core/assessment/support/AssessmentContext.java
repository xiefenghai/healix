package com.healix.core.assessment.support;

import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.catalog.InputField.Question;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * 评估输入（由档案 / 指标 / 检验 / 用药 / 问卷事实批量组装）。
 *
 * <p>检验值在加载时已统一单位：血脂 mmol/L、尿酸与肌酐 μmol/L、UACR mg/g。
 */
@Getter
@Builder(toBuilder = true)
public class AssessmentContext {
    private final String tenantId;
    private final String peopleId;
    private final LocalDateTime asOf;

    private final String gender;
    private final LocalDate birthday;
    private final Integer ageYears;

    private final BigDecimal heightCm;
    private final LocalDateTime heightRecordedAt;
    private final BigDecimal weightKg;
    private final LocalDateTime weightRecordedAt;
    private final BigDecimal waistCm;
    private final LocalDateTime waistRecordedAt;
    private final BigDecimal bmi;

    /** 近 4 周血压均值；读数不足 2 次时为最新一次 */
    private final BigDecimal sbp;
    private final BigDecimal dbp;
    private final int bpReadings;
    private final LocalDateTime bpRecordedAt;
    private final BigDecimal heartRate;
    private final LocalDateTime heartRateRecordedAt;

    private final BigDecimal fastingGlucose;
    private final LocalDateTime fastingGlucoseRecordedAt;
    private final BigDecimal postprandialGlucose;
    private final LocalDateTime postprandialGlucoseRecordedAt;
    private final BigDecimal hba1c;
    private final LocalDateTime hba1cRecordedAt;

    private final BigDecimal tc;
    private final BigDecimal ldlC;
    private final BigDecimal hdlC;
    private final BigDecimal tg;
    private final LocalDateTime lipidRecordedAt;
    private final BigDecimal uricAcid;
    private final BigDecimal creatinine;
    private final BigDecimal egfr;
    private final BigDecimal uacr;
    private final BigDecimal alt;
    private final LocalDateTime renalRecordedAt;

    /** null=未采集 */
    private final Boolean fhDiabetes;
    private final Boolean fhHypertension;
    private final Boolean fhAscvd;
    private final Boolean fhEarlyAscvd;
    private final boolean familyHistoryCollected;
    /** 一级亲属病种编码（FH_*），供问卷预填 */
    @Builder.Default
    private final Set<String> familyHistoryCodes = Set.of();

    private final boolean hasDiabetesArchive;
    private final boolean hasHypertensionArchive;
    private final boolean hasObesityArchive;
    @Builder.Default
    private final Set<String> presentIllness = Set.of();
    private final boolean presentIllnessCollected;

    private final boolean pastHistoryCollected;
    private final Boolean prediabetesInArchive;

    private final String exerciseFrequency;
    private final String smokingStatus;
    private final String drinkingStatus;
    private final String drinkingFrequency;
    private final String drinkingAmountPerDay;
    private final String dietType;
    private final String dietPreference;
    private final boolean dietCollected;

    private final LocalDateTime controlWindowStart;
    private final int hypoEvents14d;
    private final boolean hypoArchiveFallbackHit;
    @Builder.Default
    private final List<String> diabetesComorbidityHits = new ArrayList<>();
    private final boolean diabetesEndStageChronic;

    private final BigDecimal archiveHighestSbp;
    private final BigDecimal archiveHighestDbp;
    /** 高血压病种档案手填的心血管风险分层 */
    private final String archiveCvRisk;

    /** 在用药品目录分类：BP / DM / LIPID / CV … */
    @Builder.Default
    private final Set<String> activeDrugCategories = Set.of();

    @Builder.Default
    private final Map<InputField, FactValue> facts = Map.of();

    public record FactValue(Object value, String source, LocalDateTime recordedAt) {}

    public boolean isMale() {
        return "MALE".equalsIgnoreCase(gender);
    }

    public boolean isFemale() {
        return "FEMALE".equalsIgnoreCase(gender);
    }

    public boolean hasGender() {
        return isMale() || isFemale();
    }

    public boolean hasKnownDiabetes() {
        return hasDiabetesArchive || presentIllness.contains("DIABETES");
    }

    public boolean hasKnownHypertension() {
        return hasHypertensionArchive || presentIllness.contains("HYPERTENSION");
    }

    public boolean hasKnownObesity() {
        return hasObesityArchive || presentIllness.contains("OBESITY");
    }

    public boolean answered(InputField field) {
        return facts.containsKey(field);
    }

    /** 问卷是非题：YES → true，NO → false，不知道 / 未答 → null。 */
    public Boolean fact(InputField field) {
        FactValue v = facts.get(field);
        if (v == null || v.value() == null) {
            return null;
        }
        String s = String.valueOf(v.value());
        if (Question.YES.equals(s)) {
            return true;
        }
        if (Question.NO.equals(s)) {
            return false;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<String> factList(InputField field) {
        FactValue v = facts.get(field);
        if (v == null || !(v.value() instanceof List<?> list)) {
            return null;
        }
        return (List<String>) list;
    }

    public BigDecimal factNumber(InputField field) {
        FactValue v = facts.get(field);
        if (v == null || v.value() == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v.value()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 现有疾病是否包含；未采集返回 null。 */
    public Boolean hasIllness(String code) {
        if (presentIllness.contains(code)) {
            return true;
        }
        return presentIllnessCollected ? false : null;
    }

    /** 既往最高收缩压：病种档案优先，未建档时取问卷补充值。 */
    public BigDecimal highestSbp() {
        return archiveHighestSbp != null ? archiveHighestSbp : factNumber(InputField.HTN_MAX_SBP);
    }

    public BigDecimal highestDbp() {
        return archiveHighestDbp != null ? archiveHighestDbp : factNumber(InputField.HTN_MAX_DBP);
    }

    public boolean onDrug(String category) {
        return activeDrugCategories.contains(category);
    }

    public Boolean antihypertensiveTherapy() {
        return onDrug("BP") ? Boolean.TRUE : fact(InputField.ANTIHYPERTENSIVE);
    }

    public Boolean lipidTherapy() {
        return onDrug("LIPID") ? Boolean.TRUE : fact(InputField.STATIN);
    }

    public Boolean prediabetes() {
        if (Boolean.TRUE.equals(prediabetesInArchive)) {
            return true;
        }
        Boolean f = fact(InputField.PREDIABETES);
        if (f != null) {
            return f;
        }
        return pastHistoryCollected ? Boolean.FALSE : null;
    }

    public Boolean highSalt() {
        Boolean f = fact(InputField.HIGH_SALT);
        if (f != null) {
            return f;
        }
        if (!dietCollected) {
            return null;
        }
        String type = dietType == null ? "" : dietType.toUpperCase();
        if (type.contains("LOW_SALT")) {
            return false;
        }
        String pref = dietPreference == null ? "" : dietPreference;
        return pref.contains("高盐") || pref.contains("偏咸") || pref.contains("口味重")
                || pref.contains("重口味") || pref.contains("咸食");
    }

    public Boolean currentSmoker() {
        if (smokingStatus == null || smokingStatus.isBlank()) {
            return null;
        }
        String s = smokingStatus.toUpperCase();
        return "CURRENT".equals(s) || "OCCASIONAL".equals(s);
    }

    public Boolean sedentary() {
        if (exerciseFrequency == null || exerciseFrequency.isBlank()) {
            return null;
        }
        return "NONE".equalsIgnoreCase(exerciseFrequency);
    }

    public Boolean heavyDrinking() {
        if (drinkingStatus == null && drinkingFrequency == null) {
            return null;
        }
        String status = drinkingStatus == null ? "" : drinkingStatus.toUpperCase();
        if ("NEVER".equals(status) || "FORMER".equals(status)) {
            return false;
        }
        String freq = drinkingFrequency == null ? "" : drinkingFrequency.toUpperCase();
        if ("DAILY".equals(freq) || "WEEKLY_3".equals(freq)) {
            return true;
        }
        String amount = drinkingAmountPerDay == null ? "" : drinkingAmountPerDay;
        return amount.contains("大量") || amount.contains("醉");
    }

    public Boolean centralObesity() {
        if (waistCm == null || !hasGender()) {
            return null;
        }
        return waistCm.compareTo(new BigDecimal(isMale() ? "90" : "85")) >= 0;
    }

    /** 系统里是否已有该字段的确定值（「不知道」不算）。 */
    public boolean has(InputField f) {
        return switch (f) {
            case GENDER -> hasGender();
            case BIRTHDAY -> ageYears != null;
            case HEIGHT -> heightCm != null;
            case WEIGHT -> weightKg != null;
            case WAIST -> waistCm != null;
            case BLOOD_PRESSURE -> sbp != null && dbp != null;
            case HEART_RATE -> heartRate != null;
            case GLUCOSE -> fastingGlucose != null || postprandialGlucose != null;
            case HBA1C -> hba1c != null;
            case LIPIDS -> tc != null || ldlC != null || hdlC != null || tg != null;
            case URIC_ACID -> uricAcid != null;
            case RENAL -> egfr != null || uacr != null || creatinine != null;
            case LIVER -> alt != null;
            case SMOKING -> smokingStatus != null && !smokingStatus.isBlank();
            case DRINKING -> (drinkingStatus != null && !drinkingStatus.isBlank())
                    || (drinkingFrequency != null && !drinkingFrequency.isBlank());
            case EXERCISE -> exerciseFrequency != null && !exerciseFrequency.isBlank();
            case FAMILY_HISTORY -> familyHistoryCollected;
            case PRESENT_ILLNESS -> presentIllnessCollected;
            case HTN_MAX_SBP -> highestSbp() != null;
            case HTN_MAX_DBP -> highestDbp() != null;
            case HIGH_SALT -> highSalt() != null;
            case PREDIABETES -> prediabetes() != null;
            case ANTIHYPERTENSIVE -> antihypertensiveTherapy() != null;
            case STATIN -> lipidTherapy() != null;
            default -> {
                FactValue v = facts.get(f);
                if (v == null || v.value() == null) {
                    yield false;
                }
                if (f.type() == InputField.ValueType.MULTI) {
                    yield v.value() instanceof List<?> l && !l.isEmpty() && !l.contains(Question.UNKNOWN);
                }
                yield !Question.UNKNOWN.equals(String.valueOf(v.value()));
            }
        };
    }

    /** 问卷预填值（与提交格式一致）；无值返回 null。 */
    public Object currentValue(InputField f) {
        return switch (f) {
            case HEIGHT -> heightCm;
            case WEIGHT -> weightKg;
            case WAIST -> waistCm;
            case SMOKING -> blankToNull(smokingStatus);
            case DRINKING -> {
                String s = drinkingStatus == null ? "" : drinkingStatus.toUpperCase();
                if ("NEVER".equals(s) || "FORMER".equals(s)) {
                    yield "NEVER";
                }
                yield blankToNull(drinkingFrequency);
            }
            case EXERCISE -> blankToNull(exerciseFrequency);
            case FAMILY_HISTORY -> {
                if (!familyHistoryCollected) {
                    yield answeredValue(f);
                }
                yield familyHistoryCodes.isEmpty() ? List.of(Question.NONE) : List.copyOf(familyHistoryCodes);
            }
            case PRESENT_ILLNESS -> {
                if (!presentIllnessCollected) {
                    yield null;
                }
                List<String> picked = f.question().options().stream()
                        .map(InputField.Option::code)
                        .filter(presentIllness::contains)
                        .toList();
                yield picked.isEmpty() ? List.of(Question.NONE) : picked;
            }
            case HTN_MAX_SBP -> highestSbp();
            case HTN_MAX_DBP -> highestDbp();
            case HIGH_SALT -> yesNo(highSalt(), f);
            case PREDIABETES -> yesNo(prediabetes(), f);
            case ANTIHYPERTENSIVE -> yesNo(antihypertensiveTherapy(), f);
            case STATIN -> yesNo(lipidTherapy(), f);
            default -> answeredValue(f);
        };
    }

    private Object answeredValue(InputField f) {
        FactValue v = facts.get(f);
        return v == null ? null : v.value();
    }

    private Object yesNo(Boolean b, InputField f) {
        if (b == null) {
            return answeredValue(f);
        }
        return b ? Question.YES : Question.NO;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase();
    }

    /** 输入快照：只保存引擎声明字段对应的原始值与时间。 */
    public Map<String, Object> snapshotOf(Set<InputField> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", str(asOf));
        for (InputField f : fields) {
            switch (f) {
                case GENDER -> m.put("gender", gender);
                case BIRTHDAY -> {
                    m.put("birthday", str(birthday));
                    m.put("ageYears", ageYears);
                }
                case HEIGHT -> put(m, "heightCm", heightCm, heightRecordedAt);
                case WEIGHT -> {
                    put(m, "weightKg", weightKg, weightRecordedAt);
                    m.put("bmi", bmi);
                }
                case WAIST -> put(m, "waistCm", waistCm, waistRecordedAt);
                case BLOOD_PRESSURE -> {
                    put(m, "sbp", sbp, bpRecordedAt);
                    m.put("dbp", dbp);
                    m.put("bpReadings", bpReadings);
                }
                case HEART_RATE -> put(m, "heartRate", heartRate, heartRateRecordedAt);
                case GLUCOSE -> {
                    put(m, "fastingGlucose", fastingGlucose, fastingGlucoseRecordedAt);
                    put(m, "postprandialGlucose", postprandialGlucose, postprandialGlucoseRecordedAt);
                    m.put("hypoEvents14d", hypoEvents14d);
                }
                case HBA1C -> put(m, "hba1c", hba1c, hba1cRecordedAt);
                case LIPIDS -> {
                    m.put("tc", tc);
                    m.put("ldlC", ldlC);
                    m.put("hdlC", hdlC);
                    put(m, "tg", tg, lipidRecordedAt);
                }
                case URIC_ACID -> m.put("uricAcid", uricAcid);
                case RENAL -> {
                    m.put("creatinine", creatinine);
                    m.put("uacr", uacr);
                    put(m, "egfr", egfr, renalRecordedAt);
                }
                case LIVER -> m.put("alt", alt);
                case SMOKING -> m.put("smokingStatus", smokingStatus);
                case DRINKING -> {
                    m.put("drinkingStatus", drinkingStatus);
                    m.put("drinkingFrequency", drinkingFrequency);
                }
                case EXERCISE -> m.put("exerciseFrequency", exerciseFrequency);
                case FAMILY_HISTORY -> {
                    m.put("familyHistoryCollected", familyHistoryCollected);
                    m.put("fhDiabetes", fhDiabetes);
                    m.put("fhHypertension", fhHypertension);
                    m.put("fhAscvd", fhAscvd);
                    m.put("fhEarlyAscvd", fhEarlyAscvd);
                }
                case PRESENT_ILLNESS -> m.put("presentIllness", presentIllnessCollected ? List.copyOf(presentIllness) : null);
                case HTN_MAX_SBP -> m.put("highestSbp", highestSbp());
                case HTN_MAX_DBP -> m.put("highestDbp", highestDbp());
                default -> {
                    FactValue v = facts.get(f);
                    if (v != null) {
                        m.put("fact." + f.name(), v.value());
                    }
                }
            }
        }
        m.put("hasDiabetesArchive", hasDiabetesArchive);
        m.put("hasHypertensionArchive", hasHypertensionArchive);
        m.put("hasObesityArchive", hasObesityArchive);
        m.put("activeDrugCategories", List.copyOf(activeDrugCategories));
        return m;
    }

    private static void put(Map<String, Object> m, String key, Object value, LocalDateTime at) {
        m.put(key, value);
        m.put(key + "At", str(at));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
