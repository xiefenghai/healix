package com.healix.core.people.service;

import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.service.AssessmentDirtyMarker;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PatientMergeMapper;
import com.healix.core.people.mapper.PeopleProfileMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 患者档案合并（S2）：把「源患者」的数据并入「目标患者」，源档案打上合并标记并软删。
 *
 * <p>冲突处理一律「目标优先」：分派、基础档案、方案头这类一人一行的数据，
 * 目标已有就丢弃源侧那份，而不是覆盖。合并常发生在同一人重复建档之后，
 * 目标通常是被持续维护的那份档案，覆盖它等于把医生确认过的信息换成旧值。
 *
 * <p>时序数据（体征、检验检查、用药、随访、报告、消息）直接改归属：只有全部落到
 * 同一个 people 下，趋势和依从性才是完整的。
 *
 * <p>{@code audit_log} 不迁移：它是操作留痕，改写等于篡改历史。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientMergeService {

    /** 无 people 维度唯一键、可直接改归属的表 */
    private static final List<String> APPEND_ONLY_TABLES = List.of(
            "vital_record",
            "lab_report",
            "exam_report",
            "people_medication",
            "people_medication_intake",
            "followup_record",
            "notify_message",
            "agent_session",
            "agent_interaction_log",
            "care_plan_draft",
            "care_plan_task",
            "care_plan_task_checkin",
            "workspace_task",
            "account_patient",
            "people_activation_invite",
            "diet_meal",
            "people_allergy",
            "people_assessment_snapshot");

    /** 一人一行、目标优先的表 */
    private static final List<String> SINGLETON_TABLES = List.of(
            "people_care_assignment", "people_basic_archive", "care_plan", "diet_goal", "people_allergy_profile");

    /** 「人 + 业务键」唯一的表 → 业务键列：同键目标优先，其余改归属 */
    private static final Map<String, List<String>> KEYED_TABLES = keyedTables();

    private static Map<String, List<String>> keyedTables() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("health_report", List.of("tenant_id", "period_type", "period_start"));
        m.put("exercise_session", List.of("tenant_id", "source", "external_id"));
        m.put("staff_patient_watch", List.of("staff_id", "org_id"));
        m.put("care_chat_thread", List.of("tenant_id", "org_id"));
        return m;
    }

    private final PeopleProfileMapper peopleProfileMapper;
    private final PatientMergeMapper mergeMapper;
    private final AssessmentStoreMapper assessmentStoreMapper;
    private final AssessmentDirtyMarker assessmentDirtyMarker;
    private final AuditService auditService;

    /**
     * @param movedRows   各表改归属的行数
     * @param droppedKeys 因目标已有而丢弃的源侧数据（表名 / 冲突键）
     */
    public record MergeResult(
            String targetPeopleId, String sourcePeopleId, Map<String, Integer> movedRows, List<String> droppedKeys) {}

    /** 预览里展示的数据量：表 → 中文名 */
    private static final Map<String, String> PREVIEW_COUNTS = previewCounts();

    private static Map<String, String> previewCounts() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("vital_record", "体征记录");
        m.put("diet_meal", "饮食记录");
        m.put("exercise_session", "运动记录");
        m.put("people_medication", "用药");
        m.put("followup_record", "随访");
        m.put("health_report", "管理报告");
        m.put("lab_report", "检验报告");
        m.put("exam_report", "检查报告");
        m.put("care_plan_task", "计划任务");
        return m;
    }

    /**
     * @param mobileMasked 中间四位打码
     * @param counts       中文名 → 行数；检索列表不带
     */
    public record Candidate(
            String peopleId,
            String displayName,
            String gender,
            LocalDate birthday,
            String mobileMasked,
            boolean hasAccount,
            List<String> orgNames,
            LocalDateTime createdAt,
            Map<String, Integer> counts) {}

    public List<Candidate> search(String tenantId, String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        List<Candidate> out = new ArrayList<>();
        for (PeopleProfile p :
                peopleProfileMapper.searchByTenant(tenantId, keyword.trim(), Math.min(Math.max(limit, 1), 50))) {
            out.add(toCandidate(p, null));
        }
        return out;
    }

    public Candidate preview(String tenantId, String peopleId) {
        PeopleProfile p = requireSameTenant(tenantId, peopleId, "档案");
        Map<String, Integer> counts = new LinkedHashMap<>();
        PREVIEW_COUNTS.forEach((table, label) -> counts.put(label, mergeMapper.countRows(table, peopleId)));
        return toCandidate(p, counts);
    }

    private Candidate toCandidate(PeopleProfile p, Map<String, Integer> counts) {
        return new Candidate(
                p.getId(),
                p.getDisplayName(),
                p.getGender(),
                p.getBirthday(),
                maskMobile(p.getMobile()),
                StringUtils.hasText(p.getAccountId()) || mergeMapper.countRows("account_patient", p.getId()) > 0,
                mergeMapper.listActiveOrgNames(p.getId()),
                p.getGmtCreated(),
                counts);
    }

    private static String maskMobile(String mobile) {
        if (!StringUtils.hasText(mobile)) {
            return null;
        }
        String m = mobile.trim();
        return m.length() >= 7 ? m.substring(0, 3) + "****" + m.substring(m.length() - 4) : "****";
    }

    /**
     * @param actorAccountId 操作者账号，写入审计
     * @param actorType      STAFF / OPS
     */
    @Transactional
    public MergeResult merge(
            String tenantId,
            String sourcePeopleId,
            String targetPeopleId,
            String actorAccountId,
            String actorType,
            String portal,
            String reason) {
        if (!StringUtils.hasText(sourcePeopleId) || !StringUtils.hasText(targetPeopleId)) {
            throw new BusinessException(400, "请选择要合并的两份档案");
        }
        if (sourcePeopleId.equals(targetPeopleId)) {
            throw new BusinessException(400, "不能与自身合并");
        }
        PeopleProfile source = requireSameTenant(tenantId, sourcePeopleId, "被合并档案");
        PeopleProfile target = requireSameTenant(tenantId, targetPeopleId, "保留档案");
        LocalDateTime now = LocalDateTime.now();
        Map<String, Integer> moved = new LinkedHashMap<>();
        List<String> dropped = new ArrayList<>();

        // OPEN 任务先作废：工作台同类型同业务键只允许一条 OPEN，直接改归属会撞唯一键
        int cancelled = mergeMapper.cancelOpenTasks(sourcePeopleId, now);
        if (cancelled > 0) {
            moved.put("workspace_task.cancelled", cancelled);
        }

        for (String table : APPEND_ONLY_TABLES) {
            int rows = mergeMapper.repointAppendOnly(table, sourcePeopleId, targetPeopleId, now);
            if (rows > 0) {
                moved.put(table, rows);
            }
        }

        int chatMoved = mergeMapper.moveChatMessagesToTargetThread(sourcePeopleId, targetPeopleId, now);
        if (chatMoved > 0) {
            moved.put("care_chat_message", chatMoved);
        }
        for (Map.Entry<String, List<String>> e : KEYED_TABLES.entrySet()) {
            String table = e.getKey();
            if (mergeMapper.softDeleteKeyConflicts(table, e.getValue(), sourcePeopleId, targetPeopleId, now) > 0) {
                dropped.add(table + ":保留目标档案同键数据");
            }
            int rows = mergeMapper.repointAppendOnly(table, sourcePeopleId, targetPeopleId, now);
            if (rows > 0) {
                moved.put(table, rows);
            }
        }

        List<String> targetIdentityTypes = mergeMapper.listIdentityTypes(targetPeopleId);
        int identityMoved = mergeMapper.moveIdentity(sourcePeopleId, targetPeopleId, targetIdentityTypes, now);
        if (identityMoved > 0) {
            moved.put("people_identity", identityMoved);
        }
        int identityDropped = mergeMapper.softDeleteIdentityRest(sourcePeopleId, now);
        if (identityDropped > 0) {
            dropped.add("people_identity:" + String.join("/", targetIdentityTypes));
        }

        List<String> targetOrgIds = mergeMapper.listMembershipOrgIds(targetPeopleId);
        int membershipMoved = mergeMapper.moveMembership(sourcePeopleId, targetPeopleId, targetOrgIds, now);
        if (membershipMoved > 0) {
            moved.put("people_org_membership", membershipMoved);
        }
        if (mergeMapper.softDeleteMembershipRest(sourcePeopleId, now) > 0) {
            dropped.add("people_org_membership:同机构重复入组");
        }

        List<String> targetTeamIds = mergeMapper.listCareTeamIds(targetPeopleId);
        int teamMoved = mergeMapper.moveCareTeamMember(sourcePeopleId, targetPeopleId, targetTeamIds, now);
        if (teamMoved > 0) {
            moved.put("care_team_member", teamMoved);
        }
        if (mergeMapper.softDeleteCareTeamMemberRest(sourcePeopleId, now) > 0) {
            dropped.add("care_team_member:同组重复成员");
        }

        for (String table : SINGLETON_TABLES) {
            if (mergeMapper.existsSingleton(table, targetPeopleId)) {
                if (mergeMapper.softDeleteSingleton(table, sourcePeopleId, now) > 0) {
                    dropped.add(table + ":保留目标档案");
                }
            } else {
                int rows = mergeMapper.moveSingleton(table, sourcePeopleId, targetPeopleId, now);
                if (rows > 0) {
                    moved.put(table, rows);
                }
            }
        }

        mergeByCode("people_disease_archive", "disease_code", sourcePeopleId, targetPeopleId, now, moved, dropped);
        mergeByCode("people_metadata_info", "metadata_code", sourcePeopleId, targetPeopleId, now, moved, dropped);
        mergeAssessment(tenantId, sourcePeopleId, targetPeopleId, moved);

        if (mergeMapper.markMerged(sourcePeopleId, targetPeopleId, now) == 0) {
            throw new BusinessException("被合并档案状态已变化，请重试");
        }

        auditService.record(
                portal,
                actorAccountId,
                actorType,
                tenantId,
                AuditActionEnum.PATIENT_MERGE.name(),
                "people_profile",
                targetPeopleId,
                targetPeopleId,
                AuditDetails.of(
                        "sourcePeopleId", sourcePeopleId,
                        "sourceName", source.getDisplayName(),
                        "targetName", target.getDisplayName(),
                        "movedRows", moved,
                        "dropped", dropped,
                        "reason", reason));
        log.warn(
                "patient merged tenant={} source={} target={} moved={} dropped={}",
                tenantId,
                sourcePeopleId,
                targetPeopleId,
                moved,
                dropped);
        return new MergeResult(targetPeopleId, sourcePeopleId, moved, dropped);
    }

    /** 问卷与事实随人迁移（目标已有同字段保留目标）；最新结果作废，目标标脏由自动任务重算。 */
    private void mergeAssessment(String tenantId, String sourcePeopleId, String targetPeopleId, Map<String, Integer> moved) {
        int subs = assessmentStoreMapper.repointQuestionnaire(sourcePeopleId, targetPeopleId);
        assessmentStoreMapper.cancelPendingRequests(tenantId, sourcePeopleId);
        assessmentStoreMapper.repointRequests(sourcePeopleId, targetPeopleId);
        int facts = assessmentStoreMapper.repointFacts(sourcePeopleId, targetPeopleId);
        assessmentStoreMapper.deleteFacts(sourcePeopleId);
        assessmentStoreMapper.deleteLatest(sourcePeopleId);
        assessmentStoreMapper.deleteState(sourcePeopleId);
        assessmentDirtyMarker.mark(tenantId, targetPeopleId);
        if (subs > 0) {
            moved.put("assessment_questionnaire_submission", subs);
        }
        if (facts > 0) {
            moved.put("people_assessment_fact", facts);
        }
    }

    private void mergeByCode(
            String table,
            String codeColumn,
            String sourcePeopleId,
            String targetPeopleId,
            LocalDateTime now,
            Map<String, Integer> moved,
            List<String> dropped) {
        List<String> targetCodes = mergeMapper.listCodes(table, codeColumn, targetPeopleId);
        int rows = mergeMapper.moveByCode(table, codeColumn, sourcePeopleId, targetPeopleId, targetCodes, now);
        if (rows > 0) {
            moved.put(table, rows);
        }
        if (mergeMapper.softDeleteByCodeRest(table, sourcePeopleId, now) > 0) {
            dropped.add(table + ":" + String.join("/", targetCodes));
        }
    }

    private PeopleProfile requireSameTenant(String tenantId, String peopleId, String label) {
        PeopleProfile profile = peopleProfileMapper.findAnyById(peopleId);
        if (profile == null) {
            throw new BusinessException(label + "不存在");
        }
        if (StringUtils.hasText(profile.getMergedIntoPeopleId())) {
            throw new BusinessException(label + "已合并到 " + profile.getMergedIntoPeopleId() + "，请选择最新档案");
        }
        if (profile.getIsDeleted() != null && profile.getIsDeleted() == 1) {
            throw new BusinessException(label + "已作废");
        }
        if (!StringUtils.hasText(tenantId) || !tenantId.equals(profile.getTenantId())) {
            throw new BusinessException(403, label + "不属于当前租户");
        }
        return profile;
    }
}
