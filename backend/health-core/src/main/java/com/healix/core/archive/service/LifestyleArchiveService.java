package com.healix.core.archive.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveViewDto;
import java.util.Iterator;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class LifestyleArchiveService {

    /** psych：C 端生活方式页整合「心理与情绪」轻量字段（如 stressLevel / mood） */
    private static final String[] LIFESTYLE_KEYS = {"diet", "exercise", "sleep", "lifestyle", "psych"};

    private final BasicArchiveService basicArchiveService;

    public ArchiveViewDto get(String tenantId, String peopleId) {
        ArchiveViewDto full = basicArchiveService.get(tenantId, peopleId);
        return sliceLifestyle(full);
    }

    @Transactional
    public ArchiveViewDto patch(
            String tenantId, String peopleId, int expectedVersion, String lifestylePatchJson) {
        ArchiveViewDto current = basicArchiveService.get(tenantId, peopleId);
        int version = current.getVersion() == null ? 0 : current.getVersion();
        if (expectedVersion != version) {
            throw new com.healix.common.exception.BusinessException(409, "档案版本冲突，请刷新后重试");
        }
        ObjectNode merged = JsonUtils.mapper().createObjectNode();
        JsonNode currentContent = JsonUtils.readTree(JsonUtils.toJson(current.getContentJson()));
        if (currentContent.isObject()) {
            merged.setAll((ObjectNode) currentContent);
        }
        JsonNode patch = JsonUtils.readTree(lifestylePatchJson);
        if (patch.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (!isLifestyleKey(e.getKey())) {
                    continue;
                }
                // psych 等嵌套对象做浅合并，避免 C 端轻量字段覆盖健管端已有明细
                if ("psych".equals(e.getKey())
                        && e.getValue() != null
                        && e.getValue().isObject()
                        && merged.has("psych")
                        && merged.get("psych").isObject()) {
                    ObjectNode psychNode = (ObjectNode) merged.get("psych");
                    psychNode.setAll((ObjectNode) e.getValue());
                } else {
                    merged.set(e.getKey(), e.getValue());
                }
            }
        }
        return basicArchiveService.save(
                tenantId,
                peopleId,
                version,
                JsonUtils.toJson(merged),
                ArchiveOperatorContext.people(peopleId));
    }

    private static ArchiveViewDto sliceLifestyle(ArchiveViewDto full) {
        ObjectNode slice = JsonUtils.mapper().createObjectNode();
        JsonNode content = JsonUtils.readTree(JsonUtils.toJson(full.getContentJson()));
        for (String key : LIFESTYLE_KEYS) {
            if (content.has(key)) {
                slice.set(key, content.get(key));
            }
        }
        ArchiveViewDto dto = new ArchiveViewDto();
        dto.setPeopleId(full.getPeopleId());
        dto.setTenantId(full.getTenantId());
        dto.setVersion(full.getVersion());
        dto.setSchemaVersion(full.getSchemaVersion());
        dto.setContentJson(slice);
        return dto;
    }

    private static boolean isLifestyleKey(String key) {
        for (String k : LIFESTYLE_KEYS) {
            if (k.equals(key)) {
                return true;
            }
        }
        return false;
    }
}
