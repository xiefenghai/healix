package com.healix.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Slf4j
@Component
public class AgentLlmStartupLogger {

    @Value("${healix.agent.enabled:false}")
    private boolean agentEnabled;

    @Value("${healix.agent.provider:deepseek}")
    private String provider;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String chatModel;

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Value("${healix.agent.vision.api-key:}")
    private String visionApiKey;

    @Value("${healix.agent.vision.model:}")
    private String visionModel;

    @EventListener(ApplicationReadyEvent.class)
    public void logAgentStatus() {
        boolean keyConfigured = StringUtils.hasText(apiKey) && !apiKey.contains("placeholder");
        boolean visionConfigured = StringUtils.hasText(visionApiKey);
        log.info(
                "[Agent] startup profiles={} llmEnabled={} provider={} model={} apiKeyConfigured={} visionConfigured={} visionModel={}",
                activeProfiles,
                agentEnabled,
                provider,
                chatModel,
                keyConfigured,
                visionConfigured,
                StringUtils.hasText(visionModel) ? visionModel : "-");
        if (!agentEnabled) {
            log.warn(
                    "[Agent] LLM 未启用：健管助手将使用模板生成。请设置 healix.agent.enabled=true 或启用 dev profile");
        } else if (!keyConfigured) {
            log.warn(
                    "[Agent] DeepSeek API Key 未配置：请在 application-local.yml 设置 spring.ai.openai.api-key");
        } else if (!visionConfigured) {
            log.warn(
                    "[Agent] OCR 识图未配置：请设置 API Key，并确认 healix.agent.vision.model=deepseek-v4-flash-vision-exp");
        }
    }
}
