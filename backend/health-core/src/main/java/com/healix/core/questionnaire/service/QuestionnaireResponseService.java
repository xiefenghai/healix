package com.healix.core.questionnaire.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.consent.domain.PeopleConsent;
import com.healix.core.consent.mapper.PeopleConsentMapper;
import com.healix.core.field.ArchiveFields;
import com.healix.core.field.FieldCatalog;
import com.healix.core.field.FieldDef;
import com.healix.core.field.FieldStore;
import com.healix.core.field.FieldValue;
import com.healix.core.field.FieldWriteSource;
import com.healix.core.field.PeopleFieldService;
import com.healix.core.goal.domain.PeopleHealthGoal;
import com.healix.core.goal.mapper.PeopleHealthGoalMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.program.domain.PeopleProgramEnrollment;
import com.healix.core.program.service.ProgramEnrollmentService;
import com.healix.core.questionnaire.HighlightRule;
import com.healix.core.questionnaire.ModuleDef;
import com.healix.core.questionnaire.ModuleItem;
import com.healix.core.questionnaire.QuestionnaireCatalog;
import com.healix.core.questionnaire.QuestionnaireDef;
import com.healix.core.questionnaire.ShowIf;
import com.healix.core.questionnaire.domain.QuestionnaireResponse;
import com.healix.core.questionnaire.dto.IntakeStatusView;
import com.healix.core.questionnaire.dto.QuestionnaireResponseView;
import com.healix.core.questionnaire.mapper.QuestionnaireResponseMapper;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.VitalSourceEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.vitals.service.VitalService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 问卷作答：打开预填、草稿保存、提交写回。草稿不写元数据；提交走 {@link PeopleFieldService}。
 */
@Service
@RequiredArgsConstructor
public class QuestionnaireResponseService {

    public static final String PURPOSE_INTAKE = "INTAKE";
    public static final String PURPOSE_SUPPLEMENT = "SUPPLEMENT";
    public static final String PURPOSE_ASSESSMENT = "ASSESSMENT";
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String Q_WL_INTAKE = "WL_INTAKE";

    private final QuestionnaireCatalog catalog;
    private final FieldCatalog fieldCatalog;
    private final QuestionnaireResponseMapper responseMapper;
    private final PeopleFieldService peopleFieldService;
    private final PeopleProfileMapper peopleProfileMapper;
    private final VitalRecordMapper vitalRecordMapper;
    private final VitalService vitalService;
    private final ProgramEnrollmentService enrollmentService;
    private final PeopleHealthGoalMapper goalMapper;
    private final PeopleConsentMapper consentMapper;

    public QuestionnaireResponseView get(String id) {
        QuestionnaireResponse row = require(id);
        return toView(row, true);
    }

    /**
     * 客户页首诊状态：已提交后相对最新问卷版本，统计元数据仍为空的题（待补）。
     */
    public IntakeStatusView intakeStatus(String tenantId, String peopleId) {
        QuestionnaireDef latest = catalog.latest(Q_WL_INTAKE);
        QuestionnaireResponse submitted =
                responseMapper.findLatestSubmitted(tenantId, peopleId, PURPOSE_INTAKE);
        if (submitted == null) {
            // 补填提交也算「已做过首诊」
            submitted = responseMapper.findLatestSubmitted(tenantId, peopleId, PURPOSE_SUPPLEMENT);
        }
        IntakeStatusView view = new IntakeStatusView();
        view.setQuestionnaireCode(latest.code());
        view.setLatestVersion(latest.version());
        view.setHasSubmitted(submitted != null);
        if (submitted != null) {
            view.setSubmittedVersion(submitted.getQuestionnaireVersion());
        }
        if (submitted == null) {
            return view;
        }
        List<IntakeStatusView.PendingField> pending = computePendingFields(tenantId, peopleId, latest);
        view.setPendingFields(pending);
        view.setPendingCount(pending.size());
        return view;
    }

    /**
     * 打开 / 续开首诊草稿。
     *
     * @param programCode {@code WEIGHT_LOSS} / {@code NONE}；空则沿用草稿或默认减重
     */
    @Transactional
    public QuestionnaireResponseView openIntake(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String followupRecordId,
            String programCode,
            String channel) {
        QuestionnaireDef q = catalog.latest(Q_WL_INTAKE);
        QuestionnaireResponse draft =
                responseMapper.findDraft(tenantId, peopleId, PURPOSE_INTAKE, followupRecordId, null);
        if (draft != null) {
            if (programCode != null && !programCode.isBlank()) {
                draft.setProgramCode(programCode);
                applyEnrollment(draft, tenantId, orgId, peopleId, staffId);
            }
            if (channel != null && !channel.isBlank()) {
                draft.setChannel(channel);
            }
            EntityMeta.onUpdate(draft);
            responseMapper.updateDraft(draft);
            return toView(draft, true);
        }

        String prog = programCode == null || programCode.isBlank() ? ProgramEnrollmentService.WEIGHT_LOSS : programCode;
        ObjectNode prefill = buildPrefill(tenantId, peopleId, q);
        QuestionnaireResponse row = new QuestionnaireResponse();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setQuestionnaireCode(q.code());
        row.setQuestionnaireVersion(q.version());
        row.setPurpose(PURPOSE_INTAKE);
        row.setFollowupRecordId(followupRecordId);
        row.setStatus(STATUS_DRAFT);
        row.setChannel(channel);
        row.setProgramCode(prog);
        row.setPrefillJson(JsonUtils.toJson(prefill));
        row.setAnswersJson("{}");
        row.setFilledByType("STAFF");
        row.setFilledById(staffId);
        row.setCompletenessJson(JsonUtils.toJson(completeness(q, JsonUtils.emptyObject(), answersLookup(JsonUtils.emptyObject()), null)));
        applyEnrollment(row, tenantId, orgId, peopleId, staffId);
        EntityMeta.onCreate(row);
        responseMapper.insert(row);
        return toView(row, true);
    }

