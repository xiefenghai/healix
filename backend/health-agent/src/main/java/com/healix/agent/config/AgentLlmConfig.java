package com.healix.agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentLlmConfig {

    @Bean(name = "visionChatModel")
    @ConditionalOnExpression(
            """
            !'${healix.agent.vision.api-key:}'.isBlank()
            && !'${healix.agent.vision.api-key:}'.contains('placeholder')
            && !'${healix.agent.vision.base-url:}'.isBlank()
            && !'${healix.agent.vision.model:}'.isBlank()
            """)
    public ChatModel visionChatModel(
            @Value("${healix.agent.vision.api-key}") String apiKey,
            @Value("${healix.agent.vision.base-url}") String baseUrl,
            @Value("${healix.agent.vision.model}") String model) {
        OpenAiApi api = OpenAiApi.builder().apiKey(apiKey).baseUrl(baseUrl).build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
                        .temperature(0.1)
                        .maxTokens(8192)
                        .build())
                .build();
    }
}
