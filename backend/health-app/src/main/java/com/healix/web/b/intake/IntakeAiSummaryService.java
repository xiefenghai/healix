package com.healix.web.b.intake;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.followup.FollowupSummaryGenerator;
import com.healix.agent.followup.FollowupSummaryGenerator.FollowupSummaryDraft;
import com.healix.agent.support.AiUsageGuard;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.field.ArchiveFields;
import com.healix.core.field.FieldCatalog;
import com.healix.core.field.FieldDef;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.questionnaire.QuestionnaireCatalog;
import com.healix.core.questionnaire.QuestionnaireDef;
import com.healix.core.questionnaire.dto.QuestionnaireResponseView;
import com.healix.core.questionnaire.service.QuestionnaireResponseService;
import com.healix.core.vitals.support.MetricPlausibility;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 首诊问卷 AI 总结：组装事实 → FollowupSummaryGenerator。 */
@Service
@RequiredArgsConstructor
public class IntakeAiSummaryService {

    public static final String FIELD_AI_SUMMARY = "staff.aiSummary";

    private final QuestionnaireResponseService responseService;
    private final QuestionnaireCatalog questionnaireCatalog;
    private final FieldCatalog fieldCatalog;
    private final PeopleProfileMapper peopleProfileMapper;
    private final FollowupSummaryGenerator followupSummaryGenerator;
    private final AiUsageGuard aiUsageGuard;

    public FollowupSummaryDraft generate(String responseId, String tenantId, JsonNode answersOverride) {
        aiUsageGuard.check(tenantId, FeatureFlagKeyEnum.AI_STAFF_COPILOT, QuotaKeyEnum.AI_CALL_MONTHLY);
        QuestionnaireResponseView view = responseService.get(responseId);
        if (!QuestionnaireResponseService.STATUS_DRAFT.equals(view.getStatus())
                && !QuestionnaireResponseService.STATUS_SUBMITTED.equals(view.getStatus())) {
            throw new BusinessException("作答状态不可生成总结");
        }
        JsonNode answers =
                answersOverride != null && answersOverride.isObject() ? answersOverride : view.getAnswers();
        QuestionnaireDef q =
                questionnaireCatalog.requireQuestionnaire(view.getQuestionnaireCode(), view.getQuestionnaireVersion());
        ObjectNode visible = answers != null && answers.isObject()
                ? (ObjectNode) answers.deepCopy()
                : JsonUtils.emptyObject();
        questionnaireCatalog.dropHiddenAnswers(q, visible);
        ObjectNode facts = buildFacts(view.getPeopleId(), visible, visibleHighlights(q, view.getHighlights(), visible));
        return followupSummaryGenerator.generate("INTAKE", facts);
    }

    /** 高亮也按当前管理方向裁掉，避免总结仍引用已取消模块的阳性指标。 */
    private JsonNode visibleHighlights(QuestionnaireDef q, JsonNode highlights, ObjectNode answers) {
        if (highlights == null || !highlights.isArray()) {
            return highlights;
        }
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        for (JsonNode hit : highlights) {
            String field = hit.path("field").asText("");
            if (!StringUtils.hasText(field) || questionnaireCatalog.fieldVisible(q, field, answers::get)) {
                out.add(hit);
            }
        }
        return out;
    }

