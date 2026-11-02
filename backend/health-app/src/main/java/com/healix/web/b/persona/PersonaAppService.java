package com.healix.web.b.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.persona.PersonaLlmGenerator;
import com.healix.agent.persona.PersonaLlmGenerator.PersonaNarrativeDraft;
import com.healix.agent.support.AiUsageGuard;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.persona.domain.PeoplePersona;
import com.healix.core.persona.dto.PersonaView;
import com.healix.core.persona.service.PersonaService;
import com.healix.core.persona.support.PersonaTemplateSupport;
import com.healix.core.questionnaire.domain.QuestionnaireResponse;
import com.healix.core.questionnaire.mapper.QuestionnaireResponseMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** B 端用户画像编排：维度聚合 + LLM 叙事 + 首诊异步钩子。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaAppService {

    private final PersonaService personaService;
    private final PersonaLlmGenerator personaLlmGenerator;
    private final AiUsageGuard aiUsageGuard;
    private final QuestionnaireResponseMapper questionnaireResponseMapper;

    public PersonaView get(String tenantId, String orgId, String peopleId) {
        return personaService.get(tenantId, orgId, peopleId);
    }

    public PersonaView generate(
            String tenantId, String orgId, String peopleId, String staffId, String accountId) {
        PeoplePersona existing = personaService.findRow(tenantId, peopleId);
        if (personaService.isStaffLocked(existing)) {
            throw new BusinessException(400, "叙事已锁定，请先解锁再生成");
        }
        personaService.markGenerating(tenantId, peopleId);
        ObjectNode dimensions = personaService.aggregateDimensions(tenantId, orgId, peopleId);
        try {
            ObjectNode narrative = runLlmOrTemplate(tenantId, peopleId, dimensions);
            boolean template = "template".equals(narrative.path("model").asText());
            return personaService.persistGenerateResult(
                    tenantId, orgId, peopleId, dimensions, narrative, staffId, accountId, template);
        } catch (RuntimeException e) {
            ObjectNode template = PersonaTemplateSupport.templateNarrative(dimensions);
            personaService.markFailed(tenantId, peopleId, template, dimensions);
            if (e instanceof BusinessException be && be.getCode() == 409) {
                throw e;
            }
            log.warn("[Persona] generate failed peopleId={}: {}", peopleId, e.getMessage());
            return personaService.get(tenantId, orgId, peopleId);
        }
    }

    public PersonaView refresh(
            String tenantId, String orgId, String peopleId, String staffId, String accountId) {
        ObjectNode dimensions = personaService.aggregateDimensions(tenantId, orgId, peopleId);
        PeoplePersona row = personaService.findRow(tenantId, peopleId);
        boolean locked = personaService.isStaffLocked(row);
        if (locked) {
            return personaService.persistRefresh(
                    tenantId, orgId, peopleId, dimensions, null, true, staffId, accountId);
        }
        personaService.markGenerating(tenantId, peopleId);
        try {
            ObjectNode narrative = runLlmOrTemplate(tenantId, peopleId, dimensions);
            return personaService.persistRefresh(
                    tenantId, orgId, peopleId, dimensions, narrative, false, staffId, accountId);
        } catch (RuntimeException e) {
            ObjectNode template = PersonaTemplateSupport.templateNarrative(dimensions);
            personaService.markFailed(tenantId, peopleId, template, dimensions);
            log.warn("[Persona] refresh failed peopleId={}: {}", peopleId, e.getMessage());
            return personaService.get(tenantId, orgId, peopleId);
        }
    }

    public PersonaView saveNarrative(
            String tenantId,
            String orgId,
            String peopleId,
            String headline,
            String narrative,
            JsonNode watchPoints,
            String staffId,
            String accountId) {
        return personaService.saveNarrative(
                tenantId, orgId, peopleId, headline, narrative, watchPoints, staffId, accountId);
    }

    public PersonaView unlock(
            String tenantId,
            String orgId,
            String peopleId,
            boolean refreshAfter,
            String staffId,
            String accountId) {
        personaService.unlock(tenantId, peopleId, staffId, accountId);
        if (refreshAfter) {
            return refresh(tenantId, orgId, peopleId, staffId, accountId);
        }
        return personaService.get(tenantId, orgId, peopleId);
    }

    /** 首诊/待补提交后异步：持久化维度；未锁则生成叙事。 */
    public void scheduleAfterIntakeSubmit(
            String tenantId, String orgId, String peopleId, String staffId, String accountId) {
        CompletableFuture.runAsync(() -> {
            try {
                afterIntakeSubmit(tenantId, orgId, peopleId, staffId, accountId);
            } catch (Exception e) {
                log.warn("[Persona] after intake failed peopleId={}: {}", peopleId, e.getMessage());
            }
        });
    }

    void afterIntakeSubmit(
            String tenantId, String orgId, String peopleId, String staffId, String accountId) {
        PeoplePersona row = personaService.findRow(tenantId, peopleId);
        if (personaService.isStaffLocked(row)) {
            ObjectNode dimensions = personaService.aggregateDimensions(tenantId, orgId, peopleId);
            personaService.persistRefresh(
                    tenantId, orgId, peopleId, dimensions, null, true, staffId, accountId);
            return;
        }
        try {
            generate(tenantId, orgId, peopleId, staffId, accountId);
        } catch (BusinessException e) {
            if (e.getCode() == 409) {
                log.info("[Persona] skip intake generate, already GENERATING peopleId={}", peopleId);
            }
        }
    }

    private ObjectNode runLlmOrTemplate(String tenantId, String peopleId, ObjectNode dimensions) {
        try {
            aiUsageGuard.check(tenantId, FeatureFlagKeyEnum.AI_STAFF_COPILOT, QuotaKeyEnum.AI_CALL_MONTHLY);
        } catch (BusinessException e) {
            return PersonaTemplateSupport.templateNarrative(dimensions);
        }
        boolean intakeDone = dimensions != null && dimensions.path("goals").path("fromIntake").asBoolean(false);
        String intakeSummary = loadLatestIntakeAiSummary(tenantId, peopleId);
        if (intakeDone) {
            intakeSummary = scrubIncompleteIntakeWording(intakeSummary);
        }
        PersonaNarrativeDraft draft = personaLlmGenerator.generate(dimensions, intakeSummary);
        return draft.narrative();
    }

    private String loadLatestIntakeAiSummary(String tenantId, String peopleId) {
        try {
            List<QuestionnaireResponse> rows = questionnaireResponseMapper.listByPeople(tenantId, peopleId);
            for (QuestionnaireResponse r : rows) {
                if (!"SUBMITTED".equals(r.getStatus())) {
                    continue;
                }
                if (!"INTAKE".equals(r.getPurpose()) && !"SUPPLEMENT".equals(r.getPurpose())) {
                    continue;
                }
                if (!StringUtils.hasText(r.getAnswersJson())) {
                    return null;
                }
                JsonNode answers = JsonUtils.readTree(r.getAnswersJson());
                JsonNode summary = answers.path("staff.aiSummary");
                if (summary.isTextual()) {
                    return summary.asText();
                }
                if (summary.isObject()) {
                    return summary.path("text").asText(null);
                }
                return null;
            }
        } catch (Exception e) {
            log.debug("[Persona] load intake aiSummary failed: {}", e.getMessage());
        }
        return null;
    }

    /** 已提交首诊后，去掉案头总结里过时的「尚未完成」措辞，避免画像 LLM 照抄。 */
    static String scrubIncompleteIntakeWording(String summary) {
        if (!StringUtils.hasText(summary)) {
            return summary;
        }
        return summary
                .replace("入组当日尚未完成首诊", "已完成首诊")
                .replace("尚未完成首诊问卷", "已完成首诊")
                .replace("尚未完成首诊", "已完成首诊")
                .replace("首诊未完成", "已完成首诊");
    }
}
