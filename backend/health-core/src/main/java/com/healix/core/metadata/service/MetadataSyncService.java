package com.healix.core.metadata.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.dict.dto.DictItemDto;
import com.healix.core.dict.service.DictService;
import com.healix.core.metadata.domain.PeopleMetadataInfo;
import com.healix.core.metadata.mapper.PeopleMetadataInfoMapper;
import com.healix.core.metadata.support.MetadataCodeResolver;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MetadataSyncService {

    private final PeopleMetadataInfoMapper metadataMapper;
    private final DictService dictService;

    public void syncBasicArchive(
            String tenantId, String peopleId, String contentJson, String sourceBizCode, String sourceClientCode) {
        syncFromDict(
                tenantId,
                peopleId,
                "basic.",
                dictService.listFieldsForBasicArchive(tenantId),
                contentJson,
                sourceBizCode,
                sourceClientCode);
    }

    public void syncDiseaseArchive(
            String tenantId,
            String peopleId,
            String diseaseCode,
            String contentJson,
            String sourceBizCode,
            String sourceClientCode) {
        syncFromDict(
                tenantId,
                peopleId,
                "disease." + diseaseCode + ".",
                dictService.listFieldsForDisease(tenantId, diseaseCode),
                contentJson,
                sourceBizCode,
                sourceClientCode);
    }

    private void syncFromDict(
            String tenantId,
            String peopleId,
            String codePrefix,
            List<DictItemDto> fields,
            String contentJson,
            String sourceBizCode,
            String sourceClientCode) {
        JsonNode root = JsonUtils.readTree(contentJson);
        Set<String> activeCodes = new HashSet<>();
        for (DictItemDto field : fields) {
            collectFieldMetadata(
                    tenantId,
                    peopleId,
                    field,
                    root.get(field.getDictCode()),
                    codePrefix,
                    activeCodes,
                    sourceBizCode,
                    sourceClientCode);
        }
        for (String existing : metadataMapper.listCodesByPrefix(peopleId, tenantId, codePrefix)) {
            if (!activeCodes.contains(existing)) {
                PeopleMetadataInfo row = metadataMapper.findByPeopleAndCode(peopleId, existing);
                if (row != null) {
                    metadataMapper.softDeleteById(row.getId());
                }
            }
        }
    }

    private void collectFieldMetadata(
            String tenantId,
            String peopleId,
            DictItemDto field,
            JsonNode valueNode,
            String codePrefix,
            Set<String> activeCodes,
            String sourceBizCode,
            String sourceClientCode) {
        JsonNode schema = JsonUtils.readTree(field.getContent());
        String metadataCode =
                MetadataCodeResolver.resolve(
                        schema.path("metadataCode").asText(null), codePrefix, field.getDictCode());
        String widget = schema.path("widget").asText("TEXT");
        if ("COMPOSITE".equals(widget)) {
            JsonNode composite = valueNode != null && valueNode.isObject() ? valueNode : JsonUtils.emptyObject();
            JsonNode subFields = schema.path("fields");
            if (subFields.isArray()) {
                for (JsonNode sub : subFields) {
                    String subCode = sub.path("code").asText();
                    String subWidget = sub.path("widget").asText("TEXT");
                    String subMetaCode = MetadataCodeResolver.compositeSub(metadataCode, subCode);
                    JsonNode subValue = composite.get(subCode);
                    if (isEmpty(subValue)) {
                        continue;
                    }
                    upsertMetadata(
                            tenantId,
                            peopleId,
                            subMetaCode,
                            wrapValue(subWidget, subValue, null),
                            activeCodes,
                            sourceBizCode,
                            sourceClientCode);
                }
            }
            return;
        }
        if (isEmpty(valueNode)) {
            return;
        }
        upsertMetadata(
                tenantId,
                peopleId,
                metadataCode,
                wrapValue(widget, valueNode, null),
                activeCodes,
                sourceBizCode,
                sourceClientCode);
    }

    private void upsertMetadata(
            String tenantId,
            String peopleId,
            String metadataCode,
            Map<String, Object> wrapped,
            Set<String> activeCodes,
            String sourceBizCode,
            String sourceClientCode) {
        activeCodes.add(metadataCode);
        String json = JsonUtils.toJson(wrapped);
        PeopleMetadataInfo existing = metadataMapper.findByPeopleAndCode(peopleId, metadataCode);
        if (existing == null) {
            PeopleMetadataInfo row = new PeopleMetadataInfo();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setMetadataCode(metadataCode);
            row.setMetadataValue(json);
            row.setSourceBizCode(sourceBizCode);
            row.setSourceClientCode(sourceClientCode);
            EntityMeta.onCreate(row);
            metadataMapper.insert(row);
        } else {
            existing.setMetadataValue(json);
            existing.setSourceBizCode(sourceBizCode);
            existing.setSourceClientCode(sourceClientCode);
            EntityMeta.onUpdate(existing);
            metadataMapper.updateValue(existing);
        }
    }

    private static Map<String, Object> wrapValue(String widget, JsonNode value, String display) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("widget", widget);
        if ("MULTI_SELECT".equals(widget) && value.isArray()) {
            map.put("value", JsonUtils.mapper().convertValue(value, List.class));
        } else if ("NUMBER".equals(widget)) {
            map.put("value", value.isNumber() ? value.numberValue() : value.asText());
        } else if (value.isTextual()) {
            map.put("value", value.asText());
        } else if (value.isArray()) {
            map.put("value", JsonUtils.mapper().convertValue(value, List.class));
        } else if (value.isObject()) {
            map.put("value", JsonUtils.mapper().convertValue(value, Map.class));
        } else if (value.isNull()) {
            map.put("value", null);
        } else {
            map.put("value", value.asText());
        }
        map.put("display", display);
        map.put("extra", null);
        return map;
    }

    private static boolean isEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return true;
        }
        if (node.isTextual()) {
            return node.asText().isBlank();
        }
        if (node.isArray()) {
            return node.isEmpty();
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                if (!isEmpty(it.next().getValue())) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
