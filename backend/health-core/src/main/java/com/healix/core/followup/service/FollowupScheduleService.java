package com.healix.core.followup.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.followup.catalog.FollowupRecordSource;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.followup.catalog.FollowupRecordStatus;
import com.healix.core.followup.catalog.FollowupRecordType;
import com.healix.core.followup.catalog.FollowupType;
import com.healix.core.followup.domain.FollowupRecord;
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.tenant.domain.Tenant;
import com.healix.core.tenant.mapper.TenantMapper;
import com.healix.core.worktask.catalog.WorkspaceTaskStatus;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 定期随访自动排期：按「距上次定期随访（或入组）间隔」生成 OPEN PERIODIC + FOLLOW_UP。
 *
 * <p>不依赖 RequestContext，可由 Job 调用。已有 OPEN 定期随访的患者跳过，
 * 保证同一患者同时只有一张待办定期随访。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowupScheduleService {

    /** 有执行中方案：随访更密（天） */
    public static final int DEFAULT_INTERVAL_WITH_PLAN = 30;
    /** 无方案：常规回访间隔（天） */
    public static final int DEFAULT_INTERVAL_NO_PLAN = 90;

    private final TenantMapper tenantMapper;
    private final OrganizationMapper organizationMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final FollowupRecordMapper followupRecordMapper;
    private final WorkspaceTaskMapper workspaceTaskMapper;
    private final CarePlanMapper carePlanMapper;
    private final FeatureFlagService featureFlagService;

    public record ScheduleCounts(int orgs, int scanned, int created, int skippedOpen, int skippedNotDue) {}

    public ScheduleCounts scanAll(LocalDate day) {
        LocalDate scanDay = day != null ? day : LocalDate.now(JobCronSupport.ZONE);
        int orgs = 0;
        int scanned = 0;
        int created = 0;
        int skippedOpen = 0;
        int skippedNotDue = 0;
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            for (Organization org : organizationMapper.listByTenant(tenant.getId())) {
                orgs++;
                ScheduleCounts c = scanOrg(tenant.getId(), org.getId(), scanDay);
                scanned += c.scanned();
                created += c.created();
                skippedOpen += c.skippedOpen();
                skippedNotDue += c.skippedNotDue();
            }
        }
        return new ScheduleCounts(orgs, scanned, created, skippedOpen, skippedNotDue);
    }

    @Transactional
    public ScheduleCounts scanOrg(String tenantId, String orgId, LocalDate day) {
        LocalDate scanDay = day != null ? day : LocalDate.now(JobCronSupport.ZONE);
        int scanned = 0;
        int created = 0;
        int skippedOpen = 0;
        int skippedNotDue = 0;

        for (PatientOrgMembership m : membershipMapper.listActiveByOrg(orgId)) {
            if (!MembershipStatusEnum.ACTIVE.matches(m.getStatus()) || !StringUtils.hasText(m.getPeopleId())) {
                continue;
            }
            String peopleId = m.getPeopleId();
            scanned++;

            if (followupRecordMapper.findOpenPeriodicByPeople(orgId, peopleId) != null) {
                skippedOpen++;
                continue;
            }
            LocalDate anchor = resolveAnchorDay(orgId, peopleId, m);
            if (anchor == null) {
                skippedNotDue++;
                continue;
            }
            int interval = resolveIntervalDays(tenantId, peopleId);
            if (anchor.plusDays(interval).isAfter(scanDay)) {
                skippedNotDue++;
                continue;
            }
            if (openScheduled(tenantId, orgId, peopleId, scanDay, anchor, interval)) {
                created++;
            }
        }
        return new ScheduleCounts(1, scanned, created, skippedOpen, skippedNotDue);
    }

    /**
     * @param created false = 复用已有的患者请求单
     */
    public record RequestResult(String followupId, String taskId, boolean created, String message) {}

    /**
     * C 端患者主动申请回访：在其活跃机构开 OPEN 定期随访 + FOLLOW_UP。
     *
     * <p>同一机构已有未办结的患者请求单时直接复用，避免重复提交刷单。
     */
    @Transactional
    public RequestResult requestByPatient(
            String tenantId, String peopleId, String reason, LocalDate preferredDay) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(peopleId)) {
            throw new BusinessException(400, "缺少患者信息");
        }
        featureFlagService.assertEnabled(tenantId, FeatureFlagKeyEnum.PATIENT_FOLLOWUP_REQUEST);
        PatientOrgMembership target = null;
        for (PatientOrgMembership m : membershipMapper.listActiveByPeople(peopleId)) {
            if (!MembershipStatusEnum.ACTIVE.matches(m.getStatus())) {
                continue;
            }
            if (m.getTenantId() != null && !tenantId.equals(m.getTenantId())) {
                continue;
            }
            target = m;
            break;
        }
        if (target == null) {
            throw new BusinessException(400, "尚未加入健康管理机构，无法申请回访");
        }
        String orgId = target.getOrgId();

        FollowupRecord existing = followupRecordMapper.findOpenPeriodicByPeople(orgId, peopleId);
        if (existing != null) {
            return new RequestResult(
                    existing.getId(),
                    existing.getWorkspaceTaskId(),
                    false,
                    "已有待处理的随访安排，健管师会尽快联系您");
        }

        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        LocalDate planned = preferredDay != null && !preferredDay.isBefore(today) ? preferredDay : today;
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        String note = StringUtils.hasText(reason) ? reason.trim() : null;
        if (note != null && note.length() > 200) {
            note = note.substring(0, 200);
        }
        FollowupType followupType = FollowupType.ROUTINE;

        FollowupRecord record = new FollowupRecord();
        record.setTenantId(tenantId);
        record.setOrgId(orgId);
        record.setPeopleId(peopleId);
        record.setRecordType(FollowupRecordType.PERIODIC.name());
        record.setSource(FollowupRecordSource.PATIENT_REQUEST.name());
        record.setStatus(FollowupRecordStatus.OPEN.name());
        record.setTitle(followupType.label());
        record.setSummary(note != null ? "患者申请回访：" + note : "患者申请回访");
        record.setPlannedAt(planned.atTime(LocalTime.of(9, 0)));
        record.setAssigneeStaffId(resolveAssignee(tenantId, peopleId));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("followupType", followupType.name());
        if (note != null) {
            content.put("patientRequestReason", note);
        }
        content.put("patientPreferredDay", planned.toString());
        record.setContentJson(JsonUtils.toJson(content));
        record.setDueAt(today.plusDays(Math.max(0, WorkspaceTaskType.FOLLOW_UP.dueDaysInclusive() - 1))
                .atTime(LocalTime.of(23, 59, 59)));
        EntityMeta.onCreate(record);
        followupRecordMapper.insert(record);

        String taskId = openPatientRequestTask(tenantId, orgId, peopleId, record, followupType, note, planned);
        if (StringUtils.hasText(taskId)) {
            followupRecordMapper.updateWorkspaceTaskId(record.getId(), taskId, now);
        }
        return new RequestResult(record.getId(), taskId, true, "已提交回访申请，健管师会尽快联系您");
    }

    private String openPatientRequestTask(
            String tenantId,
            String orgId,
            String peopleId,
            FollowupRecord record,
            FollowupType followupType,
            String reason,
            LocalDate preferredDay) {
        WorkspaceTaskType type = WorkspaceTaskType.FOLLOW_UP;
        String bizKey = "FU:" + record.getId();
        if (workspaceTaskMapper.findOpen(tenantId, orgId, type.name(), bizKey) != null) {
            return null;
        }
        WorkspaceTask row = new WorkspaceTask();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setTaskType(type.name());
        row.setBizKey(bizKey);
        row.setStatus(WorkspaceTaskStatus.OPEN.name());
        // 患者主动开口，优先级高于系统排期
        row.setPriority("HIGH");
        row.setAssigneeStaffId(resolveAssignee(tenantId, peopleId));
        row.setTitle(type.label());
        row.setSummary(reason != null ? "患者申请回访：" + reason : "患者申请回访");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("followupId", record.getId());
        payload.put("followupType", followupType.name());
        payload.put("patientRequested", true);
        if (reason != null) {
            payload.put("reason", reason);
        }
        payload.put("preferredDay", preferredDay.toString());
        row.setPayloadJson(JsonUtils.toJson(payload));
        row.setSource("PATIENT_REQUEST");
        row.setOpenedAt(LocalDateTime.now(JobCronSupport.ZONE));
        row.setDueAt(record.getDueAt());
        EntityMeta.onCreate(row);
        workspaceTaskMapper.insert(row);
        return row.getId();
    }

    /** 上次定期随访办结日；从未随访则用入组日。 */
    private LocalDate resolveAnchorDay(String orgId, String peopleId, PatientOrgMembership membership) {
        List<FollowupRecord> history =
                followupRecordMapper.listByPeople(orgId, peopleId, FollowupRecordType.PERIODIC.name(), 5);
        for (FollowupRecord r : history) {
            if (FollowupRecordStatus.DONE.matches(r.getStatus()) && r.getCompletedAt() != null) {
                return r.getCompletedAt().toLocalDate();
            }
        }
        return membership.getJoinedAt() == null ? null : membership.getJoinedAt().toLocalDate();
    }

    private int resolveIntervalDays(String tenantId, String peopleId) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        boolean hasActivePlan = plan != null
                && "ACTIVE".equals(plan.getStatus())
                && StringUtils.hasText(plan.getCurrentVersionId());
        return hasActivePlan ? DEFAULT_INTERVAL_WITH_PLAN : DEFAULT_INTERVAL_NO_PLAN;
    }

    private boolean openScheduled(
            String tenantId, String orgId, String peopleId, LocalDate scanDay, LocalDate anchor, int interval) {
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        FollowupType followupType = FollowupType.ROUTINE;

        FollowupRecord record = new FollowupRecord();
        record.setTenantId(tenantId);
        record.setOrgId(orgId);
        record.setPeopleId(peopleId);
        record.setRecordType(FollowupRecordType.PERIODIC.name());
        record.setSource(FollowupRecordSource.SCHEDULE_JOB.name());
        record.setStatus(FollowupRecordStatus.OPEN.name());
        record.setTitle(followupType.label());
        record.setSummary("定期随访排期（间隔 " + interval + " 天）");
        record.setPlannedAt(scanDay.atTime(LocalTime.of(9, 0)));
        record.setAssigneeStaffId(resolveAssignee(tenantId, peopleId));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("followupType", followupType.name());
        record.setContentJson(JsonUtils.toJson(content));
        record.setDueAt(scanDay.plusDays(Math.max(0, WorkspaceTaskType.FOLLOW_UP.dueDaysInclusive() - 1))
                .atTime(LocalTime.of(23, 59, 59)));
        EntityMeta.onCreate(record);

        try {
            followupRecordMapper.insert(record);
            String taskId = openFollowUpTask(tenantId, orgId, peopleId, record, followupType, anchor, interval);
            if (StringUtils.hasText(taskId)) {
                followupRecordMapper.updateWorkspaceTaskId(record.getId(), taskId, now);
            }
            return true;
        } catch (RuntimeException ex) {
            log.warn("schedule followup failed org={} people={}: {}", orgId, peopleId, ex.getMessage());
            return false;
        }
    }

    private String openFollowUpTask(
            String tenantId,
            String orgId,
            String peopleId,
            FollowupRecord record,
            FollowupType followupType,
            LocalDate anchor,
            int interval) {
        WorkspaceTaskType type = WorkspaceTaskType.FOLLOW_UP;
        String bizKey = "FU:" + record.getId();
        if (workspaceTaskMapper.findOpen(tenantId, orgId, type.name(), bizKey) != null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        WorkspaceTask row = new WorkspaceTask();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setTaskType(type.name());
        row.setBizKey(bizKey);
        row.setStatus(WorkspaceTaskStatus.OPEN.name());
        row.setPriority("NORMAL");
        row.setAssigneeStaffId(resolveAssignee(tenantId, peopleId));
        row.setTitle(type.label());
        row.setSummary(followupType.label() + "（定期排期）");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("followupId", record.getId());
        payload.put("followupType", followupType.name());
        payload.put("intervalDays", interval);
        payload.put("anchorDay", anchor == null ? null : anchor.toString());
        row.setPayloadJson(JsonUtils.toJson(payload));
        row.setSource("SCHEDULE_JOB");
        row.setOpenedAt(now);
        row.setDueAt(record.getDueAt());
        EntityMeta.onCreate(row);
        workspaceTaskMapper.insert(row);
        return row.getId();
    }

    private String resolveAssignee(String tenantId, String peopleId) {
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        return a == null ? null : a.getPrimaryCareManagerStaffId();
    }
}
