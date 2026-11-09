package com.healix.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Value("${healix.agent.models.chat:deepseek-chat}")
    private String chatModel;

    @Value("${healix.agent.api-key:}")
    private String apiKey;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Value("${healix.agent.vision.api-key:}")
    private String visionApiKey;

    @Value("${healix.agent.vision.model:}")
    private String visionModel;

    @Autowired(required = false)
    private com.healix.agent.react.StaffCockpitAgent staffCockpitAgent;

    @EventListener(ApplicationReadyEvent.class)
    public void logAgentStatus() {
        boolean keyConfigured = StringUtils.hasText(apiKey) && !apiKey.contains("placeholder");
        boolean visionConfigured = StringUtils.hasText(visionApiKey);
        boolean reactReady = staffCockpitAgent != null;
        log.info(
                "[Agent] startup profiles={} llmEnabled={} provider={} model={} apiKeyConfigured={} visionConfigured={} visionModel={} reactEnabled={}",
                activeProfiles,
                agentEnabled,
                provider,
                chatModel,
                keyConfigured,
                visionConfigured,
                StringUtils.hasText(visionModel) ? visionModel : "-",
                reactReady);
        if (!agentEnabled) {
            log.warn(
                    "[Agent] LLM 未启用：健管助手将使用模板生成。请设置 healix.agent.enabled=true 或启用 dev profile");
        } else if (!keyConfigured) {
            log.warn(
                    "[Agent] DeepSeek API Key 未配置：请设置 DEEPSEEK_API_KEY 或 healix.agent.api-key");
        } else if (!visionConfigured) {
            log.warn(
                    "[Agent] OCR 识图未配置：请设置 API Key，并确认 healix.agent.vision.model=deepseek-v4-flash-vision-exp");
        } else if (!reactReady) {
            log.warn("[Agent] ReAct AiServices 未就绪：软入口将用启发式强制 Tools（仍无旧 Gateway Skill 分支）");
        }
    }
}
