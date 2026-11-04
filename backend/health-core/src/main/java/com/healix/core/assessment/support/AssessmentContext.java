package com.healix.core.assessment.support;

import com.healix.core.assessment.catalog.AssessmentFields;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.Builder;
import lombok.Getter;

/**
 * 评估输入（由档案 / 指标 / 检验 / 用药 / 元数据批量组装）。
 *
 * <p>检验值在加载时已统一单位：血脂 mmol/L、尿酸与肌酐 μmol/L、UACR mg/g。
 */
@Getter
@Builder(toBuilder = true)
public class AssessmentContext {
    public static final String YES = "YES";
    public static final String NO = "NO";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String NONE = "NONE";

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
    private final BigDecimal bodyFat;
    private final LocalDateTime bodyFatRecordedAt;
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
    @Builder.Default
    private final Set<String> dietTaste = Set.of();
    private final boolean dietTasteCollected;
    private final String stressLevel;
    @Builder.Default
    private final Set<String> medicationFlags = Set.of();
    private final boolean medicationFlagsCollected;

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

    /** 元数据 / 问卷写入的补充值，键为字段编码。 */
    @Builder.Default
    private final Map<String, FactValue> facts = Map.of();

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

    public boolean answered(String field) {
        return facts.containsKey(field);
    }

