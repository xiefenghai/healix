package com.healix.core.medication.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationFeedback;
import com.healix.core.medication.dto.MedicationFeedbackDto;
import com.healix.core.medication.enums.MedicationFeedbackKindEnum;
import com.healix.core.medication.enums.MedicationSymptomEnum;
import com.healix.core.medication.mapper.PeopleMedicationFeedbackMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 患者用药反馈：落库、标不良反应、开工作台任务，健管师处理后回复患者。 */
@Service
@RequiredArgsConstructor
public class MedicationFeedbackService {

    public static final String OPEN = "OPEN";
    public static final String HANDLED = "HANDLED";
    public static final String SEVERE = "SEVERE";
    private static final Set<String> SEVERITIES = Set.of("MILD", "MODERATE", SEVERE);

    private final PeopleMedicationFeedbackMapper feedbackMapper;
    private final PeopleMedicationMapper medicationMapper;
    private final ArchiveAccessService archiveAccessService;
    private final StaffProfileMapper staffProfileMapper;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;

    public record FeedbackCommand(String kind, List<String> symptoms, String severity, String content) {}

    /** C 端上报：仅「不适 / 申请调整」，停药与过敏冲突由用药服务内部写入。 */
    @Transactional
    public MedicationFeedbackDto submitByPatient(
            String tenantId, String peopleId, String medicationId, FeedbackCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication med = requireOwned(tenantId, peopleId, medicationId);
        MedicationFeedbackKindEnum kind = parseKind(cmd == null ? null : cmd.kind());
        if (kind != MedicationFeedbackKindEnum.ADVERSE && kind != MedicationFeedbackKindEnum.ADJUST) {
            throw new BusinessException("不支持的反馈类型");
        }
        return toDto(record(med, kind, cmd.symptoms(), cmd.severity(), cmd.content()), med.getDrugName(), null);
    }

    /** 写入一条反馈并开任务（调用方已校验归属）。 */
    @Transactional
    public PeopleMedicationFeedback record(
            PeopleMedication med,
            MedicationFeedbackKindEnum kind,
            List<String> symptoms,
            String severity,
            String content) {
        List<String> symptomCodes = normalizeSymptoms(symptoms);
        String sev = StringUtils.hasText(severity) ? severity.trim().toUpperCase() : null;
        if (kind == MedicationFeedbackKindEnum.ADVERSE) {
            if (symptomCodes.isEmpty()) {
                throw new BusinessException("请选择不适症状");
            }
            if (sev == null || !SEVERITIES.contains(sev)) {
                throw new BusinessException("请选择严重程度");
            }
        } else {
            sev = null;
        }
        if (kind == MedicationFeedbackKindEnum.ADJUST && !StringUtils.hasText(content)) {
            throw new BusinessException("请说明想调整的内容");
        }
        String text = StringUtils.hasText(content) ? content.trim() : null;
        if (text != null && text.length() > 500) {
            throw new BusinessException("描述最多 500 字");
        }
        PeopleMedicationFeedback row = new PeopleMedicationFeedback();
        row.setTenantId(med.getTenantId());
        row.setPeopleId(med.getPeopleId());
        row.setMedicationId(med.getId());
        row.setKind(kind.name());
        row.setSymptomsJson(symptomCodes.isEmpty() ? null : JsonUtils.toJson(symptomCodes));
        row.setSeverity(sev);
        row.setContent(text);
        row.setStatus(OPEN);
        EntityMeta.onCreate(row);
        feedbackMapper.insert(row);

        if (kind == MedicationFeedbackKindEnum.ADVERSE && !Integer.valueOf(1).equals(med.getHasAdverseReaction())) {
            med.setHasAdverseReaction(1);
            EntityMeta.onUpdate(med);
            medicationMapper.update(med);
        }

        boolean urgent = SEVERE.equals(sev) || kind == MedicationFeedbackKindEnum.ALLERGY_CONFLICT;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("feedbackId", row.getId());
        payload.put("medicationId", med.getId());
        payload.put("drugName", med.getDrugName());
        payload.put("kind", kind.name());
        payload.put("symptoms", symptomLabels(symptomCodes));
        payload.put("severity", sev);
        payload.put("content", text);
        String taskId = workspaceTaskGenerator.onMedicationFeedback(
                med.getTenantId(), med.getPeopleId(), row.getId(), summaryOf(med, kind, symptomCodes, sev, text), payload, urgent);
        if (taskId != null) {
            feedbackMapper.updateTask(row.getId(), taskId);
            row.setWorkTaskId(taskId);
        }
        return row;
    }

    public List<MedicationFeedbackDto> listByMedicationForPatient(String tenantId, String peopleId, String medicationId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        PeopleMedication med = requireOwned(tenantId, peopleId, medicationId);
        Map<String, String> staffNames = new HashMap<>();
        return feedbackMapper.listByMedication(medicationId).stream()
                .map(r -> toDto(r, med.getDrugName(), staffNames))
                .toList();
    }

