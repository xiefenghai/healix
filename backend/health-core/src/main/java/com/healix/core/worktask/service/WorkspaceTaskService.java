package com.healix.core.worktask.service;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.PageResult;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.JsonUtils;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.identity.mapper.StaffOrgBindingMapper;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.followup.catalog.FollowupRecordSource;
import com.healix.core.followup.catalog.FollowupRecordStatus;
import com.healix.core.followup.catalog.FollowupRecordType;
import com.healix.core.followup.domain.FollowupRecord;
import com.healix.core.followup.dto.FollowupRecordViewDto;
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.followup.service.FollowupService;
import com.healix.core.followup.support.FollowupContentValidator;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.core.worktask.catalog.WorkspaceTaskCloseReason;
import com.healix.core.worktask.catalog.WorkspaceTaskStatus;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.dto.WorkspaceTaskDetailDto;
import com.healix.core.worktask.dto.WorkspaceTaskListItemDto;
import com.healix.core.worktask.dto.WorkspaceTaskQuery;
import com.healix.core.worktask.dto.WorkspaceTaskSummaryDto;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import com.healix.core.worktask.support.MetricAbnormalEvaluator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class WorkspaceTaskService {

    private static final Set<String> CONTACT_CHANNELS = Set.of("PHONE", "WECOM", "IN_PERSON", "OTHER");
    private static final Set<String> CONTACT_RESULTS = Set.of("REACHED", "UNREACHED");

    private final OrgWorkspaceService orgWorkspaceService;
    private final WorkspaceTaskMapper taskMapper;
    private final FollowupRecordMapper followupRecordMapper;
    private final FollowupService followupService;
    private final PeopleProfileMapper peopleProfileMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final StaffOrgBindingMapper staffOrgBindingMapper;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final CareTeamMapper careTeamMapper;
    private final AuditService auditService;

    public WorkspaceTaskSummaryDto summary(String orgId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();
        List<String> exclude = doctorPublicExclude();
        WorkspaceTaskSummaryDto dto = new WorkspaceTaskSummaryDto();
        dto.setPublicOpenCount(taskMapper.countOpenPublic(orgId, exclude.isEmpty() ? null : exclude));
        dto.setMineOpenCount(taskMapper.countOpenMine(orgId, staffId));
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        dto.setDoneTodayCount(taskMapper.countDoneToday(
                requireTenantId(), orgId, staffId, today.atStartOfDay(), today.plusDays(1).atStartOfDay()));
        return dto;
    }

    public PageResult<WorkspaceTaskListItemDto> page(
            String orgId,
            String pool,
            String taskType,
            String status,
            String careTeamId,
            String keyword,
            int page,
            int size) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        String staffId = requireStaffId();
        String resolvedPool = normalizePool(pool);
        if ("ALL".equals(resolvedPool) && !isTenantAdmin()) {
            throw new BusinessException(403, "仅租户管理员可查看全部任务");
        }
        int safePage = Math.max(1, page);
        int safeSize = Math.min(100, Math.max(1, size));
        WorkspaceTaskQuery q = new WorkspaceTaskQuery();
        q.setTenantId(requireTenantId());
        q.setOrgId(orgId);
        q.setPool(resolvedPool);
        q.setAssigneeStaffId(staffId);
        q.setTaskType(blankToNull(taskType));
        q.setCareTeamId(blankToNull(careTeamId));
        q.setKeyword(blankToNull(keyword));
        if ("DONE".equals(resolvedPool)) {
            q.setStatus(WorkspaceTaskStatus.DONE.name());
            // 与摘要卡「今日已办」同口径：本人今日办结（含 done_by / 原处理人）
            LocalDate today = LocalDate.now(JobCronSupport.ZONE);
            q.setDoneFrom(today.atStartOfDay());
            q.setDoneTo(today.plusDays(1).atStartOfDay());
        } else if ("ALL".equals(resolvedPool)) {
            q.setStatus(blankToNull(status));
        } else {
            q.setStatus(WorkspaceTaskStatus.OPEN.name());
        }
        if ("PUBLIC".equals(resolvedPool)) {
            List<String> exclude = doctorPublicExclude();
            q.setExcludeTaskTypes(exclude.isEmpty() ? null : exclude);
        }
        q.setOffset((safePage - 1) * safeSize);
        q.setPageSize(safeSize);
        long total = taskMapper.countByQuery(q);
        if (total == 0) {
            return new PageResult<>(0, List.of());
        }
        List<WorkspaceTaskListItemDto> items = new ArrayList<>();
        for (WorkspaceTask row : taskMapper.listByQuery(q)) {
            items.add(toListItem(row));
        }
        return new PageResult<>(total, items);
    }

    public WorkspaceTaskDetailDto get(String orgId, String id) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        WorkspaceTask task = requireVisible(orgId, id);
        return toDetail(task);
    }

    @Transactional
    public WorkspaceTaskDetailDto claim(String orgId, String id, String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        WorkspaceTask task = requireTask(orgId, id);
        assertOpen(task);
        if (task.getAssigneeStaffId() != null) {
            throw new BusinessException(409, "任务已被领取");
        }
        WorkspaceTaskType type = WorkspaceTaskType.require(task.getTaskType());
        if (type.doctorPublicHidden() && isDoctorOnly()) {
            throw new BusinessException(403, "医生不能从公共池领取该类任务");
        }
        String staffId = requireStaffId();
        int n = taskMapper.claim(id, staffId, LocalDateTime.now(JobCronSupport.ZONE));
        if (n == 0) {
            throw new BusinessException(409, "任务已被领取");
        }
        audit(actorAccountId, AuditActionEnum.WORKSPACE_TASK_CLAIM, "workspace_task", id, task.getPeopleId(),
                AuditDetails.of("taskType", task.getTaskType(), "peopleId", task.getPeopleId()));
        return toDetail(requireTask(orgId, id));
    }

    @Transactional
    public WorkspaceTaskDetailDto assign(String orgId, String id, String targetStaffId, String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        WorkspaceTask task = requireTask(orgId, id);
        assertOpen(task);
        String staffId = requireStaffId();
        boolean canAssign = isTenantAdmin() || staffId.equals(task.getAssigneeStaffId());
        if (!canAssign) {
            throw new BusinessException(403, "仅当前处理人或管理员可分派");
        }
        if (!StringUtils.hasText(targetStaffId)) {
            throw new BusinessException(400, "请选择处理人");
        }
        StaffProfile profile = staffProfileMapper.findById(targetStaffId);
        if (profile == null || !requireTenantId().equals(profile.getTenantId())) {
            throw new BusinessException(400, "员工不存在");
        }
        if (staffOrgBindingMapper.countBinding(targetStaffId, orgId) <= 0 && !isTenantAdmin()) {
            throw new BusinessException(400, "该员工未绑定本机构");
        }
        taskMapper.updateAssignee(id, targetStaffId, LocalDateTime.now(JobCronSupport.ZONE));
        audit(actorAccountId, AuditActionEnum.WORKSPACE_TASK_ASSIGN, "workspace_task", id, task.getPeopleId(),
                AuditDetails.of("taskType", task.getTaskType(), "peopleId", task.getPeopleId(), "staffId", targetStaffId));
        return toDetail(requireTask(orgId, id));
    }

    @Transactional
    public WorkspaceTaskDetailDto release(String orgId, String id, String reason, String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        WorkspaceTask task = requireTask(orgId, id);
        assertOpen(task);
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(400, "请填写退回原因");
        }
        String staffId = requireStaffId();
        if (!isTenantAdmin() && !staffId.equals(task.getAssigneeStaffId())) {
            throw new BusinessException(403, "仅当前处理人或管理员可退回公共池");
        }
        taskMapper.updateAssignee(id, null, LocalDateTime.now(JobCronSupport.ZONE));
        audit(actorAccountId, AuditActionEnum.WORKSPACE_TASK_RELEASE, "workspace_task", id, task.getPeopleId(),
                AuditDetails.of("taskType", task.getTaskType(), "peopleId", task.getPeopleId(), "reason", reason.trim()));
        return toDetail(requireTask(orgId, id));
    }

    @Transactional
    public WorkspaceTaskDetailDto cancel(String orgId, String id, String reason, String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        if (!isTenantAdmin()) {
            throw new BusinessException(403, "仅租户管理员可取消任务");
        }
        WorkspaceTask task = requireTask(orgId, id);
        assertOpen(task);
        if (!StringUtils.hasText(reason)) {
            throw new BusinessException(400, "请填写取消原因");
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        taskMapper.close(
                id,
                WorkspaceTaskStatus.CANCELLED.name(),
                WorkspaceTaskCloseReason.CANCEL.name(),
                now,
                requireStaffId(),
                now);
        audit(actorAccountId, AuditActionEnum.WORKSPACE_TASK_DONE, "workspace_task", id, task.getPeopleId(),
                AuditDetails.of(
                        "taskType", task.getTaskType(),
                        "peopleId", task.getPeopleId(),
                        "closeReason", "CANCEL",
                        "reason", reason.trim()));
        return toDetail(requireTask(orgId, id));
    }

    @Transactional
    public WorkspaceTaskDetailDto submitForm(
            String orgId, String id, Map<String, Object> content, String actorAccountId) {
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        WorkspaceTask task = requireTask(orgId, id);
        assertOpen(task);
        String staffId = requireStaffId();
        if (!StringUtils.hasText(task.getAssigneeStaffId())) {
            throw new BusinessException(400, "请先领取任务");
        }
        if (!staffId.equals(task.getAssigneeStaffId())) {
            throw new BusinessException(400, "仅当前处理人可提交，请先分派给自己或使用处理人账号登录");
        }
        WorkspaceTaskType type = WorkspaceTaskType.require(task.getTaskType());
        if (!type.requiresFormToClose()) {
            throw new BusinessException(400, "该类任务不通过随访关单");
        }
        if (type == WorkspaceTaskType.FOLLOW_UP) {
            followupService.completeFromWorkspaceTask(task, content, staffId, actorAccountId);
            return toDetail(requireTask(orgId, id));
        }
        FollowupRecordType recordType = FollowupRecordType.forWorkspaceTask(type.name());
        Map<String, Object> formContent = content == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(content);
        if (recordType == FollowupRecordType.METRIC_REVIEW) {
            // 办结时固化任务上的异常指标，便于随访详情回看
            List<Map<String, Object>> hits = MetricAbnormalEvaluator.extractHits(task.getPayloadJson());
            Object existingHits = formContent.get("hits");
            boolean missingHits = existingHits == null
                    || (existingHits instanceof java.util.Collection<?> c && c.isEmpty());
            if (!hits.isEmpty() && missingHits) {
                formContent.put("hits", hits);
            }
        }
        Map<String, Object> normalized =
                recordType == FollowupRecordType.PLAN_NUDGE
                        ? validateNudge(formContent)
                        : FollowupContentValidator.validateMetricReview(formContent);
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        FollowupRecord record = new FollowupRecord();
        record.setTenantId(task.getTenantId());
        record.setOrgId(orgId);
        record.setPeopleId(task.getPeopleId());
        record.setWorkspaceTaskId(id);
        record.setRecordType(recordType.name());
        record.setSource(FollowupRecordSource.WORKSPACE_TASK.name());
        record.setStatus(FollowupRecordStatus.DONE.name());
        record.setTitle(recordType.label());
        record.setSummary(task.getSummary());
        record.setDueAt(task.getDueAt());
        record.setAssigneeStaffId(staffId);
        record.setContactChannel(str(normalized.get("followupMethod")));
        if (!StringUtils.hasText(record.getContactChannel())) {
            record.setContactChannel(str(normalized.get("contactChannel")));
        }
        if (!StringUtils.hasText(record.getContactChannel())) {
            record.setContactChannel(null);
        }
        record.setContactResult(str(normalized.get("contactResult")));
        if (!StringUtils.hasText(record.getContactResult())) {
            record.setContactResult(null);
        }
        record.setContentJson(JsonUtils.toJson(normalized));
        record.setCompletedAt(now);
        record.setCompletedByStaffId(staffId);
        EntityMeta.onCreate(record);
        followupRecordMapper.insert(record);
        taskMapper.close(
                id,
                WorkspaceTaskStatus.DONE.name(),
                WorkspaceTaskCloseReason.FORM.name(),
                now,
                staffId,
                now);
        audit(actorAccountId, AuditActionEnum.FOLLOWUP_RECORD_COMPLETE, "followup_record", record.getId(), task.getPeopleId(),
                AuditDetails.of(
                        "taskType", task.getTaskType(),
                        "peopleId", task.getPeopleId(),
                        "recordType", recordType.name()));
        audit(actorAccountId, AuditActionEnum.WORKSPACE_TASK_DONE, "workspace_task", id, task.getPeopleId(),
                AuditDetails.of("taskType", task.getTaskType(), "peopleId", task.getPeopleId(), "closeReason", "FORM"));
        return toDetail(requireTask(orgId, id));
    }

    private Map<String, Object> validateNudge(Map<String, Object> raw) {
        Map<String, Object> c = raw == null ? Map.of() : raw;
        String channel = str(c.get("contactChannel"));
        String result = str(c.get("contactResult"));
        if (!CONTACT_CHANNELS.contains(channel)) {
            throw new BusinessException(400, "请选择联系渠道");
        }
        if (!CONTACT_RESULTS.contains(result)) {
            throw new BusinessException(400, "请选择联系结果");
        }
        Boolean informed = bool(c.get("informedCheckin"));
        if ("REACHED".equals(result) && informed == null) {
            throw new BusinessException(400, "已接通时请确认是否已告知打卡");
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("contactChannel", channel);
        out.put("contactResult", result);
        if (informed != null) {
            out.put("informedCheckin", informed);
        }
        if (StringUtils.hasText(str(c.get("patientFeedback")))) {
            out.put("patientFeedback", str(c.get("patientFeedback")));
        }
        if (StringUtils.hasText(str(c.get("note")))) {
            out.put("note", str(c.get("note")));
        }
        return out;
    }

    private WorkspaceTask requireTask(String orgId, String id) {
        WorkspaceTask task = taskMapper.findById(id);
        if (task == null || !orgId.equals(task.getOrgId())) {
            throw new BusinessException(404, "任务不存在");
        }
        return task;
    }

    private WorkspaceTask requireVisible(String orgId, String id) {
        WorkspaceTask task = requireTask(orgId, id);
        if (isTenantAdmin()) {
            return task;
        }
        String staffId = requireStaffId();
        if (task.getAssigneeStaffId() == null) {
            WorkspaceTaskType type = WorkspaceTaskType.require(task.getTaskType());
            if (type.doctorPublicHidden() && isDoctorOnly()) {
                throw new BusinessException(403, "无权查看该任务");
            }
            return task;
        }
        if (!staffId.equals(task.getAssigneeStaffId())) {
            throw new BusinessException(403, "无权查看他人个人池任务");
        }
        return task;
    }

    private static void assertOpen(WorkspaceTask task) {
        if (!WorkspaceTaskStatus.OPEN.matches(task.getStatus())) {
            throw new BusinessException(400, "任务已关闭");
        }
    }

    private WorkspaceTaskListItemDto toListItem(WorkspaceTask row) {
        WorkspaceTaskListItemDto dto = new WorkspaceTaskListItemDto();
        dto.setId(row.getId());
        dto.setPeopleId(row.getPeopleId());
        PeopleProfile people = peopleProfileMapper.findById(row.getPeopleId());
        dto.setPeopleName(people == null ? row.getPeopleId() : people.getDisplayName());
        CareTeamMember member = careTeamMemberMapper.findPeopleInOrg(row.getOrgId(), row.getPeopleId());
        if (member != null) {
            dto.setCareTeamId(member.getTeamId());
            CareTeam team = careTeamMapper.findById(member.getTeamId());
            dto.setCareTeamName(team == null ? null : team.getName());
        }
        WorkspaceTaskType type = WorkspaceTaskType.require(row.getTaskType());
        dto.setTaskType(type.name());
        dto.setTaskTypeLabel(type.label());
        dto.setSummary(row.getSummary());
        dto.setPool(row.getAssigneeStaffId() == null ? "PUBLIC" : "MINE");
        dto.setStatus(row.getStatus());
        dto.setPriority(row.getPriority());
        dto.setAssigneeStaffId(row.getAssigneeStaffId());
        if (row.getAssigneeStaffId() != null) {
            StaffProfile staff = staffProfileMapper.findById(row.getAssigneeStaffId());
            dto.setAssigneeName(staff == null ? row.getAssigneeStaffId() : staff.getDisplayName());
        }
        dto.setDoneByStaffId(row.getDoneByStaffId());
        if (row.getDoneByStaffId() != null) {
            StaffProfile closer = staffProfileMapper.findById(row.getDoneByStaffId());
            dto.setDoneByName(closer == null ? row.getDoneByStaffId() : closer.getDisplayName());
        }
        dto.setOpenedAt(row.getOpenedAt());
        dto.setDueAt(row.getDueAt());
        dto.setDoneAt(row.getDoneAt());
        dto.setDeepLink(deepLink(type, row.getPeopleId(), dto.getPeopleName()));
        int hits = MetricAbnormalEvaluator.hitCount(row.getPayloadJson());
        dto.setHitCount(hits);
        dto.setSuppressedCount(Math.max(0, hits - 1));
        return dto;
    }

    private WorkspaceTaskDetailDto toDetail(WorkspaceTask row) {
        WorkspaceTaskListItemDto list = toListItem(row);
        WorkspaceTaskDetailDto dto = new WorkspaceTaskDetailDto();
        dto.setId(list.getId());
        dto.setPeopleId(list.getPeopleId());
        dto.setPeopleName(list.getPeopleName());
        dto.setCareTeamId(list.getCareTeamId());
        dto.setCareTeamName(list.getCareTeamName());
        dto.setTaskType(list.getTaskType());
        dto.setTaskTypeLabel(list.getTaskTypeLabel());
        dto.setTitle(row.getTitle());
        dto.setSummary(list.getSummary());
        dto.setPool(list.getPool());
        dto.setStatus(list.getStatus());
        dto.setPriority(list.getPriority());
        dto.setAssigneeStaffId(list.getAssigneeStaffId());
        dto.setAssigneeName(list.getAssigneeName());
        dto.setDoneByStaffId(list.getDoneByStaffId());
        dto.setDoneByName(list.getDoneByName());
        dto.setSource(row.getSource());
        dto.setCloseReason(row.getCloseReason());
        dto.setOpenedAt(list.getOpenedAt());
        dto.setDueAt(list.getDueAt());
        dto.setDoneAt(row.getDoneAt());
        dto.setDeepLink(list.getDeepLink());
        dto.setPayload(JsonUtils.readTree(row.getPayloadJson()));
        List<FollowupRecordViewDto> followups = new ArrayList<>();
        for (FollowupRecord rec : followupRecordMapper.listByTaskId(row.getId())) {
            FollowupRecordViewDto v = new FollowupRecordViewDto();
            v.setId(rec.getId());
            v.setRecordType(rec.getRecordType());
            FollowupRecordType rt = FollowupRecordType.require(rec.getRecordType());
            v.setRecordTypeLabel(rt.label());
            v.setSource(rec.getSource());
            v.setStatus(rec.getStatus());
            v.setTitle(rec.getTitle());
            v.setSummary(rec.getSummary());
            v.setWorkspaceTaskId(rec.getWorkspaceTaskId());
            v.setContent(JsonUtils.readTree(rec.getContentJson()));
            v.setContactChannel(rec.getContactChannel());
            v.setContactResult(rec.getContactResult());
            v.setCompletedByStaffId(rec.getCompletedByStaffId());
            if (rec.getCompletedByStaffId() != null) {
                StaffProfile staff = staffProfileMapper.findById(rec.getCompletedByStaffId());
                v.setCompletedByName(staff == null ? rec.getCompletedByStaffId() : staff.getDisplayName());
            }
            v.setCompletedAt(rec.getCompletedAt());
            v.setPlannedAt(rec.getPlannedAt());
            v.setDueAt(rec.getDueAt());
            followups.add(v);
        }
        dto.setFollowups(followups);
        return dto;
    }

    private static String deepLink(WorkspaceTaskType type, String peopleId, String name) {
        StringBuilder q = new StringBuilder();
        appendQuery(q, "name", name);
        String qs = q.isEmpty() ? "" : "?" + q;
        return switch (type) {
            case TEAM_ASSIGN -> "/workspace/patients/" + peopleId + "/archive" + qs;
            case PLAN_CREATE, PLAN_NUDGE, PLAN_REVIEW -> "/workspace/patients/" + peopleId + "/care-plan" + qs;
            case METRIC_ALERT -> "/workspace/patients/" + peopleId + "/observations/metrics" + qs;
            case FOLLOW_UP -> "/workspace/patients/" + peopleId + "/followups" + qs;
            case REPORT_REVIEW -> "/workspace/patients/" + peopleId + "/health-reports" + qs;
        };
    }

    private static void appendQuery(StringBuilder q, String key, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (!q.isEmpty()) {
            q.append('&');
        }
        q.append(java.net.URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8));
        q.append('=');
        q.append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
    }

    private void audit(
            String actorAccountId,
            AuditActionEnum action,
            String resourceType,
            String resourceId,
            String peopleId,
            String details) {
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                requireTenantId(),
                action.name(),
                resourceType,
                resourceId,
                peopleId,
                details);
    }

    private List<String> doctorPublicExclude() {
        if (isDoctorOnly()) {
            return List.of(
                    WorkspaceTaskType.TEAM_ASSIGN.name(),
                    WorkspaceTaskType.PLAN_CREATE.name(),
                    WorkspaceTaskType.REPORT_REVIEW.name());
        }
        return List.of();
    }

    private static String normalizePool(String pool) {
        if (!StringUtils.hasText(pool) || "PUBLIC".equalsIgnoreCase(pool)) {
            return "PUBLIC";
        }
        if ("MINE".equalsIgnoreCase(pool)) {
            return "MINE";
        }
        if ("DONE".equalsIgnoreCase(pool)) {
            return "DONE";
        }
        if ("ALL".equalsIgnoreCase(pool)) {
            return "ALL";
        }
        throw new BusinessException(400, "未知任务池");
    }

    private boolean isTenantAdmin() {
        return currentRoles().contains(StaffRoleEnum.TENANT_ADMIN.name());
    }

    private boolean isDoctorOnly() {
        Set<String> roles = currentRoles();
        return roles.contains(StaffRoleEnum.DOCTOR.name())
                && !roles.contains(StaffRoleEnum.CARE_MANAGER.name())
                && !roles.contains(StaffRoleEnum.TENANT_ADMIN.name());
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return Set.of();
        }
        return ctx.getRoles();
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

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static Boolean bool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return null;
    }
}