    /** yesNoUnknown：YES → true，NO → false，不清楚 / 未答 → null。 */
    public Boolean yesNo(String field) {
        FactValue v = facts.get(field);
        if (v == null || v.value() == null) {
            return null;
        }
        String s = String.valueOf(v.value()).trim().toUpperCase(Locale.ROOT);
        if (YES.equals(s)) {
            return true;
        }
        if (NO.equals(s)) {
            return false;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<String> factList(String field) {
        FactValue v = facts.get(field);
        if (v == null || !(v.value() instanceof List<?> list)) {
            return null;
        }
        return (List<String>) list;
    }

    public BigDecimal factNumber(String field) {
        if (AssessmentFields.BODY_FAT.equals(field) && bodyFat != null) {
            return bodyFat;
        }
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

    public Boolean hasMedicationFlag(String code) {
        if (medicationFlags.contains(code)) {
            return true;
        }
        return medicationFlagsCollected ? false : null;
    }

    /** 既往最高收缩压：病种档案优先，未建档时取问卷补充值。 */
    public BigDecimal highestSbp() {
        if (archiveHighestSbp != null) {
            return archiveHighestSbp;
        }
        return factNumber(AssessmentFields.HTN_MAX_SBP);
    }

    public BigDecimal highestDbp() {
        if (archiveHighestDbp != null) {
            return archiveHighestDbp;
        }
        return factNumber(AssessmentFields.HTN_MAX_DBP);
    }

    public boolean onDrug(String category) {
        return activeDrugCategories.contains(category);
    }

    public Boolean antihypertensiveTherapy() {
        if (onDrug("BP")) {
            return Boolean.TRUE;
        }
        return hasMedicationFlag(AssessmentFields.MED_ANTIHYPERTENSIVE);
    }

    public Boolean lipidTherapy() {
        if (onDrug("LIPID")) {
            return Boolean.TRUE;
        }
        return hasMedicationFlag(AssessmentFields.MED_STATIN);
    }

    public Boolean prediabetes() {
        if (Boolean.TRUE.equals(prediabetesInArchive) || presentIllness.contains(AssessmentFields.ILLNESS_PREDIABETES)) {
            return true;
        }
        if (presentIllnessCollected || pastHistoryCollected) {
            return false;
        }
        return null;
    }

    public Boolean highSalt() {
        if (dietTasteCollected) {
            return dietTaste.contains("SALTY");
        }
        if (!dietCollected) {
            return null;
        }
        String type = dietType == null ? "" : dietType.toUpperCase(Locale.ROOT);
        if (type.contains("LOW_SALT")) {
            return false;
        }
        String pref = dietPreference == null ? "" : dietPreference;
        return pref.contains("高盐")
                || pref.contains("偏咸")
                || pref.contains("口味重")
                || pref.contains("重口味")
                || pref.contains("咸食");
    }

    public Boolean chronicStress() {
        if (stressLevel == null || stressLevel.isBlank()) {
            return null;
        }
        return "HIGH".equalsIgnoreCase(stressLevel);
    }

    public Boolean currentSmoker() {
        if (smokingStatus == null || smokingStatus.isBlank()) {
            return null;
        }
        String s = smokingStatus.toUpperCase(Locale.ROOT);
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
        String status = drinkingStatus == null ? "" : drinkingStatus.toUpperCase(Locale.ROOT);
        if ("NEVER".equals(status) || "FORMER".equals(status)) {
            return false;
        }
        String freq = drinkingFrequency == null ? "" : drinkingFrequency.toUpperCase(Locale.ROOT);
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
    public boolean has(String code) {
        if (code == null) {
            return false;
        }
        return switch (code) {
            case AssessmentFields.GENDER -> hasGender();
            case AssessmentFields.BIRTHDAY -> ageYears != null;
            case AssessmentFields.HEIGHT -> heightCm != null;
            case AssessmentFields.WEIGHT -> weightKg != null;
            case AssessmentFields.WAIST -> waistCm != null;
            case AssessmentFields.BODY_FAT -> bodyFat != null || factNumber(AssessmentFields.BODY_FAT) != null;
            case AssessmentFields.BLOOD_PRESSURE_SYS, AssessmentFields.BLOOD_PRESSURE_DIA -> sbp != null && dbp != null;
            case AssessmentFields.HEART_RATE -> heartRate != null;
            case AssessmentFields.GLUCOSE -> fastingGlucose != null || postprandialGlucose != null;
            case AssessmentFields.HBA1C -> hba1c != null;
            case AssessmentFields.TC, AssessmentFields.TG, AssessmentFields.LDL_C, AssessmentFields.HDL_C ->
                    tc != null || ldlC != null || hdlC != null || tg != null;
            case AssessmentFields.URIC_ACID -> uricAcid != null;
            case AssessmentFields.CR, AssessmentFields.EGFR, AssessmentFields.UACR ->
                    egfr != null || uacr != null || creatinine != null;
            case AssessmentFields.ALT, AssessmentFields.AST -> alt != null;
            case AssessmentFields.SMOKING -> smokingStatus != null && !smokingStatus.isBlank();
            case AssessmentFields.DRINKING, AssessmentFields.DRINKING_FREQUENCY ->
                    (drinkingStatus != null && !drinkingStatus.isBlank())
                            || (drinkingFrequency != null && !drinkingFrequency.isBlank());
            case AssessmentFields.EXERCISE -> exerciseFrequency != null && !exerciseFrequency.isBlank();
            case AssessmentFields.FAMILY_HISTORY,
                    AssessmentFields.FAMILY_HISTORY_STATUS,
                    AssessmentFields.FAMILY_HISTORY_ITEMS -> familyHistoryCollected;
            case AssessmentFields.PRESENT_ILLNESS -> presentIllnessCollected;
            case AssessmentFields.HTN_MAX_SBP -> highestSbp() != null;
            case AssessmentFields.HTN_MAX_DBP -> highestDbp() != null;
            case AssessmentFields.HIGH_SALT -> highSalt() != null;
            case AssessmentFields.CHRONIC_STRESS -> chronicStress() != null;
            case AssessmentFields.MEDICATION_FLAGS -> medicationFlagsCollected || !activeDrugCategories.isEmpty();
            case AssessmentFields.PASSIVE_SMOKING,
                    AssessmentFields.GDM_HISTORY,
                    AssessmentFields.STEROID_DIABETES,
                    AssessmentFields.ACANTHOSIS,
                    AssessmentFields.EARLY_MENOPAUSE,
                    AssessmentFields.LVH,
                    AssessmentFields.CAROTID_PLAQUE,
                    AssessmentFields.PWV_HIGH,
                    AssessmentFields.ABI_LOW,
                    AssessmentFields.HCY_HIGH,
                    AssessmentFields.PERIPHERAL_ARTERY_DISEASE,
                    AssessmentFields.ADVANCED_RETINOPATHY -> yesNoPresent(code);
            case AssessmentFields.DM_COMPLICATIONS -> {
                List<String> list = factList(code);
                if (list == null || list.isEmpty()) {
                    yield false;
                }
                yield !list.contains(UNKNOWN);
            }
            default -> {
                FactValue v = facts.get(code);
                if (v == null || v.value() == null) {
                    yield false;
                }
                if (v.value() instanceof List<?> l) {
                    yield !l.isEmpty() && !l.contains(UNKNOWN);
                }
                yield !UNKNOWN.equals(String.valueOf(v.value()));
            }
        };
    }

    private boolean yesNoPresent(String code) {
        Boolean b = yesNo(code);
        return b != null;
    }

    /** 问卷预填值；无值返回 null。 */
    public Object currentValue(String code) {
        return switch (code) {
            case AssessmentFields.HEIGHT -> heightCm;
            case AssessmentFields.WEIGHT -> weightKg;
            case AssessmentFields.WAIST -> waistCm;
            case AssessmentFields.BODY_FAT -> bodyFat != null ? bodyFat : factNumber(AssessmentFields.BODY_FAT);
            case AssessmentFields.SMOKING -> blankToNull(smokingStatus);
            case AssessmentFields.DRINKING, AssessmentFields.DRINKING_FREQUENCY -> {
                String s = drinkingStatus == null ? "" : drinkingStatus.toUpperCase(Locale.ROOT);
                if ("NEVER".equals(s) || "FORMER".equals(s)) {
                    yield "NEVER";
                }
                yield blankToNull(drinkingFrequency);
            }
            case AssessmentFields.EXERCISE -> blankToNull(exerciseFrequency);
            case AssessmentFields.FAMILY_HISTORY,
                    AssessmentFields.FAMILY_HISTORY_STATUS,
                    AssessmentFields.FAMILY_HISTORY_ITEMS -> {
                if (!familyHistoryCollected) {
                    yield answeredValue(code);
                }
                yield familyHistoryCodes.isEmpty() ? List.of(NONE) : List.copyOf(familyHistoryCodes);
            }
            case AssessmentFields.PRESENT_ILLNESS -> {
                if (!presentIllnessCollected) {
                    yield null;
                }
                yield presentIllness.isEmpty() ? List.of(NONE) : List.copyOf(presentIllness);
            }
            case AssessmentFields.HTN_MAX_SBP -> highestSbp();
            case AssessmentFields.HTN_MAX_DBP -> highestDbp();
            case AssessmentFields.HIGH_SALT -> dietTasteCollected ? List.copyOf(dietTaste) : answeredValue(code);
            case AssessmentFields.CHRONIC_STRESS -> blankToNull(stressLevel);
            case AssessmentFields.MEDICATION_FLAGS ->
                    medicationFlagsCollected ? List.copyOf(medicationFlags) : answeredValue(code);
            case AssessmentFields.DM_COMPLICATIONS -> answeredValue(code);
            default -> {
                Boolean yn = yesNo(code);
                if (yn != null) {
                    yield yn ? YES : NO;
                }
                yield answeredValue(code);
            }
        };
    }

    private Object answeredValue(String field) {
        FactValue v = facts.get(field);
        return v == null ? null : v.value();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim().toUpperCase(Locale.ROOT);
    }

    /** 输入快照：只保存引擎声明字段对应的原始值与时间。 */
    public Map<String, Object> snapshotOf(Set<String> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("asOf", str(asOf));
        boolean lipidsDone = false;
        boolean renalDone = false;
        boolean bpDone = false;
        for (String f : fields) {
            switch (f) {
                case AssessmentFields.GENDER -> m.put("gender", gender);
                case AssessmentFields.BIRTHDAY -> {
                    m.put("birthday", str(birthday));
                    m.put("ageYears", ageYears);
                }
                case AssessmentFields.HEIGHT -> put(m, "heightCm", heightCm, heightRecordedAt);
                case AssessmentFields.WEIGHT -> {
                    put(m, "weightKg", weightKg, weightRecordedAt);
                    m.put("bmi", bmi);
                }
                case AssessmentFields.WAIST -> put(m, "waistCm", waistCm, waistRecordedAt);
                case AssessmentFields.BODY_FAT -> put(m, "bodyFat", bodyFat, bodyFatRecordedAt);
                case AssessmentFields.BLOOD_PRESSURE_SYS, AssessmentFields.BLOOD_PRESSURE_DIA -> {
                    if (!bpDone) {
                        put(m, "sbp", sbp, bpRecordedAt);
                        m.put("dbp", dbp);
                        m.put("bpReadings", bpReadings);
                        bpDone = true;
                    }
                }
                case AssessmentFields.HEART_RATE -> put(m, "heartRate", heartRate, heartRateRecordedAt);
                case AssessmentFields.GLUCOSE -> {
                    put(m, "fastingGlucose", fastingGlucose, fastingGlucoseRecordedAt);
                    put(m, "postprandialGlucose", postprandialGlucose, postprandialGlucoseRecordedAt);
                    m.put("hypoEvents14d", hypoEvents14d);
                }
                case AssessmentFields.HBA1C -> put(m, "hba1c", hba1c, hba1cRecordedAt);
                case AssessmentFields.TC, AssessmentFields.TG, AssessmentFields.LDL_C, AssessmentFields.HDL_C -> {
                    if (!lipidsDone) {
                        m.put("tc", tc);
                        m.put("ldlC", ldlC);
                        m.put("hdlC", hdlC);
                        put(m, "tg", tg, lipidRecordedAt);
                        lipidsDone = true;
                    }
                }
                case AssessmentFields.URIC_ACID -> m.put("uricAcid", uricAcid);
                case AssessmentFields.CR, AssessmentFields.EGFR, AssessmentFields.UACR -> {
                    if (!renalDone) {
                        m.put("creatinine", creatinine);
                        m.put("uacr", uacr);
                        put(m, "egfr", egfr, renalRecordedAt);
                        renalDone = true;
                    }
                }
                case AssessmentFields.ALT, AssessmentFields.AST -> m.put("alt", alt);
                case AssessmentFields.SMOKING -> m.put("smokingStatus", smokingStatus);
                case AssessmentFields.DRINKING, AssessmentFields.DRINKING_FREQUENCY -> {
                    m.put("drinkingStatus", drinkingStatus);
                    m.put("drinkingFrequency", drinkingFrequency);
                }
                case AssessmentFields.EXERCISE -> m.put("exerciseFrequency", exerciseFrequency);
                case AssessmentFields.FAMILY_HISTORY,
                        AssessmentFields.FAMILY_HISTORY_STATUS,
                        AssessmentFields.FAMILY_HISTORY_ITEMS -> {
                    m.put("familyHistoryCollected", familyHistoryCollected);
                    m.put("fhDiabetes", fhDiabetes);
                    m.put("fhHypertension", fhHypertension);
                    m.put("fhAscvd", fhAscvd);
                    m.put("fhEarlyAscvd", fhEarlyAscvd);
                }
                case AssessmentFields.PRESENT_ILLNESS ->
                        m.put("presentIllness", presentIllnessCollected ? List.copyOf(presentIllness) : null);
                case AssessmentFields.HTN_MAX_SBP -> m.put("highestSbp", highestSbp());
                case AssessmentFields.HTN_MAX_DBP -> m.put("highestDbp", highestDbp());
                case AssessmentFields.HIGH_SALT -> m.put("dietTaste", dietTasteCollected ? List.copyOf(dietTaste) : null);
                case AssessmentFields.CHRONIC_STRESS -> m.put("stressLevel", stressLevel);
                default -> {
                    FactValue v = facts.get(f);
                    if (v != null) {
                        m.put("field." + f, v.value());
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