    public List<MedicationFeedbackDto> listForStaff(String tenantId, String orgId, String peopleId, String status) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        Map<String, String> drugNames = new HashMap<>();
        Map<String, String> staffNames = new HashMap<>();
        List<MedicationFeedbackDto> out = new ArrayList<>();
        for (PeopleMedicationFeedback r : feedbackMapper.listByPeople(tenantId, peopleId, status)) {
            String drugName = drugNames.computeIfAbsent(r.getMedicationId(), id -> {
                PeopleMedication m = medicationMapper.findById(id);
                return m == null ? null : m.getDrugName();
            });
            out.add(toDto(r, drugName, staffNames));
        }
        return out;
    }

    /** 每种药未处理反馈数（药品列表角标用）。 */
    public Map<String, Integer> openCountByMedication(String tenantId, String peopleId) {
        Map<String, Integer> out = new HashMap<>();
        for (PeopleMedicationFeedback r : feedbackMapper.listByPeople(tenantId, peopleId, OPEN)) {
            out.merge(r.getMedicationId(), 1, Integer::sum);
        }
        return out;
    }

    @Transactional
    public MedicationFeedbackDto handle(
            String tenantId, String orgId, String peopleId, String staffId, String feedbackId, String reply) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleMedicationFeedback row = feedbackMapper.findById(feedbackId);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(404, "反馈不存在");
        }
        if (!StringUtils.hasText(reply)) {
            throw new BusinessException("请填写处理说明");
        }
        String text = reply.trim();
        if (text.length() > 500) {
            throw new BusinessException("处理说明最多 500 字");
        }
        row.setStatus(HANDLED);
        row.setHandledByStaffId(staffId);
        row.setHandledAt(LocalDateTime.now(JobCronSupport.ZONE));
        row.setReply(text);
        EntityMeta.onUpdate(row);
        feedbackMapper.handle(row);
        workspaceTaskGenerator.closeMedicationFeedback(row.getWorkTaskId(), staffId);
        PeopleMedication med = medicationMapper.findById(row.getMedicationId());
        return toDto(row, med == null ? null : med.getDrugName(), new HashMap<>());
    }

    private static String summaryOf(
            PeopleMedication med, MedicationFeedbackKindEnum kind, List<String> symptoms, String severity, String content) {
        StringBuilder sb = new StringBuilder(med.getDrugName()).append(" · ").append(kind.label());
        if (!symptoms.isEmpty()) {
            sb.append("：").append(String.join("、", symptomLabels(symptoms)));
        } else if (StringUtils.hasText(content)) {
            sb.append("：").append(content);
        }
        if (severity != null) {
            sb.append("（").append(severityLabel(severity)).append("）");
        }
        String s = sb.toString();
        return s.length() > 250 ? s.substring(0, 250) + "…" : s;
    }

    public static String severityLabel(String severity) {
        if (severity == null) {
            return null;
        }
        return switch (severity) {
            case "MILD" -> "轻度";
            case "MODERATE" -> "中度";
            case SEVERE -> "重度";
            default -> severity;
        };
    }

    private static List<String> normalizeSymptoms(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String s : raw) {
                if (!StringUtils.hasText(s)) {
                    continue;
                }
                String code = s.trim().toUpperCase();
                try {
                    MedicationSymptomEnum.valueOf(code);
                    out.add(code);
                } catch (IllegalArgumentException e) {
                    throw new BusinessException("无效的症状：" + s);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static List<String> symptomLabels(List<String> codes) {
        return codes.stream().map(MedicationSymptomEnum::labelOf).toList();
    }

    private static MedicationFeedbackKindEnum parseKind(String raw) {
        try {
            return MedicationFeedbackKindEnum.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            throw new BusinessException("无效的反馈类型");
        }
    }

    private PeopleMedication requireOwned(String tenantId, String peopleId, String medicationId) {
        PeopleMedication row = medicationMapper.findById(medicationId);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(404, "用药记录不存在");
        }
        return row;
    }

    private MedicationFeedbackDto toDto(PeopleMedicationFeedback r, String drugName, Map<String, String> staffNames) {
        List<String> codes = List.of();
        if (StringUtils.hasText(r.getSymptomsJson())) {
            try {
                List<String> parsed = JsonUtils.fromJson(r.getSymptomsJson(), new TypeReference<List<String>>() {});
                codes = parsed == null ? List.of() : parsed;
            } catch (IllegalArgumentException ignored) {
                codes = List.of();
            }
        }
        String handledBy = null;
        if (StringUtils.hasText(r.getHandledByStaffId())) {
            Map<String, String> cache = staffNames == null ? new HashMap<>() : staffNames;
            handledBy = cache.computeIfAbsent(r.getHandledByStaffId(), id -> {
                StaffProfile p = staffProfileMapper.findById(id);
                return p == null ? "健管师" : p.getDisplayName();
            });
        }
        MedicationFeedbackKindEnum kind;
        try {
            kind = MedicationFeedbackKindEnum.valueOf(r.getKind());
        } catch (Exception e) {
            kind = null;
        }
        return new MedicationFeedbackDto(
                r.getId(),
                r.getMedicationId(),
                drugName,
                r.getKind(),
                kind == null ? r.getKind() : kind.label(),
                codes,
                symptomLabels(codes),
                r.getSeverity(),
                r.getContent(),
                r.getStatus(),
                r.getReply(),
                handledBy,
                r.getHandledAt(),
                r.getWorkTaskId(),
                r.getGmtCreated());
    }
}
