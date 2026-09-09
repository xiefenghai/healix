package com.healix.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek Chat Completions 直连客户端，支持关闭 thinking 并记录原始响应。
 * 用于 V4 视觉模型 OCR：Spring AI 默认会把 token 预算耗尽在 reasoning_content 上。
 */
@Slf4j
@Component
@ConditionalOnExpression(
        """
        !'${healix.agent.vision.api-key:}'.isBlank()
        && !'${healix.agent.vision.api-key:}'.contains('placeholder')
        && !'${healix.agent.vision.base-url:}'.isBlank()
        && !'${healix.agent.vision.model:}'.isBlank()
        """)
public class DeepSeekChatCompletionsClient {

    private final RestClient restClient;
    private final String model;
    private final int maxTokens;

    public DeepSeekChatCompletionsClient(
            @Value("${healix.agent.vision.api-key}") String apiKey,
            @Value("${healix.agent.vision.base-url}") String baseUrl,
            @Value("${healix.agent.vision.model}") String model,
            @Value("${healix.agent.max-tokens:8192}") int maxTokens) {
        this.model = model;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder()
                .baseUrl(normalizeBaseUrl(baseUrl))
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public VisionChatResult chatWithImage(
            String scene, String systemPrompt, String userPrompt, String imageDataUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", maxTokens);
        body.put("thinking", Map.of("type", "disabled"));
        body.put("messages", buildMessages(systemPrompt, userPrompt, imageDataUrl));

        long started = System.currentTimeMillis();
        log.info(
                "[DeepSeek] start scene={} model={} systemChars={} userChars={} maxTokens={}",
                scene,
                model,
                length(systemPrompt),
                length(userPrompt),
                maxTokens);
        String raw = restClient
                .post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(String.class);

        VisionChatResult result = parseResponse(raw);
        log.info(
                "[DeepSeek] success scene={} model={} elapsedMs={} finishReason={} contentChars={} reasoningChars={} promptTokens={} completionTokens={}",
                scene,
                model,
                System.currentTimeMillis() - started,
                result.finishReason(),
                length(result.content()),
                length(result.reasoningContent()),
                result.promptTokens(),
                result.completionTokens());
        log.debug("[DeepSeek] scene={} rawResponse={}", scene, truncate(raw, 6000));
        if (!StringUtils.hasText(result.content())) {
            log.warn(
                    "[DeepSeek] scene={} empty content finishReason={} reasoningPreview={}",
                    scene,
                    result.finishReason(),
                    truncate(result.reasoningContent(), 800));
        }
        return result;
    }

    private static List<Map<String, Object>> buildMessages(
            String systemPrompt, String userPrompt, String imageDataUrl) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", userPrompt));
        userContent.add(Map.of("type", "image_url", "image_url", Map.of("url", imageDataUrl)));
        messages.add(Map.of("role", "user", "content", userContent));
        return messages;
    }

    private VisionChatResult parseResponse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return VisionChatResult.empty("empty_http_body");
        }
        try {
            JsonNode root = JsonUtils.readTree(raw);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = textOrNull(message.get("content"));
            String reasoning = textOrNull(message.get("reasoning_content"));
            String finishReason = textOrNull(choice.get("finish_reason"));
            Integer promptTokens = intOrNull(root.path("usage").get("prompt_tokens"));
            Integer completionTokens = intOrNull(root.path("usage").get("completion_tokens"));
            return new VisionChatResult(content, reasoning, finishReason, promptTokens, completionTokens, raw);
        } catch (Exception e) {
            log.warn("[DeepSeek] parse response failed: {}", e.getMessage());
            return VisionChatResult.empty("parse_error");
        }
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl.trim();
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (!url.endsWith("/v1")) {
            url = url + "/v1";
        }
        return url;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return StringUtils.hasText(text) ? text : null;
    }

    private static Integer intOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...(truncated)";
    }

    public record VisionChatResult(
            String content,
            String reasoningContent,
            String finishReason,
            Integer promptTokens,
            Integer completionTokens,
            String rawBody) {

        static VisionChatResult empty(String finishReason) {
            return new VisionChatResult(null, null, finishReason, null, null, null);
        }

        boolean hasContent() {
            return StringUtils.hasText(content);
        }
    }
}
