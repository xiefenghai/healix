package com.healix.core.assessment.support;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

/** 评估输入上下文（由现有档案/指标组装，不另造主数据）。 */
@Getter
@Builder(toBuilder = true)
public class AssessmentContext {
    private final String tenantId;
    private final String peopleId;
    private final String gender;
    private final LocalDate birthday;
    private final Integer ageYears;
    private final BigDecimal heightCm;
    private final LocalDateTime heightRecordedAt;
    private final BigDecimal weightKg;
    private final LocalDateTime weightRecordedAt;
    private final BigDecimal waistCm;
    private final LocalDateTime waistRecordedAt;
    private final BigDecimal sbp;
    private final LocalDateTime sbpRecordedAt;
    private final BigDecimal dbp;
    private final LocalDateTime dbpRecordedAt;
    private final BigDecimal bmi;
    private final BigDecimal heartRate;
    private final LocalDateTime heartRateRecordedAt;
    /** null = 家族史未采集；true/false = 已采集且一级亲属有无糖尿病 */
    private final Boolean firstDegreeDiabetesFamilyHistory;
    private final boolean familyHistoryCollected;
    /** 一级亲属高血压家族史；null=未采集 */
    private final Boolean firstDegreeHypertensionFamilyHistory;
    /** 一级亲属 ASCVD 家族史（冠心病/早发心梗/脑卒中）；null=未采集 */
    private final Boolean firstDegreeAscvdFamilyHistory;
    /** 一级亲属早发心梗（FH_EARLY_MI）；null=未采集 */
    private final Boolean earlyAscvdFamilyHistory;
    private final boolean hasDiabetesDiseaseArchive;
    private final boolean hasHypertensionDiseaseArchive;
    private final boolean hasObesityDiseaseArchive;
    /** 基础档案「现有疾病」勾选 */
    private final boolean hasDiabetesPresentIllness;
    private final boolean hasHypertensionPresentIllness;
    private final boolean hasObesityPresentIllness;
    /** 既往史是否提示糖尿病前期；null=既往史未采集 */
    private final Boolean prediabetesHistory;
    private final boolean pastHistoryCollected;
    /** 运动频率字典码；空=未采集 */
    private final String exerciseFrequency;
    private final boolean exerciseCollected;
    /** 吸烟状态字典码；空=未采集 */
    private final String smokingStatus;
    private final boolean smokingCollected;
    /** 饮酒状态/频率；空=未采集 */
    private final String drinkingStatus;
    private final String drinkingFrequency;
    private final String drinkingAmountPerDay;
    private final boolean drinkingCollected;
    /** 饮食类型/偏好（高盐启发式） */
    private final String dietType;
    private final String dietPreference;
    private final boolean dietCollected;
    private final BigDecimal hdlC;
    private final String hdlUnit;
    private final LocalDateTime hdlRecordedAt;
    private final BigDecimal tg;
    private final String tgUnit;
    private final LocalDateTime tgRecordedAt;
    private final BigDecimal tc;
    private final String tcUnit;
    private final LocalDateTime tcRecordedAt;
    private final BigDecimal ldlC;
    private final String ldlUnit;
    private final LocalDateTime ldlRecordedAt;
    /** 空腹血糖 mmol/L（指尖或检验） */
    private final BigDecimal fastingGlucose;
    private final LocalDateTime fastingGlucoseRecordedAt;
    /** 餐后 2h 血糖 mmol/L */
    private final BigDecimal postprandialGlucose;
    private final LocalDateTime postprandialGlucoseRecordedAt;
    private final BigDecimal uricAcid;
    private final String uricAcidUnit;
    private final LocalDateTime uricAcidRecordedAt;
    /** 糖化血红蛋白 %（检验 HBA1C） */
    private final BigDecimal hba1c;
    private final LocalDateTime hba1cRecordedAt;
    /** 控制分标评估时点；窗口起点 = asOf - 90 天 */
    private final LocalDateTime controlLabelAsOf;
    private final LocalDateTime controlLabelWindowStart;
    /** 近 14 天指尖血糖 &lt;4.0 次数 */
    @Builder.Default
    private final int hypoEvents14d = 0;
    /** 14 天内无监测时，档案自填低血糖兜底是否命中红标阈值 */
    @Builder.Default
    private final boolean hypoArchiveFallbackHit = false;
    /** 分标合并症命中码（presentIllness / 病种档案映射） */
    @Builder.Default
    private final List<String> diabetesComorbidityHits = new ArrayList<>();
    @Builder.Default
    private final int diabetesComorbidityCount = 0;
    /** 糖尿病病种档案勾选：终末期慢性疾病 */
    @Builder.Default
    private final boolean diabetesEndStageChronic = false;

