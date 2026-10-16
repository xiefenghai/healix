package com.healix.core.assessment.service;

import com.healix.common.exception.BusinessException;
import com.healix.common.result.PageResult;
import com.healix.common.util.AuditDetails;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentTriggerSource;
import com.healix.core.assessment.domain.AssessmentQuestionnaireRequest;
import com.healix.core.assessment.domain.AssessmentQuestionnaireSubmission;
import com.healix.core.assessment.domain.PeopleAssessmentLatest;
import com.healix.core.assessment.domain.PeopleAssessmentSnapshot;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentOverviewDto.DiseaseGroupDto;
import com.healix.core.assessment.dto.AssessmentOverviewDto.QuestionnaireBriefDto;
import com.healix.core.assessment.dto.AssessmentOverviewDto.QuestionnaireRequestDto;
import com.healix.core.assessment.dto.AssessmentRuleSheetDto;
import com.healix.core.assessment.dto.AssessmentSnapshotDto;
import com.healix.core.assessment.engine.AssessmentEngine;
import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.mapper.PeopleAssessmentSnapshotMapper;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.portal.enums.PortalEnum;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** B 端疾病评估：概览（按病种）、历史、详情、手动重算、规则说明、一键建档。 */
@Service
@RequiredArgsConstructor
public class AssessmentOrchestrator {

    private final AssessmentEngineRegistry registry;
    private final AssessmentRecomputeService recomputeService;
    private final AssessmentViewMapper viewMapper;
    private final PeopleAssessmentSnapshotMapper snapshotMapper;
    private final AssessmentStoreMapper storeMapper;
    private final ArchiveAccessService archiveAccessService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final AuditService auditService;

    /** 打开评估页时顺带重算（结果不变不新增快照），保证看到的是最新数据的结论。 */
    public AssessmentOverviewDto overview(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        AssessmentRecomputeService.Outcome outcome =
                recomputeService.recompute(tenantId, peopleId, AssessmentTriggerSource.AUTO, null, false);
        return buildOverview(tenantId, peopleId, outcome.context());
    }

    AssessmentOverviewDto buildOverview(String tenantId, String peopleId, AssessmentContext ctx) {
        Map<String, PeopleAssessmentLatest> latest = storeMapper.listLatest(tenantId, peopleId).stream()
                .collect(Collectors.toMap(PeopleAssessmentLatest::getEngineCode, Function.identity(), (a, b) -> a));
        AssessmentOverviewDto dto = new AssessmentOverviewDto();
        dto.setEvaluatedAt(ctx.getAsOf());
        for (AssessmentDisease disease : AssessmentDisease.values()) {
            boolean diagnosed = switch (disease) {
                case DIABETES -> ctx.hasKnownDiabetes();
                case HYPERTENSION -> ctx.hasKnownHypertension();
                case OBESITY -> ctx.hasKnownObesity();
            };
            boolean archive = switch (disease) {
                case DIABETES -> ctx.isHasDiabetesArchive();
                case HYPERTENSION -> ctx.isHasHypertensionArchive();
                case OBESITY -> ctx.isHasObesityArchive();
            };
            AssessmentEngine engine =
                    registry.find(disease, diagnosed ? AssessmentCategory.SEVERITY : AssessmentCategory.RISK);
            if (engine == null) {
                continue;
            }
            DiseaseGroupDto g = new DiseaseGroupDto();
            g.setDiseaseCode(disease.code());
            g.setDiseaseLabel(disease.label());
            g.setDiagnosed(diagnosed);
            g.setHasDiseaseArchive(archive);
            if (diagnosed) {
                g.setDiagnosedReason(archive ? "已建" + disease.label() + "档案" : "基础档案「现有疾病」已勾选" + disease.label());
            }
            g.setEngineCode(engine.code());
            g.setEngineLabel(engine.label());
            g.setCategory(engine.category().name());
            g.setCategoryLabel(engine.category().label());
            g.setApplicable(engine.applicable(ctx));
            if (!g.isApplicable()) {
                g.setNotApplicableReason(notApplicableReason(engine, ctx));
            }
            PeopleAssessmentLatest row = latest.get(engine.code());
            if (row != null && row.effective() && StringUtils.hasText(row.getSnapshotId())) {
                PeopleAssessmentSnapshot snap = snapshotMapper.findById(row.getSnapshotId());
                if (snap != null) {
                    g.setLatest(viewMapper.toDto(snap));
                }
            }
            dto.getGroups().add(g);
        }
        AssessmentQuestionnaireSubmission sub = storeMapper.findLatestSubmission(tenantId, peopleId);
        if (sub != null) {
            QuestionnaireBriefDto q = new QuestionnaireBriefDto();
            q.setId(sub.getId());
            q.setPortal(sub.getPortal());
            q.setSubmittedAt(sub.getSubmittedAt());
            dto.setLastQuestionnaire(q);
        }
        AssessmentQuestionnaireRequest req = storeMapper.findLatestRequest(tenantId, peopleId);
        if (req != null && !AssessmentQuestionnaireRequest.CANCELLED.equals(req.getStatus())) {
            dto.setPendingRequest(toRequestDto(req));
        }
        return dto;
    }

