package com.healix.core.worktask.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.adherence.service.AdherenceQueryService;
import com.healix.core.adherence.service.AdherenceQueryService.PlanStreakSnapshot;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.enums.MembershipStatusEnum;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.followup.catalog.FollowupRecordType;
import com.healix.core.followup.domain.FollowupRecord;
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.worktask.catalog.WorkspaceTaskCloseReason;
import com.healix.core.worktask.catalog.WorkspaceTaskPriority;
import com.healix.core.worktask.catalog.WorkspaceTaskStatus;
import com.healix.core.worktask.catalog.WorkspaceTaskType;
import com.healix.core.worktask.domain.WorkspaceTask;
import com.healix.core.worktask.mapper.WorkspaceTaskMapper;
import com.healix.core.worktask.support.MetricAbnormalEvaluator;
import com.healix.core.worktask.support.MetricAbnormalEvaluator.AbnormalHit;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class WorkspaceTaskGenerator {

    public static final int METRIC_BACKFILL_DAYS = 7;

    public record ScanCounts(
            int expired,
            int teamAssignOpened,
            int planCreateOpened,
            int planNudgeOpened,
            int planNudgeUpdated,
            int metricOpened,
            int metricMerged) {}

    private final WorkspaceTaskMapper taskMapper;
    private final FollowupRecordMapper followupRecordMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final CarePlanMapper carePlanMapper;
    private final VitalRecordMapper vitalRecordMapper;
    private final AdherenceQueryService adherenceQueryService;
    private final MetricAbnormalEvaluator metricAbnormalEvaluator;

    @Transactional
    public ScanCounts scanOrg(String tenantId, String orgId, LocalDate scanDay) {
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        int expired = taskMapper.expireOrphans(orgId, now);
        int teamOpened = 0;
        int planOpened = 0;
        int nudgeOpened = 0;
        int nudgeUpdated = 0;
        int metricOpened = 0;
        int metricMerged = 0;

        for (PatientOrgMembership m : membershipMapper.listActiveByOrg(orgId)) {
            if (!MembershipStatusEnum.ACTIVE.matches(m.getStatus())) {
                continue;
            }
            String peopleId = m.getPeopleId();
            CareTeamMember teamMember = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
            if (teamMember == null) {
                // 扫描时尚未入组即开单（不再要求建档次日）
                if (openIfAbsent(
                        tenantId,
                        orgId,
                        peopleId,
                        WorkspaceTaskType.TEAM_ASSIGN,
                        peopleId,
                        "JOB",
                        scanDay,
                        "尚未加入健管组",
                        Map.of("joinedAt", m.getJoinedAt() == null ? null : m.getJoinedAt().toString()),
                        true)) {
                    teamOpened++;
                }
                continue;
            }
            // 已入组但无 ACTIVE 方案即开单（不再要求入组次日）
            if (!hasActivePlan(tenantId, peopleId)) {
                if (openIfAbsent(
                        tenantId,
                        orgId,
                        peopleId,
                        WorkspaceTaskType.PLAN_CREATE,
                        peopleId,
                        "JOB",
                        scanDay,
                        "入组后尚未发布管理方案",
                        Map.of(
                                "joinedAt",
                                teamMember.getJoinedAt() == null ? null : teamMember.getJoinedAt().toString()),
                        false)) {
                    planOpened++;
                }
            }
            PlanStreakSnapshot streak = adherenceQueryService.lookupPlanStreak(tenantId, peopleId, scanDay);
            if (streak.hasActivePlan() && streak.streakDays() >= 3) {
                int[] nudge = upsertNudge(tenantId, orgId, peopleId, scanDay, streak.streakDays());
                nudgeOpened += nudge[0];
                nudgeUpdated += nudge[1];
            }
            int[] metric = backfillMetrics(tenantId, orgId, peopleId, scanDay);
            metricOpened += metric[0];
            metricMerged += metric[1];
        }
        return new ScanCounts(expired, teamOpened, planOpened, nudgeOpened, nudgeUpdated, metricOpened, metricMerged);
    }

    @Transactional
    public void onPatientJoinedTeam(
            String tenantId,
            String orgId,
            String peopleId,
            String primaryCareManagerStaffId,
            String doneByStaffId) {
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        WorkspaceTask open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.TEAM_ASSIGN.name(), peopleId);
        if (open != null) {
            taskMapper.close(
                    open.getId(),
                    WorkspaceTaskStatus.DONE.name(),
                    WorkspaceTaskCloseReason.CONDITION.name(),
                    now,
                    StringUtils.hasText(doneByStaffId) ? doneByStaffId : null,
                    now);
        }
        if (StringUtils.hasText(primaryCareManagerStaffId)) {
            taskMapper.reassignPublicToStaff(orgId, peopleId, primaryCareManagerStaffId, now);
        }
    }

    @Transactional
    public void onPrimaryCareManagerChanged(
            String orgId, String peopleId, String oldStaffId, String newStaffId) {
        if (!StringUtils.hasText(oldStaffId) || !StringUtils.hasText(newStaffId) || oldStaffId.equals(newStaffId)) {
            return;
        }
        taskMapper.reassignFromStaff(orgId, peopleId, oldStaffId, newStaffId, LocalDateTime.now(JobCronSupport.ZONE));
    }

    @Transactional
    public void onPatientLeftTeam(String orgId, String peopleId) {
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        for (WorkspaceTask task : taskMapper.listOpenByPeople(orgId, peopleId)) {
            taskMapper.close(
                    task.getId(),
                    WorkspaceTaskStatus.EXPIRED.name(),
                    WorkspaceTaskCloseReason.EXPIRED.name(),
                    now,
                    null,
                    now);
        }
    }

    @Transactional
    public void onPlanPublished(
            String tenantId,
            String orgId,
            String peopleId,
            String doneByStaffId,
            String carePlanId,
            String carePlanVersionId) {
        WorkspaceTask open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_CREATE.name(), peopleId);
        if (open == null) {
            return;
        }
        Map<String, Object> payload = JsonUtils.fromJson(open.getPayloadJson(), new TypeReference<>() {});
        if (payload == null) {
            payload = new LinkedHashMap<>();
        } else {
            payload = new LinkedHashMap<>(payload);
        }
        if (StringUtils.hasText(carePlanId)) {
            payload.put("carePlanId", carePlanId);
        }
        if (StringUtils.hasText(carePlanVersionId)) {
            payload.put("carePlanVersionId", carePlanVersionId);
        }
        taskMapper.updatePayload(open.getId(), open.getSummary(), JsonUtils.toJson(payload));
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        taskMapper.close(
                open.getId(),
                WorkspaceTaskStatus.DONE.name(),
                WorkspaceTaskCloseReason.CONDITION.name(),
                now,
                StringUtils.hasText(doneByStaffId) ? doneByStaffId : null,
                now);
    }

    /**
     * 随访勾选「建议调整方案」：确保存在 OPEN 的 PLAN_CREATE（bizKey=peopleId，与入组后制定方案同键）。
     *
     * @return 任务 id（新建或已有 OPEN）
     */
    @Transactional
    public String ensurePlanCreateFromFollowup(
            String tenantId, String orgId, String peopleId, String followupId) {
        WorkspaceTask open =
                taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_CREATE.name(), peopleId);
        if (open != null) {
            return open.getId();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(followupId)) {
            payload.put("fromFollowupId", followupId);
        }
        payload.put("reason", "SUGGEST_PLAN_ADJUST");
        openIfAbsent(
                tenantId,
                orgId,
                peopleId,
                WorkspaceTaskType.PLAN_CREATE,
                peopleId,
                "FOLLOWUP",
                LocalDate.now(JobCronSupport.ZONE),
                "随访建议调整方案",
                payload,
                false);
        open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_CREATE.name(), peopleId);
        return open == null ? null : open.getId();
    }

    /**
     * 三月报填写阶段建议：确保存在 OPEN 的 PLAN_CREATE（bizKey=peopleId，与随访建议同键）。
     *
     * @return 任务 id（新建或已有 OPEN）
     */
    @Transactional
    public String ensurePlanCreateFromReport(
            String tenantId, String orgId, String peopleId, String reportId, String quarterAdvice) {
        WorkspaceTask open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_CREATE.name(), peopleId);
        if (open != null) {
            return open.getId();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        if (StringUtils.hasText(reportId)) {
            payload.put("fromReportId", reportId);
        }
        if (StringUtils.hasText(quarterAdvice)) {
            payload.put("quarterAdvice", clip(quarterAdvice, 200));
        }
        payload.put("reason", "QUARTER_ADVICE");
        openIfAbsent(
                tenantId,
                orgId,
                peopleId,
                WorkspaceTaskType.PLAN_CREATE,
                peopleId,
                "REPORT",
                LocalDate.now(JobCronSupport.ZONE),
                "三月报建议调整方案",
                payload,
                false);
        open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_CREATE.name(), peopleId);
        return open == null ? null : open.getId();
    }

    private static String clip(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    /**
     * 管理报告审阅：一人一单 REPORT_REVIEW（bizKey=reportId）。
     *
     * @return 任务 id（新建或已有 OPEN）
     */
    @Transactional
    public String ensureReportReview(
            String tenantId,
            String orgId,
            String peopleId,
            String reportId,
            String periodType,
            LocalDate periodStart,
            LocalDate periodEnd,
            String assigneeStaffId,
            String source) {
        if (!StringUtils.hasText(reportId)) {
            return null;
        }
        WorkspaceTask open =
                taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.REPORT_REVIEW.name(), reportId);
        if (open != null) {
            return open.getId();
        }
        String typeLabel =
                "WEEK".equals(periodType) ? "周报" : "MONTH".equals(periodType) ? "月报" : "季报";
        String periodLabel = periodStart == null || periodEnd == null
                ? ""
                : periodStart + "～" + periodEnd;
        String summary = typeLabel + "审阅 · " + periodLabel;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("healthReportId", reportId);
        payload.put("periodType", periodType);
        payload.put("periodStart", periodStart == null ? null : periodStart.toString());
        payload.put("periodEnd", periodEnd == null ? null : periodEnd.toString());
        openIfAbsent(
                tenantId,
                orgId,
                peopleId,
                WorkspaceTaskType.REPORT_REVIEW,
                reportId,
                StringUtils.hasText(source) ? source : "JOB",
                LocalDate.now(JobCronSupport.ZONE),
                summary,
                payload,
                false);
        open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.REPORT_REVIEW.name(), reportId);
        if (open != null && StringUtils.hasText(assigneeStaffId)
                && !assigneeStaffId.equals(open.getAssigneeStaffId())) {
            LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
            taskMapper.updateAssignee(open.getId(), assigneeStaffId, now);
            open.setAssigneeStaffId(assigneeStaffId);
        }
        return open == null ? null : open.getId();
    }

    /**
     * 方案医生复核：一版一单 PLAN_REVIEW（bizKey=carePlanVersionId）。
     *
     * <p>指派给主责医生；没有主责医生时留在公共池由本机构医生领取。
     *
     * @return 任务 id（新建或已有 OPEN）
     */
    @Transactional
    public String ensurePlanReview(
            String tenantId,
            String orgId,
            String peopleId,
            String carePlanVersionId,
            String planTitle,
            String doctorStaffId) {
        if (!StringUtils.hasText(carePlanVersionId)) {
            return null;
        }
        WorkspaceTask open =
                taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_REVIEW.name(), carePlanVersionId);
        if (open == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("carePlanVersionId", carePlanVersionId);
            payload.put("planTitle", planTitle);
            openIfAbsent(
                    tenantId,
                    orgId,
                    peopleId,
                    WorkspaceTaskType.PLAN_REVIEW,
                    carePlanVersionId,
                    "PLAN_PUBLISH",
                    LocalDate.now(JobCronSupport.ZONE),
                    "方案待医生复核" + (StringUtils.hasText(planTitle) ? " · " + clip(planTitle, 40) : ""),
                    payload,
                    false);
            open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_REVIEW.name(), carePlanVersionId);
        }
        if (open != null
                && StringUtils.hasText(doctorStaffId)
                && !doctorStaffId.equals(open.getAssigneeStaffId())) {
            taskMapper.updateAssignee(open.getId(), doctorStaffId, LocalDateTime.now(JobCronSupport.ZONE));
            open.setAssigneeStaffId(doctorStaffId);
        }
        return open == null ? null : open.getId();
    }

    /** 医生签署后关掉对应的 PLAN_REVIEW。 */
    @Transactional
    public void onPlanVersionSigned(
            String tenantId, String orgId, String carePlanVersionId, String doneByStaffId) {
        WorkspaceTask open =
                taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_REVIEW.name(), carePlanVersionId);
        if (open == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        taskMapper.close(
                open.getId(),
                WorkspaceTaskStatus.DONE.name(),
                WorkspaceTaskCloseReason.CONDITION.name(),
                now,
                StringUtils.hasText(doneByStaffId) ? doneByStaffId : null,
                now);
    }

    @Transactional
    public void onVitalsWritten(String tenantId, String orgId, String peopleId, List<VitalRecord> written) {
        if (written == null || written.isEmpty()) {
            return;
        }
        String resolvedOrg = resolveOrgId(tenantId, orgId, peopleId);
        if (!StringUtils.hasText(resolvedOrg)) {
            return;
        }
        PatientOrgMembership membership = membershipMapper.findByOrgAndPeople(resolvedOrg, peopleId);
        if (membership == null || !MembershipStatusEnum.ACTIVE.matches(membership.getStatus())) {
            return;
        }
        LocalDateTime from = LocalDateTime.now(JobCronSupport.ZONE).minusYears(5);
        List<VitalRecord> heights = vitalRecordMapper.listByPeople(
                tenantId, peopleId, MetricTypeEnum.HEIGHT.name(), from, LocalDateTime.now(JobCronSupport.ZONE), 50);
        List<VitalRecord> groupExtras = new ArrayList<>(written);
        Set<String> groupIds = new HashSet<>();
        for (VitalRecord row : written) {
            if (StringUtils.hasText(row.getGroupId())) {
                groupIds.add(row.getGroupId());
            }
        }
        for (String gid : groupIds) {
            for (VitalRecord g : vitalRecordMapper.listByGroupId(gid)) {
                if (groupExtras.stream().noneMatch(x -> x.getId().equals(g.getId()))) {
                    groupExtras.add(g);
                }
            }
        }
        List<AbnormalHit> hits = metricAbnormalEvaluator.evaluate(groupExtras, heights);
        Set<String> writtenIds = new HashSet<>();
        Set<String> writtenGroups = new HashSet<>();
        for (VitalRecord row : written) {
            writtenIds.add(row.getId());
            if (StringUtils.hasText(row.getGroupId())) {
                writtenGroups.add(row.getGroupId());
            }
        }
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        ensureSingleOpenMetricAlert(tenantId, resolvedOrg, peopleId);
        for (AbnormalHit hit : hits) {
            if (!writtenIds.contains(hit.sourceRecordId()) && !writtenGroups.contains(hit.sourceRecordId())) {
                boolean weightWritten = written.stream()
                        .anyMatch(r -> MetricTypeEnum.WEIGHT.matches(r.getMetricType())
                                && r.getId().equals(hit.sourceRecordId()));
                if (!weightWritten) {
                    continue;
                }
            }
            applyMetricHit(tenantId, resolvedOrg, peopleId, today, hit, "EVENT");
        }
    }

    private int[] backfillMetrics(String tenantId, String orgId, String peopleId, LocalDate scanDay) {
        LocalDateTime from = scanDay.minusDays(METRIC_BACKFILL_DAYS).atStartOfDay();
        LocalDateTime to = scanDay.plusDays(1).atStartOfDay();
        List<VitalRecord> records = vitalRecordMapper.listByPeople(tenantId, peopleId, null, from, to, 500);
        List<VitalRecord> heights = vitalRecordMapper.listByPeople(
                tenantId, peopleId, MetricTypeEnum.HEIGHT.name(), from.minusYears(5), to, 50);
        ensureSingleOpenMetricAlert(tenantId, orgId, peopleId);
        int opened = 0;
        int merged = 0;
        for (AbnormalHit hit : metricAbnormalEvaluator.evaluate(records, heights)) {
            int r = applyMetricHit(tenantId, orgId, peopleId, scanDay, hit, "JOB");
            if (r == 1) {
                opened++;
            } else if (r == 2) {
                merged++;
            }
        }
        return new int[] {opened, merged};
    }

    /** @return 0 skip, 1 opened, 2 merged into existing OPEN */
    private int applyMetricHit(
            String tenantId, String orgId, String peopleId, LocalDate day, AbnormalHit hit, String source) {
        if (consumedSourceIds(orgId, peopleId).contains(hit.sourceRecordId())) {
            return 0;
        }
        WorkspaceTask open = ensureSingleOpenMetricAlert(tenantId, orgId, peopleId);
        if (open != null) {
            appendHit(open, hit);
            return 2;
        }
        List<Map<String, Object>> hits = List.of(MetricAbnormalEvaluator.hitEntry(hit));
        boolean ok = openIfAbsent(
                tenantId,
                orgId,
                peopleId,
                WorkspaceTaskType.METRIC_ALERT,
                MetricAbnormalEvaluator.patientBizKey(peopleId),
                source,
                day,
                MetricAbnormalEvaluator.buildSummary(hits),
                MetricAbnormalEvaluator.aggregatePayload(new ArrayList<>(hits)),
                false);
        return ok ? 1 : 0;
    }

    /**
     * 同一患者只保留一张 OPEN 异常单：合并历史多张（旧按测量开单）后关掉多余的。
     */
    private WorkspaceTask ensureSingleOpenMetricAlert(String tenantId, String orgId, String peopleId) {
        List<WorkspaceTask> opens = new ArrayList<>();
        for (WorkspaceTask t : taskMapper.listOpenByPeople(orgId, peopleId)) {
            if (WorkspaceTaskType.METRIC_ALERT.matches(t.getTaskType())) {
                opens.add(t);
            }
        }
        if (opens.isEmpty()) {
            return null;
        }
        if (opens.size() == 1 && peopleId.equals(opens.get(0).getBizKey())) {
            return normalizeHitsPayload(opens.get(0));
        }

        List<Map<String, Object>> mergedHits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String assignee = null;
        String source = "EVENT";
        for (WorkspaceTask t : opens) {
            if (assignee == null && StringUtils.hasText(t.getAssigneeStaffId())) {
                assignee = t.getAssigneeStaffId();
            }
            if (StringUtils.hasText(t.getSource())) {
                source = t.getSource();
            }
            for (Map<String, Object> h : MetricAbnormalEvaluator.extractHits(t.getPayloadJson())) {
                Object id = h.get("sourceRecordId");
                if (id != null) {
                    if (!seen.add(String.valueOf(id))) {
                        continue;
                    }
                }
                mergedHits.add(h);
            }
        }

        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        for (WorkspaceTask t : opens) {
            taskMapper.close(
                    t.getId(),
                    WorkspaceTaskStatus.DONE.name(),
                    WorkspaceTaskCloseReason.CONDITION.name(),
                    now,
                    null,
                    now);
        }

        boolean ok = openIfAbsent(
                tenantId,
                orgId,
                peopleId,
                WorkspaceTaskType.METRIC_ALERT,
                MetricAbnormalEvaluator.patientBizKey(peopleId),
                source,
                LocalDate.now(JobCronSupport.ZONE),
                MetricAbnormalEvaluator.buildSummary(mergedHits),
                MetricAbnormalEvaluator.aggregatePayload(mergedHits),
                false);
        WorkspaceTask created = taskMapper.findOpen(
                tenantId, orgId, WorkspaceTaskType.METRIC_ALERT.name(), peopleId);
        if (created != null && assignee != null) {
            taskMapper.updateAssignee(created.getId(), assignee, now);
            created.setAssigneeStaffId(assignee);
        }
        if (!ok && created == null) {
            log.warn("failed to consolidate METRIC_ALERT for people {}", peopleId);
        }
        return created;
    }

    private WorkspaceTask normalizeHitsPayload(WorkspaceTask open) {
        String json = open.getPayloadJson();
        List<Map<String, Object>> hits = MetricAbnormalEvaluator.extractHits(json);
        if (hits.isEmpty()) {
            return open;
        }
        boolean already = StringUtils.hasText(json) && json.contains("\"hits\"");
        int count = MetricAbnormalEvaluator.hitCount(json);
        if (already && count == hits.size()) {
            return open;
        }
        String summary = MetricAbnormalEvaluator.buildSummary(hits);
        String payloadJson = JsonUtils.toJson(MetricAbnormalEvaluator.aggregatePayload(hits));
        taskMapper.updatePayload(open.getId(), summary, payloadJson);
        open.setSummary(summary);
        open.setPayloadJson(payloadJson);
        return open;
    }

    private void appendHit(WorkspaceTask open, AbnormalHit hit) {
        List<Map<String, Object>> hits = new ArrayList<>(MetricAbnormalEvaluator.extractHits(open.getPayloadJson()));
        for (Map<String, Object> existing : hits) {
            if (hit.sourceRecordId().equals(String.valueOf(existing.get("sourceRecordId")))) {
                return;
            }
        }
        hits.add(MetricAbnormalEvaluator.hitEntry(hit));
        String summary = MetricAbnormalEvaluator.buildSummary(hits);
        String payloadJson = JsonUtils.toJson(MetricAbnormalEvaluator.aggregatePayload(hits));
        taskMapper.updatePayload(open.getId(), summary, payloadJson);
        open.setSummary(summary);
        open.setPayloadJson(payloadJson);
    }

    private Set<String> consumedSourceIds(String orgId, String peopleId) {
        Set<String> ids = new HashSet<>();
        for (WorkspaceTask t : taskMapper.listMetricAlertsByPeople(orgId, peopleId)) {
            if (t.getBizKey() != null && t.getBizKey().contains(":")) {
                String fromKey = MetricAbnormalEvaluator.sourceIdFromBizKey(t.getBizKey());
                if (StringUtils.hasText(fromKey) && !fromKey.equals(peopleId)) {
                    ids.add(fromKey);
                }
            }
            ids.addAll(MetricAbnormalEvaluator.sourceIdsFromPayload(t.getPayloadJson()));
        }
        return ids;
    }

    public record StaffNudgeResult(String taskId, boolean created, boolean reused) {}

    /**
     * 看板人工催办：开或复用 OPEN 的 PLAN_NUDGE（不走 Job 冷却规则）。
     */
    @Transactional
    public StaffNudgeResult openPlanNudgeFromBoard(
            String tenantId, String orgId, String peopleId, int streakDays, LocalDate boardDay) {
        LocalDate day = boardDay != null ? boardDay : LocalDate.now(JobCronSupport.ZONE);
        int streak = Math.max(0, streakDays);
        WorkspaceTask open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_NUDGE.name(), peopleId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("streakDays", streak);
        payload.put("source", "STAFF_BOARD");
        String summary = streak >= 3
                ? "连续 " + streak + " 天方案未完成（看板催办）"
                : "看板催办：方案打卡跟进";
        if (open != null) {
            taskMapper.updatePayload(open.getId(), summary, JsonUtils.toJson(payload));
            return new StaffNudgeResult(open.getId(), false, true);
        }
        boolean ok = openIfAbsent(
                tenantId,
                orgId,
                peopleId,
                WorkspaceTaskType.PLAN_NUDGE,
                peopleId,
                "STAFF_BOARD",
                day,
                summary,
                payload,
                false);
        WorkspaceTask created = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_NUDGE.name(), peopleId);
        if (created == null) {
            throw new IllegalStateException("PLAN_NUDGE 创建失败");
        }
        return new StaffNudgeResult(created.getId(), ok, !ok);
    }

    /**
     * 患者补打卡使某日方案应打任务已完成（due&gt;0 且无 pending）时：
     * 各机构 OPEN 的 {@code PLAN_NUDGE} 条件自闭（{@code close_reason=CONDITION}）。
     *
     * @return 关闭的任务数
     */
    @Transactional
    public int onPlanDayCompletedByCheckin(String tenantId, String peopleId, LocalDate completedDay) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(peopleId) || completedDay == null) {
            return 0;
        }
        LocalDateTime now = LocalDateTime.now(JobCronSupport.ZONE);
        int closed = 0;
        for (PatientOrgMembership m : membershipMapper.listActiveByPeople(peopleId)) {
            if (m.getTenantId() != null && !tenantId.equals(m.getTenantId())) {
                continue;
            }
            WorkspaceTask open =
                    taskMapper.findOpen(tenantId, m.getOrgId(), WorkspaceTaskType.PLAN_NUDGE.name(), peopleId);
            if (open == null) {
                continue;
            }
            closePlanNudgeByCondition(open, completedDay, now, "PATIENT_CHECKIN");
            closed++;
        }
        return closed;
    }

    private void closePlanNudgeByCondition(
            WorkspaceTask open, LocalDate completedDay, LocalDateTime now, String trigger) {
        Map<String, Object> payload = JsonUtils.fromJson(open.getPayloadJson(), new TypeReference<>() {});
        if (payload == null) {
            payload = new LinkedHashMap<>();
        } else {
            payload = new LinkedHashMap<>(payload);
        }
        payload.put("autoClose", trigger);
        if (completedDay != null) {
            payload.put("completedCheckinDate", completedDay.toString());
        }
        String base = StringUtils.hasText(open.getSummary()) ? open.getSummary() : WorkspaceTaskType.PLAN_NUDGE.label();
        String summary = base + " · 患者已补打卡，条件关闭";
        taskMapper.updatePayload(open.getId(), summary, JsonUtils.toJson(payload));
        taskMapper.close(
                open.getId(),
                WorkspaceTaskStatus.DONE.name(),
                WorkspaceTaskCloseReason.CONDITION.name(),
                now,
                null,
                now);
    }

    private int[] upsertNudge(String tenantId, String orgId, String peopleId, LocalDate scanDay, int streakDays) {
        WorkspaceTask open = taskMapper.findOpen(tenantId, orgId, WorkspaceTaskType.PLAN_NUDGE.name(), peopleId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("streakDays", streakDays);
        String summary = "连续 " + streakDays + " 天方案未完成";
        if (open != null) {
            // 连续已打断 / 当日已恢复：条件自闭，避免健管师仍看到过期催办
            if (streakDays < 3) {
                closePlanNudgeByCondition(open, scanDay, LocalDateTime.now(JobCronSupport.ZONE), "STREAK_RECOVERED");
                return new int[] {0, 0};
            }
            taskMapper.updatePayload(open.getId(), summary, JsonUtils.toJson(payload));
            return new int[] {0, 1};
        }
        if (!shouldOpenNudge(tenantId, orgId, peopleId, scanDay, streakDays)) {
            return new int[] {0, 0};
        }
        boolean ok = openIfAbsent(
                tenantId,
                orgId,
                peopleId,
                WorkspaceTaskType.PLAN_NUDGE,
                peopleId,
                "JOB",
                scanDay,
                summary,
                payload,
                false);
        return new int[] {ok ? 1 : 0, 0};
    }

    private boolean shouldOpenNudge(String tenantId, String orgId, String peopleId, LocalDate scanDay, int streakDays) {
        if (streakDays < 3) {
            return false;
        }
        WorkspaceTask last = taskMapper.findLatestClosedNudge(orgId, peopleId);
        if (last == null) {
            return true;
        }
        if (WorkspaceTaskCloseReason.FORM.matches(last.getCloseReason())) {
            String result = lastNudgeContactResult(last.getId());
            if ("UNREACHED".equals(result)) {
                return true;
            }
            LocalDate closeDay = last.getDoneAt() == null
                    ? calendarDay(last.getGmtModified())
                    : last.getDoneAt().toLocalDate();
            return adherenceQueryService.hasCompletedDueDayAfter(tenantId, peopleId, closeDay, scanDay);
        }
        if (WorkspaceTaskCloseReason.CANCEL.matches(last.getCloseReason())) {
            LocalDate closeDay = last.getDoneAt() == null
                    ? calendarDay(last.getGmtModified())
                    : last.getDoneAt().toLocalDate();
            return adherenceQueryService.hasCompletedDueDayAfter(tenantId, peopleId, closeDay, scanDay);
        }
        // CONDITION（患者补打卡）等：关单时本轮已结束；之后再连续≥3 即可开新催办
        return true;
    }

    private String lastNudgeContactResult(String taskId) {
        FollowupRecord form =
                followupRecordMapper.findLatestByTaskAndType(taskId, FollowupRecordType.PLAN_NUDGE.name());
        if (form == null || !StringUtils.hasText(form.getContentJson())) {
            return null;
        }
        Map<String, Object> content = JsonUtils.fromJson(form.getContentJson(), new TypeReference<>() {});
        if (content == null) {
            return null;
        }
        Object v = content.get("contactResult");
        return v == null ? null : String.valueOf(v);
    }

    private boolean openIfAbsent(
            String tenantId,
            String orgId,
            String peopleId,
            WorkspaceTaskType type,
            String bizKey,
            String source,
            LocalDate day,
            String summary,
            Map<String, Object> payload,
            boolean forcePublic) {
        if (taskMapper.findOpen(tenantId, orgId, type.name(), bizKey) != null) {
            return false;
        }
        WorkspaceTask row = new WorkspaceTask();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setTaskType(type.name());
        row.setBizKey(bizKey);
        row.setStatus(WorkspaceTaskStatus.OPEN.name());
        row.setPriority(type.defaultPriority().name());
        row.setAssigneeStaffId(forcePublic ? null : resolveAssignee(tenantId, peopleId));
        row.setTitle(type.label());
        row.setSummary(summary);
        row.setPayloadJson(payload == null ? null : JsonUtils.toJson(payload));
        row.setSource(source);
        row.setOpenedAt(LocalDateTime.now(JobCronSupport.ZONE));
        row.setDueAt(day.plusDays(Math.max(0, type.dueDaysInclusive() - 1)).atTime(LocalTime.of(23, 59, 59)));
        EntityMeta.onCreate(row);
        try {
            taskMapper.insert(row);
            return true;
        } catch (RuntimeException ex) {
            if (isDuplicateOpen(ex)) {
                log.debug("workspace task already open {} {}", type, bizKey);
                return false;
            }
            throw ex;
        }
    }

    private static boolean isDuplicateOpen(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof DuplicateKeyException) {
                return true;
            }
            String msg = t.getMessage();
            if (msg != null && msg.toLowerCase().contains("duplicate")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private String resolveAssignee(String tenantId, String peopleId) {
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        if (a == null || !StringUtils.hasText(a.getPrimaryCareManagerStaffId())) {
            return null;
        }
        return a.getPrimaryCareManagerStaffId();
    }

    private String resolveOrgId(String tenantId, String orgId, String peopleId) {
        if (StringUtils.hasText(orgId)) {
            return orgId;
        }
        PatientCareAssignment a = careAssignmentMapper.find(tenantId, peopleId);
        if (a != null && StringUtils.hasText(a.getPrimaryOrgId())) {
            PatientOrgMembership m = membershipMapper.findByOrgAndPeople(a.getPrimaryOrgId(), peopleId);
            if (m != null && MembershipStatusEnum.ACTIVE.matches(m.getStatus())) {
                return a.getPrimaryOrgId();
            }
        }
        List<PatientOrgMembership> list = membershipMapper.listActiveByPeople(peopleId);
        return list.isEmpty() ? null : list.get(0).getOrgId();
    }

    private boolean hasActivePlan(String tenantId, String peopleId) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        return plan != null && "ACTIVE".equals(plan.getStatus()) && StringUtils.hasText(plan.getCurrentVersionId());
    }

    private static LocalDate calendarDay(LocalDateTime t) {
        if (t == null) {
            return LocalDate.MIN;
        }
        return t.toLocalDate();
    }

    private static Map<String, Object> readPayload(String json) {
        Map<String, Object> map = JsonUtils.fromJson(json, new TypeReference<>() {});
        return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
    }
}
