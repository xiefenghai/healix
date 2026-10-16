package com.healix.core.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.JsonUtils;
import com.healix.common.util.SnowflakeId;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.core.archive.dto.DiseaseArchiveViewDto;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.archive.service.BasicArchiveService;
import com.healix.core.archive.service.DiseaseArchiveService;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentTriggerSource;
import com.healix.core.assessment.catalog.InputField;
import com.healix.core.assessment.catalog.InputField.Audience;
import com.healix.core.assessment.catalog.InputField.Question;
import com.healix.core.assessment.catalog.InputField.ValueType;
import com.healix.core.assessment.catalog.InputField.WriteBack;
import com.healix.core.assessment.domain.AssessmentQuestionnaireRequest;
import com.healix.core.assessment.domain.AssessmentQuestionnaireSubmission;
import com.healix.core.assessment.domain.PeopleAssessmentFact;
import com.healix.core.assessment.dto.AssessmentOverviewDto;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.ActivityItem;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Conflict;
import com.healix.core.assessment.dto.AssessmentQuestionnaireDto.FillHint;
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
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.service.NotifyFacade;
import com.healix.core.observation.service.MetricService;
import com.healix.core.observation.service.MetricService.MetricCommand;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.vitals.service.VitalService;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 评估问卷：按「适用引擎所需字段」动态出题；提交后写回档案 / 指标 / 问卷事实并立即重算。
 *
 * <ul>
 *   <li>与档案已有值不一致的字段需二次确认，确认后以问卷为准（档案留修订记录）
 *   <li>患者覆盖档案值时给健管师开「患者自报更新」任务
 *   <li>健管师可向患者发送问卷请求：7 天有效，48 小时未填提醒一次，过期开任务
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentQuestionnaireService {

    public static final String PORTAL_B = "B";
    public static final String PORTAL_C = "C";
    public static final String C_LINK_PATH = "/assessment-questionnaire";
    private static final int REQUEST_VALID_DAYS = 7;
    private static final String HTN = AssessmentDisease.HYPERTENSION.code();

    private final AssessmentEngineRegistry registry;
    private final AssessmentContextBuilder contextBuilder;
    private final AssessmentRecomputeService recomputeService;
    private final AssessmentOrchestrator orchestrator;
    private final AssessmentStoreMapper storeMapper;
    private final ArchiveAccessService archiveAccessService;
    private final BasicArchiveService basicArchiveService;
    private final DiseaseArchiveService diseaseArchiveService;
    private final MetricService metricService;
    private final VitalService vitalService;
    private final NotifyFacade notifyFacade;
    private final WorkspaceTaskGenerator taskGenerator;
    private final AuditService auditService;

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
        Map<InputField, List<String>> usedBy = new EnumMap<>(InputField.class);
        Set<InputField> required = new HashSet<>();
        for (AssessmentEngine engine : registry.all()) {
            if (!engine.applicable(ctx)) {
                continue;
            }
            for (InputField f : engine.inputs()) {
                usedBy.computeIfAbsent(f, k -> new ArrayList<>()).add(engine.label());
            }
            required.addAll(engine.requiredInputs());
        }

        Map<String, List<com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Question>> bySection =
                new LinkedHashMap<>();
        for (String code : List.of("body", "lifestyle", "history", "professional")) {
            bySection.put(code, new ArrayList<>());
        }
        List<FillHint> hints = new ArrayList<>();
        int missing = 0;
        for (InputField f : InputField.values()) {
            List<String> engines = usedBy.get(f);
            if (engines == null) {
                continue;
            }
            if (!f.inQuestionnaire()) {
                if (f.fillHint() != null && !ctx.has(f)) {
                    hints.add(new FillHint(f.name(), f.label(), f.fillHint(), engines));
                }
                continue;
            }
            if (!visible(f, portal, ctx)) {
                continue;
            }
            boolean has = ctx.has(f);
            if (!has) {
                missing++;
            }
            Object current = ctx.currentValue(f);
            Question q = f.question();
            bySection.get(sectionOf(f)).add(new com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Question(
                    f.name(),
                    f.label(),
                    f.type().name(),
                    q.text(),
                    q.unit(),
                    q.min(),
                    q.max(),
                    q.options(),
                    f.audience().name(),
                    required.contains(f),
                    !has,
                    current,
                    answerText(f, current),
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
        AssessmentQuestionnaireSubmission last = storeMapper.findLatestSubmission(tenantId, peopleId);
        return new View(
                sections,
                hints,
                missing,
                total,
                req == null ? null : AssessmentOrchestrator.toRequestDto(req),
                last == null ? null : last.getSubmittedAt());
    }

    private static boolean visible(InputField f, String portal, AssessmentContext ctx) {
        if (f.audience() == Audience.STAFF && !PORTAL_B.equals(portal)) {
            return false;
        }
        if (f == InputField.DM_COMPLICATIONS && !ctx.hasKnownDiabetes()) {
            return false;
        }
        return !f.question().femaleOnly() || !ctx.isMale();
    }

    private static String sectionOf(InputField f) {
        if (f.audience() == Audience.STAFF) {
            return "professional";
        }
        return switch (f) {
            case HEIGHT, WEIGHT, WAIST, BODY_FAT -> "body";
            case SMOKING, PASSIVE_SMOKING, DRINKING, EXERCISE, HIGH_SALT, CHRONIC_STRESS -> "lifestyle";
            default -> "history";
        };
    }

    private static String sectionTitle(String code) {
        return switch (code) {
            case "body" -> "身体测量";
            case "lifestyle" -> "生活方式";
            case "history" -> "疾病与用药史";
            default -> "专业检查（健管师填写）";
        };
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
        Map<InputField, Object> answers = normalizeAnswers(cmd.answers(), portal, ctx);
        if (answers.isEmpty()) {
            throw new BusinessException(400, "请至少回答一题");
        }

        Set<String> confirmed = cmd.confirmOverrides() == null ? Set.of() : Set.copyOf(cmd.confirmOverrides());
        List<Conflict> conflicts = detectConflicts(ctx, answers);
        List<Conflict> unconfirmed = conflicts.stream().filter(c -> !confirmed.contains(c.field())).toList();
        if (!unconfirmed.isEmpty()) {
            return new SubmitResult("CONFLICT", unconfirmed, null, null);
        }

        LocalDateTime now = LocalDateTime.now();
        String submissionId = SnowflakeId.nextBizId();
        ArchiveOperatorContext operator = PORTAL_B.equals(portal)
                ? ArchiveOperatorContext.staff(staffId, orgId)
                : ArchiveOperatorContext.people(peopleId);
        String factSource = PORTAL_B.equals(portal) ? "QUESTIONNAIRE_STAFF" : "QUESTIONNAIRE_PATIENT";
        String recordedBy = PORTAL_B.equals(portal) ? staffId : peopleId;

        writeBasicArchive(tenantId, peopleId, answers, operator);
        Set<InputField> asFacts = new LinkedHashSet<>();
        writeDiseaseArchive(tenantId, peopleId, answers, operator, asFacts);
        writeMetrics(tenantId, orgId, peopleId, staffId, portal, ctx, answers, now);
        for (Map.Entry<InputField, Object> e : answers.entrySet()) {
            InputField f = e.getKey();
            if (f.writeBack() == WriteBack.FACT || asFacts.contains(f) || isUnknown(e.getValue())) {
                PeopleAssessmentFact fact = new PeopleAssessmentFact();
                fact.setId(SnowflakeId.nextBizId());
                fact.setTenantId(tenantId);
                fact.setPeopleId(peopleId);
                fact.setFieldCode(f.name());
                fact.setValueJson(JsonUtils.toJson(Map.of("value", e.getValue())));
                fact.setSource(factSource);
                fact.setSubmissionId(submissionId);
                fact.setRecordedAt(now);
                fact.setRecordedBy(recordedBy);
                storeMapper.upsertFact(fact);
            }
        }

        AssessmentQuestionnaireRequest pending = storeMapper.findPendingRequest(tenantId, peopleId);
        String requestId = pending != null && (PORTAL_C.equals(portal) || Objects.equals(pending.getId(), cmd.requestId()))
                ? pending.getId()
                : null;

        AssessmentQuestionnaireSubmission sub = new AssessmentQuestionnaireSubmission();
        sub.setId(submissionId);
        sub.setTenantId(tenantId);
        sub.setPeopleId(peopleId);
        sub.setPortal(portal);
        sub.setSubmittedBy(recordedBy);
        sub.setRequestId(requestId);
        Map<String, Object> answerJson = new LinkedHashMap<>();
        answers.forEach((k, v) -> answerJson.put(k.name(), v));
        sub.setAnswersJson(JsonUtils.toJson(answerJson));
        sub.setOverridesJson(conflicts.isEmpty() ? null : JsonUtils.toJson(conflicts));
        sub.setSubmittedAt(now);
        storeMapper.insertSubmission(sub);
        if (requestId != null) {
            storeMapper.markRequestSubmitted(requestId, submissionId, now);
        }

        AssessmentRecomputeService.Outcome outcome =
                recomputeService.recompute(tenantId, peopleId, AssessmentTriggerSource.QUESTIONNAIRE, staffId, false);

        auditService.record(
                PortalEnum.valueOf(portal).code(),
                accountId,
                PORTAL_B.equals(portal) ? "STAFF" : "PATIENT",
                tenantId,
                AuditActionEnum.PATIENT_ASSESSMENT_QUESTIONNAIRE.name(),
                "assessment_questionnaire_submission",
                submissionId,
                peopleId,
                AuditDetails.of(
                        "fields", answers.keySet().stream().map(Enum::name).toList(),
                        "overrides", conflicts.stream().map(Conflict::field).toList(),
                        "requestId", requestId));

        if (PORTAL_C.equals(portal) && !conflicts.isEmpty()) {
            notifySelfReport(tenantId, peopleId, submissionId, conflicts);
        }
        AssessmentOverviewDto overview = PORTAL_B.equals(portal)
                ? orchestrator.buildOverview(tenantId, peopleId, outcome.context())
                : null;
        return new SubmitResult("OK", List.of(), submissionId, overview);
    }

    private void notifySelfReport(String tenantId, String peopleId, String submissionId, List<Conflict> conflicts) {
        try {
            String summary = conflicts.stream()
                    .map(c -> c.label() + "：" + c.currentText() + " → " + c.newText())
                    .collect(Collectors.joining("；"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("submissionId", submissionId);
            payload.put("changes", conflicts);
            taskGenerator.onArchiveSelfReport(tenantId, peopleId, submissionId, summary, payload);
        } catch (RuntimeException e) {
            log.warn("archive self report task failed people={}: {}", peopleId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 校验与冲突

    private Map<InputField, Object> normalizeAnswers(Map<String, Object> raw, String portal, AssessmentContext ctx) {
        Map<InputField, Object> out = new EnumMap<>(InputField.class);
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            InputField f = InputField.parse(e.getKey());
            if (f == null || !f.inQuestionnaire()) {
                throw new BusinessException(400, "未知题目: " + e.getKey());
            }
            if (!visible(f, portal, ctx)) {
                throw new BusinessException(400, f.label() + " 不可在此填写");
            }
            Object v = normalize(f, e.getValue());
            if (v != null) {
                out.put(f, v);
            }
        }
        return out;
    }

    private static Object normalize(InputField f, Object raw) {
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            return null;
        }
        Question q = f.question();
        Set<String> codes = q.options().stream().map(InputField.Option::code).collect(Collectors.toSet());
        switch (f.type()) {
            case NUMBER -> {
                BigDecimal n;
                try {
                    n = new BigDecimal(String.valueOf(raw).trim());
                } catch (NumberFormatException ex) {
                    throw new BusinessException(400, f.label() + " 请填写数字");
                }
                if ((q.min() != null && n.compareTo(q.min()) < 0) || (q.max() != null && n.compareTo(q.max()) > 0)) {
                    throw new BusinessException(
                            400, f.label() + " 应在 " + q.min().toPlainString() + "–" + q.max().toPlainString() + " " + q.unit() + " 之间");
                }
                return n.stripTrailingZeros().scale() < 0 ? n.setScale(0) : n.stripTrailingZeros();
            }
            case MULTI -> {
                if (!(raw instanceof Collection<?> list)) {
                    throw new BusinessException(400, f.label() + " 格式不正确");
                }
                List<String> picked = new ArrayList<>();
                for (Object o : list) {
                    String c = String.valueOf(o).trim().toUpperCase(Locale.ROOT);
                    if (!codes.contains(c)) {
                        throw new BusinessException(400, f.label() + " 选项不正确: " + o);
                    }
                    if (!picked.contains(c)) {
                        picked.add(c);
                    }
                }
                if (picked.isEmpty()) {
                    return null;
                }
                boolean exclusive = picked.contains(Question.NONE) || picked.contains(Question.UNKNOWN);
                if (exclusive && picked.size() > 1) {
                    throw new BusinessException(400, f.label() + "：「都没有 / 不清楚」不能与其他选项同时选择");
                }
                return picked;
            }
            default -> {
                String c = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
                if (!codes.contains(c)) {
                    throw new BusinessException(400, f.label() + " 选项不正确: " + raw);
                }
                return c;
            }
        }
    }

    private static List<Conflict> detectConflicts(AssessmentContext ctx, Map<InputField, Object> answers) {
        List<Conflict> out = new ArrayList<>();
        for (Map.Entry<InputField, Object> e : answers.entrySet()) {
            InputField f = e.getKey();
            Object next = e.getValue();
            if (isUnknown(next)) {
                continue;
            }
            Object current = switch (f.writeBack()) {
                case ARCHIVE -> f == InputField.FAMILY_HISTORY && !ctx.isFamilyHistoryCollected() ? null : ctx.currentValue(f);
                case DISEASE_ARCHIVE -> f == InputField.HTN_MAX_SBP ? ctx.getArchiveHighestSbp()
                        : f == InputField.HTN_MAX_DBP ? ctx.getArchiveHighestDbp() : null;
                default -> null;
            };
            if (current != null && !sameValue(current, next)) {
                out.add(new Conflict(f.name(), f.label(), current, answerText(f, current), next, answerText(f, next)));
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
            return c.size() == 1 && Question.UNKNOWN.equals(String.valueOf(c.iterator().next()));
        }
        return Question.UNKNOWN.equals(String.valueOf(v));
    }

    static String answerText(InputField f, Object v) {
        if (v == null) {
            return null;
        }
        if (f.type() == ValueType.NUMBER) {
            BigDecimal n = number(v);
            String unit = f.question() == null || f.question().unit() == null ? "" : " " + f.question().unit();
            return (n == null ? String.valueOf(v) : n.stripTrailingZeros().toPlainString()) + unit;
        }
        Map<String, String> labels = f.question() == null
                ? Map.of()
                : f.question().options().stream()
                        .collect(Collectors.toMap(InputField.Option::code, InputField.Option::label, (a, b) -> a));
        if (v instanceof Collection<?> c) {
            return c.stream().map(o -> labels.getOrDefault(String.valueOf(o), String.valueOf(o)))
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
        return null;
    }

    // ------------------------------------------------------------------ 写回

    private void writeBasicArchive(
            String tenantId, String peopleId, Map<InputField, Object> answers, ArchiveOperatorContext operator) {
        boolean touches = answers.keySet().stream().anyMatch(f -> f.writeBack() == WriteBack.ARCHIVE);
        if (!touches) {
            return;
        }
        ArchiveViewDto view = basicArchiveService.get(tenantId, peopleId);
        JsonNode parsed = JsonUtils.readTree(JsonUtils.toJson(view.getContentJson()));
        ObjectNode root = parsed instanceof ObjectNode o ? o.deepCopy() : JsonUtils.emptyObject();
        String before = root.toString();
        for (Map.Entry<InputField, Object> e : answers.entrySet()) {
            Object v = e.getValue();
            if (isUnknown(v)) {
                continue;
            }
            switch (e.getKey()) {
                case SMOKING -> obj(obj(root, "lifestyle"), "smoking").put("status", String.valueOf(v));
                case DRINKING -> {
                    ObjectNode drinking = obj(obj(root, "lifestyle"), "drinking");
                    if (Question.NONE.equals(v) || "NEVER".equals(v)) {
                        drinking.put("status", "NEVER");
                        drinking.remove("frequency");
                    } else {
                        String status = drinking.path("status").asText("").toUpperCase(Locale.ROOT);
                        if (!"CURRENT".equals(status) && !"OCCASIONAL".equals(status)) {
                            drinking.put("status", "CURRENT");
                        }
                        drinking.put("frequency", String.valueOf(v));
                    }
                }
                case EXERCISE -> obj(root, "exercise").put("frequency", String.valueOf(v));
                case FAMILY_HISTORY -> applyFamilyHistory(root, toCodes(v));
                case PRESENT_ILLNESS -> applyPresentIllness(root, toCodes(v));
                default -> {
                    /* 非基础档案字段 */
                }
            }
        }
        if (!before.equals(root.toString())) {
            basicArchiveService.save(tenantId, peopleId, view.getVersion() == null ? 0 : view.getVersion(), root.toString(), operator);
        }
    }

    /** 只改一级亲属：问卷没选的病种从一级亲属条目移除，新增病种挂到「一级亲属·未细分」。 */
    static void applyFamilyHistory(ObjectNode root, List<String> picked) {
        Set<String> wanted = new LinkedHashSet<>(picked);
        wanted.remove(Question.NONE);
        ArrayNode items = root.get("familyHistoryItems") instanceof ArrayNode a ? a : root.putArray("familyHistoryItems");
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
                } else if (!isQuestionnaireFhCode(code)) {
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

    private static boolean isQuestionnaireFhCode(String code) {
        return InputField.FAMILY_HISTORY.question().options().stream().anyMatch(o -> o.code().equals(code));
    }

    /** 只替换问卷选项范围内的病种，其他现有疾病保持不变。 */
    static void applyPresentIllness(ObjectNode root, List<String> picked) {
        Set<String> optionCodes = InputField.PRESENT_ILLNESS.question().options().stream()
                .map(InputField.Option::code)
                .filter(c -> !Question.NONE.equals(c))
                .collect(Collectors.toSet());
        ArrayNode next = JsonUtils.mapper().createArrayNode();
        Set<String> seen = new HashSet<>();
        JsonNode current = root.get("presentIllness");
        if (current != null && current.isArray()) {
            for (JsonNode n : current) {
                String c = n.asText("").trim().toUpperCase(Locale.ROOT);
                if (!c.isEmpty() && !optionCodes.contains(c) && seen.add(c)) {
                    next.add(c);
                }
            }
        }
        for (String c : picked) {
            if (!Question.NONE.equals(c) && seen.add(c)) {
                next.add(c);
            }
        }
        root.set("presentIllness", next);
    }

    private void writeDiseaseArchive(
            String tenantId,
            String peopleId,
            Map<InputField, Object> answers,
            ArchiveOperatorContext operator,
            Set<InputField> asFacts) {
        BigDecimal sbp = answers.get(InputField.HTN_MAX_SBP) instanceof BigDecimal b ? b : null;
        BigDecimal dbp = answers.get(InputField.HTN_MAX_DBP) instanceof BigDecimal b ? b : null;
        if (sbp == null && dbp == null) {
            return;
        }
        DiseaseArchiveViewDto view = diseaseArchiveService.get(tenantId, peopleId, HTN);
        if (view.getVersion() == null || view.getVersion() == 0) {
            if (sbp != null) {
                asFacts.add(InputField.HTN_MAX_SBP);
            }
            if (dbp != null) {
                asFacts.add(InputField.HTN_MAX_DBP);
            }
            return;
        }
        JsonNode parsed = JsonUtils.readTree(JsonUtils.toJson(view.getContentJson()));
        ObjectNode root = parsed instanceof ObjectNode o ? o.deepCopy() : JsonUtils.emptyObject();
        if (sbp != null) {
            root.put("highestSystolic", sbp);
        }
        if (dbp != null) {
            root.put("highestDiastolic", dbp);
        }
        diseaseArchiveService.save(tenantId, peopleId, HTN, view.getVersion(), root.toString(), operator);
    }

    private void writeMetrics(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String portal,
            AssessmentContext ctx,
            Map<InputField, Object> answers,
            LocalDateTime now) {
        for (InputField f : List.of(InputField.HEIGHT, InputField.WEIGHT, InputField.WAIST)) {
            if (!(answers.get(f) instanceof BigDecimal value)) {
                continue;
            }
            BigDecimal current = switch (f) {
                case HEIGHT -> ctx.getHeightCm();
                case WEIGHT -> ctx.getWeightKg();
                default -> ctx.getWaistCm();
            };
            if (current != null && current.compareTo(value) == 0) {
                continue;
            }
            String unit = f.question().unit();
            if (PORTAL_B.equals(portal)) {
                metricService.create(
                        tenantId, orgId, peopleId, staffId,
                        new MetricCommand(f.name(), value, unit, now, null, null, "评估问卷", null));
            } else {
                vitalService.record(peopleId, f.name(), value, unit, now, "SELF");
            }
        }
    }

    private static ObjectNode obj(ObjectNode parent, String key) {
        JsonNode n = parent.get(key);
        if (n instanceof ObjectNode o) {
            return o;
        }
        return parent.putObject(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toCodes(Object v) {
        return v instanceof List<?> l ? (List<String>) l : List.of(String.valueOf(v));
    }

    // ------------------------------------------------------------------ 发送请求

    /** 健管师向患者发送问卷：同一患者只保留一份待填请求。 */
    @Transactional
    public AssessmentOverviewDto.QuestionnaireRequestDto sendRequest(
            String tenantId, String orgId, String peopleId, String staffId, List<String> fields) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        View view = buildView(tenantId, peopleId, PORTAL_C);
        List<String> missing = view.sections().stream()
                .flatMap(s -> s.questions().stream())
                .filter(com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Question::missing)
                .map(com.healix.core.assessment.dto.AssessmentQuestionnaireDto.Question::field)
                .toList();
        List<String> picked = fields == null || fields.isEmpty()
                ? missing
                : fields.stream().filter(f -> InputField.parse(f) != null).distinct().toList();
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
        publishRequest(req, false);
        return AssessmentOrchestrator.toRequestDto(req);
    }

    public AssessmentOverviewDto.QuestionnaireRequestDto pendingForPatient(String tenantId, String peopleId) {
        AssessmentQuestionnaireRequest req = storeMapper.findPendingRequest(tenantId, peopleId);
        if (req == null || (req.getExpiresAt() != null && req.getExpiresAt().isBefore(LocalDateTime.now()))) {
            return null;
        }
        return AssessmentOrchestrator.toRequestDto(req);
    }

    /** 评估问卷活动列表：发送记录 + 提交记录，按时间倒序。 */
    public List<ActivityItem> listActivity(String tenantId, String orgId, String peopleId, int limit) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        int n = Math.min(Math.max(limit, 1), 50);
        List<AssessmentQuestionnaireRequest> requests = storeMapper.listRequests(tenantId, peopleId, n);
        List<AssessmentQuestionnaireSubmission> submissions = storeMapper.listSubmissions(tenantId, peopleId, n);
        Set<String> coveredRequestIds = submissions.stream()
                .map(AssessmentQuestionnaireSubmission::getRequestId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(HashSet::new));

        List<ActivityItem> items = new ArrayList<>();
        for (AssessmentQuestionnaireSubmission sub : submissions) {
            int answerCount = countAnswers(sub.getAnswersJson());
            String portal = StringUtils.hasText(sub.getPortal()) ? sub.getPortal() : PORTAL_B;
            String who = PORTAL_C.equals(portal) ? "患者填写" : "健管师填写";
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
            // 已有对应提交时，列表里用提交记录展示，避免重复
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
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return tb.compareTo(ta);
        });
        if (items.size() > n) {
            return items.subList(0, n);
        }
        return items;
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
            // ignore malformed historical rows
        }
        return 0;
    }

    void publishRequest(AssessmentQuestionnaireRequest req, boolean reminder) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", req.getId());
        payload.put("expiresAt", req.getExpiresAt() == null ? null : req.getExpiresAt().toString());
        notifyFacade.publish(NotifyPublishCommand.builder()
                .tenantId(req.getTenantId())
                .eventType(NotifyEventType.ASSESSMENT_QUESTIONNAIRE)
                .dedupeKey(req.getId() + (reminder ? ":remind" : ":send"))
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
}