    /**
     * 打开升版 / 后补作答：只展示元数据仍为空的题（含 LATER）；草稿可续填。
     */
    @Transactional
    public QuestionnaireResponseView openSupplement(
            String tenantId, String orgId, String peopleId, String staffId, String channel) {
        QuestionnaireDef q = catalog.latest(Q_WL_INTAKE);
        List<IntakeStatusView.PendingField> pending = computePendingFields(tenantId, peopleId, q);
        if (pending.isEmpty()) {
            throw new BusinessException("当前无待补题目");
        }
        Set<String> filter = new LinkedHashSet<>();
        for (IntakeStatusView.PendingField p : pending) {
            filter.add(p.getField());
        }

        QuestionnaireResponse draft =
                responseMapper.findDraft(tenantId, peopleId, PURPOSE_SUPPLEMENT, null, null);
        ObjectNode prefill = buildPrefill(tenantId, peopleId, q);
        ObjectNode complete = completeness(q, JsonUtils.emptyObject(), answersLookup(JsonUtils.emptyObject()), filter);
        ArrayNode supplementFields = JsonUtils.mapper().createArrayNode();
        filter.forEach(supplementFields::add);
        complete.set("supplementFields", supplementFields);

        if (draft != null) {
            draft.setQuestionnaireCode(q.code());
            draft.setQuestionnaireVersion(q.version());
            draft.setPrefillJson(JsonUtils.toJson(prefill));
            draft.setCompletenessJson(JsonUtils.toJson(complete));
            if (channel != null && !channel.isBlank()) {
                draft.setChannel(channel);
            }
            draft.setFilledByType("STAFF");
            draft.setFilledById(staffId);
            EntityMeta.onUpdate(draft);
            responseMapper.updateDraft(draft);
            return toView(draft, true);
        }

        QuestionnaireResponse row = new QuestionnaireResponse();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setQuestionnaireCode(q.code());
        row.setQuestionnaireVersion(q.version());
        row.setPurpose(PURPOSE_SUPPLEMENT);
        row.setStatus(STATUS_DRAFT);
        row.setChannel(channel);
        row.setPrefillJson(JsonUtils.toJson(prefill));
        row.setAnswersJson("{}");
        row.setCompletenessJson(JsonUtils.toJson(complete));
        row.setFilledByType("STAFF");
        row.setFilledById(staffId);
        EntityMeta.onCreate(row);
        responseMapper.insert(row);
        return toView(row, true);
    }

    @Transactional
    public QuestionnaireResponseView saveDraft(
            String id, String staffId, String answersJson, String programCode, String channel) {
        QuestionnaireResponse row = require(id);
        if (!STATUS_DRAFT.equals(row.getStatus())) {
            throw new BusinessException("已提交的作答不能再改草稿");
        }
        QuestionnaireDef q = catalog.requireQuestionnaire(row.getQuestionnaireCode(), row.getQuestionnaireVersion());
        ObjectNode answers = asObject(answersJson);
        row.setAnswersJson(JsonUtils.toJson(answers));
        if (programCode != null && !programCode.isBlank()) {
            row.setProgramCode(programCode);
            applyEnrollment(row, row.getTenantId(), row.getOrgId(), row.getPeopleId(), staffId);
        }
        if (channel != null && !channel.isBlank()) {
            row.setChannel(channel);
        }
        row.setFilledByType("STAFF");
        row.setFilledById(staffId);
        Function<String, JsonNode> lookup = answersLookup(answers);
        Set<String> filter = supplementFieldFilter(row);
        ObjectNode complete = completeness(q, answers, lookup, filter);
        if (filter != null) {
            ArrayNode supplementFields = JsonUtils.mapper().createArrayNode();
            filter.forEach(supplementFields::add);
            complete.set("supplementFields", supplementFields);
        }
        row.setCompletenessJson(JsonUtils.toJson(complete));
        row.setHighlightsJson(JsonUtils.toJson(collectHighlights(q, lookup)));
        EntityMeta.onUpdate(row);
        responseMapper.updateDraft(row);
        return toView(row, true);
    }

