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
import java.util.Iterator;
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
            }
        }
        if (highlights != null && highlights.isArray() && !highlights.isEmpty()) {
            facts.set("highlights", highlights);
        }
        return facts;
    }
}
