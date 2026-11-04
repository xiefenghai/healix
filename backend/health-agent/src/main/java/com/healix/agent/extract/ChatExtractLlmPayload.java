package com.healix.agent.extract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.math.BigDecimal;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 模型返回的原始 JSON。服务端会再校验，不直接入库。 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatExtractLlmPayload {
    private List<Item> items;
    private List<Ignored> ignored;

    /** 模型有时把档案放进 profile。并回 items，避免整段解析失败。 */
    public static ChatExtractLlmPayload read(String json) {
        JsonNode tree = JsonUtils.readTree(json);
        if (!(tree instanceof ObjectNode obj)) {
            throw new IllegalArgumentException("chat extract json");
        }
        JsonNode profile = obj.remove("profile");
        if (profile instanceof ArrayNode extra && !extra.isEmpty()) {
            ArrayNode items = obj.withArray("items");
            extra.forEach(items::add);
        }
        try {
            return JsonUtils.mapper().treeToValue(obj, ChatExtractLlmPayload.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("chat extract json", ex);
        }
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private String target;
        private String code;
        private JsonNode value;
        private BigDecimal systolic;
        private BigDecimal diastolic;
        private String unit;
        private String sourceUnit;
        private BigDecimal sourceValue;
        private String quote;
        private Boolean habit;
        private Boolean derived;
        private String mealContext;
        private String bpContext;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Ignored {
        private String quote;
        private String reason;
    }
}
