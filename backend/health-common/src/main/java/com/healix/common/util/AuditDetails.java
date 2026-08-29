package com.healix.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 审计日志 detail_json 序列化，避免手写 JSON 在字符串 ID 场景下格式错误。 */
public final class AuditDetails {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditDetails() {
    }

    public static Map<String, Object> map() {
        return new LinkedHashMap<>();
    }

    public static String toJson(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("failed to serialize audit detail", e);
        }
    }

    public static String of(Object... keyValues) {
        if (keyValues.length == 0) {
            return null;
        }
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be key/value pairs");
        }
        Map<String, Object> detail = map();
        for (int i = 0; i < keyValues.length; i += 2) {
            detail.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return toJson(detail);
    }
}
