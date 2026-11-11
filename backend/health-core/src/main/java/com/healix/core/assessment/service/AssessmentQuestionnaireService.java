package com.healix.core.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.JsonUtils;
import com.healix.common.util.SnowflakeId;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.intake.service.IntakeAccessLinkService;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.assessment.catalog.AssessmentFields;
import com.healix.core.assessment.catalog.AssessmentFields.Audience;
import com.healix.core.assessment.catalog.AssessmentFields.Spec;
import com.healix.core.assessment.catalog.AssessmentTriggerSource;
import com.healix.core.assessment.catalog.InputFieldMapping;
import com.healix.core.assessment.domain.AssessmentQuestionnaireRequest;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.ActivityItem;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Conflict;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.FillHint;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Question;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Section;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.SubmitCommand;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.SubmitResult;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.View;
import com.healix.core.assessment.engine.AssessmentEngine;
import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentContextBuilder;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.field.FieldCatalog;
import com.healix.core.field.FieldDef;
import com.healix.core.field.FieldOption;
import com.healix.core.field.FieldOptionService;
import com.healix.core.field.FieldStore;
import com.healix.core.field.FieldType;
import com.healix.core.field.FieldWriteSource;
import com.healix.core.field.PeopleFieldService;
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.service.NotifyFacade;
import com.healix.core.observation.enums.HealthDataSourceEnum;
import com.healix.core.observation.service.LabReportService;
import com.healix.core.observation.service.LabReportService.LabItemCommand;
import com.healix.core.observation.service.LabReportService.LabReportCommand;
import com.healix.core.observation.service.MetricService;
import com.healix.core.observation.service.MetricService.MetricCommand;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.questionnaire.QuestionnaireCatalog;
import com.healix.core.questionnaire.QuestionnaireDef;
import com.healix.core.questionnaire.domain.QuestionnaireResponse;
import com.healix.core.questionnaire.mapper.QuestionnaireResponseMapper;
import com.healix.core.questionnaire.service.QuestionnaireResponseService;
import com.healix.core.vitals.service.VitalService;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 评估补充问卷：按适用引擎缺字段动态出题；作答写入 {@code questionnaire_response}，字段走
 * {@link PeopleFieldService} / 指标表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentQuestionnaireService {

    public static final String PORTAL_B = "B";
    public static final String PORTAL_C = "C";
    public static final String C_LINK_PATH = "/assessment-questionnaire";
    public static final String QUESTIONNAIRE_CODE = "ASSESSMENT_SUPPLEMENT";
    private static final int REQUEST_VALID_DAYS = 7;

    private final AssessmentEngineRegistry registry;
    private final AssessmentContextBuilder contextBuilder;
    private final AssessmentRecomputeService recomputeService;
    private final AssessmentOrchestrator orchestrator;
    private final AssessmentStoreMapper storeMapper;
    private final ArchiveAccessService archiveAccessService;
    private final FieldCatalog fieldCatalog;
    private final FieldOptionService fieldOptionService;
    private final PeopleFieldService peopleFieldService;
    private final QuestionnaireCatalog questionnaireCatalog;
    private final QuestionnaireResponseMapper responseMapper;
    private final MetricService metricService;
    private final VitalService vitalService;
    private final LabReportService labReportService;
    private final NotifyFacade notifyFacade;
    private final WorkspaceTaskGenerator taskGenerator;
    private final AuditService auditService;
    private final StaffProfileMapper staffProfileMapper;

    @Value("${healix.public.user-base-url:http://localhost:5173}")
    private String userBaseUrl;

    // ------------------------------------------------------------------ 出题

    public View viewForStaff(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return buildView(tenantId, peopleId, PORTAL_B);
    }

    public View viewForPatient(String tenantId, String peopleId) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return buildView(tenantId, peopleId, PORTAL_C);
    }

    private View buildView(String tenantId, String peopleId, String portal) {
        AssessmentContext ctx = contextBuilder.build(tenantId, peopleId);
        Map<String, List<String>> usedBy = new LinkedHashMap<>();
        Set<String> required = new HashSet<>();
        for (AssessmentEngine engine : registry.all()) {
            if (!engine.applicable(ctx)) {
                continue;
            }
            for (String f : engine.inputs()) {
                usedBy.computeIfAbsent(f, k -> new ArrayList<>()).add(engine.label());
            }
            required.addAll(engine.requiredInputs());
        }

        Map<String, List<Question>> bySection = new LinkedHashMap<>();
        for (String code : List.of("body", "lab", "lifestyle", "history", "professional")) {
            bySection.put(code, new ArrayList<>());
        }
        List<FillHint> hints = new ArrayList<>();
        int missing = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (String rawCode : usedBy.keySet()) {
            // 饮酒：引擎声明 status，问卷出频率题
            String code = AssessmentFields.DRINKING.equals(rawCode)
                    ? AssessmentFields.DRINKING_FREQUENCY
                    : rawCode;
            if (!seen.add(code)) {
                continue;
            }
            List<String> engines = usedBy.containsKey(rawCode) ? usedBy.get(rawCode) : usedBy.get(code);
            if (engines == null) {
                engines = List.of();
            }
            Spec spec = AssessmentFields.find(code).orElse(null);
            if (spec == null || !spec.inQuestionnaire()) {
                if (spec != null && spec.fillHint() != null && !ctx.has(code)) {
                    hints.add(new FillHint(code, spec.label(), spec.fillHint(), engines));
                } else if (spec == null && AssessmentFields.fillHintOf(code) != null && !ctx.has(code)) {
                    hints.add(new FillHint(
                            code, AssessmentFields.labelOf(code), AssessmentFields.fillHintOf(code), engines));
                }
                continue;
            }
            if (!visible(code, portal, ctx)) {
                continue;
            }
            FieldDef def = fieldCatalog.find(code).orElse(null);
            // lab.* 目录为 readOnly（主入口在检验报告）；问卷补录字段例外
            if (def == null || (def.readOnly() && !isQuestionnaireLabField(code))) {
                continue;
            }
            boolean has = ctx.has(code);
            if (!has) {
                missing++;
            }
            Object current = ctx.currentValue(code);
            List<FieldOption> options = fieldOptionService.options(tenantId, def);
            bySection.get(sectionOf(spec)).add(new Question(
                    code,
                    spec.label(),
                    def.type().name(),
                    questionText(spec, def),
                    def.unit(),
                    def.min(),
                    def.max(),
                    options,
                    spec.audience().name(),
                    required.contains(code),
                    !has,
                    current,
                    answerText(tenantId, code, current),
                    engines));
        }
        List<Section> sections = new ArrayList<>();
        bySection.forEach((code, qs) -> {
            if (!qs.isEmpty()) {
                sections.add(new Section(code, sectionTitle(code), qs));
            }
        });
        int total = sections.stream().mapToInt(s -> s.questions().size()).sum();
        AssessmentQuestionnaireRequest req = storeMapper.findPendingRequest(tenantId, peopleId);
        QuestionnaireResponse last = responseMapper.findLatestSubmitted(
                tenantId, peopleId, QuestionnaireResponseService.PURPOSE_ASSESSMENT);
        return new View(
                sections,
                hints,
                missing,
                total,
                req == null ? null : AssessmentOrchestrator.toRequestDto(req),
                last == null ? null : last.getSubmittedAt());
    }

    private static boolean visible(String code, String portal, AssessmentContext ctx) {
        Spec spec = AssessmentFields.find(code).orElse(null);
        if (spec == null) {
            return false;
        }
        if (spec.audience() == Audience.STAFF && !PORTAL_B.equals(portal)) {
            return false;
        }
        if (AssessmentFields.DM_COMPLICATIONS.equals(code) && !ctx.hasKnownDiabetes()) {
            return false;
        }
        return !spec.femaleOnly() || !ctx.isMale();
    }

    private static String sectionOf(Spec spec) {
        return spec.section() == null ? "history" : spec.section();
    }

    private static String sectionTitle(String code) {
        return switch (code) {
            case "body" -> "身体测量";
            case "lab" -> "肾功能相关检验";
            case "lifestyle" -> "生活方式";
            case "history" -> "疾病与用药史";
            default -> "专业检查（健管师填写）";
        };
    }

    private static boolean isQuestionnaireLabField(String code) {
        return AssessmentFields.CR.equals(code)
                || AssessmentFields.EGFR.equals(code)
                || AssessmentFields.UACR.equals(code);
    }

    private static String questionText(Spec spec, FieldDef def) {
        return spec.label() != null ? spec.label() : def.label();
    }

    // ------------------------------------------------------------------ 提交

    @Transactional
    public SubmitResult submitByStaff(
            String tenantId, String orgId, String peopleId, String staffId, String accountId, SubmitCommand cmd) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        return submit(tenantId, orgId, peopleId, PORTAL_B, staffId, accountId, cmd);
    }

    @Transactional
    public SubmitResult submitByPatient(String tenantId, String peopleId, String accountId, SubmitCommand cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        return submit(tenantId, null, peopleId, PORTAL_C, null, accountId, cmd);
    }

    private SubmitResult submit(
            String tenantId,
            String orgId,
            String peopleId,
            String portal,
            String staffId,
            String accountId,
            SubmitCommand cmd) {
        if (cmd == null || cmd.answers() == null || cmd.answers().isEmpty()) {
            throw new BusinessException(400, "请至少回答一题");
        }
        AssessmentContext ctx = contextBuilder.build(tenantId, peopleId);
        Map<String, Object> answers = normalizeAnswers(tenantId, cmd.answers(), portal, ctx);
        if (answers.isEmpty()) {
            throw new BusinessException(400, "请至少回答一题");
        }

        Set<String> confirmed = cmd.confirmOverrides() == null ? Set.of() : Set.copyOf(cmd.confirmOverrides());
        List<Conflict> conflicts = detectConflicts(tenantId, ctx, answers);
        List<Conflict> unconfirmed = conflicts.stream().filter(c -> !confirmed.contains(c.field())).toList();
        if (!unconfirmed.isEmpty()) {
            return new SubmitResult("CONFLICT", unconfirmed, null, null);
        }

        LocalDateTime now = LocalDateTime.now();
        AssessmentQuestionnaireRequest pending = storeMapper.findPendingRequest(tenantId, peopleId);
        String requestId = pending != null
                        && (PORTAL_C.equals(portal) || Objects.equals(pending.getId(), cmd.requestId()))
                ? pending.getId()
                : null;

        QuestionnaireDef q = questionnaireCatalog.latest(QUESTIONNAIRE_CODE);
        QuestionnaireResponse row = new QuestionnaireResponse();
        row.setTenantId(tenantId);
        // questionnaire_response.org_id 非空；C 端用待填请求上的机构，否则占位
        String responseOrgId = StringUtils.hasText(orgId)
                ? orgId
                : (pending != null && StringUtils.hasText(pending.getOrgId()) ? pending.getOrgId() : "0");
        row.setOrgId(responseOrgId);
        row.setPeopleId(peopleId);
        row.setQuestionnaireCode(q.code());
        row.setQuestionnaireVersion(q.version());
        row.setPurpose(QuestionnaireResponseService.PURPOSE_ASSESSMENT);
        row.setRequestId(requestId);
        row.setStatus(QuestionnaireResponseService.STATUS_DRAFT);
        row.setChannel(PORTAL_B.equals(portal) ? "ONSITE" : "WECHAT");
        row.setPrefillJson("{}");
        row.setAnswersJson(JsonUtils.toJson(answers));
        row.setFilledByType(PORTAL_B.equals(portal) ? "STAFF" : "PEOPLE");
        row.setFilledById(PORTAL_B.equals(portal) ? staffId : peopleId);
        EntityMeta.onCreate(row);
        responseMapper.insert(row);

        ArchiveOperatorContext operator = PORTAL_B.equals(portal)
                ? ArchiveOperatorContext.staff(staffId, orgId, "ASSESSMENT_QUESTIONNAIRE")
                : ArchiveOperatorContext.people(peopleId, "ASSESSMENT_QUESTIONNAIRE");
        FieldWriteSource source = operator.toFieldSource(row.getId());

        writeAnswers(
                tenantId,
                orgId,
                peopleId,
                staffId,
                portal,
                ctx,
                answers,
                source,
                now,
                cmd.labSampledAtByField());

        ObjectNode snapshot = JsonUtils.emptyObject();
        snapshot.put("questionnaire", q.key());
        snapshot.set("answers", JsonUtils.mapper().valueToTree(answers));
        row.setSnapshotJson(JsonUtils.toJson(snapshot));
        row.setAnswersJson(JsonUtils.toJson(answers));
        row.setChangesJson(conflicts.isEmpty() ? null : JsonUtils.toJson(conflicts));
        row.setSubmittedAt(now);
        row.setStatus(QuestionnaireResponseService.STATUS_SUBMITTED);
        EntityMeta.onUpdate(row);
        responseMapper.markSubmitted(row);
        if (requestId != null) {
            storeMapper.markRequestSubmitted(requestId, row.getId(), now);
        }

        AssessmentRecomputeService.Outcome outcome =
                recomputeService.recompute(tenantId, peopleId, AssessmentTriggerSource.QUESTIONNAIRE, staffId, false);

        auditService.record(
                PortalEnum.valueOf(portal).code(),
                accountId,
                PORTAL_B.equals(portal) ? "STAFF" : "PATIENT",
                tenantId,
                AuditActionEnum.PATIENT_ASSESSMENT_QUESTIONNAIRE.name(),
                "questionnaire_response",
                row.getId(),
                peopleId,
                AuditDetails.of(
                        "fields", answers.keySet(),
                        "overrides", conflicts.stream().map(Conflict::field).toList(),
                        "requestId", requestId));

        if (PORTAL_C.equals(portal) && !conflicts.isEmpty()) {
            notifySelfReport(tenantId, peopleId, row.getId(), conflicts);
        }
        AssessmentOverviewDto overview = PORTAL_B.equals(portal)
                ? orchestrator.buildOverview(tenantId, peopleId, outcome.context())
                : null;
        return new SubmitResult("OK", List.of(), row.getId(), overview);
    }

    private void writeAnswers(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String portal,
            AssessmentContext ctx,
            Map<String, Object> answers,
            FieldWriteSource source,
            LocalDateTime now,
            Map<String, String> labSampledAtByField) {
        Map<String, JsonNode> meta = new LinkedHashMap<>();
        // 同一采样时间合并为一份报告；不同时间各自建报告
        Map<LocalDateTime, List<LabItemCommand>> labBySampledAt = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            String code = e.getKey();
            Object raw = e.getValue();
            if (isUnknown(raw)) {
                continue;
            }
            FieldStore store;
            try {
                store = FieldStore.of(code);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (store == FieldStore.METRIC) {
                writeMetric(tenantId, orgId, peopleId, staffId, portal, ctx, code, raw, now);
                continue;
            }
            if (store == FieldStore.LAB) {
                LabItemCommand item = toLabItem(code, raw);
                if (item == null) {
                    continue;
                }
                String sampledRaw =
                        labSampledAtByField == null ? null : labSampledAtByField.get(code);
                LocalDateTime sampledAt = parseLabSampledAt(sampledRaw);
                if (sampledAt == null) {
                    throw new BusinessException(400, AssessmentFields.labelOf(code) + " 请填写采样时间");
                }
                if (sampledAt.isAfter(now.plusMinutes(5))) {
                    throw new BusinessException(400, AssessmentFields.labelOf(code) + " 采样时间不能晚于当前时间");
                }
                labBySampledAt.computeIfAbsent(sampledAt, k -> new ArrayList<>()).add(item);
                continue;
            }
            if (store != FieldStore.BASIC && store != FieldStore.DISEASE) {
                continue;
            }
            if (AssessmentFields.DRINKING.equals(code) || AssessmentFields.DRINKING_FREQUENCY.equals(code)) {
                applyDrinking(meta, String.valueOf(raw));
                continue;
            }
            meta.put(code, toJsonNode(raw));
        }
        if (!meta.isEmpty()) {
            peopleFieldService.write(tenantId, peopleId, meta, source);
        }
        for (Map.Entry<LocalDateTime, List<LabItemCommand>> e : labBySampledAt.entrySet()) {
            writeLabReport(tenantId, orgId, peopleId, staffId, portal, e.getValue(), e.getKey(), now);
        }
    }

    private static LocalDateTime parseLabSampledAt(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String s = raw.trim().replace(' ', 'T');
        if (s.length() == 16) {
            s = s + ":00";
        }
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            throw new BusinessException(400, "采样时间格式不正确");
        }
    }

    private LabItemCommand toLabItem(String code, Object raw) {
        if (!isQuestionnaireLabField(code)) {
            return null;
        }
        BigDecimal value = number(raw);
        if (value == null) {
            return null;
        }
        // 就地录入始终落报告：上下文里的值可能已做单位纠偏（如肌酐 1mg/dL→88μmol/L），
        // 若再按 sameValue 跳过，会把医生确认过的数值写不进去，重评也看不到更新。
        FieldDef def = fieldCatalog.find(code).orElse(null);
        String unit = def == null ? null : def.unit();
        String itemCode = code.startsWith("lab.") ? code.substring("lab.".length()) : code;
        return new LabItemCommand(itemCode, null, value, null, unit, null, null, null);
    }

    private void writeLabReport(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String portal,
            List<LabItemCommand> items,
            LocalDateTime sampledAt,
            LocalDateTime now) {
        LocalDateTime reportedAt = sampledAt.isAfter(now) ? sampledAt : now;
        LabReportCommand cmd = new LabReportCommand(
                null,
                sampledAt,
                reportedAt,
                "评估问卷补充",
                PORTAL_B.equals(portal)
                        ? HealthDataSourceEnum.staffManual().name()
                        : HealthDataSourceEnum.patientManual().name(),
                items);
        if (PORTAL_B.equals(portal)) {
            labReportService.create(tenantId, orgId, peopleId, staffId, cmd);
        } else {
            labReportService.createForPatient(tenantId, peopleId, cmd);
        }
    }

    private void applyDrinking(Map<String, JsonNode> meta, String raw) {
        String v = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if ("NEVER".equals(v) || AssessmentContext.NONE.equals(v)) {
            meta.put(AssessmentFields.DRINKING_STATUS, JsonUtils.mapper().valueToTree("NEVER"));
            meta.put(AssessmentFields.DRINKING_FREQUENCY, null);
        } else {
            meta.put(AssessmentFields.DRINKING_STATUS, JsonUtils.mapper().valueToTree("CURRENT"));
            meta.put(AssessmentFields.DRINKING_FREQUENCY, JsonUtils.mapper().valueToTree(v));
        }
    }

    private void writeMetric(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String portal,
            AssessmentContext ctx,
            String code,
            Object raw,
            LocalDateTime now) {
        BigDecimal value = number(raw);
        if (value == null) {
            return;
        }
        String metricType = code.startsWith("metric.") ? code.substring("metric.".length()) : code;
        BigDecimal current = switch (code) {
            case AssessmentFields.HEIGHT -> ctx.getHeightCm();
            case AssessmentFields.WEIGHT -> ctx.getWeightKg();
            case AssessmentFields.WAIST -> ctx.getWaistCm();
            case AssessmentFields.BODY_FAT -> ctx.getBodyFat();
            default -> null;
        };
        if (current != null && current.compareTo(value) == 0) {
            return;
        }
        FieldDef def = fieldCatalog.find(code).orElse(null);
        String unit = def == null ? null : def.unit();
        if (PORTAL_B.equals(portal)) {
            metricService.create(
                    tenantId,
                    orgId,
                    peopleId,
                    staffId,
                    new MetricCommand(metricType, value, unit, now, null, null, "评估问卷", null));
        } else {
            vitalService.record(peopleId, metricType, value, unit, now, "SELF");
        }
    }

    private void notifySelfReport(String tenantId, String peopleId, String submissionId, List<Conflict> conflicts) {
        try {
            String changes = conflicts.stream().map(AssessmentQuestionnaireService::changeLine).collect(Collectors.joining("；"));
            String summary = "请核对是否调整方案：" + changes;
            if (summary.length() > 240) {
                summary = summary.substring(0, 240) + "…";
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("submissionId", submissionId);
            payload.put("changes", conflicts);
            taskGenerator.onArchiveSelfReport(tenantId, peopleId, submissionId, summary, payload);
        } catch (RuntimeException e) {
            log.warn("archive self report task failed people={}: {}", peopleId, e.getMessage());
        }
    }

    private static String changeLine(Conflict c) {
        String from = StringUtils.hasText(c.currentText()) ? c.currentText() : "未填写";
        String to = StringUtils.hasText(c.newText()) ? c.newText() : "未填写";
        return c.label() + " " + from + " → " + to;
    }

    // ------------------------------------------------------------------ 校验

    private Map<String, Object> normalizeAnswers(
            String tenantId, Map<String, Object> raw, String portal, AssessmentContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            String code = resolveAnswerKey(e.getKey());
            if (!AssessmentFields.inQuestionnaire(code)) {
                throw new BusinessException(400, "未知题目: " + e.getKey());
            }
            if (!visible(code, portal, ctx)) {
                throw new BusinessException(400, AssessmentFields.labelOf(code) + " 不可在此填写");
            }
            FieldDef def = fieldCatalog.require(code);
            Object v = normalize(tenantId, def, e.getValue());
            if (v != null) {
                out.put(code, v);
            }
        }
        return out;
    }

    private static String resolveAnswerKey(String key) {
        if (key == null) {
            return null;
        }
        List<String> codes = InputFieldMapping.resolve(key);
        return codes.isEmpty() ? key : codes.get(0);
    }

    private Object normalize(String tenantId, FieldDef def, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return null;
        }
        List<FieldOption> options = fieldOptionService.options(tenantId, def);
        Set<String> codes = options.stream().map(FieldOption::code).collect(Collectors.toSet());
        return switch (def.type()) {
            case NUMBER -> {
                BigDecimal n;
                try {
                    n = new BigDecimal(String.valueOf(raw).trim());
                } catch (NumberFormatException ex) {
                    throw new BusinessException(400, def.label() + " 请填写数字");
                }
                if ((def.min() != null && n.compareTo(def.min()) < 0)
                        || (def.max() != null && n.compareTo(def.max()) > 0)) {
                    throw new BusinessException(
                            400,
                            def.label()
                                    + " 应在 "
                                    + def.min().toPlainString()
                                    + "–"
                                    + def.max().toPlainString()
                                    + (def.unit() == null ? "" : " " + def.unit())
                                    + " 之间");
                }
                yield n.stripTrailingZeros().scale() < 0 ? n.setScale(0) : n.stripTrailingZeros();
            }
            case MULTI -> {
                if (!(raw instanceof Collection<?> list)) {
                    throw new BusinessException(400, def.label() + " 格式不正确");
                }
                List<String> picked = new ArrayList<>();
                for (Object o : list) {
                    String c = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
                    if (!codes.isEmpty() && !codes.contains(c)) {
                        throw new BusinessException(400, def.label() + " 选项不正确: " + o);
                    }
                    if (!picked.contains(c)) {
                        picked.add(c);
                    }
                }
                if (picked.isEmpty()) {
                    yield null;
                }
                boolean exclusive = picked.stream().anyMatch(def.exclusive()::contains)
                        || picked.contains(AssessmentContext.NONE)
                        || picked.contains(AssessmentContext.UNKNOWN);
                if (exclusive && picked.size() > 1) {
                    throw new BusinessException(400, def.label() + "：互斥选项不能与其他选项同时选择");
                }
                yield picked;
            }
            case ENUM -> {
                String c = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
                if (!codes.isEmpty() && !codes.contains(c)) {
                    throw new BusinessException(400, def.label() + " 选项不正确: " + raw);
                }
                yield c;
            }
            default -> String.valueOf(raw).trim();
        };
    }

    private List<Conflict> detectConflicts(String tenantId, AssessmentContext ctx, Map<String, Object> answers) {
        List<Conflict> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : answers.entrySet()) {
            String code = e.getKey();
            Object next = e.getValue();
            if (isUnknown(next)) {
                continue;
            }
            FieldStore store;
            try {
                store = FieldStore.of(code);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            if (store != FieldStore.BASIC && store != FieldStore.DISEASE) {
                continue;
            }
            Object current = ctx.currentValue(code);
            if (AssessmentFields.FAMILY_HISTORY.equals(code) && !ctx.isFamilyHistoryCollected()) {
                current = null;
            }
            if (AssessmentFields.HTN_MAX_SBP.equals(code)) {
                current = ctx.getArchiveHighestSbp();
            }
            if (AssessmentFields.HTN_MAX_DBP.equals(code)) {
                current = ctx.getArchiveHighestDbp();
            }
            if (current != null && !sameValue(current, next)) {
                out.add(new Conflict(
                        code,
                        AssessmentFields.labelOf(code),
                        current,
                        answerText(tenantId, code, current),
                        next,
                        answerText(tenantId, code, next)));
            }
        }
        return out;
    }

    static boolean sameValue(Object a, Object b) {
        if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
            Set<String> sa = ca.stream().map(o -> String.valueOf(o).toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
            Set<String> sb = cb.stream().map(o -> String.valueOf(o).toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
            return sa.equals(sb);
        }
        BigDecimal na = number(a);
        BigDecimal nb = number(b);
        if (na != null && nb != null) {
            return na.compareTo(nb) == 0;
        }
        return String.valueOf(a).equalsIgnoreCase(String.valueOf(b));
    }

    private static boolean isUnknown(Object v) {
        if (v instanceof Collection<?> c) {
            return c.size() == 1 && AssessmentContext.UNKNOWN.equals(String.valueOf(c.iterator().next()));
        }
        return AssessmentContext.UNKNOWN.equals(String.valueOf(v));
    }

    String answerText(String tenantId, String code, Object v) {
        if (v == null) {
            return null;
        }
        FieldDef def = fieldCatalog.find(code).orElse(null);
        if (def != null && def.type() == FieldType.NUMBER) {
            BigDecimal n = number(v);
            String unit = def.unit() == null ? "" : " " + def.unit();
            return (n == null ? String.valueOf(v) : n.stripTrailingZeros().toPlainString()) + unit;
        }
        Map<String, String> labels = Map.of();
        if (def != null && def.choice()) {
            labels = fieldOptionService.options(tenantId, def).stream()
                    .collect(Collectors.toMap(FieldOption::code, FieldOption::label, (a, b) -> a));
        }
        if (v instanceof Collection<?> c) {
            Map<String, String> finalLabels = labels;
            return c.stream()
                    .map(o -> finalLabels.getOrDefault(String.valueOf(o), String.valueOf(o)))
                    .collect(Collectors.joining("、"));
        }
        return labels.getOrDefault(String.valueOf(v), String.valueOf(v));
    }

    /** 单测 / 静态调用兼容：无租户时用目录选项。 */
    public static String answerText(String code, Object v) {
        if (v == null) {
            return null;
        }
        FieldCatalog catalog = new FieldCatalog();
        FieldDef def = catalog.find(code).orElse(null);
        if (def != null && def.type() == FieldType.NUMBER) {
            BigDecimal n = number(v);
            String unit = def.unit() == null ? "" : " " + def.unit();
            return (n == null ? String.valueOf(v) : n.stripTrailingZeros().toPlainString()) + unit;
        }
        Map<String, String> labels = Map.of();
        if (def != null && def.options() != null) {
            labels = catalog.catalogOptions(def.options()).stream()
                    .collect(Collectors.toMap(FieldOption::code, FieldOption::label, (a, b) -> a));
        }
        if (v instanceof Collection<?> c) {
            Map<String, String> finalLabels = labels;
            return c.stream()
                    .map(o -> finalLabels.getOrDefault(String.valueOf(o), String.valueOf(o)))
                    .collect(Collectors.joining("、"));
        }
        return labels.getOrDefault(String.valueOf(v), String.valueOf(v));
    }

    private static BigDecimal number(Object v) {
        if (v instanceof BigDecimal b) {
            return b;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return v == null ? null : new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static JsonNode toJsonNode(Object v) {
        return JsonUtils.mapper().valueToTree(v);
    }

    // ------------------------------------------------------------------ 发送请求

    @Transactional
    public AssessmentOverviewDto.QuestionnaireRequestDto sendRequest(
            String tenantId, String orgId, String peopleId, String staffId, List<String> fields) {
        return sendRequest(tenantId, orgId, peopleId, staffId, fields, null);
    }

    @Transactional
    public AssessmentOverviewDto.QuestionnaireRequestDto sendRequest(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            List<String> fields,
            String channel) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        View view = buildView(tenantId, peopleId, PORTAL_C);
        List<String> missing = view.sections().stream()
                .flatMap(s -> s.questions().stream())
                .filter(Question::missing)
                .map(Question::field)
                .toList();
        List<String> picked = fields == null || fields.isEmpty()
                ? missing
                : InputFieldMapping.convertFieldList(fields).stream()
                        .filter(AssessmentFields::inQuestionnaire)
                        .distinct()
                        .toList();
        if (picked.isEmpty()) {
            throw new BusinessException(400, "患者可填写的信息都已齐全，无需发送问卷");
        }
        storeMapper.cancelPendingRequests(tenantId, peopleId);
        LocalDateTime now = LocalDateTime.now();
        AssessmentQuestionnaireRequest req = new AssessmentQuestionnaireRequest();
        req.setId(SnowflakeId.nextBizId());
        req.setTenantId(tenantId);
        req.setPeopleId(peopleId);
        req.setOrgId(orgId);
        req.setStaffId(staffId);
        req.setFieldsJson(JsonUtils.toJson(picked));
        req.setStatus(AssessmentQuestionnaireRequest.PENDING);
        req.setExpiresAt(now.plusDays(REQUEST_VALID_DAYS));
        req.setGmtCreated(now);
        storeMapper.insertRequest(req);
        // COPY/LINK/MANUAL：只生成专属链接，不静默推送
        if (!isManualCopyChannel(channel)) {
            publishRequest(req, false, channel);
        }
        AssessmentOverviewDto.QuestionnaireRequestDto dto = AssessmentOrchestrator.toRequestDto(req);
        String url = buildQuestionnaireUrl();
        dto.setUrl(url);
        dto.setCopyText(buildQuestionnaireCopyText(tenantId, peopleId, staffId, url));
        return dto;
    }

    private static boolean isManualCopyChannel(String channel) {
        if (!StringUtils.hasText(channel)) {
            return false;
        }
        String ch = channel.trim().toUpperCase(Locale.ROOT);
        return "COPY".equals(ch) || "LINK".equals(ch) || "MANUAL".equals(ch);
    }

    private String buildQuestionnaireUrl() {
        String base = userBaseUrl == null ? "" : userBaseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + C_LINK_PATH;
    }

    private String buildQuestionnaireCopyText(String tenantId, String peopleId, String staffId, String url) {
        PeopleProfile people = archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        String address = IntakeAccessLinkService.politeAddress(
                people.getDisplayName(), people.getGender(), people.getBirthday());
        String staffName = "";
        if (StringUtils.hasText(staffId)) {
            StaffProfile staff = staffProfileMapper.findById(staffId);
            if (staff != null && StringUtils.hasText(staff.getDisplayName())) {
                staffName = staff.getDisplayName().trim();
            }
        }
        StringBuilder sb = new StringBuilder();
        String who = StringUtils.hasText(address) ? address.trim() : "朋友";
        sb.append(who).append("您好，");
        if (StringUtils.hasText(staffName)) {
            sb.append("我是您的专属健管师").append(staffName).append("。\n");
        } else {
            sb.append("我是您的专属健管师。\n");
        }
        sb.append("为了更准确地评估您的健康风险，劳烦您花几分钟填写《极简健康评估》问卷（")
                .append(REQUEST_VALID_DAYS)
                .append("天内有效，信息严格保密）。\n");
        sb.append("👉 点击填写：").append(url).append('\n');
        sb.append("填写完成后，我会尽快为您更新评估结论。谢谢！");
        return sb.toString();
    }

    public AssessmentOverviewDto.QuestionnaireRequestDto pendingForPatient(String tenantId, String peopleId) {
        AssessmentQuestionnaireRequest req = storeMapper.findPendingRequest(tenantId, peopleId);
        if (req == null || (req.getExpiresAt() != null && req.getExpiresAt().isBefore(LocalDateTime.now()))) {
            return null;
        }
        return AssessmentOrchestrator.toRequestDto(req);
    }

    public List<ActivityItem> listActivity(String tenantId, String orgId, String peopleId, int limit) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        int n = Math.min(Math.max(limit, 1), 50);
        List<AssessmentQuestionnaireRequest> requests = storeMapper.listRequests(tenantId, peopleId, n);
        List<QuestionnaireResponse> submissions = responseMapper.listSubmitted(
                tenantId, peopleId, QuestionnaireResponseService.PURPOSE_ASSESSMENT, n);
        Set<String> coveredRequestIds = submissions.stream()
                .map(QuestionnaireResponse::getRequestId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(HashSet::new));

        List<ActivityItem> items = new ArrayList<>();
        for (QuestionnaireResponse sub : submissions) {
            int answerCount = countAnswers(sub.getAnswersJson());
            boolean patient = "PEOPLE".equalsIgnoreCase(sub.getFilledByType());
            String portal = patient ? PORTAL_C : PORTAL_B;
            String who = patient ? "患者填写" : "健管师填写";
            items.add(new ActivityItem(
                    "SUBMISSION",
                    sub.getId(),
                    "SUBMITTED",
                    portal,
                    who,
                    answerCount > 0 ? "补充了 " + answerCount + " 项信息" : "已提交",
                    answerCount,
                    sub.getRequestId(),
                    sub.getSubmittedAt(),
                    null,
                    sub.getSubmittedAt()));
        }
        for (AssessmentQuestionnaireRequest req : requests) {
            if (coveredRequestIds.contains(req.getId())
                    && AssessmentQuestionnaireRequest.SUBMITTED.equals(req.getStatus())) {
                continue;
            }
            String status = req.getStatus();
            String title =
                    switch (status == null ? "" : status) {
                        case AssessmentQuestionnaireRequest.PENDING -> "待患者填写";
                        case AssessmentQuestionnaireRequest.EXPIRED -> "问卷已过期";
                        case AssessmentQuestionnaireRequest.SUBMITTED -> "患者已填写";
                        default -> "问卷请求";
                    };
            String summary =
                    switch (status == null ? "" : status) {
                        case AssessmentQuestionnaireRequest.PENDING ->
                                req.getExpiresAt() == null
                                        ? "已发送给患者"
                                        : "有效至 " + req.getExpiresAt().toLocalDate();
                        case AssessmentQuestionnaireRequest.EXPIRED -> "患者未在有效期内完成";
                        case AssessmentQuestionnaireRequest.SUBMITTED -> "已关联提交记录";
                        default -> "";
                    };
            items.add(new ActivityItem(
                    "REQUEST",
                    req.getId(),
                    status,
                    PORTAL_C,
                    title,
                    summary,
                    0,
                    req.getId(),
                    req.getGmtCreated(),
                    req.getExpiresAt(),
                    req.getSubmittedAt()));
        }
        items.sort((a, b) -> {
            LocalDateTime ta = a.submittedAt() != null ? a.submittedAt() : a.createdAt();
            LocalDateTime tb = b.submittedAt() != null ? b.submittedAt() : b.createdAt();
            if (ta == null && tb == null) {
                return 0;
            }
            if (ta == null) {
                return 1;
            }
            if (tb == null) {
                return -1;
            }
            return tb.compareTo(ta);
        });
        return items.size() > n ? items.subList(0, n) : items;
    }

    private static int countAnswers(String answersJson) {
        if (!StringUtils.hasText(answersJson)) {
            return 0;
        }
        try {
            JsonNode node = JsonUtils.readTree(answersJson);
            if (node != null && node.isObject()) {
                return node.size();
            }
        } catch (Exception ignored) {
            // ignore
        }
        return 0;
    }

    void publishRequest(AssessmentQuestionnaireRequest req, boolean reminder) {
        publishRequest(req, reminder, null);
    }

    void publishRequest(AssessmentQuestionnaireRequest req, boolean reminder, String channel) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", req.getId());
        payload.put("expiresAt", req.getExpiresAt() == null ? null : req.getExpiresAt().toString());
        String ch = StringUtils.hasText(channel) ? channel.trim().toUpperCase() : "WECHAT";
        payload.put("channel", ch);
        notifyFacade.publish(NotifyPublishCommand.builder()
                .tenantId(req.getTenantId())
                .eventType(NotifyEventType.ASSESSMENT_QUESTIONNAIRE)
                .dedupeKey(req.getId() + (reminder ? ":remind" : ":send") + ":" + ch)
                .audience(NotifyAudience.C_ACCOUNT)
                .peopleId(req.getPeopleId())
                .orgId(req.getOrgId())
                .title(reminder ? "健康信息还没完善哦" : "请完善你的健康信息")
                .body(reminder
                        ? "你的健康管理师邀请你完善健康信息，花 2 分钟填写，帮助制定更合适的管理方案"
                        : "你的健康管理师需要了解一些健康情况，花 2 分钟填写即可")
                .linkPath(C_LINK_PATH)
                .payload(payload)
                .build());
    }

    // ------------------------------------------------------------------ 兼容旧单测的家族史 / 现病史工具

    /** 问卷管理的一级亲属病种；其它编码（如 FH_CANCER）保留不动。 */
    private static final Set<String> QUESTIONNAIRE_FH_CODES = Set.of(
            "FH_DIABETES",
            "FH_HYPERTENSION",
            "FH_CHD",
            "FH_STROKE",
            "FH_EARLY_MI",
            AssessmentContext.NONE,
            AssessmentContext.UNKNOWN);

    /** @deprecated 写回已走 {@link PeopleFieldService}；保留给单测。 */
    public static void applyFamilyHistory(ObjectNode root, List<String> picked) {
        Set<String> wanted = new LinkedHashSet<>(picked);
        wanted.remove(AssessmentContext.NONE);
        ArrayNode items =
                root.get("familyHistoryItems") instanceof ArrayNode a ? a : root.putArray("familyHistoryItems");
        ArrayNode kept = JsonUtils.mapper().createArrayNode();
        Set<String> present = new HashSet<>();
        ObjectNode unspecified = null;
        for (JsonNode item : items) {
            if (!(item instanceof ObjectNode o) || !"1".equals(o.path("kinshipLevel").asText("1"))) {
                kept.add(item);
                continue;
            }
            ArrayNode diseases = JsonUtils.mapper().createArrayNode();
            for (JsonNode d : o.path("diseases")) {
                String code = d.asText("").trim().toUpperCase(Locale.ROOT);
                if (wanted.contains(code)) {
                    diseases.add(code);
                    present.add(code);
                } else if (!QUESTIONNAIRE_FH_CODES.contains(code)) {
                    diseases.add(d.asText());
                }
            }
            if (diseases.isEmpty()) {
                continue;
            }
            o.set("diseases", diseases);
            if ("UNSPECIFIED".equals(o.path("member").asText())) {
                unspecified = o;
            }
            kept.add(o);
        }
        List<String> toAdd = wanted.stream().filter(c -> !present.contains(c)).toList();
        if (!toAdd.isEmpty()) {
            if (unspecified == null) {
                unspecified = JsonUtils.mapper().createObjectNode();
                unspecified.put("kinshipLevel", "1");
                unspecified.put("member", "UNSPECIFIED");
                unspecified.putArray("diseases");
                unspecified.put("note", "评估问卷填写");
                kept.add(unspecified);
            }
            ArrayNode diseases = (ArrayNode) unspecified.get("diseases");
            toAdd.forEach(diseases::add);
        }
        root.set("familyHistoryItems", kept);
        root.put("familyHistoryStatus", kept.isEmpty() ? "none" : "has");
    }

    /** @deprecated 写回已走 {@link PeopleFieldService}；保留给单测。 */
    public static void applyPresentIllness(ObjectNode root, List<String> picked) {
        Set<String> managed = Set.of(
                "CHD",
                "STROKE",
                "HEART_FAILURE",
                "ATRIAL_FIBRILLATION",
                "CKD",
                "HYPERLIPIDEMIA",
                "NAFLD",
                "OSTEOARTHRITIS",
                AssessmentFields.ILLNESS_PREDIABETES,
                AssessmentFields.ILLNESS_PCOS,
                AssessmentFields.ILLNESS_OSA);
        ArrayNode next = JsonUtils.mapper().createArrayNode();
        Set<String> seen = new HashSet<>();
        JsonNode current = root.get("presentIllness");
        if (current != null && current.isArray()) {
            for (JsonNode n : current) {
                String c = n.asText("").trim().toUpperCase(Locale.ROOT);
                if (!c.isEmpty() && !managed.contains(c) && seen.add(c)) {
                    next.add(c);
                }
            }
        }
        for (String c : picked) {
            if (!AssessmentContext.NONE.equals(c) && seen.add(c)) {
                next.add(c);
            }
        }
        root.set("presentIllness", next);
    }
}
