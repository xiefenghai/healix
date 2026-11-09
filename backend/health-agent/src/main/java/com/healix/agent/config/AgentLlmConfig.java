package com.healix.agent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * langchain4j OpenAI 兼容客户端（DeepSeek chat / streaming）。
 * 识图走 {@link com.healix.agent.llm.DeepSeekChatCompletionsClient}，不经本配置。
 */
@Configuration
public class AgentLlmConfig {

    private static final String KEY_READY =
            """
            !'${healix.agent.api-key:${DEEPSEEK_API_KEY:${spring.ai.openai.api-key:}}}'.isBlank()
            && !'${healix.agent.api-key:${DEEPSEEK_API_KEY:${spring.ai.openai.api-key:}}}'.contains('placeholder')
            """;

    @Bean(name = "healixChatModel")
    @ConditionalOnExpression(KEY_READY)
    public ChatLanguageModel healixChatModel(
            @Value("${healix.agent.api-key:${DEEPSEEK_API_KEY:${spring.ai.openai.api-key:}}}") String apiKey,
            @Value("${healix.agent.base-url:${DEEPSEEK_BASE_URL:${spring.ai.openai.base-url:https://api.deepseek.com}}}")
                    String baseUrl,
            @Value("${healix.agent.models.chat:${DEEPSEEK_CHAT_MODEL:deepseek-chat}}") String model,
            @Value("${healix.agent.max-tokens:8192}") int maxTokens,
            @Value("${spring.ai.openai.chat.options.temperature:0.3}") double temperature) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(normalizeBaseUrl(baseUrl))
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(90))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    @Bean(name = "healixStreamingChatModel")
    @ConditionalOnExpression(KEY_READY)
    public StreamingChatLanguageModel healixStreamingChatModel(
            @Value("${healix.agent.api-key:${DEEPSEEK_API_KEY:${spring.ai.openai.api-key:}}}") String apiKey,
            @Value("${healix.agent.base-url:${DEEPSEEK_BASE_URL:${spring.ai.openai.base-url:https://api.deepseek.com}}}")
                    String baseUrl,
            @Value("${healix.agent.models.chat:${DEEPSEEK_CHAT_MODEL:deepseek-chat}}") String model,
            @Value("${healix.agent.max-tokens:8192}") int maxTokens,
            @Value("${spring.ai.openai.chat.options.temperature:0.3}") double temperature) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(normalizeBaseUrl(baseUrl))
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(120))
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String u = baseUrl == null ? "" : baseUrl.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        // langchain4j OpenAI client 会拼 /chat/completions；DeepSeek 根通常已含或不含 /v1
        if (!u.endsWith("/v1")) {
            u = u + "/v1";
        }
        return u;
    }
}
