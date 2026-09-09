package com.healix.core.followup.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.PageResult;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.followup.catalog.FollowupType;
import com.healix.core.followup.catalog.FollowupRecordSource;
import com.healix.core.followup.catalog.FollowupRecordStatus;
import com.healix.core.followup.catalog.FollowupRecordType;
import com.healix.core.followup.domain.FollowupRecord;
import com.healix.core.followup.dto.FollowupListItemDto;
import com.healix.core.followup.dto.FollowupRecordViewDto;
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.followup.support.FollowupContentValidator;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.VitalSourceEnum;
import com.healix.core.vitals.service.VitalService;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.core.worktask.catalog.WorkspaceTaskCloseReason;
import com.healix.core.worktask.catalog.WorkspaceTaskStatus;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import com.healix.core.worktask.support.MetricAbnormalEvaluator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class FollowupService {

    private final OrgWorkspaceService orgWorkspaceService;
    private final ArchiveAccessService archiveAccessService;
    private final FollowupRecordMapper followupRecordMapper;
    private final WorkspaceTaskMapper workspaceTaskMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final AuditService auditService;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;
    private final VitalService vitalService;

    public PageResult<FollowupListItemDto> page(
            String orgId,
            String status,
            String recordType,
            String keyword,
            int page,
            int size) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 100);
        String st = blankToNull(status);
        String rt = blankToNull(recordType);
        String kw = blankToNull(keyword);
        long total = followupRecordMapper.countByOrg(orgId, st, rt, kw);
        List<FollowupListItemDto> items = new ArrayList<>();
        for (FollowupRecord row : followupRecordMapper.listByOrg(orgId, st, rt, kw, (p - 1) * s, s)) {
            items.add(toListItem(row));
        }
        return new PageResult<>(total, items);
    }

    public List<FollowupListItemDto> listByPeople(String orgId, String peopleId, String recordType, int limit) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, peopleId);
        int lim = Math.min(Math.max(limit, 1), 200);
        List<FollowupListItemDto> items = new ArrayList<>();
        for (FollowupRecord row : followupRecordMapper.listByPeople(orgId, peopleId, blankToNull(recordType), lim)) {
            items.add(toListItem(row));
        }
        return items;
    }

    /** C 端：当前就诊人可见的随访（OPEN/DONE）。 */
    public List<FollowupListItemDto> listForPatient(String tenantId, String peopleId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        List<FollowupListItemDto> items = new ArrayList<>();
        for (FollowupRecord row : followupRecordMapper.listVisibleForPatient(tenantId, peopleId, lim)) {
            FollowupListItemDto item = toListItem(row);
            item.setWorkspaceTaskId(null);
            items.add(item);
        }
        return items;
    }

    /** C 端详情：须属于当前患者且未取消。 */
    public FollowupRecordViewDto getForPatient(String tenantId, String peopleId, String id) {
        FollowupRecord row = followupRecordMapper.findById(id);
        if (row == null
                || !tenantId.equals(row.getTenantId())
                || !peopleId.equals(row.getPeopleId())
                || FollowupRecordStatus.CANCELLED.matches(row.getStatus())) {
            throw new BusinessException(404, "随访记录不存在");
        }
        return toPatientView(row);
    }

    public FollowupRecordViewDto get(String orgId, String id) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        FollowupRecord row = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, row.getPeopleId());
        return toView(row);
    }

    /**
     * 创建定期随访。
     *
     * @param completeNow true：当场办结（写 DONE，不建任务）；false：建 OPEN，可选建 FOLLOW_UP 待办
     * @param createTask 仅 completeNow=false 时有效，默认 true
     */
    @Transactional
    public FollowupRecordViewDto createPeriodic(
            String orgId,
            String peopleId,
            String followupTypeCode,
            LocalDateTime plannedAt,
            Boolean createTask,
            boolean completeNow,
            Map<String, Object> content,
            String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String tenantId = requireTenantId();
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        String staffId = requireStaffId();
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);

        FollowupType followupType;
        Map<String, Object> normalized;
        if (completeNow) {
            Map<String, Object> raw = content == null ? new LinkedHashMap<>() : new LinkedHashMap<>(content);
            if (!StringUtils.hasText(FollowupContentValidator.str(raw.get("followupType")))) {
                raw.put("followupType", followupTypeCode);
            }
            normalized = FollowupContentValidator.validatePeriodic(raw);
            followupType = FollowupType.require(String.valueOf(normalized.get("followupType")));
        } else {
            String code = StringUtils.hasText(followupTypeCode)
                    ? followupTypeCode
                    : FollowupContentValidator.requireFollowupType(content);
            followupType = FollowupType.require(code);
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("followupType", followupType.name());
            normalized = draft;
        }

        FollowupRecord record = new FollowupRecord();
        record.setTenantId(tenantId);
        record.setOrgId(orgId);
        record.setPeopleId(peopleId);
        record.setRecordType(FollowupRecordType.PERIODIC.name());
        record.setSource(FollowupRecordSource.MANUAL.name());
        record.setTitle(followupType.label());
        record.setSummary(followupType.label());
        record.setPlannedAt(plannedAt);
        record.setAssigneeStaffId(staffId);
        record.setContentJson(JsonUtils.toJson(normalized));
        EntityMeta.onCreate(record);

        if (completeNow) {
            applyCompleteFields(record, normalized, staffId, now);
            followupRecordMapper.insert(record);
            maybeOpenPlanCreateTask(tenantId, orgId, peopleId, record.getId(), normalized, record);
            audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_CREATE, record.getId(), peopleId,
                    AuditDetails.of(
                            "recordType", "PERIODIC", "status", "DONE", "followupType", followupType.name()));
            audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_COMPLETE, record.getId(), peopleId,
                    AuditDetails.of("recordType", "PERIODIC", "source", "MANUAL"));
        } else {
            record.setStatus(FollowupRecordStatus.OPEN.name());
            WorkspaceTaskType taskType = WorkspaceTaskType.FOLLOW_UP;
            record.setDueAt(LocalDate.now(JobCronSupport.ZONE)
                    .plusDays(Math.max(0, taskType.dueDaysInclusive() - 1))
                    .atTime(LocalTime.of(23, 59, 59)));
            followupRecordMapper.insert(record);
            boolean wantTask = createTask == null || createTask;
            if (wantTask) {
                String taskId = openFollowUpTask(tenantId, orgId, peopleId, record, staffId, followupType);
                followupRecordMapper.updateWorkspaceTaskId(record.getId(), taskId, now);
                record.setWorkspaceTaskId(taskId);
            }
            audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_CREATE, record.getId(), peopleId,
                    AuditDetails.of(
                            "recordType", "PERIODIC",
                            "status", "OPEN",
                            "followupType", followupType.name(),
                            "workspaceTaskId", record.getWorkspaceTaskId()));
        }
        return toView(followupRecordMapper.findById(record.getId()));
    }

    @Transactional
    public FollowupRecordViewDto complete(String orgId, String id, Map<String, Object> content, String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        FollowupRecord record = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, record.getPeopleId());
        if (!FollowupRecordStatus.OPEN.matches(record.getStatus())) {
            throw new BusinessException(400, "仅待办随访可办结");
        }
        if (!FollowupRecordType.PERIODIC.matches(record.getRecordType())) {
            throw new BusinessException(400, "该类型随访请从工作台任务处理");
        }
        Map<String, Object> merged = mergeFollowupType(record, content);
        Map<String, Object> normalized = FollowupContentValidator.validatePeriodic(merged);
        String staffId = requireStaffId();
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        applyCompleteFields(record, normalized, staffId, now);
        EntityMeta.onUpdate(record);
        followupRecordMapper.updateOnComplete(record);
        maybeOpenPlanCreateTask(requireTenantId(), orgId, record.getPeopleId(), record.getId(), normalized, record);
        closeLinkedTaskIfOpen(record, staffId, now, actorAccountId);
        audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_COMPLETE, record.getId(), record.getPeopleId(),
                AuditDetails.of("recordType", record.getRecordType(), "source", record.getSource()));
        return toView(followupRecordMapper.findById(record.getId()));
    }

    @Transactional
    public FollowupRecordViewDto cancel(String orgId, String id, String reason, String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        FollowupRecord record = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, record.getPeopleId());
        if (!FollowupRecordStatus.OPEN.matches(record.getStatus())) {
            throw new BusinessException(400, "仅待办随访可取消");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(400, "请填写取消原因");
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        followupRecordMapper.updateCancel(
                record.getId(), FollowupRecordStatus.CANCELLED.name(), reason.trim(), now);
        if (StringUtils.hasText(record.getWorkspaceTaskId())) {
            WorkspaceTask task = workspaceTaskMapper.findById(record.getWorkspaceTaskId());
            if (task != null && WorkspaceTaskStatus.OPEN.matches(task.getStatus())) {
                String staffId = requireStaffId();
                workspaceTaskMapper.close(
                        task.getId(),
                        WorkspaceTaskStatus.CANCELLED.name(),
                        WorkspaceTaskCloseReason.CANCEL.name(),
                        now,
                        staffId,
                        now);
                auditService.record(
                        PortalEnum.B.code(),
                        actorAccountId,
                        "STAFF",
                        requireTenantId(),
                        AuditActionEnum.WORKSPACE_TASK_DONE.name(),
                        "workspace_task",
                        task.getId(),
                        record.getPeopleId(),
                        AuditDetails.of(
                                "taskType",
                                task.getTaskType(),
                                "closeReason",
                                "CANCEL",
                                "fromFollowup",
                                record.getId()));
            }
        }
        audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_CANCEL, record.getId(), record.getPeopleId(),
                AuditDetails.of("reason", reason.trim()));
        return toView(followupRecordMapper.findById(record.getId()));
    }

    /** 工作台 FOLLOW_UP 任务填单关单。 */
    @Transactional
    public void completeFromWorkspaceTask(
            WorkspaceTask task, Map<String, Object> content, String staffId, String actorAccountId) {
        FollowupRecord open = followupRecordMapper.findOpenByTaskId(task.getId());
        Map<String, Object> merged = content == null ? new LinkedHashMap<>() : new LinkedHashMap<>(content);
        if (!StringUtils.hasText(FollowupContentValidator.str(merged.get("followupType")))) {
            if (open != null) {
                Map<String, Object> existing =
                        JsonUtils.fromJson(open.getContentJson(), new TypeReference<>() {});
                String t = existing == null ? "" : FollowupContentValidator.str(existing.get("followupType"));
                if (StringUtils.hasText(t)) {
                    merged.put("followupType", t);
                }
            }
            if (!StringUtils.hasText(FollowupContentValidator.str(merged.get("followupType")))
                    && StringUtils.hasText(task.getPayloadJson())) {
                Map<String, Object> payload =
                        JsonUtils.fromJson(task.getPayloadJson(), new TypeReference<>() {});
                String t = payload == null ? "" : FollowupContentValidator.str(payload.get("followupType"));
                if (StringUtils.hasText(t)) {
                    merged.put("followupType", t);
                }
            }
        }
        Map<String, Object> normalized = FollowupContentValidator.validatePeriodic(merged);
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        String followupIdForPlan;
        if (open != null) {
            applyCompleteFields(open, normalized, staffId, now);
            EntityMeta.onUpdate(open);
            followupRecordMapper.updateOnComplete(open);
            followupIdForPlan = open.getId();
            maybeOpenPlanCreateTask(
                    task.getTenantId(), task.getOrgId(), task.getPeopleId(), followupIdForPlan, normalized, open);
            audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_COMPLETE, open.getId(), task.getPeopleId(),
                    AuditDetails.of("recordType", "PERIODIC", "source", "WORKSPACE_TASK", "taskId", task.getId()));
        } else {
            FollowupRecord record = new FollowupRecord();
            record.setTenantId(task.getTenantId());
            record.setOrgId(task.getOrgId());
            record.setPeopleId(task.getPeopleId());
            record.setWorkspaceTaskId(task.getId());
            record.setRecordType(FollowupRecordType.PERIODIC.name());
            record.setSource(FollowupRecordSource.WORKSPACE_TASK.name());
            record.setTitle(FollowupRecordType.PERIODIC.label());
            record.setSummary(task.getSummary());
            record.setDueAt(task.getDueAt());
            record.setAssigneeStaffId(staffId);
            applyCompleteFields(record, normalized, staffId, now);
            EntityMeta.onCreate(record);
            followupRecordMapper.insert(record);
            followupIdForPlan = record.getId();
            maybeOpenPlanCreateTask(
                    task.getTenantId(), task.getOrgId(), task.getPeopleId(), followupIdForPlan, normalized, record);
            audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_COMPLETE, record.getId(), task.getPeopleId(),
                    AuditDetails.of("recordType", "PERIODIC", "source", "WORKSPACE_TASK", "taskId", task.getId()));
        }
        workspaceTaskMapper.close(
                task.getId(),
                WorkspaceTaskStatus.DONE.name(),
                WorkspaceTaskCloseReason.FORM.name(),
                now,
                staffId,
                now);
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                AuditActionEnum.WORKSPACE_TASK_DONE.name(),
                "workspace_task",
                task.getId(),
                task.getPeopleId(),
                AuditDetails.of(
                        "taskType", task.getTaskType(), "peopleId", task.getPeopleId(), "closeReason", "FORM"));
    }

    private String openFollowUpTask(
            String tenantId,
            String orgId,
            String peopleId,
            FollowupRecord record,
            String staffId,
            FollowupType followupType) {
        WorkspaceTaskType type = WorkspaceTaskType.FOLLOW_UP;
        String bizKey = "FU:" + record.getId();
        if (workspaceTaskMapper.findOpen(tenantId, orgId, type.name(), bizKey) != null) {
            throw new BusinessException(400, "已存在关联待办，请勿重复创建");
        }
        WorkspaceTask row = new WorkspaceTask();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setTaskType(type.name());
        row.setBizKey(bizKey);
        row.setStatus(WorkspaceTaskStatus.OPEN.name());
        row.setPriority("HIGH");
        String assignee = staffId;
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        if (a != null && StringUtils.hasText(a.getPrimaryCareManagerStaffId())) {
            assignee = a.getPrimaryCareManagerStaffId();
        }
        row.setAssigneeStaffId(assignee);
        row.setTitle(type.label());
        row.setSummary(followupType.label());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("followupId", record.getId());
        payload.put("followupType", followupType.name());
        row.setPayloadJson(JsonUtils.toJson(payload));
        row.setSource("FOLLOWUP");
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        row.setOpenedAt(now);
        row.setDueAt(LocalDate.now(JobCronSupport.ZONE)
                .plusDays(Math.max(0, type.dueDaysInclusive() - 1))
                .atTime(LocalTime.of(23, 59, 59)));
        EntityMeta.onCreate(row);
        workspaceTaskMapper.insert(row);
        return row.getId();
    }

    private void closeLinkedTaskIfOpen(
            FollowupRecord record, String staffId, LocalDateTime now, String actorAccountId) {
        if (!StringUtils.hasText(record.getWorkspaceTaskId())) {
            return;
        }
        WorkspaceTask task = workspaceTaskMapper.findById(record.getWorkspaceTaskId());
        if (task == null || !WorkspaceTaskStatus.OPEN.matches(task.getStatus())) {
            return;
        }
        workspaceTaskMapper.close(
                task.getId(),
                WorkspaceTaskStatus.DONE.name(),
                WorkspaceTaskCloseReason.FORM.name(),
                now,
                staffId,
                now);
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                AuditActionEnum.WORKSPACE_TASK_DONE.name(),
                "workspace_task",
                task.getId(),
                record.getPeopleId(),
                AuditDetails.of(
                        "taskType", task.getTaskType(), "closeReason", "FORM", "fromFollowup", record.getId()));
    }

    private void applyCompleteFields(
            FollowupRecord record, Map<String, Object> normalized, String staffId, LocalDateTime now) {
        FollowupType followupType = FollowupType.require(String.valueOf(normalized.get("followupType")));
        record.setStatus(FollowupRecordStatus.DONE.name());
        record.setTitle(followupType.label());
        String summary = FollowupContentValidator.buildSummary(normalized);
        record.setSummary(StringUtils.hasText(summary) ? summary : followupType.label());
        record.setContactChannel(FollowupContentValidator.str(normalized.get("followupMethod")));
        record.setContactResult(null);
        record.setContentJson(JsonUtils.toJson(normalized));
        record.setCompletedAt(now);
        record.setCompletedByStaffId(staffId);
        record.setAssigneeStaffId(staffId);
        record.setGmtModified(now);
        writeBaselineMetrics(record, normalized, now);
    }

    /**
     * 入组随访基线指标：办结时写入 vital_record（source=STAFF 医护代录），
     * 并把生成的 id 回写 section 便于追溯。
     */
    @SuppressWarnings("unchecked")
    private void writeBaselineMetrics(
            FollowupRecord record, Map<String, Object> normalized, LocalDateTime now) {
        Object sectionRaw = normalized.get("section");
        if (!(sectionRaw instanceof Map<?, ?> sectionMap)) {
            return;
        }
        Object metricsRaw = ((Map<String, Object>) sectionMap).get("baselineMetrics");
        if (!(metricsRaw instanceof List<?> metrics) || metrics.isEmpty()) {
            return;
        }
        List<String> vitalIds = new ArrayList<>();
        for (Object item : metrics) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            String metricType = FollowupContentValidator.str(m.get("metricType"));
            String value = FollowupContentValidator.str(m.get("value"));
            String unit = FollowupContentValidator.str(m.get("unit"));
            if (!StringUtils.hasText(metricType) || !StringUtils.hasText(value)) {
                continue;
            }
            try {
                VitalRecord written = vitalService.record(
                        record.getPeopleId(),
                        metricType,
                        new BigDecimal(value),
                        StringUtils.hasText(unit) ? unit : null,
                        now,
                        VitalSourceEnum.STAFF.name());
                vitalIds.add(written.getId());
            } catch (Exception ex) {
                log.warn(
                        "write baseline vital failed people={} metric={}: {}",
                        record.getPeopleId(),
                        metricType,
                        ex.getMessage());
            }
        }
        if (!vitalIds.isEmpty()) {
            ((Map<String, Object>) sectionMap).put("baselineVitalIds", vitalIds);
            record.setContentJson(JsonUtils.toJson(normalized));
        }
    }

    /** 勾选建议调整方案时，确保工作台有 OPEN 的制定方案待办。 */
    private void maybeOpenPlanCreateTask(
            String tenantId,
            String orgId,
            String peopleId,
            String followupId,
            Map<String, Object> normalized,
            FollowupRecord record) {
        Object raw = normalized.get("suggestPlanAdjust");
        boolean suggest = Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw));
        if (!suggest) {
            return;
        }
        String planTaskId =
                workspaceTaskGenerator.ensurePlanCreateFromFollowup(tenantId, orgId, peopleId, followupId);
        if (!StringUtils.hasText(planTaskId)) {
            return;
        }
        normalized.put("planCreateTaskId", planTaskId);
        record.setContentJson(JsonUtils.toJson(normalized));
        followupRecordMapper.updateOnComplete(record);
    }

    private Map<String, Object> mergeFollowupType(FollowupRecord record, Map<String, Object> content) {
        Map<String, Object> merged = content == null ? new LinkedHashMap<>() : new LinkedHashMap<>(content);
        if (!StringUtils.hasText(FollowupContentValidator.str(merged.get("followupType")))) {
            Map<String, Object> existing =
                    JsonUtils.fromJson(record.getContentJson(), new TypeReference<>() {});
            String existingType =
                    existing == null ? "" : FollowupContentValidator.str(existing.get("followupType"));
            if (StringUtils.hasText(existingType)) {
                merged.put("followupType", existingType);
            }
        }
        return merged;
    }

    private FollowupRecord requireInOrg(String orgId, String id) {
        FollowupRecord row = followupRecordMapper.findById(id);
        if (row == null || !orgId.equals(row.getOrgId())) {
            throw new BusinessException(404, "随访记录不存在");
        }
        return row;
    }

    private FollowupListItemDto toListItem(FollowupRecord row) {
        FollowupListItemDto dto = new FollowupListItemDto();
        dto.setId(row.getId());
        dto.setPeopleId(row.getPeopleId());
        PeopleProfile people = peopleProfileMapper.findById(row.getPeopleId());
        dto.setPeopleName(people == null ? row.getPeopleId() : people.getDisplayName());
        dto.setRecordType(row.getRecordType());
        try {
            dto.setRecordTypeLabel(FollowupRecordType.require(row.getRecordType()).label());
        } catch (Exception e) {
            dto.setRecordTypeLabel(row.getRecordType());
        }
        dto.setSource(row.getSource());
        dto.setStatus(row.getStatus());
        dto.setTitle(row.getTitle());
        dto.setSummary(row.getSummary());
        fillFollowupType(dto, row.getContentJson());
        dto.setWorkspaceTaskId(row.getWorkspaceTaskId());
        dto.setContactChannel(row.getContactChannel());
        dto.setAssigneeStaffId(row.getAssigneeStaffId());
        dto.setAssigneeName(staffName(row.getAssigneeStaffId()));
        dto.setCompletedByStaffId(row.getCompletedByStaffId());
        dto.setCompletedByName(staffName(row.getCompletedByStaffId()));
        dto.setPlannedAt(row.getPlannedAt());
        dto.setDueAt(row.getDueAt());
        dto.setCompletedAt(row.getCompletedAt());
        dto.setGmtCreated(row.getGmtCreated());
        return dto;
    }

    private FollowupRecordViewDto toView(FollowupRecord rec) {
        FollowupRecordViewDto v = new FollowupRecordViewDto();
        v.setId(rec.getId());
        v.setRecordType(rec.getRecordType());
        try {
            v.setRecordTypeLabel(FollowupRecordType.require(rec.getRecordType()).label());
        } catch (Exception e) {
            v.setRecordTypeLabel(rec.getRecordType());
        }
        v.setSource(rec.getSource());
        v.setStatus(rec.getStatus());
        v.setTitle(rec.getTitle());
        v.setSummary(rec.getSummary());
        v.setWorkspaceTaskId(rec.getWorkspaceTaskId());
        Map<String, Object> content = JsonUtils.fromJson(rec.getContentJson(), new TypeReference<>() {});
        if (content == null) {
            content = new LinkedHashMap<>();
        }
        enrichMetricHitsForView(rec, content);
        v.setContent(JsonUtils.readTree(JsonUtils.toJson(content)));
        v.setContactChannel(rec.getContactChannel());
        v.setContactResult(rec.getContactResult());
        v.setCompletedByStaffId(rec.getCompletedByStaffId());
        v.setCompletedByName(staffName(rec.getCompletedByStaffId()));
        v.setCompletedAt(rec.getCompletedAt());
        v.setPlannedAt(rec.getPlannedAt());
        v.setDueAt(rec.getDueAt());
        return v;
    }

    /** C 端详情：与 B 端同构字段；仅去掉工单等内部引用。 */
    private FollowupRecordViewDto toPatientView(FollowupRecord rec) {
        FollowupRecordViewDto v = toView(rec);
        v.setWorkspaceTaskId(null);
        if (v.getContent() != null && v.getContent().isObject()) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) v.getContent()).remove("planCreateTaskId");
            ((com.fasterxml.jackson.databind.node.ObjectNode) v.getContent()).remove("workspaceTaskId");
        }
        return v;
    }

    /** 旧数据办结未固化 hits 时，从关联工作台任务补回展示。 */
    private void enrichMetricHitsForView(FollowupRecord rec, Map<String, Object> content) {
        if (!FollowupRecordType.METRIC_REVIEW.matches(rec.getRecordType())) {
            return;
        }
        Object existing = content.get("hits");
        if (existing instanceof List<?> list && !list.isEmpty()) {
            return;
        }
        List<Map<String, Object>> hits = List.of();
        if (StringUtils.hasText(rec.getWorkspaceTaskId())) {
            WorkspaceTask task = workspaceTaskMapper.findById(rec.getWorkspaceTaskId());
            if (task != null) {
                hits = MetricAbnormalEvaluator.extractHits(task.getPayloadJson());
            }
        }
        if (!hits.isEmpty()) {
            content.put("hits", hits);
            content.put("hitCount", hits.size());
        }
    }

    private void fillFollowupType(FollowupListItemDto dto, String contentJson) {
        Map<String, Object> content = JsonUtils.fromJson(contentJson, new TypeReference<>() {});
        if (content == null) {
            return;
        }
        String code = FollowupContentValidator.str(content.get("followupType"));
        if (!StringUtils.hasText(code)) {
            return;
        }
        dto.setFollowupType(code);
        try {
            dto.setFollowupTypeLabel(FollowupType.require(code).label());
        } catch (Exception e) {
            dto.setFollowupTypeLabel(code);
        }
    }

    private String staffName(String staffId) {
        if (!StringUtils.hasText(staffId)) {
            return null;
        }
        StaffProfile staff = staffProfileMapper.findById(staffId);
        return staff == null ? staffId : staff.getDisplayName();
    }

    private void audit(
            String actorAccountId, AuditActionEnum action, String resourceId, String peopleId, String details) {
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                action.name(),
                "followup_record",
                resourceId,
                peopleId,
                details);
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static String requireStaffId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getStaffId() == null) {
            throw new BusinessException(401, "缺少员工上下文");
        }
        return ctx.getStaffId();
    }

    private static String requireTenantId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getTenantId() == null) {
            throw new BusinessException(401, "缺少租户上下文");
        }
        return ctx.getTenantId();
    }
}
