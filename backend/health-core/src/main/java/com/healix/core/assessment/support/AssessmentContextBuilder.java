package com.healix.core.assessment.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.DiseaseArchiveService;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class AssessmentContextBuilder {

    private static final String DISEASE_DIABETES = "diabetes";
    private static final String DISEASE_HYPERTENSION = "hypertension";
    private static final String DISEASE_OBESITY = "obesity";
    private static final String PI_DIABETES = "DIABETES";
    private static final String PI_HYPERTENSION = "HYPERTENSION";
    private static final String PI_OBESITY = "OBESITY";
    private static final String FH_DIABETES = "FH_DIABETES";
    private static final String FH_HYPERTENSION = "FH_HYPERTENSION";
    private static final String FH_EARLY_MI = "FH_EARLY_MI";
    private static final Set<String> FH_ASCVD = Set.of("FH_CHD", "FH_EARLY_MI", "FH_STROKE");
    private static final List<String> LIPID_CODES = List.of("HDL_C", "TG", "TC", "LDL_C", "FPG", "UA", "HBA1C");
    private static final BigDecimal HYPO_THRESHOLD = new BigDecimal("4.0");
    private static final Set<String> COMORBIDITY_CODES = Set.of(
            "OSTEOARTHRITIS",
            "RHEUMATOID_ARTHRITIS",
            "MALIGNANT_TUMOR",
            "HEART_FAILURE",
            "DEPRESSION",
            "COPD",
            "HYPERTENSION",
            "CKD",
            "CHD",
            "STROKE");

    private final PeopleProfileMapper peopleProfileMapper;
    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final DiseaseArchiveService diseaseArchiveService;
    private final MetricService metricService;
    private final LabResultItemMapper labResultItemMapper;
    private final VitalRecordMapper vitalRecordMapper;

    public AssessmentContext build(String tenantId, String peopleId) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        String gender = profile == null ? null : profile.getGender();
        LocalDate birthday = profile == null ? null : profile.getBirthday();
        Integer age = birthday == null ? null : Period.between(birthday, LocalDate.now()).getYears();

        MetricPick height = pickMetric(tenantId, peopleId, MetricTypeEnum.HEIGHT);
        MetricPick weight = pickMetric(tenantId, peopleId, MetricTypeEnum.WEIGHT);
        MetricPick waist = pickMetric(tenantId, peopleId, MetricTypeEnum.WAIST);
        MetricPick sbp = pickMetric(tenantId, peopleId, MetricTypeEnum.BLOOD_PRESSURE_SYS);
        MetricPick dbp = pickMetric(tenantId, peopleId, MetricTypeEnum.BLOOD_PRESSURE_DIA);
        MetricPick heartRate = pickMetric(tenantId, peopleId, MetricTypeEnum.HEART_RATE);
        GlucosePick glucose = pickGlucose(tenantId, peopleId);

        BigDecimal bmi = null;
        if (height.value != null
                && weight.value != null
                && height.value.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal meters = height.value.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
            bmi = weight.value.divide(meters.multiply(meters), 2, RoundingMode.HALF_UP);
        }

        FamilyHistoryFh fh = resolveFamilyHistory(tenantId, peopleId);
        ArchiveExtras extras = resolveArchiveExtras(tenantId, peopleId);
        PresentIllnessFlags presentIllness = resolvePresentIllness(tenantId, peopleId);
        LipidPick lipids = resolveLipids(tenantId, peopleId);
        DiseaseFlags diseases = resolveDiseases(tenantId, peopleId);
        DiabetesControlExtras dmControl =
                resolveDiabetesControlExtras(tenantId, peopleId, diseases, presentIllness);

        BigDecimal fastingGlucose = glucose.fasting;
        LocalDateTime fastingAt = glucose.fastingAt;
        if (fastingGlucose == null && lipids.fpg != null) {
            fastingGlucose = lipids.fpg;
            fastingAt = lipids.fpgAt;
        }

        LocalDateTime asOf = LocalDateTime.now();
        LocalDateTime windowStart = asOf.minusDays(90);

        return AssessmentContext.builder()
                .tenantId(tenantId)
                .peopleId(peopleId)
                .gender(gender)
                .birthday(birthday)
                .ageYears(age)
                .heightCm(height.value)
                .heightRecordedAt(height.at)
                .weightKg(weight.value)
                .weightRecordedAt(weight.at)
                .waistCm(waist.value)
                .waistRecordedAt(waist.at)
                .sbp(sbp.value)
                .sbpRecordedAt(sbp.at)
                .dbp(dbp.value)
                .dbpRecordedAt(dbp.at)
                .bmi(bmi)
                .heartRate(heartRate.value)
                .heartRateRecordedAt(heartRate.at)
                .familyHistoryCollected(fh.collected)
                .firstDegreeDiabetesFamilyHistory(fh.firstDegreeDiabetes)
                .firstDegreeHypertensionFamilyHistory(fh.firstDegreeHypertension)
                .firstDegreeAscvdFamilyHistory(fh.firstDegreeAscvd)
                .earlyAscvdFamilyHistory(fh.earlyAscvd)
                .hasDiabetesDiseaseArchive(diseases.diabetes)
                .hasHypertensionDiseaseArchive(diseases.hypertension)
                .hasObesityDiseaseArchive(diseases.obesity)
                .hasDiabetesPresentIllness(presentIllness.diabetes)
                .hasHypertensionPresentIllness(presentIllness.hypertension)
                .hasObesityPresentIllness(presentIllness.obesity)
                .pastHistoryCollected(extras.pastHistoryCollected)
                .prediabetesHistory(extras.prediabetes)
                .exerciseCollected(extras.exerciseCollected)
                .exerciseFrequency(extras.exerciseFrequency)
                .smokingCollected(extras.smokingCollected)
                .smokingStatus(extras.smokingStatus)
                .drinkingCollected(extras.drinkingCollected)
                .drinkingStatus(extras.drinkingStatus)
                .drinkingFrequency(extras.drinkingFrequency)
                .drinkingAmountPerDay(extras.drinkingAmountPerDay)
                .dietCollected(extras.dietCollected)
                .dietType(extras.dietType)
                .dietPreference(extras.dietPreference)
                .hdlC(lipids.hdl)
                .hdlUnit(lipids.hdlUnit)
                .hdlRecordedAt(lipids.hdlAt)
                .tg(lipids.tg)
                .tgUnit(lipids.tgUnit)
                .tgRecordedAt(lipids.tgAt)
                .tc(lipids.tc)
                .tcUnit(lipids.tcUnit)
                .tcRecordedAt(lipids.tcAt)
                .ldlC(lipids.ldl)
                .ldlUnit(lipids.ldlUnit)
                .ldlRecordedAt(lipids.ldlAt)
                .fastingGlucose(fastingGlucose)
                .fastingGlucoseRecordedAt(fastingAt)
                .postprandialGlucose(glucose.postprandial)
                .postprandialGlucoseRecordedAt(glucose.postprandialAt)
                .uricAcid(lipids.ua)
                .uricAcidUnit(lipids.uaUnit)
                .uricAcidRecordedAt(lipids.uaAt)
                .hba1c(lipids.hba1c)
                .hba1cRecordedAt(lipids.hba1cAt)
                .controlLabelAsOf(asOf)
                .controlLabelWindowStart(windowStart)
                .hypoEvents14d(dmControl.hypoEvents14d)
                .hypoArchiveFallbackHit(dmControl.hypoArchiveFallbackHit)
                .diabetesComorbidityHits(dmControl.comorbidityHits)
                .diabetesComorbidityCount(dmControl.comorbidityHits.size())
                .diabetesEndStageChronic(dmControl.endStageChronic)
                .build();
    }

    private MetricPick pickMetric(String tenantId, String peopleId, MetricTypeEnum type) {
        BigDecimal best = null;
        LocalDateTime bestAt = null;
        for (MetricLatestSlotDto slot : metricService.latestForPatient(tenantId, peopleId)) {
            if (!type.matches(slot.metricType()) || slot.value() == null) {
                continue;
            }
            if (bestAt == null || (slot.recordedAt() != null && slot.recordedAt().isAfter(bestAt))) {
                best = slot.value();
                bestAt = slot.recordedAt();
            }
        }
        return new MetricPick(best, bestAt);
    }

    private GlucosePick pickGlucose(String tenantId, String peopleId) {
        BigDecimal fasting = null;
        LocalDateTime fastingAt = null;
        BigDecimal post = null;
        LocalDateTime postAt = null;
        for (MetricLatestSlotDto slot : metricService.latestForPatient(tenantId, peopleId)) {
            if (!MetricTypeEnum.BLOOD_GLUCOSE.matches(slot.metricType()) || slot.value() == null) {
                continue;
            }
            String meal = slot.mealContext() == null ? "" : slot.mealContext().toUpperCase(Locale.ROOT);
            if ("FASTING".equals(meal)) {
                if (fastingAt == null || (slot.recordedAt() != null && slot.recordedAt().isAfter(fastingAt))) {
                    fasting = slot.value();
                    fastingAt = slot.recordedAt();
                }
            } else if ("POSTPRANDIAL".equals(meal)) {
                if (postAt == null || (slot.recordedAt() != null && slot.recordedAt().isAfter(postAt))) {
                    post = slot.value();
                    postAt = slot.recordedAt();
                }
            }
        }
        return new GlucosePick(fasting, fastingAt, post, postAt);
    }

    private DiseaseFlags resolveDiseases(String tenantId, String peopleId) {
        boolean diabetes = false;
        boolean hypertension = false;
        boolean obesity = false;
        for (DiseaseArchiveViewDto row : diseaseArchiveService.list(tenantId, peopleId)) {
            if (DISEASE_DIABETES.equalsIgnoreCase(row.getDiseaseCode())) {
                diabetes = true;
            }
            if (DISEASE_HYPERTENSION.equalsIgnoreCase(row.getDiseaseCode())) {
                hypertension = true;
            }
            if (DISEASE_OBESITY.equalsIgnoreCase(row.getDiseaseCode())) {
                obesity = true;
            }
        }
        return new DiseaseFlags(diabetes, hypertension, obesity);
    }

    private PresentIllnessFlags resolvePresentIllness(String tenantId, String peopleId) {
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive == null || !StringUtils.hasText(archive.getContentJson())) {
            return new PresentIllnessFlags(false, false, false, List.of());
        }
        JsonNode root = JsonUtils.readTree(archive.getContentJson());
        JsonNode items = root.get("presentIllness");
        boolean diabetes = false;
        boolean hypertension = false;
        boolean obesity = false;
        List<String> codes = new ArrayList<>();
        if (items != null && items.isArray()) {
            for (JsonNode n : items) {
                if (n == null || n.isNull()) {
                    continue;
                }
                String code = n.asText();
                if (!StringUtils.hasText(code)) {
                    continue;
                }
                String c = code.trim().toUpperCase(Locale.ROOT);
                codes.add(c);
                if (PI_DIABETES.equals(c)) {
                    diabetes = true;
                } else if (PI_HYPERTENSION.equals(c)) {
                    hypertension = true;
                } else if (PI_OBESITY.equals(c)) {
                    obesity = true;
                }
            }
        }
        // 兼容其它文本字段误写
        String other = text(root, "presentIllnessOther");
        if (StringUtils.hasText(other)) {
            String s = other.toLowerCase(Locale.ROOT);
            if (s.contains("糖尿病") || s.contains("diabetes")) {
                diabetes = true;
            }
            if (s.contains("高血压") || s.contains("hypertension")) {
                hypertension = true;
            }
            if (s.contains("肥胖") || s.contains("obesity")) {
                obesity = true;
            }
        }
        return new PresentIllnessFlags(diabetes, hypertension, obesity, codes);
    }

    private DiabetesControlExtras resolveDiabetesControlExtras(
            String tenantId,
            String peopleId,
            DiseaseFlags diseases,
            PresentIllnessFlags presentIllness) {
        Set<String> hits = new LinkedHashSet<>();
        for (String code : presentIllness.codes) {
            String category = comorbidityCategory(code);
            if (category != null) {
                hits.add(category);
            }
        }
        if (diseases.hypertension) {
            hits.add("HYPERTENSION");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime from14 = now.minusDays(14);
        List<VitalRecord> glucoseRows = vitalRecordMapper.listByRange(
                tenantId, peopleId, MetricTypeEnum.BLOOD_GLUCOSE.name(), from14, now);
        int hypoCount = 0;
        boolean anyGlucose14d = false;
        for (VitalRecord row : glucoseRows) {
            if (row == null || row.getValue() == null) {
                continue;
            }
            anyGlucose14d = true;
            if (row.getValue().compareTo(HYPO_THRESHOLD) < 0) {
                hypoCount++;
            }
        }

        boolean endStage = false;
        boolean archiveHypoFallback = false;
        for (DiseaseArchiveViewDto row : diseaseArchiveService.list(tenantId, peopleId)) {
            if (!DISEASE_DIABETES.equalsIgnoreCase(row.getDiseaseCode()) || row.getContentJson() == null) {
                continue;
            }
            JsonNode content = contentNode(row.getContentJson());
            if (content == null) {
                continue;
            }
            if (content.path("endStageChronicDisease").asBoolean(false)
                    || content.path("endStageChronic").asBoolean(false)) {
                endStage = true;
            }
            if (!anyGlucose14d) {
                int monthCount = content.path("hypoglycemiaCountLastMonth").asInt(0);
                String reaction = text(content, "hypoglycemiaReaction");
                if (monthCount >= 2 || "FREQUENT".equalsIgnoreCase(reaction)) {
                    archiveHypoFallback = true;
                }
            }
        }

        return new DiabetesControlExtras(hypoCount, archiveHypoFallback, new ArrayList<>(hits), endStage);
    }

    private static String comorbidityCategory(String code) {
        if (!StringUtils.hasText(code) || !COMORBIDITY_CODES.contains(code)) {
            return null;
        }
        if ("OSTEOARTHRITIS".equals(code) || "RHEUMATOID_ARTHRITIS".equals(code)) {
            return "ARTHRITIS";
        }
        return code;
    }

    private static JsonNode contentNode(Object contentJson) {
        if (contentJson == null) {
            return null;
        }
        if (contentJson instanceof JsonNode node) {
            return node;
        }
        if (contentJson instanceof String s) {
            return StringUtils.hasText(s) ? JsonUtils.readTree(s) : null;
        }
        return JsonUtils.readTree(JsonUtils.toJson(contentJson));
    }

    private LipidPick resolveLipids(String tenantId, String peopleId) {
        List<LabLatestNumeric> rows =
                labResultItemMapper.listLatestNumericByPeople(tenantId, peopleId, LIPID_CODES);
        BigDecimal hdl = null;
        String hdlUnit = null;
        LocalDateTime hdlAt = null;
        BigDecimal tg = null;
        String tgUnit = null;
        LocalDateTime tgAt = null;
        BigDecimal tc = null;
        String tcUnit = null;
        LocalDateTime tcAt = null;
        BigDecimal ldl = null;
        String ldlUnit = null;
        LocalDateTime ldlAt = null;
        BigDecimal fpg = null;
        LocalDateTime fpgAt = null;
        BigDecimal ua = null;
        String uaUnit = null;
        LocalDateTime uaAt = null;
        BigDecimal hba1c = null;
        LocalDateTime hba1cAt = null;
        Set<String> seen = new HashSet<>();
        for (LabLatestNumeric row : rows) {
            if (row == null || !StringUtils.hasText(row.getItemCode()) || row.getValueNum() == null) {
                continue;
            }
            String code = row.getItemCode();
            if (!seen.add(code)) {
                continue;
            }
            switch (code) {
                case "HDL_C" -> {
                    hdl = row.getValueNum();
                    hdlUnit = row.getUnit();
                    hdlAt = row.getRecordedAt();
                }
                case "TG" -> {
                    tg = row.getValueNum();
                    tgUnit = row.getUnit();
                    tgAt = row.getRecordedAt();
                }
                case "TC" -> {
                    tc = row.getValueNum();
                    tcUnit = row.getUnit();
                    tcAt = row.getRecordedAt();
                }
                case "LDL_C" -> {
                    ldl = row.getValueNum();
                    ldlUnit = row.getUnit();
                    ldlAt = row.getRecordedAt();
                }
                case "FPG" -> {
                    fpg = row.getValueNum();
                    fpgAt = row.getRecordedAt();
                }
                case "UA" -> {
                    ua = row.getValueNum();
                    uaUnit = row.getUnit();
                    uaAt = row.getRecordedAt();
                }
                case "HBA1C" -> {
                    hba1c = row.getValueNum();
                    hba1cAt = row.getRecordedAt();
                }
                default -> {
                    /* ignore */
                }
            }
        }
        return new LipidPick(
                hdl,
                hdlUnit,
                hdlAt,
                tg,
                tgUnit,
                tgAt,
                tc,
                tcUnit,
                tcAt,
                ldl,
                ldlUnit,
                ldlAt,
                fpg,
                fpgAt,
                ua,
                uaUnit,
                uaAt,
                hba1c,
                hba1cAt);
    }

    private ArchiveExtras resolveArchiveExtras(String tenantId, String peopleId) {
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive == null || !StringUtils.hasText(archive.getContentJson())) {
            return ArchiveExtras.empty();
        }
        JsonNode root = JsonUtils.readTree(archive.getContentJson());

        String exerciseFreq = text(root.path("exercise"), "frequency");
        boolean exerciseCollected = StringUtils.hasText(exerciseFreq);

        JsonNode lifestyle = root.get("lifestyle");
        String smokingStatus = null;
        boolean smokingCollected = false;
        String drinkingStatus = null;
        String drinkingFrequency = null;
        String drinkingAmount = null;
        boolean drinkingCollected = false;
        if (lifestyle != null && !lifestyle.isNull()) {
            JsonNode smoking = lifestyle.get("smoking");
            if (smoking != null && smoking.isObject()) {
                smokingStatus = text(smoking, "status");
                smokingCollected = StringUtils.hasText(smokingStatus);
            } else if (smoking != null && smoking.isTextual()) {
                smokingStatus = smoking.asText();
                smokingCollected = StringUtils.hasText(smokingStatus);
            }
            JsonNode drinking = lifestyle.get("drinking");
            if (drinking != null && drinking.isObject()) {
                drinkingStatus = text(drinking, "status");
                drinkingFrequency = text(drinking, "frequency");
                drinkingAmount = text(drinking, "amountPerDay");
                drinkingCollected = StringUtils.hasText(drinkingStatus);
            } else if (drinking != null && drinking.isTextual()) {
                drinkingStatus = drinking.asText();
                drinkingCollected = StringUtils.hasText(drinkingStatus);
            }
        }

        JsonNode diet = root.get("diet");
        String dietType = null;
        String dietPreference = null;
        boolean dietCollected = false;
        if (diet != null && diet.isObject()) {
            dietType = text(diet, "type");
            dietPreference = text(diet, "preference");
            dietCollected = StringUtils.hasText(dietType)
                    || StringUtils.hasText(dietPreference)
                    || StringUtils.hasText(text(diet, "habit"))
                    || StringUtils.hasText(text(diet, "appetite"));
        }

        String pastStatus = text(root, "pastHistoryStatus");
        JsonNode pastItems = root.get("pastHistoryItems");
        boolean pastCollected = "none".equalsIgnoreCase(pastStatus)
                || "has".equalsIgnoreCase(pastStatus)
                || (pastItems != null && !pastItems.isNull());
        Boolean prediabetes = null;
        if (pastCollected) {
            prediabetes = false;
            if (pastItems != null && pastItems.isObject()) {
                JsonNode diseases = pastItems.get("diseases");
                if (diseases != null && diseases.isArray()) {
                    for (JsonNode item : diseases) {
                        if (looksLikePrediabetes(text(item, "name"))
                                || looksLikePrediabetes(text(item, "code"))) {
                            prediabetes = true;
                            break;
                        }
                    }
                }
            } else if (pastItems != null && pastItems.isArray()) {
                for (JsonNode item : pastItems) {
                    if (looksLikePrediabetes(text(item, "name")) || looksLikePrediabetes(text(item, "code"))) {
                        prediabetes = true;
                        break;
                    }
                }
            }
            if (!Boolean.TRUE.equals(prediabetes) && looksLikePrediabetes(text(root, "pastHistory"))) {
                prediabetes = true;
            }
        }

        return new ArchiveExtras(
                pastCollected,
                prediabetes,
                exerciseCollected,
                exerciseFreq,
                smokingCollected,
                smokingStatus,
                drinkingCollected,
                drinkingStatus,
                drinkingFrequency,
                drinkingAmount,
                dietCollected,
                dietType,
                dietPreference);
    }

    private FamilyHistoryFh resolveFamilyHistory(String tenantId, String peopleId) {
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive == null || !StringUtils.hasText(archive.getContentJson())) {
            return new FamilyHistoryFh(false, null, null, null, null);
        }
        JsonNode root = JsonUtils.readTree(archive.getContentJson());
        String status = text(root, "familyHistoryStatus");
        JsonNode items = root.get("familyHistoryItems");

        if ("none".equalsIgnoreCase(status)) {
            return new FamilyHistoryFh(true, false, false, false, false);
        }
        if (items != null && items.isArray() && items.isEmpty() && !"has".equalsIgnoreCase(status)) {
            return new FamilyHistoryFh(true, false, false, false, false);
        }

        if (!"has".equalsIgnoreCase(status) && (items == null || !items.isArray())) {
            return new FamilyHistoryFh(false, null, null, null, null);
        }
        if (items == null || !items.isArray()) {
            return new FamilyHistoryFh(true, false, false, false, false);
        }

        boolean dm = false;
        boolean htn = false;
        boolean ascvd = false;
        boolean earlyAscvd = false;
        for (JsonNode item : items) {
            String level = text(item, "kinshipLevel");
            if (!"1".equals(level)) {
                continue;
            }
            JsonNode diseases = item.get("diseases");
            if (diseases == null || !diseases.isArray()) {
                continue;
            }
            for (JsonNode d : diseases) {
                String code = d.asText();
                if (FH_DIABETES.equals(code)) {
                    dm = true;
                }
                if (FH_HYPERTENSION.equals(code)) {
                    htn = true;
                }
                if (FH_ASCVD.contains(code)) {
                    ascvd = true;
                }
                if (FH_EARLY_MI.equals(code)) {
                    earlyAscvd = true;
                }
            }
        }
        return new FamilyHistoryFh(true, dm, htn, ascvd, earlyAscvd);
    }

    private static boolean looksLikePrediabetes(String raw) {
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        String s = raw.toLowerCase(Locale.ROOT);
        return s.contains("糖尿病前期")
                || s.contains("糖耐量")
                || s.contains("空腹血糖受损")
                || s.contains("ifg")
                || s.contains("igt")
                || s.contains("igr")
                || s.contains("prediabetes");
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private record MetricPick(BigDecimal value, LocalDateTime at) {}

    private record GlucosePick(
            BigDecimal fasting, LocalDateTime fastingAt, BigDecimal postprandial, LocalDateTime postprandialAt) {}

    private record FamilyHistoryFh(
            boolean collected,
            Boolean firstDegreeDiabetes,
            Boolean firstDegreeHypertension,
            Boolean firstDegreeAscvd,
            Boolean earlyAscvd) {}

    private record DiseaseFlags(boolean diabetes, boolean hypertension, boolean obesity) {}

    private record PresentIllnessFlags(
            boolean diabetes, boolean hypertension, boolean obesity, List<String> codes) {}

    private record DiabetesControlExtras(
            int hypoEvents14d,
            boolean hypoArchiveFallbackHit,
            List<String> comorbidityHits,
            boolean endStageChronic) {}

    private record LipidPick(
            BigDecimal hdl,
            String hdlUnit,
            LocalDateTime hdlAt,
            BigDecimal tg,
            String tgUnit,
            LocalDateTime tgAt,
            BigDecimal tc,
            String tcUnit,
            LocalDateTime tcAt,
            BigDecimal ldl,
            String ldlUnit,
            LocalDateTime ldlAt,
            BigDecimal fpg,
            LocalDateTime fpgAt,
            BigDecimal ua,
            String uaUnit,
            LocalDateTime uaAt,
            BigDecimal hba1c,
            LocalDateTime hba1cAt) {}

    private record ArchiveExtras(
            boolean pastHistoryCollected,
            Boolean prediabetes,
            boolean exerciseCollected,
            String exerciseFrequency,
            boolean smokingCollected,
            String smokingStatus,
            boolean drinkingCollected,
            String drinkingStatus,
            String drinkingFrequency,
            String drinkingAmountPerDay,
            boolean dietCollected,
            String dietType,
            String dietPreference) {
        static ArchiveExtras empty() {
            return new ArchiveExtras(
                    false, null, false, null, false, null, false, null, null, null, false, null, null);
        }
    }
}