    private ObjectNode buildFacts(String peopleId, JsonNode answers, JsonNode highlights) {
        ObjectNode facts = JsonUtils.emptyObject();
        facts.put("purpose", "INTAKE");
        // 档案作兜底；问卷 answers 里的姓名/性别/生日优先，避免总结与变更对照各说各话
        ObjectNode patient = JsonUtils.emptyObject();
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        if (profile != null) {
            if (StringUtils.hasText(profile.getDisplayName())) {
                patient.put("displayName", profile.getDisplayName());
            }
            if (StringUtils.hasText(profile.getGender())) {
                patient.put("gender", profile.getGender());
            }
            if (profile.getBirthday() != null) {
                patient.put("birthday", profile.getBirthday().toString());
            }
        }
        overlayPatientFromAnswers(patient, answers);
        if (!patient.isEmpty()) {
            facts.set("patient", patient);
        }
        boolean appealCollected = answers != null
                && answers.has("goal.primaryAppeal")
                && !ArchiveFields.isEmpty(answers.get("goal.primaryAppeal"));
        facts.put("appealCollected", appealCollected);
        if (!appealCollected) {
            facts.put("appealNote", "患者主观诉求未采集全。管理方向若来自疾病或健管师勾选，不得写成核心诉求。");
        }
        List<MetricPlausibility.Hit> suspects = MetricPlausibility.assessIntakeAnswers(answers);
        Map<String, MetricPlausibility.Hit> suspectByField = new HashMap<>();
        ArrayNode suspectArr = facts.putArray("suspectMetrics");
        for (MetricPlausibility.Hit hit : suspects) {
            suspectByField.put(hit.field(), hit);
            ObjectNode s = suspectArr.addObject();
            s.put("field", hit.field());
            s.put("metricType", hit.metricType());
            s.put("label", hit.label());
            s.put("reason", hit.reason());
        }
        if (suspectArr.isEmpty()) {
            facts.remove("suspectMetrics");
        }

        ArrayNode answersArr = facts.putArray("answers");
        if (answers != null && answers.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = answers.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String code = e.getKey();
                if (FIELD_AI_SUMMARY.equals(code)) {
                    continue;
                }
                JsonNode value = e.getValue();
                if (ArchiveFields.isEmpty(value)) {
                    continue;
                }
                ObjectNode row = answersArr.addObject();
                row.put("field", code);
                FieldDef def = fieldCatalog.find(code).orElse(null);
                row.put("label", def != null && StringUtils.hasText(def.label()) ? def.label() : code);
                row.set("value", value);
                MetricPlausibility.Hit hit = suspectByField.get(code);
                if (hit != null) {
                    row.put("suspect", true);
                    row.put("suspectReason", hit.reason());
                }
            }
        }
        putClinicalAlerts(facts, patient, answers);
        if (highlights != null && highlights.isArray() && !highlights.isEmpty()) {
            facts.set("highlights", highlights);
        }
        return facts;
    }

    /** 青少年/青年合并 T1+慢病等 → 结构化极高危，供模型拉满预警级别。 */
    private static void putClinicalAlerts(ObjectNode facts, ObjectNode patient, JsonNode answers) {
        if (facts == null) {
            return;
        }
        ArrayNode alerts = facts.putArray("clinicalAlerts");
        Integer age = ageYears(patient != null ? patient.path("birthday").asText(null) : null);
        boolean young = age != null && age < 35;
        boolean t1 = textContains(answers, "disease.diabetes.type", "T1")
                || textContains(answers, "disease.diabetes.type", "1型");
        boolean htn = codeIntersects(answers, "basic.presentIllness", "HYPERTENSION")
                || codeIntersects(answers, "goal.careTracks", "HYPERTENSION");
        boolean onInsulin = codeIntersects(answers, "basic.medicationFlags", "INSULIN")
                || codeIntersects(answers, "basic.medicationFlags", "HYPOGLYCEMIC")
                || textContains(answers, "medication.current", "胰岛素");
        if (young && t1 && htn) {
            ObjectNode a = alerts.addObject();
            a.put("level", "EXTREME");
            a.put(
                    "message",
                    "极高危提示：患者为 "
                            + age
                            + " 岁青年，已患 T1 糖尿病且合并高血压"
                            + (onInsulin ? "，且在用胰岛素/降糖药" : "")
                            + "。存在极高心血管及低血糖风险，方案须极度保守并严密监测。");
        } else if (young && t1) {
            ObjectNode a = alerts.addObject();
            a.put("level", "HIGH");
            a.put(
                    "message",
                    "高危提示："
                            + age
                            + " 岁青年已患 T1 糖尿病"
                            + (onInsulin ? "且使用胰岛素" : "")
                            + "，运动/饮食方案须防低血糖，强度取保守。");
        }
        if (alerts.isEmpty()) {
            facts.remove("clinicalAlerts");
        }
    }

    private static Integer ageYears(String birthday) {
        if (!StringUtils.hasText(birthday) || birthday.length() < 10) {
            return null;
        }
        try {
            LocalDate birth = LocalDate.parse(birthday.substring(0, 10));
            if (birth.getYear() < 1900) {
                return null;
            }
            int age = Period.between(birth, LocalDate.now()).getYears();
            return age >= 0 && age <= 120 ? age : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean codeIntersects(JsonNode answers, String field, String code) {
        if (answers == null || !answers.isObject() || !StringUtils.hasText(code)) {
            return false;
        }
        JsonNode n = answers.get(field);
        if (n == null || n.isNull()) {
            return false;
        }
        String want = code.trim().toUpperCase();
        if (n.isArray()) {
            for (JsonNode x : n) {
                if (x != null && want.equalsIgnoreCase(x.asText("").trim())) {
                    return true;
                }
            }
            return false;
        }
        String raw = n.asText("").trim().toUpperCase();
        return raw.equals(want) || raw.contains("\"" + want + "\"") || raw.contains(want);
    }

    private static boolean textContains(JsonNode answers, String field, String needle) {
        if (answers == null || !answers.isObject() || !StringUtils.hasText(needle)) {
            return false;
        }
        JsonNode n = answers.get(field);
        if (n == null || n.isNull()) {
            return false;
        }
        return n.toString().toUpperCase().contains(needle.trim().toUpperCase());
    }

    /** 问卷草稿优先于档案，保证提交前确认里的 AI 总结与当前答案一致。 */
    private static void overlayPatientFromAnswers(ObjectNode patient, JsonNode answers) {
        if (patient == null || answers == null || !answers.isObject()) {
            return;
        }
        putAnswerText(patient, "displayName", answers.get("profile.displayName"));
        putAnswerText(patient, "gender", answers.get("profile.gender"));
        putAnswerText(patient, "birthday", answers.get("profile.birthday"));
    }

    private static void putAnswerText(ObjectNode patient, String key, JsonNode node) {
        if (node == null || node.isNull() || ArchiveFields.isEmpty(node)) {
            return;
        }
        String text = node.isValueNode() ? node.asText("").trim() : node.asText("").trim();
        if (StringUtils.hasText(text)) {
            patient.put(key, text);
        }
    }
}