    public Map<String, Object> toInputSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("gender", gender);
        m.put("birthday", birthday == null ? null : birthday.toString());
        m.put("ageYears", ageYears);
        m.put("heightCm", heightCm);
        m.put("heightRecordedAt", heightRecordedAt == null ? null : heightRecordedAt.toString());
        m.put("weightKg", weightKg);
        m.put("weightRecordedAt", weightRecordedAt == null ? null : weightRecordedAt.toString());
        m.put("waistCm", waistCm);
        m.put("waistRecordedAt", waistRecordedAt == null ? null : waistRecordedAt.toString());
        m.put("sbp", sbp);
        m.put("sbpRecordedAt", sbpRecordedAt == null ? null : sbpRecordedAt.toString());
        m.put("dbp", dbp);
        m.put("dbpRecordedAt", dbpRecordedAt == null ? null : dbpRecordedAt.toString());
        m.put("bmi", bmi);
        m.put("heartRate", heartRate);
        m.put("heartRateRecordedAt", heartRateRecordedAt == null ? null : heartRateRecordedAt.toString());
        m.put("familyHistoryCollected", familyHistoryCollected);
        m.put("firstDegreeDiabetesFamilyHistory", firstDegreeDiabetesFamilyHistory);
        m.put("firstDegreeHypertensionFamilyHistory", firstDegreeHypertensionFamilyHistory);
        m.put("firstDegreeAscvdFamilyHistory", firstDegreeAscvdFamilyHistory);
        m.put("earlyAscvdFamilyHistory", earlyAscvdFamilyHistory);
        m.put("hasDiabetesDiseaseArchive", hasDiabetesDiseaseArchive);
        m.put("hasHypertensionDiseaseArchive", hasHypertensionDiseaseArchive);
        m.put("hasObesityDiseaseArchive", hasObesityDiseaseArchive);
        m.put("hasDiabetesPresentIllness", hasDiabetesPresentIllness);
        m.put("hasHypertensionPresentIllness", hasHypertensionPresentIllness);
        m.put("hasObesityPresentIllness", hasObesityPresentIllness);
        m.put("pastHistoryCollected", pastHistoryCollected);
        m.put("prediabetesHistory", prediabetesHistory);
        m.put("exerciseCollected", exerciseCollected);
        m.put("exerciseFrequency", exerciseFrequency);
        m.put("smokingCollected", smokingCollected);
        m.put("smokingStatus", smokingStatus);
        m.put("drinkingCollected", drinkingCollected);
        m.put("drinkingStatus", drinkingStatus);
        m.put("drinkingFrequency", drinkingFrequency);
        m.put("drinkingAmountPerDay", drinkingAmountPerDay);
        m.put("dietCollected", dietCollected);
        m.put("dietType", dietType);
        m.put("dietPreference", dietPreference);
        m.put("hdlC", hdlC);
        m.put("hdlUnit", hdlUnit);
        m.put("hdlRecordedAt", hdlRecordedAt == null ? null : hdlRecordedAt.toString());
        m.put("tg", tg);
        m.put("tgUnit", tgUnit);
        m.put("tgRecordedAt", tgRecordedAt == null ? null : tgRecordedAt.toString());
        m.put("tc", tc);
        m.put("tcUnit", tcUnit);
        m.put("tcRecordedAt", tcRecordedAt == null ? null : tcRecordedAt.toString());
        m.put("ldlC", ldlC);
        m.put("ldlUnit", ldlUnit);
        m.put("ldlRecordedAt", ldlRecordedAt == null ? null : ldlRecordedAt.toString());
        m.put("fastingGlucose", fastingGlucose);
        m.put(
                "fastingGlucoseRecordedAt",
                fastingGlucoseRecordedAt == null ? null : fastingGlucoseRecordedAt.toString());
        m.put("postprandialGlucose", postprandialGlucose);
        m.put(
                "postprandialGlucoseRecordedAt",
                postprandialGlucoseRecordedAt == null ? null : postprandialGlucoseRecordedAt.toString());
        m.put("uricAcid", uricAcid);
        m.put("uricAcidUnit", uricAcidUnit);
        m.put("uricAcidRecordedAt", uricAcidRecordedAt == null ? null : uricAcidRecordedAt.toString());
        m.put("hba1c", hba1c);
        m.put("hba1cRecordedAt", hba1cRecordedAt == null ? null : hba1cRecordedAt.toString());
        m.put("controlLabelAsOf", controlLabelAsOf == null ? null : controlLabelAsOf.toString());
        m.put(
                "controlLabelWindowStart",
                controlLabelWindowStart == null ? null : controlLabelWindowStart.toString());
        m.put("hypoEvents14d", hypoEvents14d);
        m.put("hypoArchiveFallbackHit", hypoArchiveFallbackHit);
        m.put("diabetesComorbidityHits", diabetesComorbidityHits);
        m.put("diabetesComorbidityCount", diabetesComorbidityCount);
        m.put("diabetesEndStageChronic", diabetesEndStageChronic);
        return m;
    }

    /** 已有糖尿病病种档案或基础档案勾选现有疾病「糖尿病」→ 不做 CDRS 风险评估。 */
    public boolean hasKnownDiabetes() {
        return hasDiabetesDiseaseArchive || hasDiabetesPresentIllness;
    }

    /** 已有高血压病种档案或基础档案勾选现有疾病「高血压」→ 不做高血压风险评估。 */
    public boolean hasKnownHypertension() {
        return hasHypertensionDiseaseArchive || hasHypertensionPresentIllness;
    }

    /** 已有肥胖病种档案或基础档案勾选现有疾病「肥胖症」→ 不做肥胖风险评估。 */
    public boolean hasKnownObesity() {
        return hasObesityDiseaseArchive || hasObesityPresentIllness;
    }
}