    @Transactional
    public QuestionnaireResponseView submit(String id, String staffId, Map<String, JsonNode> conflictResolutions) {
        QuestionnaireResponse row = require(id);
        if (!STATUS_DRAFT.equals(row.getStatus())) {
            throw new BusinessException("作答已提交");
        }
        QuestionnaireDef q = catalog.requireQuestionnaire(row.getQuestionnaireCode(), row.getQuestionnaireVersion());
        ObjectNode answers = asObject(row.getAnswersJson());
        Function<String, JsonNode> lookup = answersLookup(answers);
        Set<String> filter = supplementFieldFilter(row);
        ObjectNode complete = completeness(q, answers, lookup, filter);
        if (filter != null) {
            ArrayNode supplementFields = JsonUtils.mapper().createArrayNode();
            filter.forEach(supplementFields::add);
            complete.set("supplementFields", supplementFields);
        }
        if (complete.path("requiredMissing").isArray() && complete.get("requiredMissing").size() > 0) {
            throw new BusinessException(400, "必收项未填全");
        }

        ObjectNode prefill = asObject(row.getPrefillJson());
        ObjectNode changes = diffChanges(prefill, answers);
        ArrayNode conflicts = detectConflicts(row.getTenantId(), row.getPeopleId(), prefill, answers);
        if (conflicts.size() > 0) {
            if (conflictResolutions == null || conflictResolutions.isEmpty()) {
                throw new BusinessException(409, "存在字段冲突，请先选择保留值");
            }
            for (JsonNode c : conflicts) {
                String code = c.path("field").asText();
                JsonNode chosen = conflictResolutions.get(code);
                if (chosen != null) {
                    answers.set(code, chosen);
                }
            }
            lookup = answersLookup(answers);
            changes = diffChanges(prefill, answers);
        }

        ArrayNode highlights = collectHighlights(q, lookup);
        String bizCode = PURPOSE_ASSESSMENT.equals(row.getPurpose())
                ? "ASSESSMENT_QUESTIONNAIRE"
                : "INTAKE_QUESTIONNAIRE";
        FieldWriteSource source = FieldWriteSource.of(
                bizCode,
                "B_WORKSPACE",
                row.getId(),
                "STAFF",
                staffId,
                row.getOrgId());

        writeBack(row, answers, prefill, source);

        if (row.getEnrollmentId() != null && PURPOSE_INTAKE.equals(row.getPurpose())) {
            enrollmentService.activate(row.getEnrollmentId(), row.getId());
        }

        row.setAnswersJson(JsonUtils.toJson(answers));
        row.setChangesJson(JsonUtils.toJson(changes));
        row.setHighlightsJson(JsonUtils.toJson(highlights));
        row.setCompletenessJson(JsonUtils.toJson(complete));
        ObjectNode snapshot = JsonUtils.emptyObject();
        snapshot.put("questionnaire", q.key());
        snapshot.set("answers", answers);
        snapshot.set("highlights", highlights);
        row.setSnapshotJson(JsonUtils.toJson(snapshot));
        row.setSubmittedAt(LocalDateTime.now());
        row.setFilledByType("STAFF");
        row.setFilledById(staffId);
        EntityMeta.onUpdate(row);
        int n = responseMapper.markSubmitted(row);
        if (n == 0) {
            throw new BusinessException("提交失败，请刷新后重试");
        }
        return toView(require(row.getId()), true);
    }

    private void writeBack(
            QuestionnaireResponse row, ObjectNode answers, ObjectNode prefill, FieldWriteSource source) {
        Map<String, JsonNode> meta = new LinkedHashMap<>();
        Map<String, JsonNode> profile = new LinkedHashMap<>();
        Map<String, JsonNode> metrics = new LinkedHashMap<>();
        Map<String, JsonNode> goals = new LinkedHashMap<>();
        Map<String, JsonNode> consents = new LinkedHashMap<>();
        Set<String> filter = supplementFieldFilter(row);

        Iterator<Map.Entry<String, JsonNode>> it = answers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String code = e.getKey();
            JsonNode value = e.getValue();
            if (filter != null && !filter.contains(code)) {
                continue;
            }
            if (!isVisible(row, code, answersLookup(answers))) {
                continue; // 收起的显示条件题：草稿可留，提交不写回
            }
            FieldStore store;
            try {
                store = FieldStore.of(code);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            switch (store) {
                case BASIC, DISEASE -> meta.put(code, value);
                case PROFILE -> profile.put(code, value);
                case METRIC -> metrics.put(code, value);
                case GOAL -> goals.put(code, value);
                case CONSENT -> consents.put(code, value);
                default -> {
                    /* allergy/medication/lab/staff/measure：专属写或仅作答 */
                }
            }
        }
        if (!meta.isEmpty()) {
            peopleFieldService.write(row.getTenantId(), row.getPeopleId(), meta, source);
        }
        if (!profile.isEmpty()) {
            writeProfile(row.getPeopleId(), profile);
        }
        writeMetrics(row, metrics, prefill, source);
        writeGoals(row, goals, source);
        writeConsents(row, consents, source);
    }

