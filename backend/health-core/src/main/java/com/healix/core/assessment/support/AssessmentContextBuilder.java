package com.healix.core.assessment.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.domain.PeopleAssessmentFact;
import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.support.AssessmentContext.FactValue;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.dto.DrugCatalogItemDto;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.service.DrugCatalogService;
import com.healix.core.observation.dto.MetricLatestSlotDto;
import com.healix.core.observation.mapper.LabResultItemMapper;
import com.healix.core.observation.mapper.LabResultItemMapper.LabLatestNumeric;
import com.healix.core.observation.service.MetricService;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 评估输入批量加载：每类数据源只取一次。
 *
 * <p>指标最新值、基础档案、病种档案、检验、近 4 周血压、近 14 天血糖、在用药品、问卷事实各一次查询。
 */
@Component
@RequiredArgsConstructor
public class AssessmentContextBuilder {

    static final int BP_WINDOW_DAYS = 28;
    static final int CONTROL_WINDOW_DAYS = 90;
    private static final BigDecimal HYPO_THRESHOLD = new BigDecimal("4.0");
    private static final List<String> LAB_CODES =
            List.of("HDL_C", "TG", "TC", "LDL_C", "FPG", "UA", "HBA1C", "CR", "EGFR", "UACR", "ALT");
    private static final Set<String> FH_ASCVD = Set.of("FH_CHD", "FH_EARLY_MI", "FH_STROKE");
    private static final Set<String> COMORBIDITY_CODES = Set.of(
            "OSTEOARTHRITIS", "RHEUMATOID_ARTHRITIS", "MALIGNANT_TUMOR", "HEART_FAILURE", "DEPRESSION",
            "COPD", "HYPERTENSION", "CKD", "CHD", "STROKE");

    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final MetricService metricService;
    private final LabResultItemMapper labResultItemMapper;
    private final VitalRecordMapper vitalRecordMapper;
    private final PeopleMedicationMapper medicationMapper;
    private final DrugCatalogService drugCatalogService;
    private final AssessmentStoreMapper storeMapper;

    public AssessmentContext build(String tenantId, String peopleId) {
        LocalDateTime now = LocalDateTime.now();
        AssessmentContext.AssessmentContextBuilder b = AssessmentContext.builder()
                .tenantId(tenantId)
                .peopleId(peopleId)
                .asOf(now)
                .controlWindowStart(now.minusDays(CONTROL_WINDOW_DAYS));

        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile != null) {
            b.gender(normalizeGender(profile.getGender()));
            b.birthday(profile.getBirthday());
            if (profile.getBirthday() != null) {
                b.ageYears(Period.between(profile.getBirthday(), now.toLocalDate()).getYears());
            }
        }

