package com.healix.core.adherence.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.JsonUtils;
import com.healix.core.adherence.dto.AdherenceBoardActionResultDto;
import com.healix.core.adherence.dto.AdherenceBoardEscalateRequest;
import com.healix.core.adherence.dto.AdherenceNudgeResultDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.followup.catalog.FollowupType;
import com.healix.core.followup.domain.FollowupRecord;
import com.healix.core.followup.dto.FollowupRecordViewDto;
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.followup.service.FollowupService;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.notify.service.StaffNudgeService;
import com.healix.core.notify.service.StaffNudgeService.NudgeResult;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.workspace.service.OrgWorkspaceService;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import com.healix.core.worktask.service.WorkspaceTaskGenerator.StaffNudgeResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 依从性看板「看见红 → 催办」：一键开随访 / 打卡跟进（不做用药催办单）。
 */
@Service
@RequiredArgsConstructor
public class AdherenceBoardActionService {

    private final OrgWorkspaceService orgWorkspaceService;
    private final ArchiveAccessService archiveAccessService;
    private final FollowupService followupService;
    private final FollowupRecordMapper followupRecordMapper;
    private final WorkspaceTaskGenerator workspaceTaskGenerator;
    private final StaffNudgeService staffNudgeService;
    private final AuditService auditService;

    @Transactional
    public AdherenceBoardActionResultDto escalate(
            String tenantId,
            String orgId,
            String peopleId,
            AdherenceBoardEscalateRequest req,
            String actorAccountId) {
        if (!StringUtils.hasText(peopleId)) {
            throw new BusinessException(400, "peopleId 不能为空");
        }
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);

        AdherenceBoardEscalateRequest body = req == null ? new AdherenceBoardEscalateRequest() : req;
        boolean wantFollowup = body.getOpenFollowup() == null || Boolean.TRUE.equals(body.getOpenFollowup());
        int streak = body.getStreakDays() == null ? 0 : Math.max(0, body.getStreakDays());
        boolean wantNudge = body.getOpenPlanNudge() != null
                ? Boolean.TRUE.equals(body.getOpenPlanNudge())
                : streak >= 3;
        if (!wantFollowup && !wantNudge) {
            throw new BusinessException(400, "请至少选择开随访或开打卡跟进");
        }

        boolean planInc = Boolean.TRUE.equals(body.getPlanIncomplete());
        boolean medInc = Boolean.TRUE.equals(body.getMedIncomplete());
        LocalDate boardDate = body.getDate() != null ? body.getDate() : LocalDate.now(JobCronSupport.ZONE);

        AdherenceBoardActionResultDto result = new AdherenceBoardActionResultDto();
        List<String> parts = new ArrayList<>();

        if (wantFollowup) {
            FollowupType type = resolveFollowupType(planInc, medInc, streak);
            FollowupRecord existing = followupRecordMapper.findOpenPeriodicByPeople(orgId, peopleId);
            if (existing != null) {
                result.setFollowupId(existing.getId());
                result.setFollowupWorkspaceTaskId(existing.getWorkspaceTaskId());
                result.setFollowupType(readFollowupType(existing));
                result.setFollowupReused(true);
                if (StringUtils.hasText(existing.getWorkspaceTaskId())) {
                    parts.add("该患者已有待办随访（未新建），请到工作台「随访」任务或患者「随访」页继续处理");
                } else {
                    parts.add("该患者已有待办随访（未新建），请到患者详情「随访」页继续处理");
                }
            } else {
                FollowupRecordViewDto created = followupService.createPeriodic(
                        orgId,
                        peopleId,
                        type.name(),
                        null,
                        true,
                        false,
                        null,
                        actorAccountId);
                result.setFollowupId(created.getId());
                result.setFollowupWorkspaceTaskId(created.getWorkspaceTaskId());
                result.setFollowupType(type.name());
                result.setFollowupReused(false);
                parts.add("已开「" + type.label() + "」，请到工作台或患者「随访」页处理");
            }
        }

        if (wantNudge) {
            StaffNudgeResult nudge = workspaceTaskGenerator.openPlanNudgeFromBoard(
                    tenantId, orgId, peopleId, streak, boardDate);
            result.setPlanNudgeTaskId(nudge.taskId());
            result.setPlanNudgeCreated(nudge.created());
            result.setPlanNudgeReused(nudge.reused());
            if (nudge.created()) {
                parts.add("已开打卡跟进，请到工作台「打卡跟进」处理");
            } else if (nudge.reused()) {
                parts.add("打卡跟进单已在工作台（未新建），请到「我的待办 / 公共池」继续处理");
            }
        }

        result.setMessage(String.join("；", parts));
        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.ADHERENCE_BOARD_ESCALATE.name(),
                "people",
                peopleId,
                peopleId,
                AuditDetails.of(
                        "followupId", result.getFollowupId(),
                        "followupReused", result.isFollowupReused(),
                        "planNudgeTaskId", result.getPlanNudgeTaskId(),
                        "planNudgeCreated", result.isPlanNudgeCreated(),
                        "boardDate", boardDate.toString(),
                        "streakDays", streak));
        return result;
    }

    /** B 一键提醒患者：站内信 STAFF_NUDGE（同员工同患者同日 upsert）。 */
    @Transactional
    public AdherenceNudgeResultDto nudgePatient(
            String tenantId, String orgId, String peopleId, String staffId, LocalDate date, String actorAccountId) {
        if (!StringUtils.hasText(peopleId)) {
            throw new BusinessException(400, "peopleId 不能为空");
        }
        orgWorkspaceService.requireOrgWorkspaceAccess(orgId);
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);

        LocalDate day = date != null ? date : LocalDate.now(JobCronSupport.ZONE);
        NudgeResult res = staffNudgeService.nudgePatient(tenantId, orgId, peopleId, staffId, day);

        AdherenceNudgeResultDto dto = new AdherenceNudgeResultDto();
        dto.setSent(res.sent());
        dto.setReason(res.reason());
        dto.setMessage(res.message());
        dto.setRecipientCount(res.recipientCount());
        dto.setLinkPath(res.linkPath());

        auditService.record(
                PortalEnum.B.code(),
                actorAccountId,
                "STAFF",
                tenantId,
                AuditActionEnum.STAFF_NUDGE_SEND.name(),
                "people",
                peopleId,
                peopleId,
                AuditDetails.of(
                        "sent", res.sent(),
                        "reason", res.reason(),
                        "recipientCount", res.recipientCount(),
                        "day", day.toString()));
        return dto;
    }

    static FollowupType resolveFollowupType(boolean planIncomplete, boolean medIncomplete, int streakDays) {
        if (medIncomplete && !planIncomplete && streakDays < 3) {
            return FollowupType.MEDICATION;
        }
        if (planIncomplete || streakDays >= 3) {
            return FollowupType.PLAN_ADHERENCE;
        }
        if (medIncomplete) {
            return FollowupType.MEDICATION;
        }
        return FollowupType.ROUTINE;
    }

    private static String readFollowupType(FollowupRecord row) {
        if (row == null || !StringUtils.hasText(row.getContentJson())) {
            return FollowupType.ROUTINE.name();
        }
        try {
            Map<String, Object> map = JsonUtils.fromJson(row.getContentJson(), new TypeReference<>() {});
            if (map == null || map.get("followupType") == null) {
                return FollowupType.ROUTINE.name();
            }
            return FollowupType.require(String.valueOf(map.get("followupType"))).name();
        } catch (Exception e) {
            return FollowupType.ROUTINE.name();
        }
    }
}
