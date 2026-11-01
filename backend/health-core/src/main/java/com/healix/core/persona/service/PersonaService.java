package com.healix.core.persona.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.common.util.JsonUtils;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.persona.domain.PeoplePersona;
import com.healix.core.persona.dto.PersonaView;
import com.healix.core.persona.mapper.PeoplePersonaMapper;
import com.healix.core.persona.support.PersonaTemplateSupport;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 用户画像读写与锁门闸（LLM 由上层注入叙事 JSON）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaService {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_GENERATING = "GENERATING";
    public static final String STATUS_FAILED = "FAILED";

    private final PeoplePersonaMapper personaMapper;
    private final PersonaDimensionAggregator aggregator;
    private final AuditService auditService;

    /** GET：新鲜维度不写库；带库存叙事。 */
    public PersonaView get(String tenantId, String orgId, String peopleId) {
        ObjectNode dimensions = aggregator.aggregate(tenantId, orgId, peopleId);
        PeoplePersona row = personaMapper.findByPeople(tenantId, peopleId);
        return toView(peopleId, dimensions, row, null);
    }

    public ObjectNode aggregateDimensions(String tenantId, String orgId, String peopleId) {
        return aggregator.aggregate(tenantId, orgId, peopleId);
    }

    public PeoplePersona findRow(String tenantId, String peopleId) {
        return personaMapper.findByPeople(tenantId, peopleId);
    }

    public boolean isStaffLocked(PeoplePersona row) {
        if (row == null || !StringUtils.hasText(row.getNarrativeJson())) {
            return false;
        }
        try {
            return JsonUtils.readTree(row.getNarrativeJson()).path("staffLocked").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 抢 GENERATING；无行则先插壳再抢。失败抛 409。 */
    @Transactional
    public PeoplePersona markGenerating(String tenantId, String peopleId) {
        PeoplePersona row = personaMapper.findByPeople(tenantId, peopleId);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new PeoplePersona();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setStatus(STATUS_READY);
            EntityMeta.onCreate(row);
            personaMapper.insert(row);
        }
        if (STATUS_GENERATING.equals(row.getStatus())) {
            throw new BusinessException(409, "画像叙事正在生成中，请稍候");
        }
        int n = personaMapper.tryMarkGenerating(row.getId(), now);
        if (n <= 0) {
            throw new BusinessException(409, "画像叙事正在生成中，请稍候");
        }
        row.setStatus(STATUS_GENERATING);
        row.setGmtModified(now);
        return row;
    }

    @Transactional
    public PersonaView persistGenerateResult(
            String tenantId,
            String orgId,
            String peopleId,
            ObjectNode dimensions,
            ObjectNode narrative,
            String staffId,
            String accountId,
            boolean fromTemplate) {
        PeoplePersona row = personaMapper.findByPeople(tenantId, peopleId);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new PeoplePersona();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            EntityMeta.onCreate(row);
            applyDimensions(row, dimensions, now);
            applyNarrative(row, narrative, dimensions.path("sourceFingerprint").asText(null), now, null);
            row.setStatus(STATUS_READY);
            personaMapper.insert(row);
        } else {
            applyDimensions(row, dimensions, now);
            applyNarrative(row, narrative, dimensions.path("sourceFingerprint").asText(null), now, null);
            row.setStatus(STATUS_READY);
            EntityMeta.onUpdate(row);
            personaMapper.update(row);
        }
        audit(
                accountId,
                tenantId,
                peopleId,
                AuditActionEnum.PERSONA_GENERATE,
                row.getId(),
                AuditDetails.of("fromTemplate", fromTemplate, "staffId", staffId));
        return toView(peopleId, dimensions, row, null);
    }

    @Transactional
    public PersonaView persistRefresh(
            String tenantId,
            String orgId,
            String peopleId,
            ObjectNode dimensions,
            ObjectNode narrativeOrNull,
            boolean skippedLocked,
            String staffId,
            String accountId) {
        PeoplePersona row = personaMapper.findByPeople(tenantId, peopleId);
        LocalDateTime now = LocalDateTime.now();
        if (row == null) {
            row = new PeoplePersona();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            EntityMeta.onCreate(row);
            applyDimensions(row, dimensions, now);
            if (narrativeOrNull != null) {
                applyNarrative(
                        row, narrativeOrNull, dimensions.path("sourceFingerprint").asText(null), now, null);
            }
            row.setStatus(STATUS_READY);
            personaMapper.insert(row);
        } else {
            applyDimensions(row, dimensions, now);
            if (narrativeOrNull != null) {
                applyNarrative(
                        row, narrativeOrNull, dimensions.path("sourceFingerprint").asText(null), now, null);
            }
            row.setStatus(STATUS_READY);
            EntityMeta.onUpdate(row);
            personaMapper.update(row);
        }
        audit(
                accountId,
                tenantId,
                peopleId,
                AuditActionEnum.PERSONA_REFRESH,
                row.getId(),
                AuditDetails.of("skippedLocked", skippedLocked, "staffId", staffId));
        return toView(peopleId, dimensions, row, skippedLocked);
    }

    @Transactional
    public void markFailed(String tenantId, String peopleId, ObjectNode templateNarrative, ObjectNode dimensions) {
        PeoplePersona row = personaMapper.findByPeople(tenantId, peopleId);
        if (row == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (dimensions != null) {
            applyDimensions(row, dimensions, now);
        }
        if (templateNarrative != null) {
            applyNarrative(
                    row,
                    templateNarrative,
                    dimensions != null ? dimensions.path("sourceFingerprint").asText(null) : row.getSourceFingerprint(),
                    now,
                    null);
            row.setStatus(STATUS_READY);
        } else {
            row.setStatus(STATUS_FAILED);
        }
        EntityMeta.onUpdate(row);
        personaMapper.update(row);
    }

    @Transactional
    public PersonaView saveNarrative(
            String tenantId,
            String orgId,
            String peopleId,
            String headline,
            String narrative,
            JsonNode watchPoints,
            String staffId,
            String accountId) {
        if (!StringUtils.hasText(headline) || !StringUtils.hasText(narrative)) {
            throw new BusinessException(400, "headline 与 narrative 不能为空");
        }
        ObjectNode dimensions = aggregator.aggregate(tenantId, orgId, peopleId);
        PeoplePersona row = personaMapper.findByPeople(tenantId, peopleId);
        LocalDateTime now = LocalDateTime.now();
        ObjectNode n = JsonUtils.emptyObject();
        n.put("headline", headline.trim());
        n.put("narrative", narrative.trim());
        if (watchPoints != null && watchPoints.isArray()) {
            n.set("watchPoints", watchPoints);
        } else {
            n.putArray("watchPoints");
        }
        n.put("model", "staff");
        n.put("staffLocked", true);
        n.put("editedByStaffId", staffId);
        n.put("editedAt", now.toString());
        n.put("generatedAt", now.toString());

        if (row == null) {
            row = new PeoplePersona();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            EntityMeta.onCreate(row);
            applyDimensions(row, dimensions, now);
            applyNarrative(row, n, dimensions.path("sourceFingerprint").asText(null), now, staffId);
            row.setStatus(STATUS_READY);
            personaMapper.insert(row);
        } else {
            applyNarrative(row, n, row.getSourceFingerprint(), now, staffId);
            row.setStatus(STATUS_READY);
            EntityMeta.onUpdate(row);
            personaMapper.update(row);
        }
        audit(
                accountId,
                tenantId,
                peopleId,
                AuditActionEnum.PERSONA_EDIT,
                row.getId(),
                AuditDetails.of("staffId", staffId));
        return toView(peopleId, dimensions, row, null);
    }

    @Transactional
    public PeoplePersona unlock(String tenantId, String peopleId, String staffId, String accountId) {
        PeoplePersona row = personaMapper.findByPeople(tenantId, peopleId);
        if (row == null || !StringUtils.hasText(row.getNarrativeJson())) {
            throw new BusinessException(400, "尚无叙事可解锁");
        }
        ObjectNode n = (ObjectNode) JsonUtils.readTree(row.getNarrativeJson());
        n.put("staffLocked", false);
        row.setNarrativeJson(JsonUtils.toJson(n));
        EntityMeta.onUpdate(row);
        personaMapper.update(row);
        audit(
                accountId,
                tenantId,
                peopleId,
                AuditActionEnum.PERSONA_UNLOCK,
                row.getId(),
                AuditDetails.of("staffId", staffId));
        return row;
    }

    private static void applyDimensions(PeoplePersona row, ObjectNode dimensions, LocalDateTime now) {
        row.setDimensionsJson(JsonUtils.toJson(dimensions));
        row.setSourceFingerprint(dimensions.path("sourceFingerprint").asText(null));
        row.setDimensionsAt(now);
    }

    private static void applyNarrative(
            PeoplePersona row, ObjectNode narrative, String fingerprint, LocalDateTime now, String editedBy) {
        ObjectNode n = narrative.deepCopy();
        if (!n.has("generatedAt")) {
            n.put("generatedAt", now.toString());
        }
        if (!n.has("staffLocked")) {
            n.put("staffLocked", false);
        }
        row.setNarrativeJson(JsonUtils.toJson(n));
        row.setNarrativeFingerprint(fingerprint);
        row.setGeneratedAt(now);
        if (StringUtils.hasText(editedBy)) {
            row.setEditedByStaffId(editedBy);
        }
    }

    private PersonaView toView(
            String peopleId, ObjectNode liveDimensions, PeoplePersona row, Boolean skippedLocked) {
        PersonaView v = new PersonaView();
        v.setPeopleId(peopleId);
        v.setDimensions(liveDimensions);
        v.setSourceFingerprint(liveDimensions.path("sourceFingerprint").asText(null));
        if (row == null) {
            v.setPersisted(false);
            v.setStatus(null);
            v.setNarrative(null);
            v.setDisplayHeadline(PersonaTemplateSupport.displayHeadline(liveDimensions));
            v.setGeneratedAt(null);
            v.setDimensionsAt(null);
        } else {
            v.setPersisted(true);
            v.setStatus(row.getStatus());
            v.setGeneratedAt(row.getGeneratedAt());
            v.setDimensionsAt(row.getDimensionsAt());
            JsonNode narrative = null;
            if (StringUtils.hasText(row.getNarrativeJson())) {
                try {
                    narrative = JsonUtils.readTree(row.getNarrativeJson());
                } catch (Exception e) {
                    log.warn("[Persona] bad narrative_json id={}", row.getId());
                }
            }
            v.setNarrative(narrative);
            if (narrative != null && StringUtils.hasText(narrative.path("headline").asText(null))) {
                v.setDisplayHeadline(narrative.path("headline").asText());
            } else {
                v.setDisplayHeadline(PersonaTemplateSupport.displayHeadline(liveDimensions));
            }
        }
        v.setNarrativeSkippedLocked(skippedLocked);
        return v;
    }

    private void audit(
            String accountId,
            String tenantId,
            String peopleId,
            AuditActionEnum action,
            String resourceId,
            String detailJson) {
        try {
            auditService.record(
                    "B",
                    accountId,
                    "STAFF",
                    tenantId,
                    action.name(),
                    "people_persona",
                    resourceId,
                    peopleId,
                    detailJson);
        } catch (Exception e) {
            log.warn("[Persona] audit failed action={}: {}", action, e.getMessage());
        }
    }
}
