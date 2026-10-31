package com.healix.core.careplan.support;

import com.healix.core.assessment.service.AssessmentPromptContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.careplan.guideline.GuidelineFacts;
import com.healix.core.careplan.guideline.GuidelineMatch;
import com.healix.core.careplan.guideline.GuidelineMatcher;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.water.support.WaterIntakeRules;
import java.time.Period;
import lombok.extern.slf4j.Slf4j;
import com.healix.core.allergy.service.AllergyQueryService;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.enums.MedicationStatusEnum;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.service.MedicationService;
import com.healix.core.observation.domain.LabReport;
import com.healix.core.observation.domain.LabResultItem;
import com.healix.core.observation.mapper.LabReportMapper;
import com.healix.core.observation.mapper.LabResultItemMapper;
import com.healix.core.goal.domain.PeopleHealthGoal;
import com.healix.core.goal.mapper.PeopleHealthGoalMapper;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import com.healix.core.people.support.DiseaseCodeLabels;
import com.healix.core.questionnaire.domain.QuestionnaireResponse;
import com.healix.core.questionnaire.mapper.QuestionnaireResponseMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class CarePlanContextService {

    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final AllergyQueryService allergyQueryService;
    private final VitalRecordMapper vitalRecordMapper;
    private final PeopleMedicationMapper medicationMapper;
    private final LabReportMapper labReportMapper;
    private final LabResultItemMapper labResultItemMapper;
    private final CarePlanEnergyTargetService energyTargetService;
    private final AssessmentPromptContext assessmentPromptContext;
    private final PeopleProfileMapper peopleProfileMapper;
    private final GuidelineMatcher guidelineMatcher;
    private final PeopleHealthGoalMapper healthGoalMapper;
    private final QuestionnaireResponseMapper questionnaireResponseMapper;

    /** @param guidelines 本次适用的指南条目；条目库未上线时为 {@link GuidelineMatch#DISABLED} */
    public record CarePlanContext(
            List<String> diseaseCodes,
            List<FoodAllergen> foodAllergens,
            AllergyStatusEnum allergyStatus,
            List<String> activeMedications,
            List<MetricBrief> latestMetrics,
            List<LabBrief> recentLabs,
            boolean contextIncomplete,
            boolean hypoglycemiaRisk,
            ObjectNode snapshot,
            GuidelineMatch guidelines) {}

    public record MetricBrief(String metricType, String value, String unit, String slotKey) {}

    public record LabBrief(String reportId, String specimenType, String sampledAt, List<String> itemSummaries) {}

    public CarePlanContext load(String tenantId, String peopleId) {
        return load(tenantId, peopleId, null);
    }

    public CarePlanContext load(String tenantId, String peopleId, CarePlanLoadTracer tracer) {
        Set<String> diseases = new LinkedHashSet<>();
        boolean incomplete = false;
        boolean hypoRisk = false;
        ObjectNode snapshot = JsonUtils.emptyObject();

        trace(tracer, "loadCarePlanContext", "running", "Tool: loadCarePlanContext — 开始加载");
        trace(tracer, "PeopleBasicArchive", "running", "读取基础健康档案");
        PeopleBasicArchive basic = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (basic == null || !StringUtils.hasText(basic.getContentJson())) {
            incomplete = true;
            snapshot.put("basicArchive", "MISSING");
            trace(tracer, "PeopleBasicArchive", "done", "未找到基础档案");
        } else {
            JsonNode content = JsonUtils.readTree(basic.getContentJson());
            snapshot.set("basicArchive", CarePlanPromptContextSupport.summarizeBasicArchive(content));
            JsonNode present = content.get("presentIllness");
            if (present != null && present.isArray()) {
                for (JsonNode n : present) {
                    if (n != null && n.isTextual() && CarePlanPromptContextSupport.isMeaningfulDiseaseCode(n.asText())) {
                        diseases.add(n.asText());
                    }
                }
            } else {
                incomplete = true;
            }
            trace(tracer, "PeopleBasicArchive", "done", "已加载现病史与生活方式");
        }

        trace(tracer, "PeopleDiseaseArchive", "running", "读取病种专病档案");
        List<PeopleDiseaseArchive> diseaseArchives = diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId);
        ArrayNode diseaseSnap = snapshot.putArray("diseaseArchives");
        ArrayNode diseaseLabelSnap = snapshot.putArray("diseaseLabels");
        Set<String> labeled = new LinkedHashSet<>();
        if (diseaseArchives != null) {
            for (PeopleDiseaseArchive a : diseaseArchives) {
                if (a.getDiseaseCode() != null
                        && CarePlanPromptContextSupport.isMeaningfulDiseaseCode(a.getDiseaseCode())) {
                    String code = a.getDiseaseCode();
                    diseases.add(code.toUpperCase(Locale.ROOT));
                    ObjectNode row = diseaseSnap.addObject();
                    row.put("code", code);
                    String label = DiseaseCodeLabels.label(code);
                    row.put("label", label);
                    if (StringUtils.hasText(label) && labeled.add(label)) {
                        diseaseLabelSnap.add(label);
                    }
                }
                if ("diabetes".equalsIgnoreCase(a.getDiseaseCode()) && StringUtils.hasText(a.getContentJson())) {
                    JsonNode c = JsonUtils.readTree(a.getContentJson());
                    if (c.path("hypoglycemiaCountLastMonth").asInt(0) > 0
                            || "FREQUENT".equalsIgnoreCase(c.path("hypoglycemiaReaction").asText())) {
                        hypoRisk = true;
                    }
                    JsonNode emerg = c.get("emergencyComplications");
                    if (emerg != null && emerg.isArray()) {
                        for (JsonNode e : emerg) {
                            if (e != null && "HYPOGLYCEMIA".equalsIgnoreCase(e.asText())) {
                                hypoRisk = true;
                            }
                        }
                    }
                }
            }
        }
        // 现病史里的英文 code 也转成中文标签，避免 Agent 原文复述
        for (String raw : new ArrayList<>(diseases)) {
            if (!CarePlanPromptContextSupport.isMeaningfulDiseaseCode(raw)) {
                diseases.remove(raw);
                continue;
            }
            String label = DiseaseCodeLabels.label(raw);
            if (StringUtils.hasText(label) && labeled.add(label)) {
                diseaseLabelSnap.add(label);
            }
        }
        trace(
                tracer,
                "PeopleDiseaseArchive",
                "done",
                labeled.isEmpty() ? "无病种记录" : "病种: " + String.join("、", labeled));

        trace(tracer, "peopleAllergens", "running", "读取过敏史");
        // 方案饮食「过敏原回避」只放食物类；药物/环境过敏以描述形式进快照供模型参考
        List<FoodAllergen> allergens = allergyQueryService.foodAllergens(peopleId);
        List<String> allergyDescriptions = allergyQueryService.describeForContext(peopleId);
        AllergyStatusEnum allergyStatus = allergyQueryService.status(tenantId, peopleId);
        snapshot.put("allergyStatus", allergyStatus.label());
        ArrayNode allergenArr = snapshot.putArray("allergens");
        allergyDescriptions.forEach(allergenArr::add);
        trace(
                tracer,
                "peopleAllergens",
                "done",
                allergyDescriptions.isEmpty()
                        ? allergyStatus.label()
                        : "过敏史: " + String.join("、", allergyDescriptions));

        trace(tracer, "PeopleMedication", "running", "读取在用药");
        List<PeopleMedication> meds =
                medicationMapper.listByPeople(tenantId, peopleId, MedicationStatusEnum.ACTIVE.name());
        List<String> medNames = new ArrayList<>();
        ArrayNode medSnap = snapshot.putArray("activeMedications");
        LocalDate today = LocalDate.now();
        if (meds != null) {
            for (PeopleMedication m : meds) {
                if (MedicationService.isPastEndDate(m, today)) {
                    continue;
                }
                if (StringUtils.hasText(m.getDrugName())) {
                    medNames.add(m.getDrugName());
                    medSnap.add(m.getDrugName());
                }
            }
        }
        trace(tracer, "PeopleMedication", "done", medNames.isEmpty() ? "无在用药" : "在用药: " + String.join(", ", medNames));

        trace(tracer, "VitalRecord", "running", "读取最新体征指标");
        List<MetricBrief> metrics = loadLatestMetrics(tenantId, peopleId, snapshot);
        // 档案缺失才算上下文不完整；指标/用药缺失仅写入快照供模板参考
        if (metrics.isEmpty() && diseases.isEmpty()) {
            incomplete = true;
        }
        trace(tracer, "VitalRecord", "done", metrics.isEmpty() ? "无近期指标" : metrics.size() + " 项指标: " + summarizeMetrics(metrics));

        trace(tracer, "LabReport", "running", "读取近期检验报告");
        List<LabBrief> labs = loadRecentLabs(tenantId, peopleId, snapshot);
        trace(tracer, "LabReport", "done", labs.isEmpty() ? "无检验报告" : labs.size() + " 份报告");

        trace(tracer, "DietGoal", "running", "读取饮食热量目标");
        ObjectNode energyTarget = energyTargetService.promptContext(tenantId, peopleId);
        if (energyTarget != null) {
            snapshot.set("energyTarget", energyTarget);
        }
        trace(
                tracer,
                "DietGoal",
                "done",
                energyTarget != null && energyTarget.path("available").asBoolean()
                        ? "每日 " + energyTarget.path("energyKcal").asInt() + " 千卡"
                        : "暂无热量目标");

        trace(tracer, "Assessment", "running", "读取疾病评估结果");
        try {
            ArrayNode assessments = assessmentPromptContext.summarize(tenantId, peopleId);
            snapshot.set("assessments", assessments);
            trace(tracer, "Assessment", "done", assessments.isEmpty() ? "暂无评估结果" : assessments.size() + " 项评估");
        } catch (RuntimeException e) {
            trace(tracer, "Assessment", "done", "评估结果读取失败，已跳过");
        }

        snapshot.put("contextIncomplete", incomplete);
        snapshot.put("hypoglycemiaRisk", hypoRisk);

        ObjectNode goalsNode = loadGoals(tenantId, peopleId);
        if (goalsNode != null) {
            snapshot.set("goals", goalsNode);
            trace(tracer, "HealthGoal", "done", "目标模式 " + goalsNode.path("mode").asText("-"));
        }

        ObjectNode intakeBrief = loadLatestIntakeBrief(tenantId, peopleId);
        if (intakeBrief != null) {
            snapshot.set("intakeBrief", intakeBrief);
            JsonNode highlights = intakeBrief.get("highlights");
            if (highlights != null && highlights.isArray() && !highlights.isEmpty()) {
                snapshot.set("highlights", highlights);
                applyHighlightHints(snapshot, highlights, goalsNode);
                trace(tracer, "IntakeHighlights", "done", "高亮命中 " + highlights.size() + " 项");
            }
            String aiHint = intakeBrief.path("aiSummary").asText("");
            trace(
                    tracer,
                    "IntakeBrief",
                    "done",
                    StringUtils.hasText(aiHint)
                            ? "已附首诊 AI 总结 " + Math.min(aiHint.length(), 40) + " 字…"
                            : "已附首诊元数据（无 AI 总结）");
        }

        List<String> diseaseList = new ArrayList<>(diseases);
        GuidelineMatch guidelines = GuidelineMatch.DISABLED;
        try {
            guidelines = guidelineMatcher.match(guidelineFacts(tenantId, peopleId, diseaseList, hypoRisk, metrics, snapshot));
        } catch (RuntimeException e) {
            log.warn("[CarePlan] guideline match failed peopleId={}", peopleId, e);
        }
        if (guidelines.enabled()) {
            snapshot.set("guideline", guidelines.toSnapshot());
            trace(tracer, "GuidelineLibrary", "done", "适用指南条目 " + guidelines.matched().size() + " 条");
        }
        trace(tracer, "loadCarePlanContext", "done", "上下文加载完成");
        return new CarePlanContext(
                diseaseList,
                allergens,
                allergyStatus,
                medNames,
                metrics,
                labs,
                incomplete,
                hypoRisk,
                snapshot,
                guidelines);
    }

    private ObjectNode loadGoals(String tenantId, String peopleId) {
        try {
            PeopleHealthGoal goal = healthGoalMapper.findLatestByPeople(tenantId, peopleId);
            if (goal == null) {
                return null;
            }
            ObjectNode node = JsonUtils.emptyObject();
            if (StringUtils.hasText(goal.getGoalMode())) {
                node.put("mode", goal.getGoalMode());
                node.put("phase", goal.getGoalMode());
            }
            if (StringUtils.hasText(goal.getPrimaryAppeal())) {
                node.put("primaryAppeal", goal.getPrimaryAppeal());
            }
            if (StringUtils.hasText(goal.getAppealText())) {
                node.put("appealText", goal.getAppealText());
            }
            if (goal.getDoctorAdvised() != null) {
                node.put("doctorAdvised", goal.getDoctorAdvised() == 1);
            }
            if (StringUtils.hasText(goal.getGoalsJson())) {
                JsonNode rawGoals = JsonUtils.readTree(goal.getGoalsJson());
                if (rawGoals != null && rawGoals.isArray()) {
                    node.set("items", rawGoals);
                } else if (rawGoals != null && rawGoals.isObject()) {
                    ArrayNode items = JsonUtils.mapper().createArrayNode();
                    rawGoals.fields().forEachRemaining(e -> {
                        if (e.getValue() != null && e.getValue().isObject()) {
                            ObjectNode item = e.getValue().deepCopy();
                            if (!item.has("metric")) {
                                item.put("metric", e.getKey());
                            }
                            items.add(item);
                        }
                    });
                    node.set("items", items);
                }
            }
            if (StringUtils.hasText(goal.getPreferencesJson())) {
                node.set("preferences", JsonUtils.readTree(goal.getPreferencesJson()));
            }
            return node;
        } catch (RuntimeException e) {
            log.warn("[CarePlan] load goals failed peopleId={}", peopleId, e);
            return null;
        }
    }

    private static final String FIELD_STAFF_AI_SUMMARY = "staff.aiSummary";
    private static final int AI_SUMMARY_MAX_CHARS = 2000;

    /**
     * 最近一次已提交首诊/待补：元数据 + AI 案头总结 + 高亮（不塞全量 answers）。
     */
    private ObjectNode loadLatestIntakeBrief(String tenantId, String peopleId) {
        try {
            List<QuestionnaireResponse> rows = questionnaireResponseMapper.listByPeople(tenantId, peopleId);
            for (QuestionnaireResponse r : rows) {
                if (!"SUBMITTED".equals(r.getStatus())) {
                    continue;
                }
                if (!"INTAKE".equals(r.getPurpose()) && !"SUPPLEMENT".equals(r.getPurpose())) {
                    continue;
                }
                ObjectNode brief = JsonUtils.emptyObject();
                brief.put("responseId", r.getId());
                brief.put("purpose", r.getPurpose());
                if (r.getSubmittedAt() != null) {
                    brief.put("submittedAt", r.getSubmittedAt().toString());
                }
                if (StringUtils.hasText(r.getQuestionnaireCode())) {
                    brief.put("questionnaireCode", r.getQuestionnaireCode());
                }
                String aiSummary = extractAiSummary(r.getAnswersJson());
                if (StringUtils.hasText(aiSummary)) {
                    brief.put("aiSummary", aiSummary);
                }
                if (StringUtils.hasText(r.getHighlightsJson())) {
                    JsonNode highlights = JsonUtils.readTree(r.getHighlightsJson());
                    if (highlights != null && highlights.isArray() && !highlights.isEmpty()) {
                        brief.set("highlights", highlights);
                    }
                }
                return brief;
            }
        } catch (RuntimeException e) {
            log.warn("[CarePlan] load intakeBrief failed peopleId={}", peopleId, e);
        }
        return null;
    }

    private static String extractAiSummary(String answersJson) {
        if (!StringUtils.hasText(answersJson)) {
            return null;
        }
        JsonNode answers = JsonUtils.readTree(answersJson);
        if (answers == null || !answers.isObject()) {
            return null;
        }
        JsonNode v = answers.get(FIELD_STAFF_AI_SUMMARY);
        if (v == null || v.isNull()) {
            return null;
        }
        String text = v.isTextual() ? v.asText() : v.toString();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String t = text.trim();
        if (t.length() > AI_SUMMARY_MAX_CHARS) {
            return t.substring(0, AI_SUMMARY_MAX_CHARS) + "…";
        }
        return t;
    }

    /** 高亮 → 称重频次等生成提示；BLOCK 时强制目标模式不可为 LOSS。 */
    private static void applyHighlightHints(ObjectNode snapshot, JsonNode highlights, ObjectNode goalsNode) {
        boolean eatingDisorder = false;
        boolean bodyAnxiety = false;
        boolean blockLoss = false;
        for (JsonNode h : highlights) {
            String sev = h.path("severity").asText("");
            String field = h.path("field").asText("");
            if ("BLOCK".equals(sev)) {
                blockLoss = true;
            }
            if (field.contains("eatingDisorder")) {
                eatingDisorder = true;
            }
            if (field.contains("bodyAnxiety") || field.contains("bodySatisfaction")) {
                bodyAnxiety = true;
            }
        }
        ObjectNode goals = goalsNode != null ? goalsNode : JsonUtils.emptyObject();
        if (eatingDisorder || bodyAnxiety) {
            goals.put("weighFrequency", "WEEKLY");
            goals.put("pace", "进食障碍/身材焦虑命中：称重改为每周，节奏更保守");
        } else if (!goals.has("weighFrequency")) {
            goals.put("weighFrequency", "DAILY");
        }
        if (blockLoss) {
            goals.put("lossBlocked", true);
            if ("LOSS".equals(goals.path("mode").asText())) {
                goals.put("mode", "MAINTAIN");
                goals.put("phase", "MAINTAIN");
            }
        }
        snapshot.set("goals", goals);
    }

    private GuidelineFacts guidelineFacts(
            String tenantId,
            String peopleId,
            List<String> diseaseCodes,
            boolean hypoRisk,
            List<MetricBrief> metrics,
            ObjectNode snapshot) {
        Set<String> tags = new LinkedHashSet<>(CarePlanDiseaseTags.detect(diseaseCodes).tags());
        Integer age = null;
        String sex = null;
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile != null) {
            if (profile.getBirthday() != null) {
                age = Period.between(profile.getBirthday(), LocalDate.now()).getYears();
            }
            sex = normalizeSex(profile.getGender());
        }
        Set<String> highlightFields = new LinkedHashSet<>();
        boolean pregnantOrLactating = false;
        boolean eatingDisorderRisk = false;
        boolean exerciseRestricted = false;
        JsonNode highlights = snapshot.get("highlights");
        if (highlights != null && highlights.isArray()) {
            for (JsonNode h : highlights) {
                String field = h.path("field").asText("");
                if (StringUtils.hasText(field)) {
                    highlightFields.add(field);
                }
                String sev = h.path("severity").asText("");
                if ("basic.women.reproductiveStatus".equals(field) && ("BLOCK".equals(sev) || "CAUTION".equals(sev))) {
                    pregnantOrLactating = "BLOCK".equals(sev) || pregnantOrLactating;
                }
                if (field.contains("eatingDisorder") && ("BLOCK".equals(sev) || "REFER".equals(sev) || "CAUTION".equals(sev))) {
                    eatingDisorderRisk = true;
                }
                if (field.contains("exerciseRestriction")) {
                    exerciseRestricted = true;
                }
            }
        }
        // 从基础档案摘要再兜底（无高亮作答时）
        JsonNode basic = snapshot.get("basicArchive");
        if (basic != null && basic.isObject()) {
            String repro = basic.path("women").path("reproductiveStatus").asText("");
            if ("PREGNANT".equalsIgnoreCase(repro) || "LACTATING".equalsIgnoreCase(repro)) {
                pregnantOrLactating = true;
            }
            String ed = basic.path("psych").path("eatingDisorderHistory").asText("");
            if ("YES".equalsIgnoreCase(ed) || "UNSURE".equalsIgnoreCase(ed)) {
                eatingDisorderRisk = true;
            }
            String ex = basic.path("medical").path("exerciseRestriction").asText("");
            if (StringUtils.hasText(ex) && !"NONE".equalsIgnoreCase(ex) && !"NO".equalsIgnoreCase(ex)) {
                exerciseRestricted = true;
            }
        }
        return new GuidelineFacts(
                tags,
                hypoRisk,
                WaterIntakeRules.fluidRestricted(diseaseCodes, snapshot),
                bmi(metrics),
                age,
                sex,
                assessmentPromptContext.tiersByDisease(tenantId, peopleId),
                pregnantOrLactating,
                eatingDisorderRisk,
                exerciseRestricted,
                Set.copyOf(highlightFields));
    }

    static Double bmi(List<MetricBrief> metrics) {
        Double weight = null;
        Double height = null;
        for (MetricBrief m : metrics) {
            try {
                if ("WEIGHT".equals(m.metricType())) {
                    weight = Double.parseDouble(m.value());
                } else if ("HEIGHT".equals(m.metricType())) {
                    height = Double.parseDouble(m.value());
                }
            } catch (NumberFormatException ignored) {
                // 非数值读数不参与 BMI
            }
        }
        if (weight == null || height == null || height <= 0) {
            return null;
        }
        double meters = height / 100.0;
        return Math.round(weight / (meters * meters) * 10) / 10.0;
    }

    private static String normalizeSex(String g) {
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

    private static void trace(CarePlanLoadTracer tracer, String tool, String status, String detail) {
        if (tracer != null) {
            tracer.step(tool, status, detail);
        }
    }

    private static String summarizeMetrics(List<MetricBrief> metrics) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < metrics.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            MetricBrief m = metrics.get(i);
            sb.append(m.metricType()).append('=').append(m.value());
            if (StringUtils.hasText(m.unit())) {
                sb.append(m.unit());
            }
            if (i >= 4) {
                sb.append("…");
                break;
            }
        }
        return sb.toString();
    }

    private List<MetricBrief> loadLatestMetrics(String tenantId, String peopleId, ObjectNode snapshot) {
        List<VitalRecord> recent = vitalRecordMapper.listRecentForLatest(tenantId, peopleId, 120);
        Map<String, MetricBrief> slots = new LinkedHashMap<>();
        Set<String> seenTypes = new LinkedHashSet<>();
        ArrayNode arr = snapshot.putArray("latestMetrics");
        ArrayNode skipped = snapshot.putArray("skippedMetrics");
        if (recent == null) {
            return List.of();
        }
        for (VitalRecord row : recent) {
            String type = row.getMetricType();
            if (!StringUtils.hasText(type) || !seenTypes.add(type)) {
                continue;
            }
            BigDecimal raw = row.getValue();
            if (!CarePlanPromptContextSupport.isMetricUsefulForPrompt(type, raw)) {
                if (raw != null) {
                    skipped.add(CarePlanPromptContextSupport.suspectMetricNote(type, raw, row.getUnit()));
                }
                continue;
            }
            String value = CarePlanPromptContextSupport.formatMetricValue(raw);
            MetricBrief brief = new MetricBrief(type, value, row.getUnit(), type);
            slots.put(type, brief);
            ObjectNode n = arr.addObject();
            n.put("metricType", type);
            n.put("metricLabel", CarePlanPromptContextSupport.metricTypeLabel(type));
            n.put("value", value);
            n.put("unit", row.getUnit());
            if (slots.size() >= 12) {
                break;
            }
        }
        if (skipped.isEmpty()) {
            snapshot.remove("skippedMetrics");
        }
        return new ArrayList<>(slots.values());
    }

    private List<LabBrief> loadRecentLabs(String tenantId, String peopleId, ObjectNode snapshot) {
        List<LabReport> reports = labReportMapper.listByPeople(tenantId, peopleId);
        List<LabBrief> out = new ArrayList<>();
        ArrayNode arr = snapshot.putArray("recentLabs");
        if (reports == null || reports.isEmpty()) {
            return out;
        }
        int limit = Math.min(3, reports.size());
        for (int i = 0; i < limit; i++) {
            LabReport r = reports.get(i);
            List<LabResultItem> items = labResultItemMapper.listByReportId(r.getId());
            List<String> summaries = new ArrayList<>();
            ObjectNode n = arr.addObject();
            n.put("reportId", r.getId());
            n.put("specimenType", r.getSpecimenType());
            n.put("sampledAt", r.getSampledAt() == null ? null : r.getSampledAt().toString());
            ArrayNode itemArr = n.putArray("items");
            if (items != null) {
                for (LabResultItem it : items) {
                    String summary = CarePlanPromptContextSupport.formatLabItemSummary(
                            it.getItemName(), it.getValueNum(), it.getValueText(), it.getUnit());
                    summaries.add(summary);
                    itemArr.add(summary);
                    if (summaries.size() >= 8) {
                        break;
                    }
                }
            }
            out.add(new LabBrief(
                    r.getId(),
                    r.getSpecimenType(),
                    r.getSampledAt() == null ? null : r.getSampledAt().toString(),
                    summaries));
        }
        return out;
    }
}
