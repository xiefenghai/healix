package com.healix.core.careplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.careplan.domain.CarePlanDraft;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.domain.CarePlanVersion;
import com.healix.common.result.PageResult;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.dto.CarePlanListItemDto;
import com.healix.core.careplan.dto.CarePlanDraftDto;
import com.healix.core.careplan.dto.CarePlanHeadDto;
import com.healix.core.careplan.dto.CarePlanTaskDto;
import com.healix.core.careplan.dto.CarePlanVersionDto;
import com.healix.core.careplan.dto.SafetyFlagDto;
import com.healix.core.careplan.enums.CarePlanSourceEnum;
import com.healix.core.careplan.enums.CarePlanStatusEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.mapper.CarePlanDraftMapper;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.mapper.CarePlanVersionMapper;
import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanSafetyService;
import com.healix.core.careplan.support.CarePlanSchemaVersions;
import com.healix.core.careplan.template.CarePlanTemplateRegistry;
import com.healix.core.careplan.template.CarePlanTemplateRegistry.GeneratedPlan;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.service.FeatureFlagService;
import com.healix.core.identity.enums.StaffRoleEnum;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.enums.NotifyDuplicatePolicy;
import com.healix.core.notify.service.NotifyFacade;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class CarePlanService {

    private static final String OPERATOR_STAFF = "STAFF";
    private static final String SIGNED = "SIGNED";

    private final CarePlanMapper carePlanMapper;
    private final CarePlanDraftMapper draftMapper;
    private final CarePlanVersionMapper versionMapper;
    private final CarePlanTaskMapper taskMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldRevisionService fieldRevisionService;
    private final CarePlanSafetyService safetyService;
    private final CarePlanContextService contextService;
    private final CarePlanTemplateRegistry templateRegistry;
    private final StaffProfileMapper staffProfileMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final NotifyFacade notifyFacade;
    private final ObjectProvider<WorkspaceTaskGenerator> workspaceTaskGenerator;
    private final CareTeamMemberMapper careTeamMemberMapper;
    private final CareTeamMapper careTeamMapper;
    private final FeatureFlagService featureFlagService;

    public CarePlanBundleDto getBundle(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        CarePlanBundleDto bundle = new CarePlanBundleDto();
        if (plan == null) {
            return bundle;
        }
        bundle.setPlan(toHead(plan));
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft != null) {
            bundle.setDraft(toDraftDto(draft));
        }
        if (StringUtils.hasText(plan.getCurrentVersionId())) {
            CarePlanVersion ver = versionMapper.findById(plan.getCurrentVersionId());
            if (ver != null) {
                bundle.setActiveVersion(toVersionDto(ver, true));
            }
        }
        return bundle;
    }

    /** C 端：仅返回已发布方案（不含草稿）。 */
    public CarePlanBundleDto getPublishedForPatient(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        CarePlanBundleDto bundle = new CarePlanBundleDto();
        if (plan == null) {
            return bundle;
        }
        bundle.setPlan(toHead(plan));
        if (StringUtils.hasText(plan.getCurrentVersionId())) {
            CarePlanVersion ver = versionMapper.findById(plan.getCurrentVersionId());
            if (ver != null) {
                bundle.setActiveVersion(toVersionDto(ver, true));
            }
        }
        return bundle;
    }

    /** C 端：已发布方案历史列表（含当前生效版）。 */
    public List<CarePlanListItemDto> listVersionsForPatient(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            return List.of();
        }
        return versionMapper.listByPlanId(plan.getId()).stream()
                .map(v -> toVersionListItem(plan, v))
                .toList();
    }

    /** C 端：查看某一历史方案详情。 */
    public CarePlanVersionDto getVersionForPatient(String tenantId, String peopleId, String versionId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            throw new BusinessException("暂无管理方案");
        }
        CarePlanVersion ver = versionMapper.findById(versionId);
        if (ver == null || !plan.getId().equals(ver.getPlanId())) {
            throw new BusinessException("方案版本不存在");
        }
        return toVersionDto(ver, true);
    }

    @Transactional
    public CarePlanBundleDto createBlankDraft(
            String tenantId, String orgId, String peopleId, String staffId, boolean replaceDraft) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = ensurePlan(tenantId, peopleId, staffId);
        CarePlanDraft existing = draftMapper.findByPlanId(plan.getId());
        if (existing != null && !replaceDraft) {
            throw new BusinessException(409, "已有草稿，请确认覆盖或先丢弃");
        }
        if (existing != null) {
            draftMapper.softDelete(existing.getId());
        }
        ObjectNode emptyExercise = JsonUtils.emptyObject();
        ObjectNode emptyDiet = JsonUtils.emptyObject();
        emptyDiet.putArray("principles");
        emptyDiet.putArray("recommended");
        emptyDiet.putArray("limited");
        emptyDiet.putArray("allergensAvoid");
        ObjectNode emptyExec = JsonUtils.emptyObject();
        emptyExec.put("horizonDays", 14);
        emptyExec.putArray("tasks");
        List<SafetyFlagDto> flags = safetyService.evaluate(emptyExercise, emptyDiet, emptyExec, true, true, false);
        CarePlanDraft draft = newDraft(
                plan,
                peopleId,
                tenantId,
                staffId,
                CarePlanSourceEnum.MANUAL.name(),
                JsonUtils.toJson(emptyExercise),
                JsonUtils.toJson(emptyDiet),
                JsonUtils.toJson(emptyExec),
                safetyService.toFlagsJson(flags),
                "{}",
                null,
                null);
        draftMapper.insert(draft);
        recordRevision(tenantId, peopleId, staffId, orgId, plan.getId(), 0, 1, "{}", revisionSnapshot("status", "DRAFT", "action", "CREATE_BLANK_DRAFT"));
        return getBundle(tenantId, orgId, peopleId);
    }

    @Transactional
    public CarePlanBundleDto generate(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String instruction,
            String templateKey,
            List<String> diseaseCodesOverride,
            boolean replaceDraft,
            String generationLogId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlanContext ctx = contextService.load(tenantId, peopleId);
        List<String> diseaseCodes = diseaseCodesOverride != null && !diseaseCodesOverride.isEmpty()
                ? diseaseCodesOverride
                : ctx.diseaseCodes();
        CarePlanTemplateKeyEnum key = templateRegistry.resolveTemplateKey(diseaseCodes, templateKey);
        GeneratedPlan generated = templateRegistry.generate(key, ctx, instruction, diseaseCodes);
        return persistGenerated(
                tenantId,
                orgId,
                peopleId,
                staffId,
                generated,
                CarePlanSourceEnum.TEMPLATE.name(),
                null,
                replaceDraft,
                generationLogId);
    }

    @Transactional
    public CarePlanBundleDto persistGenerated(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            GeneratedPlan generated,
            String source,
            String goalSummaryOverride,
            boolean replaceDraft,
            String generationLogId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = ensurePlan(tenantId, peopleId, staffId);
        CarePlanDraft existing = draftMapper.findByPlanId(plan.getId());
        if (existing != null && !replaceDraft) {
            throw new BusinessException(409, "已有草稿，请确认覆盖或先丢弃");
        }
        List<SafetyFlagDto> flags = safetyService.evaluate(
                generated.exercise(),
                generated.diet(),
                generated.execution(),
                generated.contextIncomplete(),
                generated.noDiseaseTag(),
                generated.hypoglycemiaRisk());
        if (existing != null) {
            draftMapper.softDelete(existing.getId());
        }
        String oldSnap = existing == null ? "{}" : draftContentSnap(existing);
        CarePlanDraft draft = newDraft(
                plan,
                peopleId,
                tenantId,
                staffId,
                source,
                JsonUtils.toJson(generated.exercise()),
                JsonUtils.toJson(generated.diet()),
                JsonUtils.toJson(generated.execution()),
                safetyService.toFlagsJson(flags),
                JsonUtils.toJson(generated.contextSnapshot()),
                plan.getCurrentVersionId(),
                generationLogId);
        draftMapper.insert(draft);
        plan.setDiseaseTagsJson(JsonUtils.toJson(generated.diseaseTags()));
        String goal = StringUtils.hasText(goalSummaryOverride)
                ? goalSummaryOverride
                : generated.exercise().path("goal").asText(null);
        plan.setGoalSummary(goal);
        if (!StringUtils.hasText(plan.getTitle())) {
            plan.setTitle("管理方案");
        }
        plan.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        recordRevision(
                tenantId,
                peopleId,
                staffId,
                orgId,
                plan.getId(),
                existing == null ? 0 : 1,
                1,
                oldSnap,
                draftContentSnap(draft));
        return getBundle(tenantId, orgId, peopleId);
    }

    public CarePlanBundleDto updateDraft(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            int expectedVersion,
            JsonNode exercise,
            JsonNode diet,
            JsonNode execution,
            String title,
            String goalSummary) {
        return updateDraft(
                tenantId, orgId, peopleId, staffId, expectedVersion, exercise, diet, execution, title, goalSummary, null);
    }

    @Transactional
    public CarePlanBundleDto updateDraft(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            int expectedVersion,
            JsonNode exercise,
            JsonNode diet,
            JsonNode execution,
            String title,
            String goalSummary,
            String planSummary) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft == null) {
            throw new BusinessException("无草稿可编辑，请先生成或新建空白草稿");
        }
        int ver = draft.getVersion() == null ? 1 : draft.getVersion();
        if (expectedVersion != ver) {
            throw new BusinessException(409, "草稿版本冲突，请刷新后重试");
        }
        String oldSnap = draftContentSnap(draft);
        CarePlanContext ctx = contextService.load(tenantId, peopleId);
        List<SafetyFlagDto> flags = safetyService.evaluate(
                exercise,
                diet,
                execution,
                ctx.contextIncomplete(),
                ctx.diseaseCodes().isEmpty(),
                ctx.hypoglycemiaRisk());
        draft.setExerciseJson(JsonUtils.toJson(exercise == null ? JsonUtils.emptyObject() : exercise));
        draft.setDietJson(JsonUtils.toJson(diet == null ? JsonUtils.emptyObject() : diet));
        draft.setExecutionJson(JsonUtils.toJson(execution == null ? JsonUtils.emptyObject() : execution));
        draft.setSafetyFlagsJson(safetyService.toFlagsJson(flags));
        if (planSummary != null) {
            draft.setContextSnapshotJson(mergePlanSummaryIntoSnapshot(draft.getContextSnapshotJson(), planSummary));
        }
        if (CarePlanSourceEnum.TEMPLATE.name().equals(draft.getSource())
                || CarePlanSourceEnum.LLM.name().equals(draft.getSource())) {
            draft.setSource(
                    CarePlanSourceEnum.TEMPLATE.name().equals(draft.getSource())
                            ? CarePlanSourceEnum.TEMPLATE_THEN_EDIT.name()
                            : CarePlanSourceEnum.LLM_THEN_EDIT.name());
        }
        draft.setVersion(ver + 1);
        draft.setSchemaVersion(CarePlanSchemaVersions.CURRENT);
        draft.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(draft);
        draftMapper.update(draft);
        if (StringUtils.hasText(title)) {
            plan.setTitle(title);
        }
        if (goalSummary != null) {
            plan.setGoalSummary(goalSummary);
        }
        plan.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        recordRevision(tenantId, peopleId, staffId, orgId, plan.getId(), ver, ver + 1, oldSnap, draftContentSnap(draft));
        return getBundle(tenantId, orgId, peopleId);
    }

    /** 将方案总结写入 context_snapshot.summary，保留其余快照字段。 */
    private static String mergePlanSummaryIntoSnapshot(String existingJson, String planSummary) {
        ObjectNode snap;
        try {
            JsonNode raw = JsonUtils.readTree(existingJson == null || existingJson.isBlank() ? "{}" : existingJson);
            snap = raw != null && raw.isObject() ? (ObjectNode) raw.deepCopy() : JsonUtils.emptyObject();
        } catch (Exception e) {
            snap = JsonUtils.emptyObject();
        }
        if (StringUtils.hasText(planSummary)) {
            snap.put("summary", planSummary.trim());
        } else {
            snap.remove("summary");
        }
        return JsonUtils.toJson(snap);
    }

    @Transactional
    public CarePlanBundleDto discardDraft(String tenantId, String orgId, String peopleId, String staffId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft == null) {
            throw new BusinessException("没有可丢弃的草稿");
        }
        String oldSnap = draftContentSnap(draft);
        draftMapper.softDelete(draft.getId());
        plan.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        recordRevision(
                tenantId,
                peopleId,
                staffId,
                orgId,
                plan.getId(),
                1,
                1,
                oldSnap,
                revisionSnapshot("action", "DISCARD_DRAFT"));
        return getBundle(tenantId, orgId, peopleId);
    }

    @Transactional
    public CarePlanBundleDto openEditFromActive(String tenantId, String orgId, String peopleId, String staffId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        if (!CarePlanStatusEnum.ACTIVE.name().equals(plan.getStatus())
                || !StringUtils.hasText(plan.getCurrentVersionId())) {
            throw new BusinessException("当前无已发布方案可编辑");
        }
        CarePlanDraft existing = draftMapper.findByPlanId(plan.getId());
        if (existing != null) {
            throw new BusinessException(409, "已有草稿，请先发布或丢弃后再编辑");
        }
        CarePlanVersion active = versionMapper.findById(plan.getCurrentVersionId());
        if (active == null) {
            throw new BusinessException("生效版本不存在");
        }
        CarePlanDraft draft = newDraft(
                plan,
                peopleId,
                tenantId,
                staffId,
                CarePlanSourceEnum.MANUAL.name(),
                active.getExerciseJson(),
                active.getDietJson(),
                active.getExecutionJson(),
                active.getSafetyFlagsJson(),
                active.getContextSnapshotJson(),
                active.getId(),
                null);
        draftMapper.insert(draft);
        recordRevision(
                tenantId,
                peopleId,
                staffId,
                orgId,
                plan.getId(),
                0,
                1,
                "{}",
                revisionSnapshot("action", "CLONE_ACTIVE_TO_DRAFT", "baseVersionId", active.getId()));
        return getBundle(tenantId, orgId, peopleId);
    }

    @Transactional
    public CarePlanBundleDto publish(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            List<String> ackWarnCodes,
            String title) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft == null) {
            throw new BusinessException("无草稿可发布");
        }
        JsonNode exercise = JsonUtils.readTree(draft.getExerciseJson());
        JsonNode diet = JsonUtils.readTree(draft.getDietJson());
        JsonNode execution = JsonUtils.readTree(draft.getExecutionJson());
        CarePlanContext ctx = contextService.load(tenantId, peopleId);
        List<SafetyFlagDto> flags = safetyService.evaluate(
                exercise,
                diet,
                execution,
                ctx.contextIncomplete(),
                ctx.diseaseCodes().isEmpty(),
                ctx.hypoglycemiaRisk());
        draft.setSafetyFlagsJson(safetyService.toFlagsJson(flags));
        safetyService.assertPublishable(flags, ackWarnCodes);

        String oldStatus = plan.getStatus();
        String oldVersionId = plan.getCurrentVersionId();
        if (CarePlanStatusEnum.ACTIVE.name().equals(plan.getStatus()) && StringUtils.hasText(oldVersionId)) {
            // 旧 ACTIVE 逻辑上被新版本取代；头表 status 仍为 ACTIVE
        }

        Integer maxNo = versionMapper.maxVersionNo(plan.getId());
        int nextNo = maxNo == null ? 1 : maxNo + 1;
        CarePlanVersion version = new CarePlanVersion();
        version.setPlanId(plan.getId());
        version.setVersionNo(nextNo);
        int schemaVer =
                draft.getSchemaVersion() != null && draft.getSchemaVersion() > 0
                        ? draft.getSchemaVersion()
                        : CarePlanSchemaVersions.CURRENT;
        version.setSchemaVersion(schemaVer);
        version.setSource(draft.getSource());
        String publishTitle = resolvePublishTitle(title, plan, exercise);
        version.setTitle(publishTitle);
        version.setExerciseJson(draft.getExerciseJson());
        version.setDietJson(draft.getDietJson());
        version.setExecutionJson(draft.getExecutionJson());
        version.setContextSnapshotJson(draft.getContextSnapshotJson());
        version.setSafetyFlagsJson(draft.getSafetyFlagsJson());
        version.setPublishedAt(LocalDateTime.now());
        version.setPublishedByStaffId(staffId);
        version.setSignStatus("UNSIGNED");
        EntityMeta.onCreate(version);
        versionMapper.insert(version);
        projectTasks(tenantId, peopleId, plan.getId(), version.getId(), execution);

        plan.setTitle(publishTitle);
        plan.setStatus(CarePlanStatusEnum.ACTIVE.name());
        plan.setCurrentVersionId(version.getId());
        plan.setGoalSummary(exercise.path("goal").asText(plan.getGoalSummary()));
        plan.setUpdatedByStaffId(staffId);
        plan.setVersion((plan.getVersion() == null ? 1 : plan.getVersion()) + 1);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        draftMapper.softDelete(draft.getId());

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", oldStatus);
        before.put("currentVersionId", oldVersionId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", CarePlanStatusEnum.ACTIVE.name());
        after.put("currentVersionId", version.getId());
        after.put("versionNo", nextNo);
        after.put("exercise", exercise);
        after.put("diet", diet);
        after.put("execution", execution);
        recordRevision(
                tenantId,
                peopleId,
                staffId,
                orgId,
                plan.getId(),
                nextNo - 1,
                nextNo,
                JsonUtils.toJson(before),
                JsonUtils.toJson(after));
        WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
        if (gen != null) {
            gen.onPlanPublished(tenantId, orgId, peopleId, staffId, plan.getId(), version.getId());
            if (featureFlagService.enabled(tenantId, FeatureFlagKeyEnum.DOCTOR_PLAN_REVIEW)) {
                gen.ensurePlanReview(
                        tenantId,
                        orgId,
                        peopleId,
                        version.getId(),
                        publishTitle,
                        resolvePrimaryDoctorStaffId(orgId, peopleId));
            }
        }
        scheduleCarePlanPublishedNotify(tenantId, orgId, peopleId, publishTitle, version.getId());
        return getBundle(tenantId, orgId, peopleId);
    }

    /**
     * 医生签署已发布版本（临床复核）。签署只做一次，重复调用直接返回。
     *
     * <p>不改方案生效状态：方案发布即对患者生效，签署是事后的临床背书，
     * 把生效卡在签署上会让健管师的日常工作被医生排班堵住。
     */
    @Transactional
    public CarePlanVersionDto signVersion(
            String tenantId, String orgId, String peopleId, String versionId, String staffId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        if (!currentRoles().contains(StaffRoleEnum.DOCTOR.name())) {
            throw new BusinessException(403, "仅医生可签署方案");
        }
        CarePlan plan = requirePlan(tenantId, peopleId);
        CarePlanVersion version = versionMapper.findById(versionId);
        if (version == null || !plan.getId().equals(version.getPlanId())) {
            throw new BusinessException("方案版本不存在");
        }
        if (SIGNED.equals(version.getSignStatus())) {
            return toVersionDto(version, true);
        }
        LocalDateTime now = LocalDateTime.now();
        if (versionMapper.sign(versionId, staffId, now) > 0) {
            version.setSignStatus(SIGNED);
            version.setSignedByStaffId(staffId);
            version.setSignedAt(now);
            WorkspaceTaskGenerator gen = workspaceTaskGenerator.getIfAvailable();
            if (gen != null) {
                gen.onPlanVersionSigned(tenantId, orgId, versionId, staffId);
            }
            recordRevision(
                    tenantId,
                    peopleId,
                    staffId,
                    orgId,
                    plan.getId(),
                    version.getVersionNo(),
                    version.getVersionNo(),
                    JsonUtils.toJson(Map.of("signStatus", "UNSIGNED")),
                    JsonUtils.toJson(Map.of("signStatus", SIGNED, "versionId", versionId)));
        }
        return toVersionDto(version, true);
    }

    /** 主责医生取自患者所在健管组；未配置时返回 null，复核单留公共池。 */
    private String resolvePrimaryDoctorStaffId(String orgId, String peopleId) {
        CareTeamMember member = careTeamMemberMapper.findPeopleInOrg(orgId, peopleId);
        if (member == null || !StringUtils.hasText(member.getTeamId())) {
            return null;
        }
        CareTeam team = careTeamMapper.findById(member.getTeamId());
        return team == null ? null : team.getPrimaryDoctorStaffId();
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        return ctx == null || ctx.getRoles() == null ? Set.of() : ctx.getRoles();
    }

    private void scheduleCarePlanPublishedNotify(
            String tenantId, String orgId, String peopleId, String planTitle, String versionId) {
        if (!StringUtils.hasText(versionId)) {
            return;
        }
        String titleText = StringUtils.hasText(planTitle) ? planTitle.trim() : "管理方案";
        String peopleName = resolvePeopleDisplayName(peopleId);
        Runnable publishNotify = () -> {
            try {
                String body = StringUtils.hasText(peopleName)
                        ? peopleName + "的管理方案「" + titleText + "」已发布，请按方案执行"
                        : "管理方案「" + titleText + "」已发布，请按方案执行";
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("versionId", versionId);
                payload.put("planTitle", titleText);
                notifyFacade.publish(NotifyPublishCommand.builder()
                        .tenantId(tenantId)
                        .eventType(NotifyEventType.CARE_PLAN_PUBLISHED)
                        .dedupeKey(versionId)
                        .audience(NotifyAudience.C_ACCOUNT)
                        .peopleId(peopleId)
                        .orgId(orgId)
                        .title("管理方案已发布")
                        .body(body)
                        .linkPath("/care-plan")
                        .payload(payload)
                        .onDuplicate(NotifyDuplicatePolicy.IGNORE)
                        .build());
            } catch (Exception ex) {
                log.warn(
                        "CARE_PLAN_PUBLISHED notify failed tenant={} people={} version={}: {}",
                        tenantId,
                        peopleId,
                        versionId,
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

    public PageResult<CarePlanListItemDto> pageListItems(
            String tenantId, String orgId, String peopleId, int page, int pageSize) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            return new PageResult<>(0, List.of());
        }
        List<CarePlanListItemDto> rows = new ArrayList<>();
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft != null) {
            rows.add(toDraftListItem(plan, draft));
        }
        for (CarePlanVersion version : versionMapper.listByPlanId(plan.getId())) {
            rows.add(toVersionListItem(plan, version));
        }
        rows.sort(Comparator.comparing(CarePlanListItemDto::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        int total = rows.size();
        int from = Math.min((safePage - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        return new PageResult<>(total, rows.subList(from, to));
    }

    @Transactional
    public void deleteListItem(
            String tenantId, String orgId, String peopleId, String staffId, String recordType, String itemId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        if ("DRAFT".equalsIgnoreCase(recordType)) {
            CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
            if (draft == null || !draft.getId().equals(itemId)) {
                throw new BusinessException("草稿不存在");
            }
            draftMapper.softDelete(draft.getId());
            return;
        }
        if ("VERSION".equalsIgnoreCase(recordType)) {
            CarePlanVersion version = versionMapper.findById(itemId);
            if (version == null || !plan.getId().equals(version.getPlanId())) {
                throw new BusinessException("版本不存在");
            }
            if (version.getId().equals(plan.getCurrentVersionId())) {
                throw new BusinessException("不能删除当前生效版本");
            }
            versionMapper.softDelete(version.getId());
            return;
        }
        throw new BusinessException("不支持的记录类型");
    }

    public List<CarePlanVersionDto> listVersions(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            return List.of();
        }
        return versionMapper.listByPlanId(plan.getId()).stream().map(v -> toVersionDto(v, false)).toList();
    }

    public CarePlanVersionDto getVersion(String tenantId, String orgId, String peopleId, String versionId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        CarePlanVersion ver = versionMapper.findById(versionId);
        if (ver == null || !plan.getId().equals(ver.getPlanId())) {
            throw new BusinessException("版本不存在");
        }
        return toVersionDto(ver, true);
    }

    private void projectTasks(
            String tenantId, String peopleId, String planId, String versionId, JsonNode execution) {
        JsonNode tasks = execution.get("tasks");
        if (tasks == null || !tasks.isArray()) {
            return;
        }
        int order = 0;
        for (JsonNode t : tasks) {
            if (t == null || t.isNull()) {
                continue;
            }
            CarePlanTask row = new CarePlanTask();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setPlanId(planId);
            row.setPlanVersionId(versionId);
            row.setTaskCode(t.path("code").asText("TASK_" + order));
            row.setTitle(t.path("title").asText("未命名任务"));
            row.setCategory(t.path("category").asText("OTHER"));
            row.setFrequency(textOrNull(t, "frequency"));
            row.setTimeSlot(textOrNull(t, "timeSlot"));
            row.setRelatedRef(textOrNull(t, "relatedRef"));
            row.setEnabled(t.path("enabled").asBoolean(true) ? 1 : 0);
            row.setSortOrder(order++);
            EntityMeta.onCreate(row);
            taskMapper.insert(row);
        }
    }

    private String textOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !StringUtils.hasText(v.asText())) {
            return null;
        }
        return v.asText();
    }

    private CarePlan ensurePlan(String tenantId, String peopleId, String staffId) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan != null) {
            return plan;
        }
        plan = new CarePlan();
        plan.setTenantId(tenantId);
        plan.setPeopleId(peopleId);
        plan.setStatus(CarePlanStatusEnum.DRAFT.name());
        plan.setTitle("管理方案");
        plan.setVersion(1);
        plan.setCreatedByStaffId(staffId);
        plan.setUpdatedByStaffId(staffId);
        EntityMeta.onCreate(plan);
        carePlanMapper.insert(plan);
        return plan;
    }

    private CarePlan requirePlan(String tenantId, String peopleId) {
        CarePlan plan = carePlanMapper.findByTenantAndPeople(tenantId, peopleId);
        if (plan == null) {
            throw new BusinessException("尚未创建管理方案");
        }
        return plan;
    }

    private CarePlanDraft newDraft(
            CarePlan plan,
            String peopleId,
            String tenantId,
            String staffId,
            String source,
            String exerciseJson,
            String dietJson,
            String executionJson,
            String flagsJson,
            String contextJson,
            String baseVersionId,
            String generationLogId) {
        CarePlanDraft draft = new CarePlanDraft();
        draft.setPlanId(plan.getId());
        draft.setTenantId(tenantId);
        draft.setPeopleId(peopleId);
        draft.setVersion(1);
        draft.setSchemaVersion(CarePlanSchemaVersions.CURRENT);
        draft.setSource(source);
        draft.setExerciseJson(exerciseJson);
        draft.setDietJson(dietJson);
        draft.setExecutionJson(executionJson);
        draft.setSafetyFlagsJson(flagsJson);
        draft.setContextSnapshotJson(contextJson);
        draft.setBaseVersionId(baseVersionId);
        draft.setGenerationLogId(generationLogId);
        draft.setUpdatedByStaffId(staffId);
        EntityMeta.onCreate(draft);
        return draft;
    }

    private void recordRevision(
            String tenantId,
            String peopleId,
            String staffId,
            String orgId,
            String planId,
            int before,
            int after,
            String oldJson,
            String newJson) {
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                OPERATOR_STAFF,
                staffId,
                RevisionBizTypeEnum.CARE_PLAN.name(),
                planId,
                before,
                after,
                orgId,
                oldJson,
                newJson);
    }

    private String draftContentSnap(CarePlanDraft draft) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("source", draft.getSource());
        m.put("exercise", JsonUtils.readTree(draft.getExerciseJson()));
        m.put("diet", JsonUtils.readTree(draft.getDietJson()));
        m.put("execution", JsonUtils.readTree(draft.getExecutionJson()));
        return JsonUtils.toJson(m);
    }

    private String revisionSnapshot(String k1, Object v1) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        return JsonUtils.toJson(m);
    }

    private String revisionSnapshot(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return JsonUtils.toJson(m);
    }

    private CarePlanHeadDto toHead(CarePlan plan) {
        CarePlanHeadDto dto = new CarePlanHeadDto();
        dto.setId(plan.getId());
        dto.setPeopleId(plan.getPeopleId());
        dto.setStatus(plan.getStatus());
        dto.setTitle(plan.getTitle());
        dto.setGoalSummary(plan.getGoalSummary());
        dto.setDiseaseTags(JsonUtils.readTree(plan.getDiseaseTagsJson() == null ? "[]" : plan.getDiseaseTagsJson()));
        dto.setCurrentVersionId(plan.getCurrentVersionId());
        dto.setVersion(plan.getVersion());
        return dto;
    }

    private CarePlanDraftDto toDraftDto(CarePlanDraft draft) {
        CarePlanDraftDto dto = new CarePlanDraftDto();
        dto.setId(draft.getId());
        dto.setPlanId(draft.getPlanId());
        dto.setVersion(draft.getVersion());
        dto.setSchemaVersion(
                draft.getSchemaVersion() != null ? draft.getSchemaVersion() : CarePlanSchemaVersions.CURRENT);
        dto.setSource(draft.getSource());
        dto.setExercise(JsonUtils.readTree(draft.getExerciseJson()));
        dto.setDiet(JsonUtils.readTree(draft.getDietJson()));
        dto.setExecution(JsonUtils.readTree(draft.getExecutionJson()));
        dto.setSafetyFlags(JsonUtils.readTree(draft.getSafetyFlagsJson() == null ? "[]" : draft.getSafetyFlagsJson()));
        dto.setContextSnapshot(JsonUtils.readTree(
                draft.getContextSnapshotJson() == null ? "{}" : draft.getContextSnapshotJson()));
        dto.setBaseVersionId(draft.getBaseVersionId());
        return dto;
    }

    private CarePlanVersionDto toVersionDto(CarePlanVersion ver, boolean withTasks) {
        CarePlanVersionDto dto = new CarePlanVersionDto();
        dto.setId(ver.getId());
        dto.setPlanId(ver.getPlanId());
        dto.setVersionNo(ver.getVersionNo());
        dto.setSchemaVersion(
                ver.getSchemaVersion() != null ? ver.getSchemaVersion() : CarePlanSchemaVersions.CURRENT);
        dto.setVersionLabel(CarePlanSchemaVersions.label(dto.getSchemaVersion()));
        if (StringUtils.hasText(ver.getTitle())) {
            dto.setTitle(sanitizePlanTitle(ver.getTitle(), extractExerciseGoal(ver.getExerciseJson())));
        } else {
            dto.setTitle("管理方案");
        }
        dto.setSource(ver.getSource());
        dto.setExercise(JsonUtils.readTree(ver.getExerciseJson()));
        dto.setDiet(JsonUtils.readTree(ver.getDietJson()));
        dto.setExecution(JsonUtils.readTree(ver.getExecutionJson()));
        dto.setSafetyFlags(JsonUtils.readTree(ver.getSafetyFlagsJson() == null ? "[]" : ver.getSafetyFlagsJson()));
        dto.setContextSnapshot(JsonUtils.readTree(
                ver.getContextSnapshotJson() == null ? "{}" : ver.getContextSnapshotJson()));
        dto.setPublishedAt(ver.getPublishedAt());
        dto.setPublishedByStaffId(ver.getPublishedByStaffId());
        dto.setSignStatus(ver.getSignStatus());
        if (withTasks) {
            dto.setTasks(taskMapper.listByVersionId(ver.getId()).stream().map(this::toTaskDto).toList());
        } else {
            dto.setTasks(List.of());
        }
        return dto;
    }

    private CarePlanTaskDto toTaskDto(CarePlanTask row) {
        CarePlanTaskDto dto = new CarePlanTaskDto();
        dto.setId(row.getId());
        dto.setTaskCode(row.getTaskCode());
        dto.setTitle(row.getTitle());
        dto.setCategory(row.getCategory());
        dto.setFrequency(row.getFrequency());
        dto.setTimeSlot(row.getTimeSlot());
        dto.setRelatedRef(row.getRelatedRef());
        dto.setEnabled(row.getEnabled() != null && row.getEnabled() == 1);
        dto.setSortOrder(row.getSortOrder());
        return dto;
    }

    private CarePlanListItemDto toDraftListItem(CarePlan plan, CarePlanDraft draft) {
        CarePlanListItemDto item = new CarePlanListItemDto();
        item.setId(draft.getId());
        item.setRecordType("DRAFT");
        String goalSummary = resolveGoalSummary(plan.getGoalSummary(), draft.getExerciseJson());
        item.setTitle(sanitizePlanTitle(plan.getTitle(), goalSummary));
        item.setGoalSummary(goalSummary);
        item.setSource(draft.getSource());
        item.setSourceMode(resolveSourceMode(draft.getSource()));
        item.setVersionNo(null);
        item.setSchemaVersion(
                draft.getSchemaVersion() != null ? draft.getSchemaVersion() : CarePlanSchemaVersions.CURRENT);
        item.setVersionLabel(CarePlanSchemaVersions.label(item.getSchemaVersion()) + "·草稿");
        item.setStaffId(draft.getUpdatedByStaffId());
        item.setStaffName(resolveStaffName(draft.getUpdatedByStaffId()));
        item.setCreatedAt(draft.getGmtModified() != null ? draft.getGmtModified() : draft.getGmtCreated());
        item.setStatus("DRAFT");
        item.setDeletable(true);
        return item;
    }

    private CarePlanListItemDto toVersionListItem(CarePlan plan, CarePlanVersion version) {
        CarePlanListItemDto item = new CarePlanListItemDto();
        item.setId(version.getId());
        item.setRecordType("VERSION");
        String goalSummary = resolveGoalSummary(null, version.getExerciseJson());
        if (!StringUtils.hasText(goalSummary)) {
            goalSummary = resolveGoalSummary(plan.getGoalSummary(), null);
        }
        item.setTitle(resolveVersionTitle(plan, version, goalSummary));
        item.setGoalSummary(goalSummary);
        item.setSource(version.getSource());
        item.setSourceMode(resolveSourceMode(version.getSource()));
        item.setVersionNo(version.getVersionNo());
        item.setSchemaVersion(
                version.getSchemaVersion() != null ? version.getSchemaVersion() : CarePlanSchemaVersions.CURRENT);
        item.setVersionLabel(CarePlanSchemaVersions.label(item.getSchemaVersion()));
        item.setStaffId(version.getPublishedByStaffId());
        item.setStaffName(resolveStaffName(version.getPublishedByStaffId()));
        item.setCreatedAt(version.getPublishedAt());
        boolean active = version.getId().equals(plan.getCurrentVersionId());
        item.setStatus(active ? "ACTIVE" : "ARCHIVED");
        item.setDeletable(!active);
        return item;
    }

    /** 发布时写入版本快照的标题；不再用运动目标冒充标题。 */
    private String resolvePublishTitle(String requestedTitle, CarePlan plan, JsonNode exercise) {
        if (StringUtils.hasText(requestedTitle)) {
            return sanitizePlanTitle(requestedTitle, extractExerciseGoal(exercise));
        }
        return sanitizePlanTitle(plan.getTitle(), extractExerciseGoal(exercise));
    }

    private String resolveVersionTitle(CarePlan plan, CarePlanVersion version, String goalSummary) {
        if (version != null && StringUtils.hasText(version.getTitle())) {
            return sanitizePlanTitle(version.getTitle(), goalSummary);
        }
        return sanitizePlanTitle(plan != null ? plan.getTitle() : null, goalSummary);
    }

    private String resolveGoalSummary(String preferred, String exerciseJson) {
        if (StringUtils.hasText(preferred)) {
            return preferred.trim();
        }
        return extractExerciseGoal(exerciseJson);
    }

    private String extractExerciseGoal(String exerciseJson) {
        if (!StringUtils.hasText(exerciseJson)) {
            return null;
        }
        return extractExerciseGoal(JsonUtils.readTree(exerciseJson));
    }

    private String extractExerciseGoal(JsonNode exercise) {
        if (exercise == null || exercise.isNull()) {
            return null;
        }
        String goal = exercise.path("goal").asText("");
        return StringUtils.hasText(goal) ? goal.trim() : null;
    }

    /**
     * 标题与摘要分离：历史数据曾把运动目标截断写入 title，展示时回退为默认标题。
     */
    private static String sanitizePlanTitle(String title, String goalSummary) {
        if (!StringUtils.hasText(title)) {
            return "管理方案";
        }
        String t = title.trim();
        if (!StringUtils.hasText(goalSummary)) {
            return t;
        }
        String g = goalSummary.trim();
        if (t.equals(g)) {
            return "管理方案";
        }
        if (g.length() > 40) {
            String truncatedCn = g.substring(0, 40) + "…";
            String truncatedDots = g.substring(0, 40) + "...";
            if (t.equals(truncatedCn) || t.equals(truncatedDots)) {
                return "管理方案";
            }
        }
        return t;
    }

    private static String resolveSourceMode(String source) {
        return CarePlanSourceEnum.LLM.name().equals(source) ? "AI" : "MANUAL";
    }

    private String resolveStaffName(String staffId) {
        if (!StringUtils.hasText(staffId)) {
            return "-";
        }
        var profile = staffProfileMapper.findById(staffId);
        if (profile == null || !StringUtils.hasText(profile.getDisplayName())) {
            return staffId;
        }
        return profile.getDisplayName();
    }
}