        MetricsLoaded metrics = loadMetrics(b, tenantId, peopleId, now);
        LabLatestNumeric fpg = loadLabs(b, tenantId, peopleId);
        if (fpg != null
                && (metrics.fasting() == null
                        || (fpg.getRecordedAt() != null
                                && metrics.fasting().recordedAt() != null
                                && fpg.getRecordedAt().isAfter(metrics.fasting().recordedAt())))) {
            b.fastingGlucose(LabUnits.glucose(fpg.getValueNum(), fpg.getUnit()))
                    .fastingGlucoseRecordedAt(fpg.getRecordedAt());
        }
        Set<String> presentIllness = loadBasicArchive(b, tenantId, peopleId);
        loadDiseaseArchives(b, tenantId, peopleId, presentIllness, metrics.glucoseSeen14d());
        loadMedications(b, tenantId, peopleId);
        loadFacts(b, tenantId, peopleId);
        return b.build();
    }

    private record MetricsLoaded(MetricLatestSlotDto fasting, boolean glucoseSeen14d) {}

    // ---------------------------------------------------------------- 指标

    private MetricsLoaded loadMetrics(
            AssessmentContext.AssessmentContextBuilder b, String tenantId, String peopleId, LocalDateTime now) {
        Map<MetricTypeEnum, MetricLatestSlotDto> latest = new EnumMap<>(MetricTypeEnum.class);
        MetricLatestSlotDto fasting = null;
        MetricLatestSlotDto post = null;
        for (MetricLatestSlotDto slot : metricService.latestForPatient(tenantId, peopleId)) {
            if (slot == null || slot.value() == null) {
                continue;
            }
            if (MetricTypeEnum.BLOOD_GLUCOSE.matches(slot.metricType())) {
                String meal = slot.mealContext() == null ? "" : slot.mealContext().toUpperCase(Locale.ROOT);
                if ("FASTING".equals(meal)) {
                    fasting = newer(fasting, slot);
                } else if ("POSTPRANDIAL".equals(meal)) {
                    post = newer(post, slot);
                }
                continue;
            }
            for (MetricTypeEnum t : List.of(
                    MetricTypeEnum.HEIGHT,
                    MetricTypeEnum.WEIGHT,
                    MetricTypeEnum.WAIST,
                    MetricTypeEnum.HEART_RATE,
                    MetricTypeEnum.BLOOD_PRESSURE_SYS,
                    MetricTypeEnum.BLOOD_PRESSURE_DIA)) {
                if (t.matches(slot.metricType())) {
                    latest.put(t, newer(latest.get(t), slot));
                }
            }
        }

        MetricLatestSlotDto h = latest.get(MetricTypeEnum.HEIGHT);
        MetricLatestSlotDto w = latest.get(MetricTypeEnum.WEIGHT);
        MetricLatestSlotDto waist = latest.get(MetricTypeEnum.WAIST);
        MetricLatestSlotDto hr = latest.get(MetricTypeEnum.HEART_RATE);
        if (h != null) {
            b.heightCm(h.value()).heightRecordedAt(h.recordedAt());
        }
        if (w != null) {
            b.weightKg(w.value()).weightRecordedAt(w.recordedAt());
        }
        if (h != null && w != null && h.value().signum() > 0) {
            BigDecimal m = h.value().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            b.bmi(w.value().divide(m.multiply(m), 1, RoundingMode.HALF_UP));
        }
        if (waist != null) {
            b.waistCm(waist.value()).waistRecordedAt(waist.recordedAt());
        }
        if (hr != null) {
            b.heartRate(hr.value()).heartRateRecordedAt(hr.recordedAt());
        }
        if (fasting != null) {
            b.fastingGlucose(fasting.value()).fastingGlucoseRecordedAt(fasting.recordedAt());
        }
        if (post != null) {
            b.postprandialGlucose(post.value()).postprandialGlucoseRecordedAt(post.recordedAt());
        }

        LocalDateTime from = now.minusDays(BP_WINDOW_DAYS);
        List<VitalRecord> sysRows = nonNull(vitalRecordMapper.listByRange(
                tenantId, peopleId, MetricTypeEnum.BLOOD_PRESSURE_SYS.name(), from, now));
        List<VitalRecord> diaRows = nonNull(vitalRecordMapper.listByRange(
                tenantId, peopleId, MetricTypeEnum.BLOOD_PRESSURE_DIA.name(), from, now));
        if (sysRows.size() >= 2 && diaRows.size() >= 2) {
            b.sbp(mean(sysRows)).dbp(mean(diaRows)).bpReadings(sysRows.size());
            b.bpRecordedAt(sysRows.stream()
                    .map(VitalRecord::getRecordedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(null));
        } else {
            MetricLatestSlotDto s = latest.get(MetricTypeEnum.BLOOD_PRESSURE_SYS);
            MetricLatestSlotDto d = latest.get(MetricTypeEnum.BLOOD_PRESSURE_DIA);
            if (s != null && d != null) {
                b.sbp(s.value()).dbp(d.value()).bpReadings(1).bpRecordedAt(s.recordedAt());
            }
        }

        List<VitalRecord> glucose14 = nonNull(vitalRecordMapper.listByRange(
                tenantId, peopleId, MetricTypeEnum.BLOOD_GLUCOSE.name(), now.minusDays(14), now));
        int hypo = (int) glucose14.stream().filter(r -> r.getValue().compareTo(HYPO_THRESHOLD) < 0).count();
        b.hypoEvents14d(hypo);
        return new MetricsLoaded(fasting, !glucose14.isEmpty());
    }

    private static List<VitalRecord> nonNull(List<VitalRecord> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().filter(r -> r != null && r.getValue() != null).toList();
    }

    private static BigDecimal mean(List<VitalRecord> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (VitalRecord r : rows) {
            sum = sum.add(r.getValue());
        }
        return sum.divide(BigDecimal.valueOf(rows.size()), 0, RoundingMode.HALF_UP);
    }

    private static MetricLatestSlotDto newer(MetricLatestSlotDto a, MetricLatestSlotDto b) {
        if (a == null) {
            return b;
        }
        if (b.recordedAt() != null && (a.recordedAt() == null || b.recordedAt().isAfter(a.recordedAt()))) {
            return b;
        }
        return a;
    }

    // ---------------------------------------------------------------- 检验

    /** 返回检验空腹血糖，供指标缺失时兜底。 */
    private LabLatestNumeric loadLabs(AssessmentContext.AssessmentContextBuilder b, String tenantId, String peopleId) {
        List<LabLatestNumeric> rows = labResultItemMapper.listLatestNumericByPeople(tenantId, peopleId, LAB_CODES);
        Map<String, LabLatestNumeric> byCode = new HashMap<>();
        if (rows != null) {
            for (LabLatestNumeric r : rows) {
                if (r != null && StringUtils.hasText(r.getItemCode()) && r.getValueNum() != null) {
                    byCode.putIfAbsent(r.getItemCode(), r);
                }
            }
        }
        LocalDateTime lipidAt = null;
        LabLatestNumeric r;
        if ((r = byCode.get("TC")) != null) {
            b.tc(LabUnits.cholesterol(r.getValueNum(), r.getUnit()));
            lipidAt = max(lipidAt, r.getRecordedAt());
        }
        if ((r = byCode.get("LDL_C")) != null) {
            b.ldlC(LabUnits.cholesterol(r.getValueNum(), r.getUnit()));
            lipidAt = max(lipidAt, r.getRecordedAt());
        }
        if ((r = byCode.get("HDL_C")) != null) {
            b.hdlC(LabUnits.cholesterol(r.getValueNum(), r.getUnit()));
            lipidAt = max(lipidAt, r.getRecordedAt());
        }
        if ((r = byCode.get("TG")) != null) {
            b.tg(LabUnits.triglyceride(r.getValueNum(), r.getUnit()));
            lipidAt = max(lipidAt, r.getRecordedAt());
        }
        b.lipidRecordedAt(lipidAt);
        if ((r = byCode.get("UA")) != null) {
            b.uricAcid(LabUnits.uricAcid(r.getValueNum(), r.getUnit()));
        }
        LocalDateTime renalAt = null;
        if ((r = byCode.get("CR")) != null) {
            b.creatinine(LabUnits.creatinine(r.getValueNum(), r.getUnit()));
            renalAt = max(renalAt, r.getRecordedAt());
        }
        if ((r = byCode.get("EGFR")) != null) {
            b.egfr(r.getValueNum());
            renalAt = max(renalAt, r.getRecordedAt());
        }
        if ((r = byCode.get("UACR")) != null) {
            b.uacr(LabUnits.uacr(r.getValueNum(), r.getUnit()));
            renalAt = max(renalAt, r.getRecordedAt());
        }
        b.renalRecordedAt(renalAt);
        if ((r = byCode.get("ALT")) != null) {
            b.alt(r.getValueNum());
        }
        if ((r = byCode.get("HBA1C")) != null) {
            b.hba1c(LabUnits.hba1c(r.getValueNum(), r.getUnit())).hba1cRecordedAt(r.getRecordedAt());
        }
        return byCode.get("FPG");
    }

    private static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        return b != null && b.isAfter(a) ? b : a;
    }

    // ---------------------------------------------------------------- 基础档案

    private Set<String> loadBasicArchive(
            AssessmentContext.AssessmentContextBuilder b, String tenantId, String peopleId) {
        Set<String> illness = new LinkedHashSet<>();
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        JsonNode root = archive == null || !StringUtils.hasText(archive.getContentJson())
                ? null
                : JsonUtils.readTree(archive.getContentJson());
        if (root == null) {
            return illness;
        }

        // 家族史
        String fhStatus = text(root, "familyHistoryStatus");
        JsonNode fhItems = root.get("familyHistoryItems");
        boolean fhCollected = "none".equalsIgnoreCase(fhStatus)
                || "has".equalsIgnoreCase(fhStatus)
                || (fhItems != null && fhItems.isArray() && !fhItems.isEmpty());
        if (fhCollected) {
            Set<String> codes = new LinkedHashSet<>();
            if (!"none".equalsIgnoreCase(fhStatus) && fhItems != null && fhItems.isArray()) {
                for (JsonNode item : fhItems) {
                    String level = text(item, "kinshipLevel");
                    if (level != null && !"1".equals(level)) {
                        continue;
                    }
                    JsonNode diseases = item.get("diseases");
                    if (diseases != null && diseases.isArray()) {
                        diseases.forEach(d -> {
                            if (StringUtils.hasText(d.asText())) {
                                codes.add(d.asText().trim().toUpperCase(Locale.ROOT));
                            }
                        });
                    }
                }
            }
            b.familyHistoryCollected(true)
                    .familyHistoryCodes(codes)
                    .fhDiabetes(codes.contains("FH_DIABETES"))
                    .fhHypertension(codes.contains("FH_HYPERTENSION"))
                    .fhAscvd(codes.stream().anyMatch(FH_ASCVD::contains))
                    .fhEarlyAscvd(codes.contains("FH_EARLY_MI"));
        }

        // 现有疾病
        JsonNode pi = root.get("presentIllness");
        if (pi != null && pi.isArray()) {
            b.presentIllnessCollected(true);
            pi.forEach(n -> {
                if (n != null && StringUtils.hasText(n.asText())) {
                    illness.add(n.asText().trim().toUpperCase(Locale.ROOT));
                }
            });
        }
        String other = text(root, "presentIllnessOther");
        if (StringUtils.hasText(other)) {
            String s = other.toLowerCase(Locale.ROOT);
            if (s.contains("糖尿病")) {
                illness.add("DIABETES");
            }
            if (s.contains("高血压")) {
                illness.add("HYPERTENSION");
            }
            if (s.contains("肥胖")) {
                illness.add("OBESITY");
            }
        }
        b.presentIllness(Set.copyOf(illness));

        // 既往史：糖尿病前期
        String pastStatus = text(root, "pastHistoryStatus");
        JsonNode pastItems = root.get("pastHistoryItems");
        boolean pastCollected = "none".equalsIgnoreCase(pastStatus)
                || "has".equalsIgnoreCase(pastStatus)
                || (pastItems != null && !pastItems.isNull());
        b.pastHistoryCollected(pastCollected);
        if (pastCollected) {
            boolean pre = looksLikePrediabetes(text(root, "pastHistory"));
            JsonNode list = pastItems != null && pastItems.isObject() ? pastItems.get("diseases") : pastItems;
            if (list != null && list.isArray()) {
                for (JsonNode item : list) {
                    if (looksLikePrediabetes(item.isTextual() ? item.asText() : text(item, "name"))
                            || looksLikePrediabetes(text(item, "code"))) {
                        pre = true;
                        break;
                    }
                }
            }
            b.prediabetesInArchive(pre);
        }

        // 生活方式
        b.exerciseFrequency(text(root.path("exercise"), "frequency"));
        JsonNode lifestyle = root.get("lifestyle");
        if (lifestyle != null && lifestyle.isObject()) {
            JsonNode smoking = lifestyle.get("smoking");
            b.smokingStatus(smoking == null ? null : smoking.isObject() ? text(smoking, "status") : smoking.asText(null));
            JsonNode drinking = lifestyle.get("drinking");
            if (drinking != null && drinking.isObject()) {
                b.drinkingStatus(text(drinking, "status"))
                        .drinkingFrequency(text(drinking, "frequency"))
                        .drinkingAmountPerDay(text(drinking, "amountPerDay"));
            } else if (drinking != null && drinking.isTextual()) {
                b.drinkingStatus(drinking.asText());
            }
        }
        JsonNode diet = root.get("diet");
        if (diet != null && diet.isObject()) {
            String type = text(diet, "type");
            String pref = text(diet, "preference");
            b.dietType(type).dietPreference(pref);
            b.dietCollected(StringUtils.hasText(type) || StringUtils.hasText(pref)
                    || StringUtils.hasText(text(diet, "habit")));
        }
        return illness;
    }

    // ---------------------------------------------------------------- 病种档案

    private void loadDiseaseArchives(
            AssessmentContext.AssessmentContextBuilder b,
            String tenantId,
            String peopleId,
            Set<String> illness,
            boolean seen14d) {
        boolean dm = false;
        boolean htn = false;
        boolean obesity = false;
        boolean endStage = false;
        boolean hypoFallback = false;
        List<PeopleDiseaseArchive> rows = diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId);
        for (PeopleDiseaseArchive row : rows == null ? List.<PeopleDiseaseArchive>of() : rows) {
            String code = row.getDiseaseCode() == null ? "" : row.getDiseaseCode().toLowerCase(Locale.ROOT);
            JsonNode content = StringUtils.hasText(row.getContentJson()) ? JsonUtils.readTree(row.getContentJson()) : null;
            switch (code) {
                case "diabetes" -> {
                    dm = true;
                    if (content != null) {
                        endStage = content.path("endStageChronicDisease").asBoolean(false)
                                || content.path("endStageChronic").asBoolean(false);
                        int month = content.path("hypoglycemiaCountLastMonth").asInt(0);
                        hypoFallback = !seen14d
                                && (month >= 2 || "FREQUENT".equalsIgnoreCase(text(content, "hypoglycemiaReaction")));
                    }
                }
                case "hypertension" -> {
                    htn = true;
                    if (content != null) {
                        b.archiveHighestSbp(number(content, "highestSystolic"))
                                .archiveHighestDbp(number(content, "highestDiastolic"))
                                .archiveCvRisk(text(content, "cvRiskStratification"));
                    }
                }
                case "obesity" -> obesity = true;
                default -> {
                    /* 其它病种不参与 */
                }
            }
        }
        b.hasDiabetesArchive(dm).hasHypertensionArchive(htn).hasObesityArchive(obesity);

        List<String> comorbidity = new ArrayList<>();
        for (String c : illness) {
            if (COMORBIDITY_CODES.contains(c)) {
                String cat = "OSTEOARTHRITIS".equals(c) || "RHEUMATOID_ARTHRITIS".equals(c) ? "ARTHRITIS" : c;
                if (!comorbidity.contains(cat)) {
                    comorbidity.add(cat);
                }
            }
        }
        if (htn && !comorbidity.contains("HYPERTENSION")) {
            comorbidity.add("HYPERTENSION");
        }
        b.diabetesComorbidityHits(comorbidity).diabetesEndStageChronic(endStage).hypoArchiveFallbackHit(hypoFallback);
    }

    // ---------------------------------------------------------------- 用药 / 事实

    private void loadMedications(AssessmentContext.AssessmentContextBuilder b, String tenantId, String peopleId) {
        Set<String> cats = new LinkedHashSet<>();
        List<PeopleMedication> meds = medicationMapper.listByPeople(tenantId, peopleId, "ACTIVE");
        for (PeopleMedication m : meds == null ? List.<PeopleMedication>of() : meds) {
            String cat = null;
            if (StringUtils.hasText(m.getDrugCode())) {
                DrugCatalogItemDto item = drugCatalogService.findByCode(m.getDrugCode());
                cat = item == null ? null : item.getCategory();
            }
            if (!StringUtils.hasText(cat)) {
                cat = guessCategory(m.getDrugName());
            }
            if (StringUtils.hasText(cat)) {
                cats.add(cat.trim().toUpperCase(Locale.ROOT));
            }
        }
        b.activeDrugCategories(Set.copyOf(cats));
    }

    /** 无目录编码的自录药品按通用名后缀粗分。 */
    static String guessCategory(String name) {
        if (!StringUtils.hasText(name)) {
            return null;
        }
        if (name.contains("他汀") || name.contains("贝特") || name.contains("依折麦布")) {
            return "LIPID";
        }
        if (name.contains("沙坦") || name.contains("普利") || name.contains("地平") || name.contains("洛尔")
                || name.contains("噻嗪") || name.contains("吲达帕胺")) {
            return "BP";
        }
        if (name.contains("二甲双胍") || name.contains("格列") || name.contains("胰岛素") || name.contains("列汀")
                || name.contains("阿卡波糖") || name.contains("鲁肽") || name.contains("格列净")) {
            return "DM";
        }
        return null;
    }

    private void loadFacts(AssessmentContext.AssessmentContextBuilder b, String tenantId, String peopleId) {
        Map<InputField, FactValue> facts = new EnumMap<>(InputField.class);
        for (PeopleAssessmentFact row : storeMapper.listFacts(tenantId, peopleId)) {
            InputField f = InputField.parse(row.getFieldCode());
            if (f == null || !StringUtils.hasText(row.getValueJson())) {
                continue;
            }
            JsonNode node = JsonUtils.readTree(row.getValueJson()).get("value");
            facts.put(f, new FactValue(toJava(node), row.getSource(), row.getRecordedAt()));
        }
        b.facts(facts);
    }

    static Object toJava(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            List<String> out = new ArrayList<>();
            node.forEach(n -> out.add(n.asText()));
            return out;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        return node.asText();
    }

    // ---------------------------------------------------------------- 工具

    static String normalizeGender(String g) {
        if (!StringUtils.hasText(g)) {
            return null;
        }
        String s = g.trim().toUpperCase(Locale.ROOT);
        if ("MALE".equals(s) || "M".equals(s) || "男".equals(g.trim())) {
            return "MALE";
        }
        if ("FEMALE".equals(s) || "F".equals(s) || "女".equals(g.trim())) {
            return "FEMALE";
        }
        return null;
    }

    private static boolean looksLikePrediabetes(String raw) {
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        String s = raw.toLowerCase(Locale.ROOT);
        return s.contains("糖尿病前期") || s.contains("糖耐量") || s.contains("空腹血糖受损")
                || s.contains("ifg") || s.contains("igt") || s.contains("prediabetes");
    }

    private static BigDecimal number(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        try {
            return StringUtils.hasText(v.asText()) ? new BigDecimal(v.asText().trim()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() || !StringUtils.hasText(v.asText()) ? null : v.asText();
    }
}
