package com.healix.core.followup.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.followup.catalog.FollowupRecordSource;
import com.healix.core.followup.support.FollowupContentValidator;
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
import com.healix.core.worktask.catalog.WorkspaceTaskPriority;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 定期随访自动排期：按「距上次定期随访（或入组）间隔」生成 OPEN PERIODIC + FOLLOW_UP。
 *
 * <p>不依赖 RequestContext，可由 Job 调用。已有 OPEN 定期随访的患者跳过，
 * 保证同一患者同时只有一张待办定期随访。
 *
 * <p>入组当场开首诊失败时，同一 Job 在入组日起 {@link #ONBOARDING_COMPENSATE_DAYS} 天内补开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowupScheduleService {

    /** 入组后缺少首诊时，定时任务继续补偿的天数（含入组当天）。 */
    public static final int ONBOARDING_COMPENSATE_DAYS = 7;

    private final TenantMapper tenantMapper;
    private final OrganizationMapper organizationMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final FollowupRecordMapper followupRecordMapper;
    private final WorkspaceTaskMapper workspaceTaskMapper;
    private final CarePlanMapper carePlanMapper;
    private final FeatureFlagService featureFlagService;
    private final FollowupScheduleRuleService scheduleRuleService;
    private final ObjectProvider<FollowupScheduleService> self;

    public record ScheduleCounts(
            int orgs, int scanned, int created, int skippedOpen, int skippedNotDue, int skippedDisabled) {}

    /** 首诊补偿：新开随访、给已有随访补工作台任务、跳过、当场仍失败。 */
    public record OnboardingCompensateCounts(int scanned, int created, int repaired, int skipped, int failed) {}

    public ScheduleCounts scanAll(LocalDate day) {
        LocalDate scanDay = day != null ? day : LocalDate.now(JobCronSupport.ZONE);
        int orgs = 0;
        int scanned = 0;
        int created = 0;
        int skippedOpen = 0;
        int skippedNotDue = 0;
        int skippedDisabled = 0;
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            for (Organization org : organizationMapper.listByTenant(tenant.getId())) {
                orgs++;
                ScheduleCounts c = scanOrg(tenant.getId(), org.getId(), scanDay);
                scanned += c.scanned();
                created += c.created();
                skippedOpen += c.skippedOpen();
                skippedNotDue += c.skippedNotDue();
                skippedDisabled += c.skippedDisabled();
            }
        }
        return new ScheduleCounts(orgs, scanned, created, skippedOpen, skippedNotDue, skippedDisabled);
    }

    @Transactional
    public ScheduleCounts scanOrg(String tenantId, String orgId, LocalDate day) {
        LocalDate scanDay = day != null ? day : LocalDate.now(JobCronSupport.ZONE);
        int scanned = 0;
        int created = 0;
        int skippedOpen = 0;
        int skippedNotDue = 0;
        int skippedDisabled = 0;
        FollowupScheduleRuleService.RuleSet rules = scheduleRuleService.loadRuleSet(tenantId);

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
            Integer interval = scheduleRuleService.resolveIntervalDays(
                    rules, tenantId, orgId, peopleId, hasActivePlan(tenantId, peopleId));
            if (interval == null) {
                skippedDisabled++;
                continue;
            }
            if (anchor.plusDays(interval).isAfter(scanDay)) {
                skippedNotDue++;
                continue;
            }
            if (openScheduled(tenantId, orgId, peopleId, scanDay, anchor, interval)) {
                created++;
            }
        }
        return new ScheduleCounts(1, scanned, created, skippedOpen, skippedNotDue, skippedDisabled);
    }

    /**
     * 补偿入组首诊：活跃成员在入组日起 {@link #ONBOARDING_COMPENSATE_DAYS} 天内仍没有入组/首诊时补开。
     *
     * <p>已有 OPEN 或 DONE 的不新开；OPEN 缺工作台任务时不限入组日，直接补任务。
     * 更早入组且从未有过首诊的不补，避免给早已在管的患者追开一张首诊。
     */
    public OnboardingCompensateCounts compensateMissingOnboarding(LocalDate day) {
        LocalDate scanDay = day != null ? day : LocalDate.now(JobCronSupport.ZONE);
        int scanned = 0;
        int created = 0;
        int repaired = 0;
        int skipped = 0;
        int failed = 0;
        FollowupScheduleService svc = self.getIfAvailable();
        if (svc == null) {
            svc = this;
        }
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            for (Organization org : organizationMapper.listByTenant(tenant.getId())) {
                for (PatientOrgMembership m : membershipMapper.listActiveByOrg(org.getId())) {
                    if (!MembershipStatusEnum.ACTIVE.matches(m.getStatus())
                            || !StringUtils.hasText(m.getPeopleId())) {
                        continue;
                    }
                    scanned++;
                    String peopleId = m.getPeopleId();
                    FollowupRecord existing = findOnboardingPeriodic(org.getId(), peopleId);
                    boolean recent = withinOnboardingCompensateWindow(joinDay(m), scanDay);
                    if (existing == null && !recent) {
                        skipped++;
                        continue;
                    }
                    if (existing != null && FollowupRecordStatus.DONE.matches(existing.getStatus())) {
                        skipped++;
                        continue;
                    }
                    boolean missingTask = existing != null && !StringUtils.hasText(existing.getWorkspaceTaskId());
                    String taskId = svc.ensureOnboardingOnJoin(tenant.getId(), org.getId(), peopleId);
                    if (existing == null) {
                        if (StringUtils.hasText(taskId)) {
                            created++;
                        } else {
                            failed++;
                        }
                    } else if (missingTask && StringUtils.hasText(taskId)) {
                        repaired++;
                    } else {
                        skipped++;
                    }
                }
            }
        }
        log.info(
                "onboarding compensate day={} scanned={} created={} repaired={} skipped={} failed={}",
                scanDay,
                scanned,
                created,
                repaired,
                skipped,
                failed);
        return new OnboardingCompensateCounts(scanned, created, repaired, skipped, failed);
    }

    /** 入组日落在扫描日往前 {@link #ONBOARDING_COMPENSATE_DAYS} 天（含当天）内。 */
    public static boolean withinOnboardingCompensateWindow(LocalDate joinedDay, LocalDate scanDay) {
        if (joinedDay == null || scanDay == null) {
            return false;
        }
        return !joinedDay.isBefore(scanDay.minusDays(ONBOARDING_COMPENSATE_DAYS - 1L));
    }

    private static LocalDate joinDay(PatientOrgMembership membership) {
        if (membership.getJoinedAt() != null) {
            return membership.getJoinedAt().toLocalDate();
        }
        return membership.getGmtCreated() == null ? null : membership.getGmtCreated().toLocalDate();
    }

    /**
     * 患者新入组机构后立即开「入组/首诊随访」+ 工作台 FOLLOW_UP（幂等）。
     *
     * <p>不等定期排期 Job：默认间隔 30/90 天才会开常规回访，首诊应在入组当天出现。
     * 已有 OPEN 或 DONE 的入组/首诊则跳过；OPEN 缺任务时补开。
     *
     * @return 工作台任务 ID；跳过或失败时为 null
     */
    @Transactional
    public String ensureOnboardingOnJoin(String tenantId, String orgId, String peopleId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(orgId) || !StringUtils.hasText(peopleId)) {
            return null;
        }
        FollowupRecord existing = findOnboardingPeriodic(orgId, peopleId);
        if (existing != null) {
            if (FollowupRecordStatus.DONE.matches(existing.getStatus())) {
                return null;
            }
            if (FollowupRecordStatus.OPEN.matches(existing.getStatus())) {
                if (StringUtils.hasText(existing.getWorkspaceTaskId())) {
                    return existing.getWorkspaceTaskId();
                }
                LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
                String taskId = openOnboardingTask(tenantId, orgId, peopleId, existing);
                if (StringUtils.hasText(taskId)) {
                    followupRecordMapper.updateWorkspaceTaskId(existing.getId(), taskId, now);
                }
                return taskId;
            }
        }

        LocalDate day = LocalDate.now(JobCronSupport.ZONE);
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        FollowupType followupType = FollowupType.ONBOARDING;

        FollowupRecord record = new FollowupRecord();
        record.setTenantId(tenantId);
        record.setOrgId(orgId);
        record.setPeopleId(peopleId);
        record.setRecordType(FollowupRecordType.PERIODIC.name());
        record.setSource(FollowupRecordSource.MEMBERSHIP_JOIN.name());
        record.setStatus(FollowupRecordStatus.OPEN.name());
        record.setTitle(followupType.label());
        record.setSummary("入组后首诊随访");
        record.setPlannedAt(day.atTime(LocalTime.of(9, 0)));
        record.setAssigneeStaffId(resolveAssignee(tenantId, peopleId));
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("followupType", followupType.name());
        record.setContentJson(JsonUtils.toJson(content));
        record.setDueAt(day.plusDays(Math.max(0, WorkspaceTaskType.FOLLOW_UP.dueDaysInclusive() - 1))
                .atTime(LocalTime.of(23, 59, 59)));
        EntityMeta.onCreate(record);

        try {
            followupRecordMapper.insert(record);
            String taskId = openOnboardingTask(tenantId, orgId, peopleId, record);
            if (StringUtils.hasText(taskId)) {
                followupRecordMapper.updateWorkspaceTaskId(record.getId(), taskId, now);
            }
            log.info(
                    "onboarding followup opened tenant={} org={} people={} followupId={} taskId={}",
                    tenantId,
                    orgId,
                    peopleId,
                    record.getId(),
                    taskId);
            return taskId;
        } catch (RuntimeException ex) {
            log.warn(
                    "ensure onboarding followup failed org={} people={}: {}",
                    orgId,
                    peopleId,
                    ex.getMessage());
            return null;
        }
    }

    private FollowupRecord findOnboardingPeriodic(String orgId, String peopleId) {
        List<FollowupRecord> rows =
                followupRecordMapper.listByPeople(orgId, peopleId, FollowupRecordType.PERIODIC.name(), 50);
        FollowupRecord open = null;
        for (FollowupRecord row : rows) {
            Map<String, Object> content = JsonUtils.fromJson(row.getContentJson(), new TypeReference<>() {});
            String t = content == null ? "" : FollowupContentValidator.str(content.get("followupType"));
            if (!FollowupType.ONBOARDING.name().equals(t)) {
                continue;
            }
            if (FollowupRecordStatus.DONE.matches(row.getStatus())) {
                return row;
            }
            if (FollowupRecordStatus.OPEN.matches(row.getStatus()) && open == null) {
                open = row;
            }
        }
        return open;
    }

    private String openOnboardingTask(
            String tenantId, String orgId, String peopleId, FollowupRecord record) {
        WorkspaceTaskType type = WorkspaceTaskType.FOLLOW_UP;
        String bizKey = "FU:" + record.getId();
        WorkspaceTask existing = workspaceTaskMapper.findOpen(tenantId, orgId, type.name(), bizKey);
        if (existing != null) {
            return existing.getId();
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        WorkspaceTask row = new WorkspaceTask();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setTaskType(type.name());
        row.setBizKey(bizKey);
        row.setStatus(WorkspaceTaskStatus.OPEN.name());
        row.setPriority(type.defaultPriority().name());
        row.setAssigneeStaffId(resolveAssignee(tenantId, peopleId));
        row.setTitle(type.label());
        row.setSummary(FollowupType.ONBOARDING.label());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("followupId", record.getId());
        payload.put("followupType", FollowupType.ONBOARDING.name());
        row.setPayloadJson(JsonUtils.toJson(payload));
        row.setSource(FollowupRecordSource.MEMBERSHIP_JOIN.name());
        row.setOpenedAt(now);
        row.setDueAt(record.getDueAt());
        EntityMeta.onCreate(row);
        workspaceTaskMapper.insert(row);
        return row.getId();
    }

    /**
     * @param created false = 复用已有的患者请求单
     */
    public record RequestResult(String followupId, String taskId, boolean created, String message) {}

    /**
     * C 端患者主动申请回访：在其活跃机构开 OPEN 定期随访 + FOLLOW_UP。
     *
     * <p>只与已有未办结的「患者申请」单去重；健管师手工/用药随访等 OPEN PERIODIC
     * 不挡申请。若申请单仍在但关联任务已关，补开任务。
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

        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        LocalDate planned = preferredDay != null && !preferredDay.isBefore(today) ? preferredDay : today;
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        String note = StringUtils.hasText(reason) ? reason.trim() : null;
        if (note != null && note.length() > 200) {
            note = note.substring(0, 200);
        }
        FollowupType followupType = FollowupType.ROUTINE;

        FollowupRecord existing = followupRecordMapper.findOpenPatientRequestByPeople(orgId, peopleId);
        if (existing != null) {
            String taskId = ensurePatientRequestTask(
                    tenantId, orgId, peopleId, existing, followupType, note, planned, now);
            return new RequestResult(
                    existing.getId(),
                    taskId,
                    false,
                    "已有待处理的回访申请，健管师会尽快联系您");
        }

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

    /** 复用申请单时，若任务已取消/缺失则补开，保证管理端待办可见。 */
    private String ensurePatientRequestTask(
            String tenantId,
            String orgId,
            String peopleId,
            FollowupRecord record,
            FollowupType followupType,
            String reason,
            LocalDate preferredDay,
            LocalDateTime now) {
        if (StringUtils.hasText(record.getWorkspaceTaskId())) {
            WorkspaceTask linked = workspaceTaskMapper.findById(record.getWorkspaceTaskId());
            if (linked != null && WorkspaceTaskStatus.OPEN.matches(linked.getStatus())) {
                return linked.getId();
            }
        }
        String taskId = openPatientRequestTask(tenantId, orgId, peopleId, record, followupType, reason, preferredDay);
        if (StringUtils.hasText(taskId)) {
            followupRecordMapper.updateWorkspaceTaskId(record.getId(), taskId, now);
            record.setWorkspaceTaskId(taskId);
        }
        return taskId;
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
        // 患者主动开口，抬到 HIGH（高于类型默认 MEDIUM）
        row.setPriority(WorkspaceTaskPriority.HIGH.name());
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

    private boolean hasActivePlan(String tenantId, String peopleId) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        return plan != null && "ACTIVE".equals(plan.getStatus()) && StringUtils.hasText(plan.getCurrentVersionId());
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
        row.setPriority(type.defaultPriority().name());
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