    private boolean isVisible(QuestionnaireResponse row, String field, Function<String, JsonNode> lookup) {
        QuestionnaireDef q = catalog.requireQuestionnaire(row.getQuestionnaireCode(), row.getQuestionnaireVersion());
        for (ModuleItem item : catalog.itemsOf(q)) {
            if (item.field().equals(field)) {
                return item.showIf() == null || item.showIf().matches(lookup);
            }
        }
        return true;
    }

    private void writeProfile(String peopleId, Map<String, JsonNode> values) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile == null) {
            throw new BusinessException("客户不存在");
        }
        boolean changed = false;
        for (Map.Entry<String, JsonNode> e : values.entrySet()) {
            String leaf = e.getKey().substring("profile.".length());
            String text = e.getValue() == null || e.getValue().isNull() ? null : e.getValue().asText(null);
            switch (leaf) {
                case "displayName" -> {
                    profile.setDisplayName(text);
                    changed = true;
                }
                case "gender" -> {
                    profile.setGender(text);
                    changed = true;
                }
                case "birthday" -> {
                    profile.setBirthday(text == null || text.isBlank() ? null : LocalDate.parse(text));
                    changed = true;
                }
                case "mobile" -> {
                    profile.setMobile(text);
                    changed = true;
                }
                case "wechatId" -> {
                    profile.setWechatId(text);
                    changed = true;
                }
                case "occupation" -> {
                    profile.setOccupation(text);
                    changed = true;
                }
                case "maritalStatus" -> {
                    profile.setMaritalStatus(text);
                    changed = true;
                }
                case "workSchedule" -> {
                    profile.setWorkSchedule(text);
                    changed = true;
                }
                default -> {
                    /* 未支持列忽略 */
                }
            }
        }
        if (changed) {
            EntityMeta.onUpdate(profile);
            peopleProfileMapper.updateProfile(profile);
        }
    }

    private void writeMetrics(
            QuestionnaireResponse row,
            Map<String, JsonNode> metrics,
            ObjectNode prefill,
            FieldWriteSource source) {
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, JsonNode> e : metrics.entrySet()) {
            String metricType = e.getKey().substring("metric.".length());
            if (ArchiveFields.isEmpty(e.getValue())) {
                continue;
            }
            BigDecimal value = new BigDecimal(e.getValue().asText());
            JsonNode pref = prefill.path(e.getKey()).path("value");
            if (!pref.isMissingNode() && !pref.isNull() && numbersEqual(pref, e.getValue())) {
                continue; // 预填未改，不重复写
            }
            vitalService.record(
                    row.getPeopleId(),
                    metricType,
                    value,
                    unitOf(metricType),
                    now,
                    VitalSourceEnum.INTAKE.name());
        }
    }

    private void writeGoals(QuestionnaireResponse row, Map<String, JsonNode> goals, FieldWriteSource source) {
        if (goals.isEmpty()) {
            return;
        }
        PeopleHealthGoal existing =
                row.getEnrollmentId() != null
                        ? goalMapper.findByEnrollment(row.getEnrollmentId())
                        : goalMapper.findLatestByPeople(row.getTenantId(), row.getPeopleId());
        PeopleHealthGoal g = existing != null ? existing : new PeopleHealthGoal();
        if (existing == null) {
            g.setTenantId(row.getTenantId());
            g.setOrgId(row.getOrgId());
            g.setPeopleId(row.getPeopleId());
            g.setEnrollmentId(row.getEnrollmentId());
        }
        if (goals.containsKey("goal.primaryAppeal")) {
            g.setPrimaryAppeal(textOrNull(goals.get("goal.primaryAppeal")));
        }
        if (goals.containsKey("goal.appealText")) {
            g.setAppealText(textOrNull(goals.get("goal.appealText")));
        }
        if (goals.containsKey("goal.doctorAdvised")) {
            String v = textOrNull(goals.get("goal.doctorAdvised"));
            g.setDoctorAdvised("YES".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v) ? 1 : 0);
        }
        if (goals.containsKey("goal.mode")) {
            g.setGoalMode(textOrNull(goals.get("goal.mode")));
        }
        ObjectNode goalsJson = existing != null && existing.getGoalsJson() != null
                ? asObject(existing.getGoalsJson())
                : JsonUtils.emptyObject();
        ObjectNode answers = asObject(row.getAnswersJson());
        putGoalMetric(
                goalsJson,
                "WEIGHT",
                goals.get("goal.targetWeightKg"),
                goals.get("goal.targetDate"),
                answers.get("metric.WEIGHT"));
        putGoalMetric(goalsJson, "WAIST", goals.get("goal.targetWaistCm"), null, answers.get("metric.WAIST"));
        putGoalMetric(goalsJson, "BODY_FAT", goals.get("goal.targetBodyFat"), null, answers.get("metric.BODY_FAT"));
        Double height = numAnswer(answers.get("metric.HEIGHT"));
        Double weight = numAnswer(answers.get("metric.WEIGHT"));
        Double bmi = (height != null && height > 0 && weight != null)
                ? Math.round(weight / Math.pow(height / 100.0, 2) * 10) / 10.0
                : null;
        boolean lossBlocked = hasBlockHighlight(row);
        var decision = com.healix.core.goal.support.HealthGoalRules.requireValid(
                g.getGoalMode(), bmi, lossBlocked, goalsJson, height);
        g.setGoalMode(decision.mode());
        g.setGoalsJson(JsonUtils.toJson(goalsJson));

        ObjectNode prefs = existing != null && existing.getPreferencesJson() != null
                ? asObject(existing.getPreferencesJson())
                : JsonUtils.emptyObject();
        putIfPresent(prefs, "exerciseMinutesPerDay", goals.get("goal.exerciseMinutesPerDay"));
        putIfPresent(prefs, "dietEffort", goals.get("goal.dietEffort"));
        putIfPresent(prefs, "followupChannel", goals.get("goal.followupChannel"));
        putIfPresent(prefs, "followupFrequency", goals.get("goal.followupFrequency"));
        putIfPresent(prefs, "constraints", goals.get("goal.constraints"));
        g.setPreferencesJson(JsonUtils.toJson(prefs));
        g.setSource(source.sourceBizCode());
        g.setSourceRefId(source.sourceRefId());
        if (existing == null) {
            EntityMeta.onCreate(g);
            goalMapper.insert(g);
        } else {
            EntityMeta.onUpdate(g);
            goalMapper.update(g);
        }
    }

    private void writeConsents(
            QuestionnaireResponse row, Map<String, JsonNode> consents, FieldWriteSource source) {
        for (Map.Entry<String, JsonNode> e : consents.entrySet()) {
            String code = e.getKey().substring("consent.".length());
            if ("method".equals(code)) {
                continue;
            }
            String givenText = textOrNull(e.getValue());
            boolean given = "YES".equalsIgnoreCase(givenText) || "true".equalsIgnoreCase(givenText)
                    || "GIVEN".equalsIgnoreCase(givenText);
            String method = textOrNull(consents.get("consent.method"));
            PeopleConsent existing = consentMapper.findByPeopleAndCode(row.getPeopleId(), code);
            if (existing == null) {
                PeopleConsent c = new PeopleConsent();
                c.setTenantId(row.getTenantId());
                c.setOrgId(row.getOrgId());
                c.setPeopleId(row.getPeopleId());
                c.setConsentCode(code);
                c.setGiven(given ? 1 : 0);
                c.setMethod(method);
                c.setSourceRefId(source.sourceRefId());
                c.setRecordedByType(source.updatedByType());
                c.setRecordedById(source.updatedById());
                c.setRecordedAt(LocalDateTime.now());
                EntityMeta.onCreate(c);
                consentMapper.insert(c);
            } else {
                existing.setGiven(given ? 1 : 0);
                existing.setMethod(method);
                existing.setSourceRefId(source.sourceRefId());
                existing.setRecordedByType(source.updatedByType());
                existing.setRecordedById(source.updatedById());
                existing.setRecordedAt(LocalDateTime.now());
                EntityMeta.onUpdate(existing);
                consentMapper.update(existing);
            }
        }
    }

    private void applyEnrollment(
            QuestionnaireResponse row, String tenantId, String orgId, String peopleId, String staffId) {
        PeopleProgramEnrollment en =
                enrollmentService.ensureOnboarding(tenantId, orgId, peopleId, row.getProgramCode(), staffId);
        row.setEnrollmentId(en == null ? null : en.getId());
    }

    private ObjectNode buildPrefill(String tenantId, String peopleId, QuestionnaireDef q) {
        peopleFieldService.ensureMigrated(tenantId, peopleId);
        ObjectNode out = JsonUtils.emptyObject();
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        for (ModuleItem item : catalog.itemsOf(q)) {
            String code = item.field();
            FieldStore store;
            try {
                store = FieldStore.of(code);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ObjectNode cell = JsonUtils.emptyObject();
            switch (store) {
                case PROFILE -> {
                    JsonNode v = readProfile(profile, code);
                    if (!ArchiveFields.isEmpty(v)) {
                        cell.set("value", v);
                        cell.put("sourceBizCode", "PROFILE");
                        out.set(code, cell);
                    }
                }
                case BASIC, DISEASE -> {
                    Optional<FieldValue> fv = peopleFieldService.read(tenantId, peopleId, code);
                    if (fv.isPresent() && !ArchiveFields.isEmpty(fv.get().value())) {
                        cell.set("value", fv.get().value());
                        cell.put("sourceBizCode", nullToEmpty(fv.get().sourceBizCode()));
                        cell.put("sourceRefId", nullToEmpty(fv.get().sourceRefId()));
                        if (fv.get().gmtModified() != null) {
                            cell.put("gmtModified", fv.get().gmtModified().toString());
                        }
                        out.set(code, cell);
                    }
                }
                case METRIC -> {
                    String metricType = code.substring("metric.".length());
                    List<VitalRecord> latest =
                            vitalRecordMapper.listByPeople(tenantId, peopleId, metricType, null, null, 1);
                    if (!latest.isEmpty()) {
                        VitalRecord r = latest.get(0);
                        cell.put("value", r.getValue());
                        cell.put("sourceBizCode", r.getSource());
                        if (r.getRecordedAt() != null) {
                            cell.put("recordedAt", r.getRecordedAt().toString());
                        }
                        out.set(code, cell);
                    }
                }
                default -> {
                    /* goal/consent 等无预填或后续补 */
                }
            }
        }
        return out;
    }

    private static JsonNode readProfile(PeopleProfile profile, String code) {
        if (profile == null) {
            return null;
        }
        String leaf = code.substring("profile.".length());
        return switch (leaf) {
            case "displayName" -> textNode(profile.getDisplayName());
            case "gender" -> textNode(profile.getGender());
            case "birthday" -> profile.getBirthday() == null ? null : textNode(profile.getBirthday().toString());
            case "mobile" -> textNode(profile.getMobile());
            case "wechatId" -> textNode(profile.getWechatId());
            case "occupation" -> textNode(profile.getOccupation());
            case "maritalStatus" -> textNode(profile.getMaritalStatus());
            case "workSchedule" -> textNode(profile.getWorkSchedule());
            default -> null;
        };
    }

    private ObjectNode completeness(
            QuestionnaireDef q, ObjectNode answers, Function<String, JsonNode> lookup, Set<String> fieldFilter) {
        int reqTotal = 0, reqDone = 0, firstTotal = 0, firstDone = 0, allTotal = 0, allDone = 0;
        ArrayNode requiredMissing = JsonUtils.mapper().createArrayNode();
        ArrayNode firstMissing = JsonUtils.mapper().createArrayNode();
        for (ModuleItem item : catalog.itemsOf(q)) {
            if (fieldFilter != null && !fieldFilter.contains(item.field())) {
                continue;
            }
            if (item.showIf() != null && !item.showIf().matches(lookup)) {
                continue;
            }
            allTotal++;
            boolean filled = !ArchiveFields.isEmpty(answers.get(item.field()));
            if (filled) {
                allDone++;
            }
            if ("REQUIRED".equals(item.level())) {
                reqTotal++;
                if (filled) {
                    reqDone++;
                } else {
                    requiredMissing.add(item.field());
                }
            } else if ("FIRST_VISIT".equals(item.level())) {
                firstTotal++;
                if (filled) {
                    firstDone++;
                } else {
                    firstMissing.add(item.field());
                }
            }
        }
        ObjectNode out = JsonUtils.emptyObject();
        out.put("requiredDone", reqDone);
        out.put("requiredTotal", reqTotal);
        out.put("firstVisitDone", firstDone);
        out.put("firstVisitTotal", firstTotal);
        out.put("allDone", allDone);
        out.put("allTotal", allTotal);
        out.set("requiredMissing", requiredMissing);
        out.set("firstVisitMissing", firstMissing);
        return out;
    }

    private List<IntakeStatusView.PendingField> computePendingFields(
            String tenantId, String peopleId, QuestionnaireDef q) {
        ObjectNode prefill = buildPrefill(tenantId, peopleId, q);
        Function<String, JsonNode> lookup = code -> {
            JsonNode cell = prefill.get(code);
            return cell == null ? null : cell.get("value");
        };
        List<IntakeStatusView.PendingField> pending = new ArrayList<>();
        for (QuestionnaireDef.ModuleRef ref : q.modules()) {
            ModuleDef m = catalog.requireModule(ref.code(), ref.version());
            for (ModuleItem item : m.items()) {
                if ("STAFF".equals(item.level())) {
                    continue;
                }
                if (item.showIf() != null && !item.showIf().matches(lookup)) {
                    continue;
                }
                JsonNode cell = prefill.get(item.field());
                JsonNode value = cell == null ? null : cell.get("value");
                if (!ArchiveFields.isEmpty(value)) {
                    continue;
                }
                IntakeStatusView.PendingField p = new IntakeStatusView.PendingField();
                p.setField(item.field());
                p.setLabel(item.label());
                p.setLevel(item.level());
                p.setModuleCode(m.code());
                p.setModuleTitle(m.title());
                pending.add(p);
            }
        }
        return pending;
    }

    private Set<String> supplementFieldFilter(QuestionnaireResponse row) {
        if (!PURPOSE_SUPPLEMENT.equals(row.getPurpose())) {
            return null;
        }
        JsonNode c = JsonUtils.readTree(
                row.getCompletenessJson() == null || row.getCompletenessJson().isBlank()
                        ? "{}"
                        : row.getCompletenessJson());
        JsonNode arr = c.get("supplementFields");
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        arr.forEach(n -> {
            if (n != null && n.isTextual() && !n.asText().isBlank()) {
                out.add(n.asText());
            }
        });
        return out.isEmpty() ? null : out;
    }

    private ArrayNode collectHighlights(QuestionnaireDef q, Function<String, JsonNode> lookup) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        for (ModuleItem item : catalog.itemsOf(q)) {
            if (item.showIf() != null && !item.showIf().matches(lookup)) {
                continue;
            }
            JsonNode answer = lookup.apply(item.field());
            for (HighlightRule rule : item.highlights()) {
                if (rule.matches(answer)) {
                    ObjectNode hit = JsonUtils.emptyObject();
                    hit.put("field", item.field());
                    hit.put("severity", rule.severity());
                    if (rule.planEffect() != null) {
                        hit.put("planEffect", rule.planEffect());
                    }
                    if (rule.followUp() != null) {
                        hit.put("followUp", rule.followUp());
                    }
                    out.add(hit);
                }
            }
        }
        return out;
    }

    private ArrayNode detectConflicts(
            String tenantId, String peopleId, ObjectNode prefill, ObjectNode answers) {
        ArrayNode conflicts = JsonUtils.mapper().createArrayNode();
        Iterator<Map.Entry<String, JsonNode>> it = answers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode prefCell = prefill.get(e.getKey());
            if (prefCell == null || !prefCell.has("gmtModified")) {
                continue;
            }
            LocalDateTime prefAt;
            try {
                prefAt = LocalDateTime.parse(prefCell.path("gmtModified").asText());
            } catch (Exception ex) {
                continue;
            }
            Optional<FieldValue> current = peopleFieldService.read(tenantId, peopleId, e.getKey());
            if (current.isEmpty() || current.get().gmtModified() == null) {
                continue;
            }
            if (current.get().gmtModified().isAfter(prefAt)
                    && !jsonEquals(current.get().value(), prefCell.get("value"))) {
                ObjectNode c = JsonUtils.emptyObject();
                c.put("field", e.getKey());
                c.set("prefill", prefCell.get("value"));
                c.set("current", current.get().value());
                c.set("submitted", e.getValue());
                conflicts.add(c);
            }
        }
        return conflicts;
    }

    private ObjectNode diffChanges(ObjectNode prefill, ObjectNode answers) {
        ObjectNode changes = JsonUtils.emptyObject();
        Iterator<Map.Entry<String, JsonNode>> it = answers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode oldVal = prefill.path(e.getKey()).get("value");
            if (!jsonEquals(oldVal, e.getValue())) {
                ObjectNode d = JsonUtils.emptyObject();
                d.set("from", oldVal == null ? JsonUtils.mapper().nullNode() : oldVal);
                d.set("to", e.getValue());
                changes.set(e.getKey(), d);
            }
        }
        return changes;
    }

    private QuestionnaireResponseView toView(QuestionnaireResponse row, boolean withModules) {
        QuestionnaireResponseView v = new QuestionnaireResponseView();
        v.setId(row.getId());
        v.setPeopleId(row.getPeopleId());
        v.setQuestionnaireCode(row.getQuestionnaireCode());
        v.setQuestionnaireVersion(row.getQuestionnaireVersion() == null ? 0 : row.getQuestionnaireVersion());
        v.setPurpose(row.getPurpose());
        v.setStatus(row.getStatus());
        v.setChannel(row.getChannel());
        v.setProgramCode(row.getProgramCode());
        v.setEnrollmentId(row.getEnrollmentId());
        v.setFollowupRecordId(row.getFollowupRecordId());
        v.setPrefill(JsonUtils.readTree(row.getPrefillJson() == null ? "{}" : row.getPrefillJson()));
        v.setAnswers(JsonUtils.readTree(row.getAnswersJson() == null ? "{}" : row.getAnswersJson()));
        v.setHighlights(JsonUtils.readTree(row.getHighlightsJson() == null ? "[]" : row.getHighlightsJson()));
        v.setCompleteness(JsonUtils.readTree(row.getCompletenessJson() == null ? "{}" : row.getCompletenessJson()));
        v.setChanges(JsonUtils.readTree(row.getChangesJson() == null ? "{}" : row.getChangesJson()));
        v.setSnapshot(JsonUtils.readTree(row.getSnapshotJson() == null ? "null" : row.getSnapshotJson()));
        v.setSubmittedAt(row.getSubmittedAt());
        QuestionnaireDef q = catalog.requireQuestionnaire(row.getQuestionnaireCode(), row.getQuestionnaireVersion());
        v.setTitle(q.title());
        if (withModules) {
            Set<String> filter = supplementFieldFilter(row);
            List<QuestionnaireResponseView.ModuleView> modules = new ArrayList<>();
            for (QuestionnaireDef.ModuleRef ref : q.modules()) {
                ModuleDef m = catalog.requireModule(ref.code(), ref.version());
                QuestionnaireResponseView.ModuleView mv = new QuestionnaireResponseView.ModuleView();
                mv.setCode(m.code());
                mv.setVersion(m.version());
                mv.setTitle(m.title());
                mv.setScript(m.script());
                List<QuestionnaireResponseView.ItemView> items = new ArrayList<>();
                for (ModuleItem it : m.items()) {
                    if (filter != null && !filter.contains(it.field())) {
                        continue;
                    }
                    QuestionnaireResponseView.ItemView iv = new QuestionnaireResponseView.ItemView();
                    iv.setField(it.field());
                    iv.setLabel(it.label());
                    iv.setLevel(it.level());
                    iv.setSensitive(it.sensitive());
                    fieldCatalog.find(it.field()).ifPresent(def -> {
                        iv.setType(def.type().name());
                        iv.setUnit(def.unit());
                        iv.setOptions(def.options());
                    });
                    if (it.showIf() != null) {
                        iv.setShowIf(JsonUtils.mapper().valueToTree(showIfToMap(it.showIf())));
                    }
                    if (!it.highlights().isEmpty()) {
                        iv.setHighlights(JsonUtils.mapper().valueToTree(it.highlights()));
                    }
                    items.add(iv);
                }
                if (items.isEmpty()) {
                    continue;
                }
                mv.setItems(items);
                modules.add(mv);
            }
            v.setModules(modules);
        }
        return v;
    }

    private static Map<String, Object> showIfToMap(ShowIf s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s.field() != null) {
            m.put("field", s.field());
        }
        if (s.eq() != null) {
            m.put("eq", s.eq());
        }
        if (s.in() != null) {
            m.put("in", s.in());
        }
        if (s.notIn() != null) {
            m.put("notIn", s.notIn());
        }
        if (s.intersects() != null) {
            m.put("intersects", s.intersects());
        }
        return m;
    }

    private QuestionnaireResponse require(String id) {
        QuestionnaireResponse row = responseMapper.findById(id);
        if (row == null) {
            throw new BusinessException("作答不存在");
        }
        return row;
    }

    private static Function<String, JsonNode> answersLookup(ObjectNode answers) {
        return code -> answers.get(code);
    }

    private static ObjectNode asObject(String json) {
        JsonNode n = JsonUtils.readTree(json == null || json.isBlank() ? "{}" : json);
        return n instanceof ObjectNode o ? o : JsonUtils.emptyObject();
    }

    private static JsonNode textNode(String v) {
        return v == null || v.isBlank() ? null : JsonUtils.mapper().getNodeFactory().textNode(v);
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText().trim();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null || a.isNull() || a.isMissingNode()) {
            return b == null || b.isNull() || b.isMissingNode() || ArchiveFields.isEmpty(b);
        }
        if (b == null || b.isNull() || b.isMissingNode()) {
            return ArchiveFields.isEmpty(a);
        }
        return Objects.equals(JsonUtils.toJson(a), JsonUtils.toJson(b));
    }

    private static boolean numbersEqual(JsonNode a, JsonNode b) {
        try {
            return new BigDecimal(a.asText()).compareTo(new BigDecimal(b.asText())) == 0;
        } catch (Exception e) {
            return jsonEquals(a, b);
        }
    }

    private static String unitOf(String metricType) {
        return switch (metricType) {
            case "HEIGHT", "WAIST", "HIP" -> "cm";
            case "WEIGHT" -> "kg";
            case "BODY_FAT" -> "%";
            case "HEART_RATE" -> "bpm";
            case "BLOOD_PRESSURE_SYS", "BLOOD_PRESSURE_DIA" -> "mmHg";
            case "BLOOD_GLUCOSE" -> "mmol/L";
            default -> null;
        };
    }

    private static void putGoalMetric(
            ObjectNode goalsJson, String metric, JsonNode target, JsonNode deadline, JsonNode baseline) {
        if (ArchiveFields.isEmpty(target) && ArchiveFields.isEmpty(deadline)) {
            return;
        }
        ObjectNode item = goalsJson.has(metric) && goalsJson.get(metric).isObject()
                ? (ObjectNode) goalsJson.get(metric)
                : goalsJson.putObject(metric);
        item.put("metric", metric);
        if (!ArchiveFields.isEmpty(baseline) && !item.has("baseline")) {
            item.set("baseline", baseline);
        }
        if (!ArchiveFields.isEmpty(target)) {
            item.set("target", target);
        }
        if (!ArchiveFields.isEmpty(deadline)) {
            item.set("deadline", deadline);
        }
    }

    private static void putIfPresent(ObjectNode obj, String key, JsonNode v) {
        if (!ArchiveFields.isEmpty(v)) {
            obj.set(key, v);
        }
    }

    private static boolean hasBlockHighlight(QuestionnaireResponse row) {
        if (!StringUtils.hasText(row.getHighlightsJson())) {
            return false;
        }
        JsonNode arr = JsonUtils.readTree(row.getHighlightsJson());
        if (arr == null || !arr.isArray()) {
            return false;
        }
        for (JsonNode h : arr) {
            if ("BLOCK".equalsIgnoreCase(h.path("severity").asText(""))) {
                return true;
            }
        }
        return false;
    }

    private static Double numAnswer(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (Exception e) {
            return null;
        }
    }
}
