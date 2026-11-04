package com.healix.core.questionnaire.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.allergy.dto.AllergyItemDto;
import com.healix.core.allergy.dto.AllergySummaryDto;
import com.healix.core.allergy.enums.AllergyCategoryEnum;
import com.healix.core.allergy.enums.AllergyStatusEnum;
import com.healix.core.allergy.service.AllergyService;
import com.healix.core.allergy.service.AllergyService.AllergyCommand;
import com.healix.core.consent.domain.PeopleConsent;
import com.healix.core.consent.mapper.PeopleConsentMapper;
import com.healix.core.field.ArchiveFields;
import com.healix.core.field.FieldCatalog;
import com.healix.core.field.FieldDef;
import com.healix.core.field.FieldStore;
import com.healix.core.field.FieldValue;
import com.healix.core.field.FieldWriteSource;
import com.healix.core.field.PeopleFieldService;
import com.healix.core.field.SensitiveFieldMask;
import com.healix.core.goal.domain.PeopleHealthGoal;
import com.healix.core.goal.mapper.PeopleHealthGoalMapper;
import com.healix.core.medication.dto.MedicationViewDto;
import com.healix.core.medication.enums.MedicationSourceEnum;
import com.healix.core.medication.enums.MedicationStatusEnum;
import com.healix.core.medication.service.MedicationService;
import com.healix.core.medication.service.MedicationService.MedicationCommand;
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
    private final MedicationService medicationService;
    private final AllergyService allergyService;

    private static final String FIELD_MEDICATION_CURRENT = "medication.current";
    private static final String FIELD_ALLERGY_STATUS = "allergy.status";
    private static final String FIELD_ALLERGY_FOOD = "allergy.food";
    private static final String FIELD_ALLERGY_DRUG = "allergy.drug";

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
            view.setLatestSubmittedId(submitted.getId());
            if (submitted.getSubmittedAt() != null) {
                view.setSubmittedAt(submitted.getSubmittedAt().toString());
            }
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
            // 空草稿：按「上次答卷 + 最近档案/指标」刷新预填，并升到最新问卷版本
            if (answersBlank(asObject(draft.getAnswersJson()))) {
                ObjectNode prefill = buildPrefill(tenantId, peopleId, q);
                draft.setQuestionnaireCode(q.code());
                draft.setQuestionnaireVersion(q.version());
                draft.setPrefillJson(JsonUtils.toJson(prefill));
                draft.setCompletenessJson(
                        JsonUtils.toJson(
                                completeness(
                                        q,
                                        JsonUtils.emptyObject(),
                                        answersLookup(JsonUtils.emptyObject()),
                                        null)));
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

        if (PURPOSE_INTAKE.equals(row.getPurpose())) {
            activateEnrollments(row, answers);
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
            // 拒绝脱敏占位写回，避免档案被污染成「已采集」
            if (isMaskedPlaceholder(value)) {
                continue;
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
                case MEDICATION -> {
                    /* 见 writeMedications */
                }
                case ALLERGY -> {
                    /* 见 writeAllergies */
                }
                default -> {
                    /* lab/staff/measure：专属写或仅作答 */
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
        if (filter == null || filter.contains(FIELD_MEDICATION_CURRENT)) {
            if (isVisible(row, FIELD_MEDICATION_CURRENT, answersLookup(answers))) {
                writeMedications(row, answers.get(FIELD_MEDICATION_CURRENT), source);
            }
        }
        writeAllergies(row, answers, filter, source);
    }

    private boolean isVisible(QuestionnaireResponse row, String field, Function<String, JsonNode> lookup) {
        QuestionnaireDef q = catalog.requireQuestionnaire(row.getQuestionnaireCode(), row.getQuestionnaireVersion());
        for (QuestionnaireDef.ModuleRef ref : q.modules()) {
            if (ref.showIf() != null && !ref.showIf().matches(lookup)) {
                continue;
            }
            ModuleDef m = catalog.requireModule(ref.code(), ref.version());
            for (ModuleItem item : m.items()) {
                if (item.field().equals(field)) {
                    return item.showIf() == null || item.showIf().matches(lookup);
                }
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
                    // ENUM 存码；档案展示用中文标签（未知码则原样写入，兼容历史自由文本）
                    final String occupationCode = text;
                    String occupationLabel = occupationCode;
                    if (occupationCode != null && !occupationCode.isBlank()) {
                        occupationLabel = fieldCatalog.catalogOptions("occupation").stream()
                                .filter(o -> occupationCode.equals(o.code()))
                                .map(com.healix.core.field.FieldOption::label)
                                .findFirst()
                                .orElse(occupationCode);
                    }
                    profile.setOccupation(occupationLabel);
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

    /**
     * 首诊「在用药物明细」→ 用药清单：有 id 则更新，无 id 则新建（来源 INTAKE）。
     * 从列表移除的已有药不自动停用，避免误伤。
     */
    private void writeMedications(QuestionnaireResponse row, JsonNode value, FieldWriteSource source) {
        if (value == null || value.isNull() || value.isMissingNode() || ArchiveFields.isEmpty(value)) {
            return;
        }
        if (!value.isArray()) {
            return; // 旧文本答案忽略，避免脏写
        }
        String staffId = source.updatedById();
        String orgId = row.getOrgId();
        String tenantId = row.getTenantId();
        String peopleId = row.getPeopleId();
        for (JsonNode item : value) {
            if (item == null || !item.isObject()) {
                continue;
            }
            String drugName = textOrNull(item.get("drugName"));
            if (!StringUtils.hasText(drugName)) {
                drugName = textOrNull(item.get("name"));
            }
            if (!StringUtils.hasText(drugName)) {
                continue;
            }
            String usage = textOrNull(item.get("usageMethod"));
            if (!StringUtils.hasText(usage)) {
                usage = "ORAL";
            }
            MedicationCommand cmd = new MedicationCommand(
                    drugName.trim(),
                    usage.trim(),
                    textOrNull(item.get("frequency")),
                    textOrNull(item.get("doseAmount")),
                    textOrNull(item.get("doseUnit")),
                    parseLocalDate(item.get("startDate")),
                    parseLocalDate(item.get("stopDate")),
                    textOrNull(item.get("timingNote")),
                    parsePositiveInt(item.get("courseDays")),
                    item.path("hasAdverseReaction").asBoolean(false),
                    textOrNull(item.get("remark")),
                    MedicationStatusEnum.ACTIVE.name(),
                    textOrNull(item.get("drugCode")),
                    parseSlots(item.get("slots")));
            String existingId = textOrNull(item.get("id"));
            if (StringUtils.hasText(existingId)) {
                try {
                    medicationService.update(tenantId, orgId, peopleId, staffId, existingId, cmd);
                    continue;
                } catch (BusinessException ex) {
                    // id 失效则新建
                }
            }
            medicationService.create(
                    tenantId,
                    orgId,
                    peopleId,
                    staffId,
                    cmd,
                    MedicationSourceEnum.INTAKE.name(),
                    null);
        }
    }

    /**
     * 过敏：先写食物/药物明细（条目同步会把档案标成 HAS），再写 NKA/UNKNOWN 状态。
     * HAS 不可经 setStatus 直写；无明细时仅保留答题，不改档案。
     */
    private void writeAllergies(
            QuestionnaireResponse row, ObjectNode answers, Set<String> filter, FieldWriteSource source) {
        Function<String, JsonNode> lookup = answersLookup(answers);
        boolean touchFood = filter == null || filter.contains(FIELD_ALLERGY_FOOD);
        boolean touchDrug = filter == null || filter.contains(FIELD_ALLERGY_DRUG);
        boolean touchStatus = filter == null || filter.contains(FIELD_ALLERGY_STATUS);
        if (touchFood && isVisible(row, FIELD_ALLERGY_FOOD, lookup)) {
            writeAllergyItems(row, answers.get(FIELD_ALLERGY_FOOD), AllergyCategoryEnum.FOOD, source);
        }
        if (touchDrug && isVisible(row, FIELD_ALLERGY_DRUG, lookup)) {
            writeAllergyItems(row, answers.get(FIELD_ALLERGY_DRUG), AllergyCategoryEnum.DRUG, source);
        }
        if (touchStatus && isVisible(row, FIELD_ALLERGY_STATUS, lookup)) {
            writeAllergyStatus(row, answers.get(FIELD_ALLERGY_STATUS), source);
        }
    }

    private void writeAllergyItems(
            QuestionnaireResponse row, JsonNode value, AllergyCategoryEnum category, FieldWriteSource source) {
        if (value == null || value.isNull() || value.isMissingNode() || ArchiveFields.isEmpty(value)) {
            return;
        }
        if (!value.isArray()) {
            return;
        }
        String staffId = source.updatedById();
        String orgId = row.getOrgId();
        String tenantId = row.getTenantId();
        String peopleId = row.getPeopleId();
        for (JsonNode item : value) {
            if (item == null || !item.isObject()) {
                continue;
            }
            AllergyCommand cmd = toAllergyCommand(item, category);
            if (cmd == null) {
                continue;
            }
            String existingId = textOrNull(item.get("id"));
            if (StringUtils.hasText(existingId)) {
                try {
                    allergyService.updateByStaff(tenantId, orgId, peopleId, staffId, existingId, cmd);
                    continue;
                } catch (BusinessException ex) {
                    // id 失效则新建
                }
            }
            allergyService.createByStaff(tenantId, orgId, peopleId, staffId, cmd);
        }
    }

    private AllergyCommand toAllergyCommand(JsonNode item, AllergyCategoryEnum category) {
        String allergenName = textOrNull(item.get("allergenName"));
        if (!StringUtils.hasText(allergenName)) {
            allergenName = textOrNull(item.get("name"));
        }
        if (!StringUtils.hasText(allergenName) && !StringUtils.hasText(textOrNull(item.get("allergenCode")))) {
            return null;
        }
        String allergyType = textOrNull(item.get("allergyType"));
        if (!StringUtils.hasText(allergyType)) {
            allergyType = "ALLERGY";
        }
        String severity = textOrNull(item.get("severity"));
        if (!StringUtils.hasText(severity)) {
            severity = "UNKNOWN";
        }
        String clinicalStatus = textOrNull(item.get("clinicalStatus"));
        if (!StringUtils.hasText(clinicalStatus)) {
            clinicalStatus = "ACTIVE";
        }
        String verification = textOrNull(item.get("verification"));
        if (!StringUtils.hasText(verification)) {
            verification = "CONFIRMED";
        }
        return new AllergyCommand(
                category.name(),
                textOrNull(item.get("allergenCode")),
                allergenName == null ? null : allergenName.trim(),
                allergyType.trim(),
                parseStringList(item.get("reactions")),
                textOrNull(item.get("reactionNote")),
                severity.trim(),
                clinicalStatus.trim(),
                verification.trim(),
                textOrNull(item.get("onsetText")),
                parseLocalDate(item.get("lastOccurrence")),
                textOrNull(item.get("note")));
    }

    private void writeAllergyStatus(QuestionnaireResponse row, JsonNode value, FieldWriteSource source) {
        if (value == null || value.isNull() || value.isMissingNode() || ArchiveFields.isEmpty(value)) {
            return;
        }
        String status = value.asText("").trim().toUpperCase(java.util.Locale.ROOT);
        if (!StringUtils.hasText(status)) {
            return;
        }
        // HAS 由条目写入后 syncProfile 标记；setStatus 禁止直写 HAS
        if (AllergyStatusEnum.HAS.name().equals(status)) {
            return;
        }
        if (!AllergyStatusEnum.NKA.name().equals(status) && !AllergyStatusEnum.UNKNOWN.name().equals(status)) {
            return;
        }
        try {
            allergyService.setStatusByStaff(
                    row.getTenantId(), row.getOrgId(), row.getPeopleId(), source.updatedById(), status);
        } catch (BusinessException ex) {
            // 仍有生效条目时无法标 NKA：保留条目，不阻断整份问卷提交
            if (!AllergyStatusEnum.NKA.name().equals(status)) {
                throw ex;
            }
        }
    }

    private static List<String> parseStringList(JsonNode n) {
        if (n == null || n.isNull() || !n.isArray() || n.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode s : n) {
            if (s != null && StringUtils.hasText(s.asText())) {
                out.add(s.asText().trim());
            }
        }
        return out;
    }

    private static LocalDate parseLocalDate(JsonNode n) {
        if (n == null || n.isNull() || !StringUtils.hasText(n.asText())) {
            return null;
        }
        try {
            return LocalDate.parse(n.asText().trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer parsePositiveInt(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        int v = n.isNumber() ? n.asInt() : 0;
        if (!n.isNumber()) {
            try {
                v = Integer.parseInt(n.asText().trim());
            } catch (Exception ex) {
                return null;
            }
        }
        return v > 0 ? v : null;
    }

    private static List<String> parseSlots(JsonNode n) {
        if (n == null || n.isNull() || !n.isArray() || n.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode s : n) {
            if (s != null && StringUtils.hasText(s.asText())) {
                out.add(s.asText().trim());
            }
        }
        return out.isEmpty() ? List.of() : out;
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
            g.setPrimaryAppeal(serializePrimaryAppeal(goals.get("goal.primaryAppeal")));
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
        List<String> tracks = resolveCareTracks(answers, row.getProgramCode());
        if (!StringUtils.hasText(g.getGoalMode())) {
            g.setGoalMode(defaultGoalModeForTracks(tracks));
        }
        putGoalMetric(
                goalsJson,
                "WEIGHT",
                goals.get("goal.targetWeightKg"),
                goals.get("goal.targetDate"),
                answers.get("metric.WEIGHT"));
        putGoalMetric(goalsJson, "WAIST", goals.get("goal.targetWaistCm"), null, answers.get("metric.WAIST"));
        putGoalMetric(goalsJson, "BODY_FAT", goals.get("goal.targetBodyFat"), null, answers.get("metric.BODY_FAT"));
        putGoalMetric(goalsJson, "HBA1C", goals.get("goal.targetHba1c"), null, answers.get("disease.diabetes.latestHba1c"));
        putGoalMetric(goalsJson, "FPG", goals.get("goal.targetFpg"), null, answers.get("disease.diabetes.latestFpg"));
        putGoalMetric(
                goalsJson,
                "BP_SYS",
                goals.get("goal.targetBpSys"),
                null,
                answers.get("disease.hypertension.latestSystolic"));
        putGoalMetric(
                goalsJson,
                "BP_DIA",
                goals.get("goal.targetBpDia"),
                null,
                answers.get("disease.hypertension.latestDiastolic"));
        Double height = numAnswer(answers.get("metric.HEIGHT"));
        Double weight = numAnswer(answers.get("metric.WEIGHT"));
        Double bmi = (height != null && height > 0 && weight != null)
                ? Math.round(weight / Math.pow(height / 100.0, 2) * 10) / 10.0
                : null;
        boolean lossBlocked = hasBlockHighlight(row);
        // 未纳入减重时不做体重下降目标的 BMI 硬校验
        ObjectNode goalsForValidate = tracks.contains(ProgramEnrollmentService.WEIGHT_LOSS)
                ? goalsJson
                : JsonUtils.emptyObject();
        var decision = com.healix.core.goal.support.HealthGoalRules.requireValid(
                g.getGoalMode(), bmi, lossBlocked, goalsForValidate, height);
        g.setGoalMode(decision.mode());
        g.setGoalsJson(JsonUtils.toJson(goalsJson));

        ObjectNode prefs = existing != null && existing.getPreferencesJson() != null
                ? asObject(existing.getPreferencesJson())
                : JsonUtils.emptyObject();
        putIfPresent(prefs, "careTracks", goals.get("goal.careTracks"));
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
        ObjectNode answers = asObject(row.getAnswersJson());
        List<String> tracks = resolveCareTracks(answers, row.getProgramCode());
        syncCareTracksAnswer(answers, tracks);
        row.setAnswersJson(JsonUtils.toJson(answers));

        if (tracks.isEmpty()) {
            row.setProgramCode(ProgramEnrollmentService.NONE);
            row.setEnrollmentId(null);
            return;
        }
        String primary = resolvePrimaryProgram(tracks, answers);
        row.setProgramCode(primary);
        PeopleProgramEnrollment primaryEn = null;
        for (String track : tracks) {
            PeopleProgramEnrollment en =
                    enrollmentService.ensureOnboarding(tenantId, orgId, peopleId, track, staffId);
            if (en != null && track.equals(primary)) {
                primaryEn = en;
            }
        }
        row.setEnrollmentId(primaryEn == null ? null : primaryEn.getId());
    }

    /** 提交时激活本次 careTracks 对应的全部入组。 */
    private void activateEnrollments(QuestionnaireResponse row, ObjectNode answers) {
        List<String> tracks = resolveCareTracks(answers, row.getProgramCode());
        if (tracks.isEmpty()) {
            return;
        }
        String primary = resolvePrimaryProgram(tracks, answers);
        for (String track : tracks) {
            PeopleProgramEnrollment open =
                    enrollmentService.findOpen(row.getTenantId(), row.getOrgId(), row.getPeopleId(), track);
            if (open == null) {
                open = enrollmentService.ensureOnboarding(
                        row.getTenantId(), row.getOrgId(), row.getPeopleId(), track, row.getFilledById());
            }
            if (open == null) {
                continue;
            }
            PeopleProgramEnrollment activated = enrollmentService.activate(open.getId(), row.getId());
            if (track.equals(primary)) {
                row.setEnrollmentId(activated.getId());
                row.setProgramCode(primary);
            }
        }
    }

    private static List<String> resolveCareTracks(ObjectNode answers, String programCode) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        JsonNode tracksNode = answers == null ? null : answers.get("goal.careTracks");
        boolean explicitNone = false;
        if (tracksNode != null && !tracksNode.isNull()) {
            if (tracksNode.isArray()) {
                for (JsonNode n : tracksNode) {
                    String t = n == null ? null : n.asText(null);
                    if (t == null || t.isBlank()) {
                        continue;
                    }
                    String code = t.trim().toUpperCase();
                    if (ProgramEnrollmentService.NONE.equals(code)) {
                        explicitNone = true;
                        continue;
                    }
                    if (ProgramEnrollmentService.isKnownProgram(code)) {
                        out.add(code);
                    }
                }
            } else if (tracksNode.isTextual()) {
                String code = tracksNode.asText().trim().toUpperCase();
                if (ProgramEnrollmentService.NONE.equals(code)) {
                    explicitNone = true;
                } else if (ProgramEnrollmentService.isKnownProgram(code)) {
                    out.add(code);
                }
            }
        }
        if (explicitNone) {
            return List.of();
        }
        if (out.isEmpty() && ProgramEnrollmentService.isKnownProgram(programCode)) {
            out.add(programCode.trim().toUpperCase());
        }
        return List.copyOf(out);
    }

    private static String resolvePrimaryProgram(List<String> tracks, ObjectNode answers) {
        if (tracks == null || tracks.isEmpty()) {
            return ProgramEnrollmentService.NONE;
        }
        // 多选诉求按优先级映射主项目：糖 > 压 > 减重相关
        for (String appeal : flattenAppealCodes(answers == null ? null : answers.get("goal.primaryAppeal"))) {
            String mapped = switch (appeal) {
                case "GLUCOSE" -> ProgramEnrollmentService.DIABETES;
                case "BP" -> ProgramEnrollmentService.HYPERTENSION;
                case "FAT_LOSS", "BODY_SHAPE", "LIPID", "METABOLIC" -> ProgramEnrollmentService.WEIGHT_LOSS;
                default -> null;
            };
            if (mapped != null && tracks.contains(mapped)) {
                return mapped;
            }
        }
        return tracks.get(0);
    }

    /** 核心诉求可单选或多选：库内存 JSON 数组字符串；历史单码仍兼容。 */
    private static String serializePrimaryAppeal(JsonNode node) {
        List<String> codes = flattenAppealCodes(node);
        if (codes.isEmpty()) {
            return null;
        }
        if (codes.size() == 1) {
            return codes.get(0);
        }
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        codes.forEach(arr::add);
        return JsonUtils.toJson(arr);
    }

    private static List<String> flattenAppealCodes(JsonNode node) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            node.forEach(n -> {
                String t = n == null ? null : n.asText(null);
                if (t != null && !t.isBlank()) {
                    out.add(t.trim().toUpperCase());
                }
            });
        } else if (node.isTextual()) {
            String raw = node.asText().trim();
            if (raw.startsWith("[")) {
                JsonNode parsed = JsonUtils.readTree(raw);
                return flattenAppealCodes(parsed);
            }
            if (raw.contains(",")) {
                for (String p : raw.split(",")) {
                    if (p != null && !p.isBlank()) {
                        out.add(p.trim().toUpperCase());
                    }
                }
            } else if (!raw.isBlank()) {
                out.add(raw.toUpperCase());
            }
        }
        return List.copyOf(out);
    }

    private static void syncCareTracksAnswer(ObjectNode answers, List<String> tracks) {
        if (answers == null) {
            return;
        }
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        if (tracks == null || tracks.isEmpty()) {
            arr.add(ProgramEnrollmentService.NONE);
        } else {
            for (String t : tracks) {
                arr.add(t);
            }
        }
        answers.set("goal.careTracks", arr);
    }

    private static String defaultGoalModeForTracks(List<String> tracks) {
        if (tracks == null || tracks.isEmpty()) {
            return com.healix.core.goal.support.HealthGoalRules.MODE_MAINTAIN;
        }
        if (tracks.contains(ProgramEnrollmentService.WEIGHT_LOSS)) {
            return com.healix.core.goal.support.HealthGoalRules.MODE_LOSS;
        }
        if (tracks.contains(ProgramEnrollmentService.DIABETES)) {
            return com.healix.core.goal.support.HealthGoalRules.MODE_GLUCOSE_CONTROL;
        }
        if (tracks.contains(ProgramEnrollmentService.HYPERTENSION)) {
            return com.healix.core.goal.support.HealthGoalRules.MODE_BP_CONTROL;
        }
        return com.healix.core.goal.support.HealthGoalRules.MODE_MAINTAIN;
    }

    /**
     * 打开首诊预填：先铺「最近一次已提交答卷」，再用档案/元数据/指标/用药/过敏等最近值覆盖。
     * staff.* 案头字段不预填，避免把上次 AI 总结当成新答案。
     */
    private ObjectNode buildPrefill(String tenantId, String peopleId, QuestionnaireDef q) {
        peopleFieldService.ensureMigrated(tenantId, peopleId);
        ObjectNode out = JsonUtils.emptyObject();
        Set<String> fields = new LinkedHashSet<>();
        for (ModuleItem item : catalog.itemsOf(q)) {
            fields.add(item.field());
        }
        seedLastSubmittedAnswers(out, tenantId, peopleId, PURPOSE_INTAKE, fields);
        seedLastSubmittedAnswers(out, tenantId, peopleId, PURPOSE_SUPPLEMENT, fields);

        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        AllergySummaryDto allergySummary = null;
        boolean allergyLoaded = false;
        for (String code : fields) {
            FieldStore store;
            try {
                store = FieldStore.of(code);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (store.responseOnly()) {
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
                    if (fv.isPresent()
                            && !ArchiveFields.isEmpty(fv.get().value())
                            && !isMaskedPlaceholder(fv.get().value())) {
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
                case MEDICATION -> {
                    if (!FIELD_MEDICATION_CURRENT.equals(code)) {
                        break;
                    }
                    ArrayNode meds = medicationPrefillArray(tenantId, peopleId);
                    if (!meds.isEmpty()) {
                        cell.set("value", meds);
                        cell.put("sourceBizCode", "MEDICATION");
                        out.set(code, cell);
                    }
                }
                case ALLERGY -> {
                    if (!allergyLoaded) {
                        allergySummary = allergyService.summaryForPatient(tenantId, peopleId);
                        allergyLoaded = true;
                    }
                    if (FIELD_ALLERGY_STATUS.equals(code)) {
                        // 未询问不预填，避免首诊把「未采」当成已答
                        if (allergySummary != null
                                && StringUtils.hasText(allergySummary.status())
                                && !AllergyStatusEnum.UNKNOWN.name().equals(allergySummary.status())) {
                            cell.put("value", allergySummary.status());
                            cell.put("sourceBizCode", "ALLERGY");
                            out.set(code, cell);
                        }
                    } else if (FIELD_ALLERGY_FOOD.equals(code) || FIELD_ALLERGY_DRUG.equals(code)) {
                        String want =
                                FIELD_ALLERGY_FOOD.equals(code)
                                        ? AllergyCategoryEnum.FOOD.name()
                                        : AllergyCategoryEnum.DRUG.name();
                        ArrayNode arr = allergyPrefillArray(allergySummary, want);
                        if (!arr.isEmpty()) {
                            cell.set("value", arr);
                            cell.put("sourceBizCode", "ALLERGY");
                            out.set(code, cell);
                        }
                    }
                }
                default -> {
                    /* goal/consent 等：保留上面「上次答卷」预填 */
                }
            }
        }
        return out;
    }

    private void seedLastSubmittedAnswers(
            ObjectNode out, String tenantId, String peopleId, String purpose, Set<String> fields) {
        // 按提交时间倒序；跳过「已采集」占位，让更早的真实作答顶上
        List<QuestionnaireResponse> rows = responseMapper.listSubmitted(tenantId, peopleId, purpose, 30);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        for (QuestionnaireResponse last : rows) {
            ObjectNode answers = resolveSubmittedAnswers(last);
            Iterator<Map.Entry<String, JsonNode>> it = answers.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String code = e.getKey();
                if (!fields.contains(code)
                        || ArchiveFields.isEmpty(e.getValue())
                        || isMaskedPlaceholder(e.getValue())) {
                    continue;
                }
                if (out.has(code) && !isMaskedPrefillCell(out.get(code))) {
                    continue; // 已有更新的真实值
                }
                try {
                    if (FieldStore.of(code).responseOnly()) {
                        continue;
                    }
                } catch (IllegalArgumentException ignored) {
                    // 未知前缀仍可作答内预填
                }
                ObjectNode cell = JsonUtils.emptyObject();
                cell.set("value", e.getValue());
                cell.put("sourceBizCode", "INTAKE_QUESTIONNAIRE");
                cell.put("sourceRefId", last.getId());
                if (last.getSubmittedAt() != null) {
                    cell.put("gmtModified", last.getSubmittedAt().toString());
                }
                out.set(code, cell);
            }
        }
    }

    private static ObjectNode resolveSubmittedAnswers(QuestionnaireResponse row) {
        JsonNode snap = JsonUtils.readTree(row.getSnapshotJson() == null ? "null" : row.getSnapshotJson());
        if (snap != null && snap.isObject() && snap.path("answers").isObject()) {
            return (ObjectNode) snap.get("answers");
        }
        return asObject(row.getAnswersJson());
    }

    private static boolean answersBlank(ObjectNode answers) {
        if (answers == null || answers.isEmpty()) {
            return true;
        }
        Iterator<Map.Entry<String, JsonNode>> it = answers.fields();
        while (it.hasNext()) {
            if (!ArchiveFields.isEmpty(it.next().getValue())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isMaskedPlaceholder(JsonNode value) {
        if (value == null || value.isNull() || !value.isTextual()) {
            return false;
        }
        return SensitiveFieldMask.MASKED.equals(value.asText());
    }

    private static boolean isMaskedPrefillCell(JsonNode cell) {
        if (cell == null || !cell.isObject()) {
            return isMaskedPlaceholder(cell);
        }
        return isMaskedPlaceholder(cell.get("value"));
    }

    /**
     * 详情/读接口：把答卷里被脱敏写脏的「已采集」替换为同客户历史答卷中最近一次真实值。
     */
    private ObjectNode healMaskedAnswers(String tenantId, String peopleId, ObjectNode answers) {
        if (answers == null || answers.isEmpty()) {
            return answers;
        }
        List<String> maskedCodes = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> it = answers.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (isMaskedPlaceholder(e.getValue())) {
                maskedCodes.add(e.getKey());
            }
        }
        if (maskedCodes.isEmpty()) {
            return answers;
        }
        Map<String, JsonNode> recovered = new LinkedHashMap<>();
        for (String purpose : List.of(PURPOSE_INTAKE, PURPOSE_SUPPLEMENT)) {
            List<QuestionnaireResponse> rows = responseMapper.listSubmitted(tenantId, peopleId, purpose, 30);
            if (rows == null) {
                continue;
            }
            for (QuestionnaireResponse hist : rows) {
                ObjectNode histAnswers = resolveSubmittedAnswers(hist);
                for (String code : maskedCodes) {
                    if (recovered.containsKey(code)) {
                        continue;
                    }
                    JsonNode v = histAnswers.get(code);
                    if (!ArchiveFields.isEmpty(v) && !isMaskedPlaceholder(v)) {
                        recovered.put(code, v);
                    }
                }
            }
        }
        if (recovered.isEmpty()) {
            return answers;
        }
        ObjectNode healed = answers.deepCopy();
        for (Map.Entry<String, JsonNode> e : recovered.entrySet()) {
            healed.set(e.getKey(), e.getValue());
        }
        return healed;
    }

    private ArrayNode allergyPrefillArray(AllergySummaryDto summary, String category) {
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        if (summary == null || summary.items() == null) {
            return arr;
        }
        for (AllergyItemDto m : summary.items()) {
            if (m == null || !category.equals(m.category()) || !StringUtils.hasText(m.allergenName())) {
                continue;
            }
            ObjectNode o = JsonUtils.emptyObject();
            o.put("id", m.id());
            o.put("category", m.category());
            if (StringUtils.hasText(m.allergenCode())) {
                o.put("allergenCode", m.allergenCode());
            }
            o.put("allergenName", m.allergenName());
            if (StringUtils.hasText(m.allergyType())) {
                o.put("allergyType", m.allergyType());
            }
            ArrayNode reactions = o.putArray("reactions");
            if (m.reactions() != null) {
                for (String r : m.reactions()) {
                    if (StringUtils.hasText(r)) {
                        reactions.add(r);
                    }
                }
            }
            if (StringUtils.hasText(m.reactionNote())) {
                o.put("reactionNote", m.reactionNote());
            }
            if (StringUtils.hasText(m.severity())) {
                o.put("severity", m.severity());
            }
            if (StringUtils.hasText(m.clinicalStatus())) {
                o.put("clinicalStatus", m.clinicalStatus());
            }
            if (StringUtils.hasText(m.verification())) {
                o.put("verification", m.verification());
            }
            if (StringUtils.hasText(m.onsetText())) {
                o.put("onsetText", m.onsetText());
            }
            if (m.lastOccurrence() != null) {
                o.put("lastOccurrence", m.lastOccurrence().toString());
            }
            if (StringUtils.hasText(m.note())) {
                o.put("note", m.note());
            }
            arr.add(o);
        }
        return arr;
    }

    private ArrayNode medicationPrefillArray(String tenantId, String peopleId) {
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        List<MedicationViewDto> rows =
                medicationService.listForPatient(tenantId, peopleId, MedicationStatusEnum.ACTIVE.name());
        for (MedicationViewDto m : rows) {
            if (m == null || !StringUtils.hasText(m.getDrugName())) {
                continue;
            }
            ObjectNode o = JsonUtils.emptyObject();
            o.put("id", m.getId());
            o.put("drugName", m.getDrugName());
            if (StringUtils.hasText(m.getDrugCode())) {
                o.put("drugCode", m.getDrugCode());
            }
            if (StringUtils.hasText(m.getUsageMethod())) {
                o.put("usageMethod", m.getUsageMethod());
            }
            if (StringUtils.hasText(m.getFrequency())) {
                o.put("frequency", m.getFrequency());
            }
            if (StringUtils.hasText(m.getDoseAmount())) {
                o.put("doseAmount", m.getDoseAmount());
            }
            if (StringUtils.hasText(m.getDoseUnit())) {
                o.put("doseUnit", m.getDoseUnit());
            }
            if (StringUtils.hasText(m.getTimingNote())) {
                o.put("timingNote", m.getTimingNote());
            }
            if (m.getStartDate() != null) {
                o.put("startDate", m.getStartDate().toString());
            }
            if (m.getStopDate() != null) {
                o.put("stopDate", m.getStopDate().toString());
            }
            if (m.getCourseDays() != null && m.getCourseDays() > 0) {
                o.put("courseDays", m.getCourseDays());
                o.put("courseMode", "days");
            } else {
                o.put("courseMode", "long");
            }
            o.put("hasAdverseReaction", Boolean.TRUE.equals(m.getHasAdverseReaction()));
            if (StringUtils.hasText(m.getRemark())) {
                o.put("remark", m.getRemark());
            }
            if (m.getSuggestedSlots() != null && !m.getSuggestedSlots().isEmpty() && m.isSlotsCustom()) {
                ArrayNode slots = o.putArray("slots");
                for (String s : m.getSuggestedSlots()) {
                    slots.add(s);
                }
            }
            arr.add(o);
        }
        return arr;
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
        for (ModuleItem item : catalog.itemsOf(q, lookup)) {
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
            if (ref.showIf() != null && !ref.showIf().matches(lookup)) {
                continue;
            }
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
        for (ModuleItem item : catalog.itemsOf(q, lookup)) {
            if (item.showIf() != null && !item.showIf().matches(lookup)) {
                continue;
            }
            JsonNode answer = lookup.apply(item.field());
            for (HighlightRule rule : item.highlights()) {
                if (rule.matches(answer)) {
                    ObjectNode hit = JsonUtils.emptyObject();
                    hit.put("field", item.field());
                    if (StringUtils.hasText(item.label())) {
                        hit.put("label", item.label());
                    }
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
        ObjectNode answersNode = asObject(row.getAnswersJson());
        answersNode = healMaskedAnswers(row.getTenantId(), row.getPeopleId(), answersNode);
        v.setPrefill(JsonUtils.readTree(row.getPrefillJson() == null ? "{}" : row.getPrefillJson()));
        v.setAnswers(answersNode);
        v.setHighlights(JsonUtils.readTree(row.getHighlightsJson() == null ? "[]" : row.getHighlightsJson()));
        v.setCompleteness(JsonUtils.readTree(row.getCompletenessJson() == null ? "{}" : row.getCompletenessJson()));
        v.setChanges(JsonUtils.readTree(row.getChangesJson() == null ? "{}" : row.getChangesJson()));
        JsonNode snapshotNode = JsonUtils.readTree(row.getSnapshotJson() == null ? "null" : row.getSnapshotJson());
        if (snapshotNode != null && snapshotNode.isObject() && snapshotNode.path("answers").isObject()) {
            ObjectNode snapObj = (ObjectNode) snapshotNode.deepCopy();
            ObjectNode snapAnswers =
                    healMaskedAnswers(row.getTenantId(), row.getPeopleId(), (ObjectNode) snapObj.get("answers"));
            snapObj.set("answers", snapAnswers);
            v.setSnapshot(snapObj);
        } else {
            v.setSnapshot(snapshotNode);
        }
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
                if (ref.showIf() != null) {
                    mv.setShowIf(JsonUtils.mapper().valueToTree(showIfToMap(ref.showIf())));
                }
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
                        iv.setMin(def.min());
                        iv.setMax(def.max());
                        if (def.exclusive() != null && !def.exclusive().isEmpty()) {
                            iv.setExclusive(def.exclusive());
                        }
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
        if (s.all() != null) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (ShowIf c : s.all()) {
                kids.add(showIfToMap(c));
            }
            m.put("all", kids);
            return m;
        }
        if (s.any() != null) {
            List<Map<String, Object>> kids = new ArrayList<>();
            for (ShowIf c : s.any()) {
                kids.add(showIfToMap(c));
            }
            m.put("any", kids);
            return m;
        }
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
