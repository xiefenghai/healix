package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.identity.service.IdentityService;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.enums.MedicationStatusEnum;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.service.MedicationService;
import com.healix.core.observation.domain.LabReport;
import com.healix.core.observation.domain.LabResultItem;
import com.healix.core.observation.mapper.LabReportMapper;
import com.healix.core.observation.mapper.LabResultItemMapper;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.mapper.VitalRecordMapper;
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

@Service
@RequiredArgsConstructor
public class CarePlanContextService {

    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final IdentityService identityService;
    private final VitalRecordMapper vitalRecordMapper;
    private final PeopleMedicationMapper medicationMapper;
    private final LabReportMapper labReportMapper;
    private final LabResultItemMapper labResultItemMapper;

    public record CarePlanContext(
            List<String> diseaseCodes,
            List<String> allergenNames,
            List<String> activeMedications,
            List<MetricBrief> latestMetrics,
            List<LabBrief> recentLabs,
            boolean contextIncomplete,
            boolean hypoglycemiaRisk,
            ObjectNode snapshot) {}

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
            snapshot.set("basicArchive", summarizeBasic(content));
            JsonNode present = content.get("presentIllness");
            if (present != null && present.isArray()) {
                for (JsonNode n : present) {
                    if (n != null && n.isTextual()) {
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
        if (diseaseArchives != null) {
            for (PeopleDiseaseArchive a : diseaseArchives) {
                if (a.getDiseaseCode() != null) {
                    diseases.add(a.getDiseaseCode().toUpperCase(Locale.ROOT));
                    diseaseSnap.add(a.getDiseaseCode());
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
        trace(tracer, "PeopleDiseaseArchive", "done", diseases.isEmpty() ? "无病种记录" : "病种: " + String.join(", ", diseases));

        trace(tracer, "peopleAllergens", "running", "读取过敏原");
        List<String> allergens = identityService.peopleAllergens(peopleId);
        if (allergens == null) {
            allergens = List.of();
        }
        ArrayNode allergenArr = snapshot.putArray("allergens");
        allergens.forEach(allergenArr::add);
        trace(tracer, "peopleAllergens", "done", allergens.isEmpty() ? "无过敏原" : "过敏原: " + String.join(", ", allergens));

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

        snapshot.put("contextIncomplete", incomplete);
        snapshot.put("hypoglycemiaRisk", hypoRisk);
        trace(tracer, "loadCarePlanContext", "done", "上下文加载完成");
        return new CarePlanContext(
                new ArrayList<>(diseases),
                allergens,
                medNames,
                metrics,
                labs,
                incomplete,
                hypoRisk,
                snapshot);
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

    private ObjectNode summarizeBasic(JsonNode content) {
        ObjectNode o = JsonUtils.emptyObject();
        o.set("presentIllness", content.path("presentIllness"));
        if (content.has("diet")) {
            o.set("diet", content.get("diet"));
        }
        if (content.has("exercise")) {
            o.set("exercise", content.get("exercise"));
        }
        return o;
    }

    private List<MetricBrief> loadLatestMetrics(String tenantId, String peopleId, ObjectNode snapshot) {
        List<VitalRecord> recent = vitalRecordMapper.listRecentForLatest(tenantId, peopleId, 120);
        Map<String, MetricBrief> slots = new LinkedHashMap<>();
        ArrayNode arr = snapshot.putArray("latestMetrics");
        if (recent == null) {
            return List.of();
        }
        for (VitalRecord row : recent) {
            String type = row.getMetricType();
            if (!StringUtils.hasText(type) || slots.containsKey(type)) {
                continue;
            }
            // 每种指标保留最新一条（列表已按时间倒序）
            String value = row.getValue() == null ? null : row.getValue().toPlainString();
            MetricBrief brief = new MetricBrief(type, value, row.getUnit(), type);
            slots.put(type, brief);
            ObjectNode n = arr.addObject();
            n.put("metricType", type);
            n.put("value", value);
            n.put("unit", row.getUnit());
            if (slots.size() >= 12) {
                break;
            }
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
                    String summary = it.getItemName()
                            + "="
                            + (it.getValueNum() != null
                                    ? it.getValueNum().toPlainString()
                                    : (it.getValueText() == null ? "" : it.getValueText()))
                            + (it.getUnit() == null ? "" : it.getUnit());
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
