package com.healix.core.assessment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentDisease;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.domain.PeopleAssessmentLatest;
import com.healix.core.assessment.domain.PeopleAssessmentSnapshot;
import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.mapper.PeopleAssessmentSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 给 Agent（管理方案 / 工作台对话）的评估摘要：只读最新结果表，不触发重算。 */
@Component
@RequiredArgsConstructor
public class AssessmentPromptContext {

    private static final int MAX_FACTORS = 6;

    private final AssessmentStoreMapper storeMapper;
    private final PeopleAssessmentSnapshotMapper snapshotMapper;

    public ArrayNode summarize(String tenantId, String peopleId) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        for (PeopleAssessmentLatest row : storeMapper.listLatest(tenantId, peopleId)) {
            if (!row.effective() || !AssessmentStatus.COMPLETE.name().equals(row.getStatus())) {
                continue;
            }
            AssessmentDisease disease = AssessmentDisease.ofCode(row.getDiseaseCode());
            AssessmentTier tier = AssessmentTier.parse(row.getTier());
            ObjectNode n = out.addObject();
            n.put("disease", disease == null ? row.getDiseaseCode() : disease.label());
            n.put("type", AssessmentCategory.labelOf(row.getCategory()));
            n.put("result", row.getLevelLabel());
            n.put("tier", tier == null ? null : tier.label());
            if (StringUtils.hasText(row.getSnapshotId())) {
                PeopleAssessmentSnapshot snap = snapshotMapper.findById(row.getSnapshotId());
                if (snap != null && StringUtils.hasText(snap.getResultJson())) {
                    JsonNode result = JsonUtils.readTree(snap.getResultJson());
                    ArrayNode factors = n.putArray("keyFactors");
                    for (JsonNode group : result.path("factorGroups")) {
                        for (JsonNode hit : group.path("hits")) {
                            if (factors.size() < MAX_FACTORS && hit.hasNonNull("label")) {
                                factors.add(hit.get("label").asText());
                            }
                        }
                    }
                }
            }
        }
        return out;
    }
}
