package com.healix.core.careplan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.core.allergy.service.AllergyQueryService;
import com.healix.core.allergy.service.AllergyQueryService.FoodAllergen;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.care.domain.CareTeam;
import com.healix.core.care.domain.CareTeamMember;
import com.healix.core.care.mapper.CareTeamMapper;
import com.healix.core.care.mapper.CareTeamMemberMapper;
import com.healix.core.careplan.domain.CarePlan;
import com.healix.core.compliance.ComplianceText;
import com.healix.core.careplan.domain.CarePlanDraft;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.domain.CarePlanVersion;
import com.healix.common.result.PageResult;
import com.healix.core.careplan.dto.CarePlanBundleDto;
import com.healix.core.careplan.dto.CarePlanCommHistoryItemDto;
import com.healix.core.careplan.dto.CarePlanListItemDto;
import com.healix.core.careplan.dto.CarePlanDraftDto;
import com.healix.core.careplan.dto.CarePlanHeadDto;
import com.healix.core.careplan.dto.CarePlanTaskDto;
import com.healix.core.careplan.dto.CarePlanVersionDto;
import com.healix.core.careplan.dto.SafetyFlagDto;
import com.healix.core.careplan.enums.CarePlanCommResultEnum;
import com.healix.core.careplan.enums.CarePlanSourceEnum;
import com.healix.core.careplan.enums.CarePlanStatusEnum;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.guideline.GuidelineCheck;
import com.healix.core.careplan.mapper.CarePlanDraftMapper;
import com.healix.core.careplan.mapper.CarePlanMapper;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.mapper.CarePlanVersionMapper;
import com.healix.core.careplan.support.CarePlanAllergenGuard;
import com.healix.core.careplan.support.CarePlanContextService;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.followup.catalog.FollowupType;
import com.healix.core.followup.domain.FollowupRecord;
import com.healix.core.followup.dto.FollowupRecordViewDto;
import com.healix.core.followup.mapper.FollowupRecordMapper;
import com.healix.core.followup.service.FollowupService;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.program.service.ProgramEnrollmentService;
import com.healix.core.careplan.support.CarePlanDiseaseTags;
import com.healix.core.careplan.support.CarePlanEnergyTargetService;
import com.healix.core.careplan.support.CarePlanRationaleSupport;
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
    private final AllergyQueryService allergyQueryService;
    private final CarePlanEnergyTargetService energyTargetService;
    private final ProgramEnrollmentService programEnrollmentService;
    private final ObjectProvider<FollowupService> followupService;
    private final FollowupRecordMapper followupRecordMapper;

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
        bundle.setCommunicationHistory(loadCommunicationHistory(orgId, peopleId, plan.getId()));
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
                bundle.setActiveVersion(toVersionDto(ver, true, true));
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
        return toVersionDto(ver, true, true);
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
        List<FoodAllergen> allergens = allergyQueryService.foodAllergens(peopleId);
        CarePlanAllergenGuard.enforce(emptyDiet, allergens);
        ObjectNode emptyExec = JsonUtils.emptyObject();
        emptyExec.put("horizonDays", 14);
        emptyExec.putArray("tasks");
        List<SafetyFlagDto> flags = safetyService.evaluate(
                emptyExercise,
                emptyDiet,
                emptyExec,
                true,
                false,
                false,
                allergens,
                allergyQueryService.status(tenantId, peopleId),
                null,
                false);
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
        if (key == CarePlanTemplateKeyEnum.OBESITY
                && !programEnrollmentService.canGenerateWeightLossPlan(tenantId, orgId, peopleId)) {
            throw new BusinessException(400, "请先完成减重项目首诊问卷后再生成减重管理方案");
        }
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
        List<FoodAllergen> allergens = allergyQueryService.foodAllergens(peopleId);
        CarePlanAllergenGuard.enforce(generated.diet(), allergens);
        List<String> removed = CarePlanAllergenGuard.sanitize(generated.diet(), allergens);
        if (!removed.isEmpty() && generated.contextSnapshot() != null) {
            removed.forEach(generated.contextSnapshot().putArray("allergenRemoved")::add);
            log.info("[CarePlan] removed allergen conflicts peopleId={} items={}", peopleId, removed);
        }
        energyTargetService.applyFresh(tenantId, peopleId, generated.diet());
        List<SafetyFlagDto> flags = safetyService.evaluate(
                generated.exercise(),
                generated.diet(),
                generated.execution(),
                generated.contextIncomplete(),
                generated.specialtyMismatch(),
                generated.hypoglycemiaRisk(),
                allergens,
                allergyQueryService.status(tenantId, peopleId),
                generated.contextSnapshot(),
                true);
        flags.addAll(GuidelineCheck.apply(
                generated.exercise(), generated.diet(), generated.execution(), generated.guidelines(), true));
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
        plan.setTitle(buildDefaultTitle(generated.templateKey(), goal));
        resetReviewIfNeeded(plan);
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
                tenantId,
                orgId,
                peopleId,
                staffId,
                expectedVersion,
                exercise,
                diet,
                execution,
                title,
                goalSummary,
                null,
                null);
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
            String goalSummary,
            String planSummary) {
        return updateDraft(
                tenantId,
                orgId,
                peopleId,
                staffId,
                expectedVersion,
                exercise,
                diet,
                execution,
                title,
                goalSummary,
                planSummary,
                null);
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
            String planSummary,
            String rationaleOverview) {
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
        ObjectNode exerciseNorm = CarePlanRationaleSupport.clearIfContentChanged(
                JsonUtils.readTree(draft.getExerciseJson()),
                exercise == null ? JsonUtils.emptyObject() : exercise);
        ObjectNode dietNorm = CarePlanRationaleSupport.clearIfContentChanged(
                JsonUtils.readTree(draft.getDietJson()), diet == null ? JsonUtils.emptyObject() : diet);
        if (dietNorm != null) {
            CarePlanAllergenGuard.enforce(dietNorm, ctx.foodAllergens());
            energyTargetService.apply(tenantId, peopleId, dietNorm);
        }
        ObjectNode executionNorm = CarePlanRationaleSupport.clearIfContentChanged(
                JsonUtils.readTree(draft.getExecutionJson()),
                com.healix.core.water.support.WaterTaskMigration.normalizeExecution(execution));
        if (planSummary != null || rationaleOverview != null) {
            draft.setContextSnapshotJson(mergeSnapshotFields(
                    draft.getContextSnapshotJson(), planSummary, rationaleOverview));
        }
        JsonNode snapshotNode = JsonUtils.readTree(
                draft.getContextSnapshotJson() == null ? "{}" : draft.getContextSnapshotJson());
        CarePlanTemplateKeyEnum templateKey =
                CarePlanTemplateKeyEnum.parseOrNull(snapshotNode.path("templateKey").asText(null));
        boolean specialtyMismatch = CarePlanDiseaseTags.specialtyMismatch(templateKey, ctx.diseaseCodes());
        boolean requireRationale = templateKey != null;
        List<SafetyFlagDto> flags = safetyService.evaluate(
                exerciseNorm,
                dietNorm,
                executionNorm,
                ctx.contextIncomplete(),
                specialtyMismatch,
                ctx.hypoglycemiaRisk(),
                ctx.foodAllergens(),
                ctx.allergyStatus(),
                snapshotNode,
                requireRationale);
        flags.addAll(GuidelineCheck.apply(exerciseNorm, dietNorm, executionNorm, ctx.guidelines(), requireRationale));
        draft.setExerciseJson(JsonUtils.toJson(exerciseNorm));
        draft.setDietJson(JsonUtils.toJson(dietNorm == null ? JsonUtils.emptyObject() : dietNorm));
        draft.setExecutionJson(JsonUtils.toJson(executionNorm));
        draft.setSafetyFlagsJson(safetyService.toFlagsJson(flags));
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
        resetReviewIfNeeded(plan);
        plan.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        recordRevision(tenantId, peopleId, staffId, orgId, plan.getId(), ver, ver + 1, oldSnap, draftContentSnap(draft));
        return getBundle(tenantId, orgId, peopleId);
    }

    /** 审核/沟通后改草稿：退回 DRAFT，须重新审核与沟通。 */
    private static void resetReviewIfNeeded(CarePlan plan) {
        String st = plan.getStatus();
        if (CarePlanStatusEnum.REVIEWED.name().equals(st)
                || CarePlanStatusEnum.COMMUNICATING.name().equals(st)) {
            plan.setStatus(CarePlanStatusEnum.DRAFT.name());
            plan.setReviewedAt(null);
            plan.setReviewedByStaffId(null);
            plan.setLastCommResult(null);
            plan.setLastCommAt(null);
            plan.setLastCommFollowupId(null);
            plan.setLastCommNote(null);
        }
    }

    /** 将方案总结写入 context_snapshot.summary，保留其余快照字段。 */
    private static String mergePlanSummaryIntoSnapshot(String existingJson, String planSummary) {
        return mergeSnapshotFields(existingJson, planSummary, null);
    }

    /**
     * 合并草稿快照字段：summary / rationale.overview；传 null 表示该字段不改动，传空串则清除。
     */
    private static String mergeSnapshotFields(
            String existingJson, String planSummary, String rationaleOverview) {
        ObjectNode snap;
        try {
            JsonNode raw = JsonUtils.readTree(existingJson == null || existingJson.isBlank() ? "{}" : existingJson);
            snap = raw != null && raw.isObject() ? (ObjectNode) raw.deepCopy() : JsonUtils.emptyObject();
        } catch (Exception e) {
            snap = JsonUtils.emptyObject();
        }
        if (planSummary != null) {
            if (StringUtils.hasText(planSummary)) {
                snap.put("summary", planSummary.trim());
            } else {
                snap.remove("summary");
            }
        }
        if (rationaleOverview != null) {
            ObjectNode rationale = snap.has("rationale") && snap.get("rationale").isObject()
                    ? (ObjectNode) snap.get("rationale")
                    : snap.putObject("rationale");
            if (StringUtils.hasText(rationaleOverview)) {
                rationale.put("overview", rationaleOverview.trim());
            } else {
                rationale.remove("overview");
            }
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

    /** 审核通过：草稿存在且无 ERROR 级安全项。 */
    @Transactional
    public CarePlanBundleDto markReviewed(String tenantId, String orgId, String peopleId, String staffId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft == null) {
            throw new BusinessException("无草稿可审核");
        }
        List<SafetyFlagDto> flags =
                safetyService.parseFlags(draft.getSafetyFlagsJson() == null ? "[]" : draft.getSafetyFlagsJson());
        boolean hasError = flags.stream().anyMatch(f -> "ERROR".equals(f.level()));
        if (hasError) {
            throw new BusinessException("请先处理全部 ERROR 后再审核通过");
        }
        plan.setStatus(CarePlanStatusEnum.REVIEWED.name());
        plan.setReviewedAt(LocalDateTime.now());
        plan.setReviewedByStaffId(staffId);
        plan.setLastCommResult(null);
        plan.setLastCommAt(null);
        plan.setLastCommFollowupId(null);
        plan.setLastCommNote(null);
        plan.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        recordRevision(
                tenantId,
                peopleId,
                staffId,
                orgId,
                plan.getId(),
                0,
                0,
                "{}",
                revisionSnapshot("action", "MARK_REVIEWED", "status", CarePlanStatusEnum.REVIEWED.name()));
        return getBundle(tenantId, orgId, peopleId);
    }

    /** 发起客户沟通：REVIEWED → COMMUNICATING。 */
    @Transactional
    public CarePlanBundleDto startCommunication(String tenantId, String orgId, String peopleId, String staffId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        if (!CarePlanStatusEnum.REVIEWED.name().equals(plan.getStatus())) {
            throw new BusinessException("请先审核通过后再发起沟通");
        }
        if (draftMapper.findByPlanId(plan.getId()) == null) {
            throw new BusinessException("无草稿可沟通");
        }
        plan.setStatus(CarePlanStatusEnum.COMMUNICATING.name());
        plan.setUpdatedByStaffId(staffId);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        return getBundle(tenantId, orgId, peopleId);
    }

    /**
     * 记录沟通结果。接受 → 自动发布；需修改/需重做 → 回草稿并保留修改要点。
     *
     * @return 需重做时返回的 regenerateHint 已写入 lastCommNote，前端可带入生成说明
     */
    @Transactional
    public CarePlanBundleDto recordCommunication(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String actorAccountId,
            String resultCode,
            String customerFeedback,
            String changePoints,
            boolean faceToFace,
            List<String> ackWarnCodes,
            String title) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        if (!CarePlanStatusEnum.REVIEWED.name().equals(plan.getStatus())
                && !CarePlanStatusEnum.COMMUNICATING.name().equals(plan.getStatus())) {
            throw new BusinessException("当前状态不可记录沟通");
        }
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft == null) {
            throw new BusinessException("无草稿");
        }
        CarePlanCommResultEnum result = CarePlanCommResultEnum.require(resultCode);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("followupType", FollowupType.PLAN_COMMUNICATION.name());
        content.put("contactTarget", "PATIENT");
        content.put("followupMethod", faceToFace ? "FACE_TO_FACE" : "PHONE");
        content.put(
                "guidance",
                StringUtils.hasText(customerFeedback) ? customerFeedback : ("沟通结果：" + result.name()));
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("result", result.name());
        section.put("planId", plan.getId());
        section.put("draftId", draft.getId());
        section.put("faceToFace", faceToFace);
        if (StringUtils.hasText(customerFeedback)) {
            section.put("customerFeedback", customerFeedback);
        }
        if (StringUtils.hasText(changePoints)) {
            section.put("changePoints", changePoints);
        }
        content.put("section", section);

        FollowupService followups = followupService.getObject();
        FollowupRecordViewDto followup = followups.createPeriodic(
                orgId,
                peopleId,
                FollowupType.PLAN_COMMUNICATION.name(),
                LocalDateTime.now(),
                false,
                true,
                content,
                actorAccountId);

        plan.setLastCommResult(result.name());
        plan.setLastCommAt(LocalDateTime.now());
        plan.setLastCommFollowupId(followup == null ? null : followup.getId());
        plan.setLastCommNote(changePoints);
        plan.setUpdatedByStaffId(staffId);

        if (result == CarePlanCommResultEnum.ACCEPTED) {
            plan.setStatus(CarePlanStatusEnum.COMMUNICATING.name());
            EntityMeta.onUpdate(plan);
            carePlanMapper.update(plan);
            return publish(tenantId, orgId, peopleId, staffId, ackWarnCodes, title, null);
        }

        // 需修改 / 需重做：回草稿，须重新审核
        plan.setStatus(CarePlanStatusEnum.DRAFT.name());
        plan.setReviewedAt(null);
        plan.setReviewedByStaffId(null);
        EntityMeta.onUpdate(plan);
        carePlanMapper.update(plan);
        recordRevision(
                tenantId,
                peopleId,
                staffId,
                orgId,
                plan.getId(),
                0,
                0,
                "{}",
                revisionSnapshot("action", "COMM_RESULT_" + result.name(), "note", changePoints));
        return getBundle(tenantId, orgId, peopleId);
    }

    /** 当面沟通 · 已接受：补沟通记录并发布。 */
    @Transactional
    public CarePlanBundleDto faceToFaceAccept(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String actorAccountId,
            List<String> ackWarnCodes,
            String title) {
        CarePlan plan = requirePlan(tenantId, peopleId);
        if (CarePlanStatusEnum.DRAFT.name().equals(plan.getStatus())
                || CarePlanStatusEnum.ACTIVE.name().equals(plan.getStatus())) {
            markReviewed(tenantId, orgId, peopleId, staffId);
        }
        return recordCommunication(
                tenantId,
                orgId,
                peopleId,
                staffId,
                actorAccountId,
                CarePlanCommResultEnum.ACCEPTED.name(),
                "当面沟通 · 已接受",
                null,
                true,
                ackWarnCodes,
                title);
    }

    public CarePlanBundleDto publish(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            List<String> ackWarnCodes,
            String title) {
        return publish(tenantId, orgId, peopleId, staffId, ackWarnCodes, title, null);
    }

    /**
     * @param syncDietGoal 是否把方案热量同步为医护设定的饮食目标；null 按默认（仅手改的热量同步）
     */
    @Transactional
    public CarePlanBundleDto publish(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            List<String> ackWarnCodes,
            String title,
            Boolean syncDietGoal) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CarePlan plan = requirePlan(tenantId, peopleId);
        CarePlanDraft draft = draftMapper.findByPlanId(plan.getId());
        if (draft == null) {
            throw new BusinessException("无草稿可发布");
        }
        JsonNode exercise = JsonUtils.readTree(draft.getExerciseJson());
        JsonNode diet = JsonUtils.readTree(draft.getDietJson());
        ObjectNode execution =
                com.healix.core.water.support.WaterTaskMigration.normalizeExecution(
                        JsonUtils.readTree(draft.getExecutionJson()));
        draft.setExecutionJson(JsonUtils.toJson(execution));
        CarePlanContext ctx = contextService.load(tenantId, peopleId);
        if (diet instanceof ObjectNode dietObj) {
            CarePlanAllergenGuard.enforce(dietObj, ctx.foodAllergens());
            energyTargetService.onPublish(tenantId, peopleId, staffId, dietObj, syncDietGoal);
            draft.setDietJson(JsonUtils.toJson(dietObj));
        }
        JsonNode publishSnapshot = JsonUtils.readTree(
                draft.getContextSnapshotJson() == null ? "{}" : draft.getContextSnapshotJson());
        CarePlanTemplateKeyEnum publishKey =
                CarePlanTemplateKeyEnum.parseOrNull(publishSnapshot.path("templateKey").asText(null));
        List<SafetyFlagDto> flags = safetyService.evaluate(
                exercise,
                diet,
                execution,
                ctx.contextIncomplete(),
                CarePlanDiseaseTags.specialtyMismatch(publishKey, ctx.diseaseCodes()),
                ctx.hypoglycemiaRisk(),
                ctx.foodAllergens(),
                ctx.allergyStatus(),
                publishSnapshot,
                publishKey != null);
        flags.addAll(GuidelineCheck.apply(exercise, diet, execution, ctx.guidelines(), publishKey != null));
        if (ctx.guidelines().enabled() && publishSnapshot instanceof ObjectNode snapObj) {
            // apply 会原地剔除无效引用，需回写
            draft.setExerciseJson(JsonUtils.toJson(exercise));
            draft.setDietJson(JsonUtils.toJson(diet));
            draft.setExecutionJson(JsonUtils.toJson(execution));
            snapObj.set("guideline", ctx.guidelines().toSnapshot());
            draft.setContextSnapshotJson(JsonUtils.toJson(snapObj));
        }
        draft.setSafetyFlagsJson(safetyService.toFlagsJson(flags));
        safetyService.assertPublishable(flags, ackWarnCodes);
        if (plan.getReviewedAt() == null || !StringUtils.hasText(plan.getReviewedByStaffId())) {
            throw new BusinessException(400, "请先完成方案审核后再发布");
        }
        if (!CarePlanCommResultEnum.ACCEPTED.name().equals(plan.getLastCommResult())) {
            throw new BusinessException(400, "请先完成客户沟通且结果为「接受」后再发布");
        }

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
        return signVersion(tenantId, orgId, peopleId, versionId, staffId, null);
    }

    public CarePlanVersionDto signVersion(
            String tenantId,
            String orgId,
            String peopleId,
            String versionId,
            String staffId,
            String clinicalNote) {
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
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("signStatus", SIGNED);
            after.put("versionId", versionId);
            after.put("signedByStaffId", staffId);
            after.put("signedAt", now.toString());
            if (StringUtils.hasText(clinicalNote)) {
                String note = clinicalNote.trim();
                after.put("clinicalNote", note.length() > 500 ? note.substring(0, 500) : note);
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
                    JsonUtils.toJson(after));
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
            String title = ComplianceText.filter(t.path("title").asText("未命名任务"));
            String category = t.path("category").asText("OTHER");
            String code = t.path("code").asText("TASK_" + order);
            if (com.healix.core.water.support.WaterIntakeRules.looksLikeWaterTask(category, title, code)) {
                category = com.healix.core.water.support.WaterIntakeRules.CATEGORY;
                code = com.healix.core.water.support.WaterIntakeRules.TASK_CODE;
            }
            row.setTaskCode(code);
            row.setTitle(title);
            row.setCategory(category);
            row.setFrequency(textOrNull(t, "frequency"));
            row.setTimeSlot(textOrNull(t, "timeSlot"));
            row.setRelatedRef(textOrNull(t, "relatedRef"));
            row.setEnabled(t.path("enabled").asBoolean(true) ? 1 : 0);
            row.setPayloadJson(payloadJsonOf(t));
            row.setSortOrder(order++);
            EntityMeta.onCreate(row);
            taskMapper.insert(row);
        }
    }

    /** 合并 tasks[].payload 与根级 targetMl（Agent Skill 写在根上）。 */
    private static String payloadJsonOf(JsonNode t) {
        com.fasterxml.jackson.databind.node.ObjectNode payload;
        JsonNode existing = t.get("payload");
        if (existing != null && existing.isObject()) {
            payload = ((com.fasterxml.jackson.databind.node.ObjectNode) existing).deepCopy();
        } else {
            payload = JsonUtils.emptyObject();
        }
        if (t.has("targetMl") && t.get("targetMl").canConvertToInt()) {
            int ml = t.get("targetMl").asInt();
            if (ml > 0) {
                payload.put("targetMl", ml);
            }
        }
        return payload.isEmpty() ? null : JsonUtils.toJson(payload);
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

    private List<CarePlanCommHistoryItemDto> loadCommunicationHistory(
            String orgId, String peopleId, String planId) {
        List<CarePlanCommHistoryItemDto> out = new ArrayList<>();
        if (!StringUtils.hasText(planId)) {
            return out;
        }
        List<FollowupRecord> rows = followupRecordMapper.listByPeople(orgId, peopleId, null, 100);
        List<CarePlanCommHistoryItemDto> matched = new ArrayList<>();
        for (FollowupRecord row : rows) {
            JsonNode content = JsonUtils.readTree(
                    row.getContentJson() == null || row.getContentJson().isBlank() ? "{}" : row.getContentJson());
            String type = content.path("followupType").asText("");
            if (!FollowupType.PLAN_COMMUNICATION.name().equals(type)) {
                continue;
            }
            JsonNode section = content.path("section");
            String sectionPlanId = section.path("planId").asText("");
            if (StringUtils.hasText(sectionPlanId) && !planId.equals(sectionPlanId)) {
                continue;
            }
            CarePlanCommHistoryItemDto item = new CarePlanCommHistoryItemDto();
            item.setFollowupId(row.getId());
            String result = section.path("result").asText(null);
            item.setResult(result);
            item.setResultLabel(commResultLabel(result));
            item.setCustomerFeedback(textOrNull(section.path("customerFeedback")));
            item.setChangePoints(textOrNull(section.path("changePoints")));
            item.setFaceToFace(section.path("faceToFace").asBoolean(false));
            item.setFollowupMethod(content.path("followupMethod").asText(null));
            item.setDraftId(textOrNull(section.path("draftId")));
            item.setAssigneeStaffId(row.getAssigneeStaffId());
            if (StringUtils.hasText(row.getAssigneeStaffId())) {
                StaffProfile staff = staffProfileMapper.findById(row.getAssigneeStaffId());
                if (staff != null) {
                    item.setAssigneeName(staff.getDisplayName());
                }
            }
            if (item.getAssigneeName() == null && StringUtils.hasText(row.getCompletedByStaffId())) {
                item.setAssigneeStaffId(row.getCompletedByStaffId());
                StaffProfile staff = staffProfileMapper.findById(row.getCompletedByStaffId());
                if (staff != null) {
                    item.setAssigneeName(staff.getDisplayName());
                }
            }
            LocalDateTime at = row.getCompletedAt() != null ? row.getCompletedAt() : row.getGmtCreated();
            item.setCompletedAt(at == null ? null : at.toString());
            matched.add(item);
        }
        // listByPeople 按完成时间 DESC；历史时间线升序编号
        matched.sort(Comparator.comparing(
                CarePlanCommHistoryItemDto::getCompletedAt, Comparator.nullsLast(String::compareTo)));
        int round = 1;
        for (CarePlanCommHistoryItemDto item : matched) {
            item.setRound(round++);
            out.add(item);
        }
        return out;
    }

    private static String commResultLabel(String result) {
        if (result == null || result.isBlank()) {
            return "-";
        }
        return switch (result) {
            case "ACCEPTED" -> "客户接受";
            case "NEED_MODIFY" -> "需修改";
            case "NEED_REGENERATE" -> "需重做";
            default -> result;
        };
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        String t = n.asText();
        return t == null || t.isBlank() ? null : t;
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
        dto.setReviewedAt(plan.getReviewedAt() == null ? null : plan.getReviewedAt().toString());
        dto.setReviewedByStaffId(plan.getReviewedByStaffId());
        dto.setLastCommResult(plan.getLastCommResult());
        dto.setLastCommAt(plan.getLastCommAt() == null ? null : plan.getLastCommAt().toString());
        dto.setLastCommFollowupId(plan.getLastCommFollowupId());
        dto.setLastCommNote(plan.getLastCommNote());
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
        return toVersionDto(ver, withTasks, false);
    }

    private CarePlanVersionDto toVersionDto(CarePlanVersion ver, boolean withTasks, boolean stripRationale) {
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
        JsonNode exercise = JsonUtils.readTree(ver.getExerciseJson());
        JsonNode diet = JsonUtils.readTree(ver.getDietJson());
        JsonNode execution = JsonUtils.readTree(ver.getExecutionJson());
        JsonNode snapshot = JsonUtils.readTree(
                ver.getContextSnapshotJson() == null ? "{}" : ver.getContextSnapshotJson());
        if (stripRationale) {
            exercise = CarePlanRationaleSupport.strip(exercise);
            diet = CarePlanRationaleSupport.strip(diet);
            execution = CarePlanRationaleSupport.strip(execution);
            snapshot = CarePlanRationaleSupport.strip(snapshot);
        }
        dto.setExercise(exercise);
        dto.setDiet(diet);
        dto.setExecution(execution);
        dto.setSafetyFlags(JsonUtils.readTree(ver.getSafetyFlagsJson() == null ? "[]" : ver.getSafetyFlagsJson()));
        dto.setContextSnapshot(snapshot);
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
        String category = row.getCategory();
        if (com.healix.core.water.support.WaterIntakeRules.looksLikeWaterTask(
                category, row.getTitle(), row.getTaskCode())) {
            category = com.healix.core.water.support.WaterIntakeRules.CATEGORY;
        }
        dto.setCategory(category);
        dto.setFrequency(row.getFrequency());
        dto.setTimeSlot(row.getTimeSlot());
        dto.setRelatedRef(row.getRelatedRef());
        dto.setEnabled(row.getEnabled() != null && row.getEnabled() == 1);
        dto.setSortOrder(row.getSortOrder());
        dto.setTargetMl(com.healix.core.water.support.WaterTaskTargetResolver.targetMlOf(row));
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
            return "健康管理方案";
        }
        String t = title.trim();
        if (!StringUtils.hasText(goalSummary)) {
            return t;
        }
        String g = goalSummary.trim();
        if (t.equals(g)) {
            return "健康管理方案";
        }
        if (g.length() > 40) {
            String truncatedCn = g.substring(0, 40) + "…";
            String truncatedDots = g.substring(0, 40) + "...";
            if (t.equals(truncatedCn) || t.equals(truncatedDots)) {
                return "健康管理方案";
            }
        }
        return t;
    }

    static String buildDefaultTitle(CarePlanTemplateKeyEnum key, String goalSummary) {
        String prefix = key == null ? "健康管理方案" : key.canonical().titlePrefix();
        if (!StringUtils.hasText(goalSummary)) {
            return prefix;
        }
        String g = goalSummary.trim();
        if (g.length() > 40) {
            g = g.substring(0, 40) + "…";
        }
        return prefix + " · " + g;
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
