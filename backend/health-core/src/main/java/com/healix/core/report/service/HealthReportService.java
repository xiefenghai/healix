package com.healix.core.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.enums.NotifyDuplicatePolicy;
import com.healix.core.notify.service.NotifyFacade;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.report.catalog.HealthReportGeneratedBy;
import com.healix.core.report.catalog.HealthReportPeriodType;
import com.healix.core.report.catalog.HealthReportStatus;
import com.healix.core.report.domain.HealthReport;
import com.healix.core.report.dto.HealthReportListItemDto;
import com.healix.core.report.dto.HealthReportViewDto;
import com.healix.core.report.mapper.HealthReportMapper;
import com.healix.core.report.service.HealthReportContentBuilder.BuildResult;
import com.healix.core.report.support.HealthReportPeriodSupport;
import com.healix.core.report.support.HealthReportPeriodSupport.PeriodWindow;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.core.worktask.catalog.WorkspaceTaskCloseReason;
import com.healix.core.worktask.catalog.WorkspaceTaskStatus;
import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthReportService {

    private final HealthReportMapper healthReportMapper;
    private final HealthReportContentBuilder contentBuilder;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final WorkspaceTaskMapper workspaceTaskMapper;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;
    private final OrgWorkspaceService orgWorkspaceService;
    private final ArchiveAccessService archiveAccessService;
    private final NotifyFacade notifyFacade;
    private final AccountPatientMapper accountPatientMapper;

    /**
     * 生成报告。fromJob=true 时门槛不足/已存在则返回 null（静默跳过）。
     */
    @Transactional
    public HealthReportViewDto generate(
            String tenantId,
            String orgId,
            String peopleId,
            String periodTypeRaw,
            LocalDate periodStartIn,
            String generatedBy,
            String actorStaffId,
            boolean fromJob) {
        HealthReportPeriodType periodType = HealthReportPeriodType.require(periodTypeRaw);

        PatientCareAssignment assignment = careAssignmentMapper.find(tenantId, peopleId);
        if (assignment == null || !StringUtils.hasText(assignment.getPrimaryOrgId())) {
            if (fromJob) {
                return null;
            }
            throw new BusinessException(400, "患者无主管机构，无法出报");
        }
        if (!orgId.equals(assignment.getPrimaryOrgId())) {
            if (fromJob) {
                return null;
            }
            throw new BusinessException(403, "仅主管机构可生成管理报告");
        }

        LocalDate joinDate = resolvePrimaryJoinDate(orgId, peopleId);
        if (joinDate == null) {
            if (fromJob) {
                return null;
            }
            throw new BusinessException(400, "患者未在主管机构入组，无法出报");
        }

        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        PeriodWindow window = resolvePeriodWindow(periodType, joinDate, periodStartIn, today, fromJob);
        if (window == null) {
            if (fromJob) {
                return null;
            }
            throw new BusinessException(400, "无法确定已结束的报告周期");
        }
        LocalDate periodStart = window.start();
        LocalDate periodEnd = window.end();

        HealthReport existing =
                healthReportMapper.findByPeriod(tenantId, peopleId, periodType.name(), periodStart);
        if (existing != null) {
            if (HealthReportStatus.DRAFT.matches(existing.getStatus())
                    && !fromJob
                    && HealthReportGeneratedBy.MANUAL.matches(generatedBy)) {
                return refreshInternal(existing);
            }
            if (fromJob) {
                return null;
            }
            if (HealthReportStatus.PUBLISHED.matches(existing.getStatus())
                    || HealthReportStatus.SKIPPED.matches(existing.getStatus())) {
                throw new BusinessException(400, "该周期报告已结案，如需重生请先作废");
            }
            throw new BusinessException(400, "该周期已存在报告");
        }

        BuildResult built =
                contentBuilder.build(tenantId, peopleId, periodStart, periodEnd, periodType.name());
        if (!meetsGenerateThreshold(periodType, joinDate, periodEnd, built)) {
            if (fromJob) {
                return null;
            }
            throw new BusinessException(400, thresholdFailMessage(periodType));
        }

        HealthReport row = new HealthReport();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setCareTeamId(resolveCareTeamId(orgId, peopleId));
        row.setPeriodType(periodType.name());
        row.setPeriodStart(periodStart);
        row.setPeriodEnd(periodEnd);
        row.setTitle(buildTitle(periodType, periodStart, periodEnd, joinDate));
        row.setStatus(HealthReportStatus.DRAFT.name());
        row.setSchemaVersion(1);
        row.setContentJson(JsonUtils.toJson(built.content()));
        row.setGeneratedBy(
                StringUtils.hasText(generatedBy) ? generatedBy : HealthReportGeneratedBy.MANUAL.name());
        EntityMeta.onCreate(row);
        healthReportMapper.insert(row);

        String taskId = workspaceTaskGenerator.ensureReportReview(
                tenantId,
                orgId,
                peopleId,
                row.getId(),
                periodType.name(),
                periodStart,
                periodEnd,
                assignment.getPrimaryCareManagerStaffId(),
                fromJob ? "JOB" : "MANUAL");
        if (StringUtils.hasText(taskId)) {
            LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
            healthReportMapper.updateWorkspaceTaskId(row.getId(), taskId, now);
            row.setWorkspaceTaskId(taskId);
        }
        return toView(row);
    }

    /** Job：按入组日错开，仅在「个人周刚结束」时生成。 */
    public HealthReportViewDto tryGenerateEnrollWeekFromJob(
            String tenantId, String orgId, String peopleId) {
        LocalDate joinDate = resolvePrimaryJoinDate(orgId, peopleId);
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        PeriodWindow window = HealthReportPeriodSupport.enrollWeekJustEnded(joinDate, today);
        if (window == null) {
            return null;
        }
        return generate(
                tenantId,
                orgId,
                peopleId,
                HealthReportPeriodType.WEEK.name(),
                window.start(),
                HealthReportGeneratedBy.JOB.name(),
                null,
                true);
    }

    public HealthReportViewDto tryGenerateMonthFromJob(String tenantId, String orgId, String peopleId) {
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        PeriodWindow window = HealthReportPeriodSupport.calendarMonthJustEnded(today);
        if (window == null) {
            return null;
        }
        return generate(
                tenantId,
                orgId,
                peopleId,
                HealthReportPeriodType.MONTH.name(),
                window.start(),
                HealthReportGeneratedBy.JOB.name(),
                null,
                true);
    }

    public HealthReportViewDto tryGenerateQuarterFromJob(String tenantId, String orgId, String peopleId) {
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        PeriodWindow window = HealthReportPeriodSupport.calendarQuarterJustEnded(today);
        if (window == null) {
            return null;
        }
        return generate(
                tenantId,
                orgId,
                peopleId,
                HealthReportPeriodType.QUARTER.name(),
                window.start(),
                HealthReportGeneratedBy.JOB.name(),
                null,
                true);
    }

    @Transactional
    public HealthReportViewDto refresh(String orgId, String id) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        HealthReport row = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, row.getPeopleId());
        assertPrimaryOrg(orgId, row);
        if (!HealthReportStatus.DRAFT.matches(row.getStatus())) {
            throw new BusinessException(400, "仅待审阅报告可刷新快照");
        }
        return refreshInternal(row);
    }

    private HealthReportViewDto refreshInternal(HealthReport row) {
        BuildResult built = contentBuilder.build(
                row.getTenantId(),
                row.getPeopleId(),
                row.getPeriodStart(),
                row.getPeriodEnd(),
                row.getPeriodType());
        HealthReportPeriodType periodType = HealthReportPeriodType.require(row.getPeriodType());
        LocalDate joinDate = resolvePrimaryJoinDate(row.getOrgId(), row.getPeopleId());
        if (!meetsGenerateThreshold(periodType, joinDate, row.getPeriodEnd(), built)) {
            throw new BusinessException(400, thresholdFailMessage(periodType));
        }
        Map<String, Object> content = built.content();
        mergeNarrativeFromExisting(content, row.getContentJson());
        row.setContentJson(JsonUtils.toJson(content));
        row.setSchemaVersion(1);
        row.setTitle(buildTitle(periodType, row.getPeriodStart(), row.getPeriodEnd(), joinDate));
        row.setCareTeamId(resolveCareTeamId(row.getOrgId(), row.getPeopleId()));
        EntityMeta.onUpdate(row);
        healthReportMapper.updateContent(row);
        return toView(healthReportMapper.findById(row.getId()));
    }

    /**
     * 将 AI/人工寄语写入报告草稿（仍为 DRAFT，不发布）。供对话内点评与多轮修订。
     */
    @Transactional
    public HealthReportViewDto saveNarrativeDraft(
            String orgId, String id, String staffId, String staffComment, String nextFocus, String quarterAdvice) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        HealthReport row = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, row.getPeopleId());
        assertPrimaryOrg(orgId, row);
        if (!HealthReportStatus.DRAFT.matches(row.getStatus())) {
            throw new BusinessException(400, "仅待审阅报告可保存点评草稿");
        }
        // 对话内 AI 点评写入草稿：不强制已领取审阅任务（发布时仍校验）

        Map<String, Object> content = readContent(row.getContentJson());
        Map<String, Object> narrative = narrativeMap(content);
        if (StringUtils.hasText(staffComment)) {
            narrative.put("staffComment", staffComment.trim());
        }
        if (StringUtils.hasText(nextFocus)) {
            narrative.put("nextFocus", nextFocus.trim());
        }
        if (StringUtils.hasText(quarterAdvice)) {
            narrative.put("quarterAdvice", quarterAdvice.trim());
        }
        if (StringUtils.hasText(str(narrative.get("staffComment")))) {
            narrative.put("templateTier", null);
        }
        content.put("narrative", narrative);

        row.setContentJson(JsonUtils.toJson(content));
        row.setStaffComment(str(narrative.get("staffComment")));
        EntityMeta.onUpdate(row);
        healthReportMapper.updateDraftNarrative(row);
        return toView(healthReportMapper.findById(id));
    }

    @Transactional
    public HealthReportViewDto publish(
            String orgId, String id, String staffId, String staffComment, String nextFocus, String quarterAdvice) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        HealthReport row = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, row.getPeopleId());
        assertPrimaryOrg(orgId, row);
        if (!HealthReportStatus.DRAFT.matches(row.getStatus())) {
            throw new BusinessException(400, "仅待审阅报告可发布");
        }
        assertTaskAssignee(row, staffId);

        Map<String, Object> content = readContent(row.getContentJson());
        Map<String, Object> narrative = narrativeMap(content);
        if (StringUtils.hasText(staffComment)) {
            narrative.put("staffComment", staffComment.trim());
        }
        if (StringUtils.hasText(nextFocus)) {
            narrative.put("nextFocus", nextFocus.trim());
        }
        if (StringUtils.hasText(quarterAdvice)) {
            narrative.put("quarterAdvice", quarterAdvice.trim());
        }
        if (HealthReportPeriodType.QUARTER.matches(row.getPeriodType())
                && !StringUtils.hasText(str(narrative.get("quarterAdvice")))) {
            throw new BusinessException(400, "三个月报告须填写阶段建议");
        }
        if (!StringUtils.hasText(str(narrative.get("staffComment")))) {
            narrative.put("templateTier", resolveTemplateTier(content));
        } else {
            narrative.put("templateTier", null);
        }
        content.put("narrative", narrative);

        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        row.setStatus(HealthReportStatus.PUBLISHED.name());
        row.setContentJson(JsonUtils.toJson(content));
        row.setStaffComment(str(narrative.get("staffComment")));
        row.setPublishedAt(now);
        row.setPublishedByStaffId(staffId);
        EntityMeta.onUpdate(row);
        healthReportMapper.updateStatus(row);
        closeReportTask(row, staffId, WorkspaceTaskStatus.DONE, WorkspaceTaskCloseReason.FORM);
        maybeOpenPlanCreateFromQuarterAdvice(row, str(narrative.get("quarterAdvice")));
        scheduleReportPublishedNotify(row);
        return toView(healthReportMapper.findById(id));
    }

    /** 三月报发布且填了阶段建议 → 确保工作台有 OPEN 的制定方案单。 */
    private void maybeOpenPlanCreateFromQuarterAdvice(HealthReport row, String quarterAdvice) {
        if (!HealthReportPeriodType.QUARTER.matches(row.getPeriodType()) || !StringUtils.hasText(quarterAdvice)) {
            return;
        }
        try {
            workspaceTaskGenerator.ensurePlanCreateFromReport(
                    row.getTenantId(), row.getOrgId(), row.getPeopleId(), row.getId(), quarterAdvice);
        } catch (Exception ex) {
            log.warn(
                    "ensurePlanCreateFromReport failed tenant={} people={} report={}: {}",
                    row.getTenantId(),
                    row.getPeopleId(),
                    row.getId(),
                    ex.getMessage());
        }
    }

    private void scheduleReportPublishedNotify(HealthReport row) {
        if (row == null || !StringUtils.hasText(row.getId())) {
            return;
        }
        String tenantId = row.getTenantId();
        String peopleId = row.getPeopleId();
        String orgId = row.getOrgId();
        String reportId = row.getId();
        String reportTitle = StringUtils.hasText(row.getTitle()) ? row.getTitle() : "管理报告";
        String periodType = row.getPeriodType();
        String peopleName = resolvePeopleDisplayName(peopleId);

        Runnable publishNotify = () -> {
            try {
                String body = StringUtils.hasText(peopleName)
                        ? peopleName + "的「" + reportTitle + "」已发布，点击查看"
                        : "「" + reportTitle + "」已发布，点击查看";
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("reportId", reportId);
                payload.put("periodType", periodType);
                notifyFacade.publish(NotifyPublishCommand.builder()
                        .tenantId(tenantId)
                        .eventType(NotifyEventType.REPORT_PUBLISHED)
                        .dedupeKey(reportId)
                        .audience(NotifyAudience.C_ACCOUNT)
                        .peopleId(peopleId)
                        .orgId(orgId)
                        .title("管理报告已发布")
                        .body(body)
                        .linkPath("/management-reports/" + reportId)
                        .payload(payload)
                        .onDuplicate(NotifyDuplicatePolicy.IGNORE)
                        .build());
            } catch (Exception ex) {
                log.warn(
                        "REPORT_PUBLISHED notify failed tenant={} people={} report={}: {}",
                        tenantId,
                        peopleId,
                        reportId,
                        ex.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishNotify.run();
                }
            });
        } else {
            publishNotify.run();
        }
    }

    private String resolvePeopleDisplayName(String peopleId) {
        if (!StringUtils.hasText(peopleId)) {
            return null;
        }
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null || !StringUtils.hasText(profile.getDisplayName())) {
            return null;
        }
        return profile.getDisplayName().trim();
    }

    @Transactional
    public HealthReportViewDto skip(String orgId, String id, String staffId, String reason) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        HealthReport row = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, row.getPeopleId());
        assertPrimaryOrg(orgId, row);
        if (!HealthReportStatus.DRAFT.matches(row.getStatus())) {
            throw new BusinessException(400, "仅待审阅报告可跳过");
        }
        assertTaskAssignee(row, staffId);

        Map<String, Object> content = readContent(row.getContentJson());
        Map<String, Object> narrative = narrativeMap(content);
        if (StringUtils.hasText(reason)) {
            narrative.put("skipReason", reason.trim());
        }
        content.put("narrative", narrative);

        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        row.setStatus(HealthReportStatus.SKIPPED.name());
        row.setContentJson(JsonUtils.toJson(content));
        row.setPublishedAt(now);
        row.setPublishedByStaffId(staffId);
        EntityMeta.onUpdate(row);
        healthReportMapper.updateStatus(row);
        closeReportTask(row, staffId, WorkspaceTaskStatus.DONE, WorkspaceTaskCloseReason.FORM);
        return toView(healthReportMapper.findById(id));
    }

    @Transactional
    public void voidReport(String orgId, String id, String staffId, boolean isTenantAdmin) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        HealthReport row = requireInOrg(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, row.getPeopleId());
        assertPrimaryOrg(orgId, row);
        if (HealthReportStatus.PUBLISHED.matches(row.getStatus())) {
            throw new BusinessException(400, "已发布报告不可作废");
        }
        if (!HealthReportStatus.DRAFT.matches(row.getStatus())
                && !HealthReportStatus.SKIPPED.matches(row.getStatus())) {
            throw new BusinessException(400, "当前状态不可作废");
        }
        if (!isTenantAdmin && !canVoidAsHandler(row, staffId)) {
            throw new BusinessException(403, "仅原处理人或租户管理员可作废");
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        healthReportMapper.softDelete(id, now, now);
        cancelReportTask(row, staffId);
    }

    public HealthReportViewDto get(String orgId, String id) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        HealthReport row = requireVisible(orgId, id);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, row.getPeopleId());
        return toView(row);
    }

    public List<HealthReportListItemDto> listByPeople(String orgId, String peopleId, int limit) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        archiveAccessService.assertStaffCanAccessPeople(requireTenantId(), orgId, peopleId);
        int lim = Math.min(Math.max(limit, 1), 100);
        boolean primary = isPrimaryOrg(orgId, requireTenantId(), peopleId);
        String statusFilter = primary ? null : HealthReportStatus.PUBLISHED.name();
        List<HealthReportListItemDto> out = new ArrayList<>();
        for (HealthReport row : healthReportMapper.listByPeople(peopleId, statusFilter, lim)) {
            if (!primary && !orgId.equals(row.getOrgId()) && !HealthReportStatus.PUBLISHED.matches(row.getStatus())) {
                continue;
            }
            if (primary && !orgId.equals(row.getOrgId()) && !HealthReportStatus.PUBLISHED.matches(row.getStatus())) {
                continue;
            }
            out.add(toListItem(row));
        }
        return out;
    }

    public List<HealthReportListItemDto> listPublishedForPatient(String tenantId, String peopleId, int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        List<HealthReportListItemDto> out = new ArrayList<>();
        for (HealthReport row :
                healthReportMapper.listByPeople(peopleId, HealthReportStatus.PUBLISHED.name(), lim)) {
            if (!tenantId.equals(row.getTenantId())) {
                continue;
            }
            out.add(toListItem(row));
        }
        return out;
    }

    /**
     * C 端报告详情：账号维度可读。
     *
     * <p>消息中心按账号投递，但 JWT 当前就诊人可能不是报告归属人；只要该账号绑定了报告对应就诊人即可查看。
     */
    public HealthReportViewDto getPublishedForPatient(
            String tenantId, String accountId, String activePeopleId, String id) {
        HealthReport row = healthReportMapper.findById(id);
        if (row == null
                || !tenantId.equals(row.getTenantId())
                || !HealthReportStatus.PUBLISHED.matches(row.getStatus())) {
            throw new BusinessException(404, "报告不存在");
        }
        if (StringUtils.hasText(activePeopleId) && activePeopleId.equals(row.getPeopleId())) {
            return toView(row);
        }
        if (StringUtils.hasText(accountId)) {
            var card = accountPatientMapper.findByAccountAndPeople(accountId, row.getPeopleId());
            if (card != null && row.getTenantId().equals(card.getTenantId())) {
                return toView(row);
            }
        }
        throw new BusinessException(404, "报告不存在");
    }

    public List<String> listPeopleIdsByPrimaryOrg(String tenantId, String orgId, int limit) {
        return healthReportMapper.listPeopleIdsByPrimaryOrg(tenantId, orgId, Math.min(Math.max(limit, 1), 5000));
    }

    private void assertPrimaryOrg(String orgId, HealthReport row) {
        if (!orgId.equals(row.getOrgId())) {
            throw new BusinessException(403, "仅主管机构可操作该报告");
        }
    }

    private boolean isPrimaryOrg(String orgId, String tenantId, String peopleId) {
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        return a != null && orgId.equals(a.getPrimaryOrgId());
    }

    private void assertTaskAssignee(HealthReport row, String staffId) {
        if (!StringUtils.hasText(row.getWorkspaceTaskId())) {
            throw new BusinessException(400, "报告未关联审阅任务");
        }
        WorkspaceTask task = workspaceTaskMapper.findById(row.getWorkspaceTaskId());
        if (task == null) {
            throw new BusinessException(400, "审阅任务不存在");
        }
        if (!StringUtils.hasText(task.getAssigneeStaffId())) {
            throw new BusinessException(400, "请先领取任务");
        }
        if (!staffId.equals(task.getAssigneeStaffId())) {
            throw new BusinessException(400, "仅当前处理人可操作，请先领取或分派给自己");
        }
    }

    private boolean canVoidAsHandler(HealthReport row, String staffId) {
        if (staffId.equals(row.getPublishedByStaffId())) {
            return true;
        }
        if (StringUtils.hasText(row.getWorkspaceTaskId())) {
            WorkspaceTask task = workspaceTaskMapper.findById(row.getWorkspaceTaskId());
            if (task != null
                    && (staffId.equals(task.getAssigneeStaffId()) || staffId.equals(task.getDoneByStaffId()))) {
                return true;
            }
        }
        return false;
    }

    private void closeReportTask(
            HealthReport row, String staffId, WorkspaceTaskStatus status, WorkspaceTaskCloseReason reason) {
        if (!StringUtils.hasText(row.getWorkspaceTaskId())) {
            return;
        }
        WorkspaceTask task = workspaceTaskMapper.findById(row.getWorkspaceTaskId());
        if (task == null || !WorkspaceTaskStatus.OPEN.matches(task.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        workspaceTaskMapper.close(task.getId(), status.name(), reason.name(), now, staffId, now);
    }

    private void cancelReportTask(HealthReport row, String staffId) {
        if (!StringUtils.hasText(row.getWorkspaceTaskId())) {
            return;
        }
        WorkspaceTask task = workspaceTaskMapper.findById(row.getWorkspaceTaskId());
        if (task == null || !WorkspaceTaskStatus.OPEN.matches(task.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        workspaceTaskMapper.close(
                task.getId(),
                WorkspaceTaskStatus.CANCELLED.name(),
                WorkspaceTaskCloseReason.CANCEL.name(),
                now,
                staffId,
                now);
    }

    private HealthReport requireInOrg(String orgId, String id) {
        HealthReport row = healthReportMapper.findById(id);
        if (row == null) {
            throw new BusinessException(404, "报告不存在");
        }
        if (!orgId.equals(row.getOrgId())) {
            // 非主管机构仅可读 PUBLISHED
            if (!HealthReportStatus.PUBLISHED.matches(row.getStatus())) {
                throw new BusinessException(404, "报告不存在");
            }
        }
        return row;
    }

    private HealthReport requireVisible(String orgId, String id) {
        HealthReport row = healthReportMapper.findById(id);
        if (row == null) {
            throw new BusinessException(404, "报告不存在");
        }
        if (orgId.equals(row.getOrgId())) {
            return row;
        }
        if (!HealthReportStatus.PUBLISHED.matches(row.getStatus())) {
            throw new BusinessException(404, "报告不存在");
        }
        return row;
    }

    private String resolveCareTeamId(String orgId, String peopleId) {
        CareTeamMember member = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        return member == null ? null : member.getTeamId();
    }

    private LocalDate resolvePrimaryJoinDate(String orgId, String peopleId) {
        PatientOrgMembership m = membershipMapper.findByOrgAndPeople(orgId, peopleId);
        if (m == null || !MembershipStatusEnum.ACTIVE.matches(m.getStatus()) || m.getJoinedAt() == null) {
            return null;
        }
        return m.getJoinedAt().toLocalDate();
    }

    private PeriodWindow resolvePeriodWindow(
            HealthReportPeriodType periodType,
            LocalDate joinDate,
            LocalDate periodStartIn,
            LocalDate today,
            boolean fromJob) {
        if (periodType == HealthReportPeriodType.WEEK) {
            if (periodStartIn != null) {
                PeriodWindow w = HealthReportPeriodSupport.enrollWeekFromStart(joinDate, periodStartIn, today);
                if (w == null && !fromJob) {
                    throw new BusinessException(400, "周报起点须与入组日对齐（入组日+7n），且周期已结束");
                }
                return w;
            }
            if (fromJob) {
                return HealthReportPeriodSupport.enrollWeekJustEnded(joinDate, today);
            }
            PeriodWindow w = HealthReportPeriodSupport.lastCompletedEnrollWeek(joinDate, today);
            if (w == null) {
                throw new BusinessException(400, "入组未满一周，暂无已结束的周报周期");
            }
            return w;
        }
        if (periodType == HealthReportPeriodType.MONTH) {
            if (periodStartIn != null) {
                PeriodWindow w = HealthReportPeriodSupport.calendarMonthFromStart(periodStartIn, today);
                if (w == null && !fromJob) {
                    throw new BusinessException(400, "月报周期须为已结束的自然月");
                }
                return w;
            }
            if (fromJob) {
                return HealthReportPeriodSupport.calendarMonthJustEnded(today);
            }
            return HealthReportPeriodSupport.lastCompletedCalendarMonth(today);
        }
        if (periodStartIn != null) {
            PeriodWindow w = HealthReportPeriodSupport.calendarQuarterFromStart(periodStartIn, today);
            if (w == null && !fromJob) {
                throw new BusinessException(400, "三月报周期须为已结束的自然季");
            }
            return w;
        }
        if (fromJob) {
            return HealthReportPeriodSupport.calendarQuarterJustEnded(today);
        }
        return HealthReportPeriodSupport.lastCompletedCalendarQuarter(today);
    }

    private static boolean meetsGenerateThreshold(
            HealthReportPeriodType periodType, LocalDate joinDate, LocalDate periodEnd, BuildResult built) {
        boolean hasDue = built.planDueCount() > 0 || built.medDueDayCount() > 0;
        if (periodType == HealthReportPeriodType.WEEK) {
            return hasDue;
        }
        long membershipDays = HealthReportPeriodSupport.membershipDaysThrough(joinDate, periodEnd);
        if (periodType == HealthReportPeriodType.MONTH) {
            return hasDue && membershipDays >= 14;
        }
        // QUARTER：入组≥60，且本季有方案/用药 due，或 ≥2 条已办结随访
        if (membershipDays < 60) {
            return false;
        }
        return hasDue || built.followupCount() >= 2;
    }

    private static String thresholdFailMessage(HealthReportPeriodType periodType) {
        return switch (periodType) {
            case WEEK -> "本周期无方案应打或用药应服日，不出报";
            case MONTH -> "月报需入组≥14天且有方案/用药应执行日";
            case QUARTER -> "三月报需入组≥60天，且有方案/用药应执行或≥2条已办结随访";
        };
    }

    private static String buildTitle(
            HealthReportPeriodType type, LocalDate periodStart, LocalDate periodEnd, LocalDate joinDate) {
        if (type == HealthReportPeriodType.WEEK) {
            int idx = HealthReportPeriodSupport.enrollWeekIndex(joinDate, periodStart);
            return "入组第"
                    + idx
                    + "周管理报告（"
                    + periodStart.getMonthValue()
                    + "/"
                    + periodStart.getDayOfMonth()
                    + "–"
                    + periodEnd.getMonthValue()
                    + "/"
                    + periodEnd.getDayOfMonth()
                    + "）";
        }
        if (type == HealthReportPeriodType.MONTH) {
            return periodStart.getYear() + "年" + periodStart.getMonthValue() + "月管理报告";
        }
        int q = ((periodStart.getMonthValue() - 1) / 3) + 1;
        return periodStart.getYear() + "年Q" + q + "管理报告";
    }

    @SuppressWarnings("unchecked")
    private static String resolveTemplateTier(Map<String, Object> content) {
        Map<String, Object> adherence = asMap(content.get("adherence"));
        Map<String, Object> plan = asMap(adherence.get("plan"));
        Map<String, Object> med = asMap(adherence.get("med"));
        int planDue = intVal(plan.get("dueCount"));
        Double rate;
        if (planDue > 0) {
            rate = doubleVal(plan.get("rate"));
        } else {
            rate = doubleVal(med.get("rate"));
        }
        if (rate == null) {
            return "POOR";
        }
        if (rate >= 0.8d) {
            return "GOOD";
        }
        if (rate >= 0.5d) {
            return "FAIR";
        }
        return "POOR";
    }

    private static void mergeNarrativeFromExisting(Map<String, Object> content, String existingJson) {
        Map<String, Object> old = readContent(existingJson);
        Map<String, Object> oldNar = narrativeMap(old);
        Map<String, Object> nar = narrativeMap(content);
        if (StringUtils.hasText(str(oldNar.get("staffComment")))) {
            nar.put("staffComment", oldNar.get("staffComment"));
        }
        if (StringUtils.hasText(str(oldNar.get("nextFocus")))) {
            nar.put("nextFocus", oldNar.get("nextFocus"));
        }
        if (StringUtils.hasText(str(oldNar.get("quarterAdvice")))) {
            nar.put("quarterAdvice", oldNar.get("quarterAdvice"));
        }
        content.put("narrative", nar);
    }

    private static Map<String, Object> readContent(String json) {
        Map<String, Object> map = JsonUtils.fromJson(json, new TypeReference<>() {});
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> narrativeMap(Map<String, Object> content) {
        Object n = content.get("narrative");
        if (n instanceof Map<?, ?> m) {
            return new LinkedHashMap<>((Map<String, Object>) m);
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    private HealthReportListItemDto toListItem(HealthReport row) {
        HealthReportListItemDto dto = new HealthReportListItemDto();
        dto.setId(row.getId());
        dto.setPeopleId(row.getPeopleId());
        PeopleProfile profile = peopleProfileMapper.findById(row.getPeopleId());
        dto.setPeopleName(profile == null ? null : profile.getDisplayName());
        dto.setOrgId(row.getOrgId());
        dto.setPeriodType(row.getPeriodType());
        try {
            dto.setPeriodTypeLabel(HealthReportPeriodType.require(row.getPeriodType()).label());
        } catch (Exception ignored) {
            dto.setPeriodTypeLabel(row.getPeriodType());
        }
        dto.setPeriodStart(row.getPeriodStart());
        dto.setPeriodEnd(row.getPeriodEnd());
        dto.setTitle(row.getTitle());
        dto.setStatus(row.getStatus());
        try {
            dto.setStatusLabel(HealthReportStatus.require(row.getStatus()).label());
        } catch (Exception ignored) {
            dto.setStatusLabel(row.getStatus());
        }
        dto.setStaffComment(row.getStaffComment());
        dto.setWorkspaceTaskId(row.getWorkspaceTaskId());
        dto.setGeneratedBy(row.getGeneratedBy());
        dto.setPublishedAt(row.getPublishedAt());
        dto.setPublishedByStaffId(row.getPublishedByStaffId());
        dto.setGmtCreated(row.getGmtCreated());
        dto.setGmtModified(row.getGmtModified());
        return dto;
    }

    private HealthReportViewDto toView(HealthReport row) {
        if (row == null) {
            return null;
        }
        HealthReportViewDto dto = new HealthReportViewDto();
        dto.setId(row.getId());
        dto.setTenantId(row.getTenantId());
        dto.setOrgId(row.getOrgId());
        dto.setPeopleId(row.getPeopleId());
        PeopleProfile profile = peopleProfileMapper.findById(row.getPeopleId());
        dto.setPeopleName(profile == null ? null : profile.getDisplayName());
        dto.setCareTeamId(row.getCareTeamId());
        dto.setPeriodType(row.getPeriodType());
        try {
            dto.setPeriodTypeLabel(HealthReportPeriodType.require(row.getPeriodType()).label());
        } catch (Exception ignored) {
            dto.setPeriodTypeLabel(row.getPeriodType());
        }
        dto.setPeriodStart(row.getPeriodStart());
        dto.setPeriodEnd(row.getPeriodEnd());
        dto.setTitle(row.getTitle());
        dto.setStatus(row.getStatus());
        try {
            dto.setStatusLabel(HealthReportStatus.require(row.getStatus()).label());
        } catch (Exception ignored) {
            dto.setStatusLabel(row.getStatus());
        }
        dto.setSchemaVersion(row.getSchemaVersion());
        JsonNode content = JsonUtils.readTree(row.getContentJson());
        dto.setContent(content);
        dto.setStaffComment(row.getStaffComment());
        dto.setWorkspaceTaskId(row.getWorkspaceTaskId());
        dto.setPublishedAt(row.getPublishedAt());
        dto.setPublishedByStaffId(row.getPublishedByStaffId());
        if (StringUtils.hasText(row.getPublishedByStaffId())) {
            StaffProfile staff = staffProfileMapper.findById(row.getPublishedByStaffId());
            dto.setPublishedByName(staff == null ? row.getPublishedByStaffId() : staff.getDisplayName());
        }
        dto.setGeneratedBy(row.getGeneratedBy());
        dto.setGmtCreated(row.getGmtCreated());
        dto.setGmtModified(row.getGmtModified());
        return dto;
    }

    private static String requireTenantId() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || !StringUtils.hasText(ctx.getTenantId())) {
            throw new BusinessException(401, "未登录");
        }
        return ctx.getTenantId();
    }

    public static boolean isTenantAdminContext() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return false;
        }
        Set<String> roles = ctx.getRoles();
        return roles.contains(StaffRoleEnum.TENANT_ADMIN.name());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static int intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Double doubleVal(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
