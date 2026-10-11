package com.healix.agent.llm;

public record LlmResponse(
        String content, boolean fromLlm, Integer promptTokens, Integer completionTokens) {

    public static LlmResponse disabled() {
        return new LlmResponse(null, false, null, null);
    }

    public static LlmResponse of(String content) {
        return new LlmResponse(content, true, null, null);
    }
}