    static QuestionnaireRequestDto toRequestDto(AssessmentQuestionnaireRequest req) {
        QuestionnaireRequestDto r = new QuestionnaireRequestDto();
        r.setId(req.getId());
        r.setStatus(req.getStatus());
        r.setCreatedAt(req.getGmtCreated());
        r.setExpiresAt(req.getExpiresAt());
        r.setSubmittedAt(req.getSubmittedAt());
        return r;
    }

    private static String notApplicableReason(AssessmentEngine engine, AssessmentContext ctx) {
        Integer age = ctx.getAgeYears();
        return switch (engine.disease()) {
            case DIABETES -> age != null && (age < 20 || age > 74) ? "CDRS 适用于 20–74 岁人群" : "当前不适用";
            case OBESITY -> age != null && age < 18 ? "儿童青少年需按年龄别 BMI 百分位评估，本评估不适用" : "当前不适用";
            default -> "当前不适用";
        };
    }

    public PageResult<AssessmentSnapshotDto> history(
            String tenantId, String orgId, String peopleId, String engineCode, int page, int pageSize) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        int p = Math.max(1, page);
        int size = Math.min(100, Math.max(1, pageSize));
        String engine = StringUtils.hasText(engineCode) ? engineCode.trim() : null;
        long total = snapshotMapper.countHistory(tenantId, peopleId, engine);
        List<AssessmentSnapshotDto> items = snapshotMapper.listHistory(tenantId, peopleId, engine, (p - 1) * size, size)
                .stream()
                .map(viewMapper::toDto)
                .toList();
        return new PageResult<>(total, items);
    }

    public AssessmentSnapshotDto detail(String tenantId, String orgId, String peopleId, String id) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        PeopleAssessmentSnapshot row = snapshotMapper.findById(id);
        if (row == null || !tenantId.equals(row.getTenantId()) || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(404, "评估记录不存在");
        }
        return viewMapper.toDto(row);
    }

    /** 手动重新评估：每个适用引擎都写一条快照（便于留痕），并记审计。 */
    public AssessmentOverviewDto runManual(
            String tenantId, String orgId, String peopleId, String staffId, String accountId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        AssessmentRecomputeService.Outcome outcome =
                recomputeService.recompute(tenantId, peopleId, AssessmentTriggerSource.MANUAL, staffId, false);
        auditService.record(
                PortalEnum.B.code(),
                accountId,
                "STAFF",
                tenantId,
                AuditActionEnum.PATIENT_ASSESSMENT_RUN.name(),
                "people_assessment_snapshot",
                peopleId,
                peopleId,
                AuditDetails.of(
                        "triggerSource", AssessmentTriggerSource.MANUAL.name(),
                        "engines", outcome.written().stream().map(PeopleAssessmentSnapshot::getEngineCode).toList(),
                        "staffId", staffId));
        return buildOverview(tenantId, peopleId, outcome.context());
    }

    public List<AssessmentRuleSheetDto> rules(String engineCode) {
        if (StringUtils.hasText(engineCode)) {
            AssessmentEngine engine = registry.get(engineCode);
            if (engine == null) {
                throw new BusinessException(404, "未知评估: " + engineCode);
            }
            return List.of(viewMapper.toRuleSheet(engine));
        }
        return registry.all().stream().map(viewMapper::toRuleSheet).toList();
    }

    /** 风险评估提示疑似已患病：健管师确认后一键建病种档案，随即切换为严重等级分层。 */
    @Transactional
    public AssessmentOverviewDto createDiseaseArchive(
            String tenantId, String orgId, String peopleId, String staffId, String diseaseCode) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        AssessmentDisease disease = AssessmentDisease.ofCode(diseaseCode);
        if (disease == null) {
            throw new BusinessException(400, "不支持的病种: " + diseaseCode);
        }
        DiseaseArchiveViewDto existing = diseaseArchiveService.get(tenantId, peopleId, disease.code());
        if (existing.getVersion() == null || existing.getVersion() == 0) {
            diseaseArchiveService.save(
                    tenantId, peopleId, disease.code(), 0, "{}", ArchiveOperatorContext.staff(staffId, orgId));
        }
        AssessmentRecomputeService.Outcome outcome =
                recomputeService.recompute(tenantId, peopleId, AssessmentTriggerSource.MANUAL, staffId, false);
        return buildOverview(tenantId, peopleId, outcome.context());
    }
}
