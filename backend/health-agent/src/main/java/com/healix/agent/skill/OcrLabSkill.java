package com.healix.agent.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.gateway.AgentAction;
import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.common.util.JsonUtils;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * 检验单 OCR 占位（能力未启用）：对话内识别预留。
 */
@Component
@RequiredArgsConstructor
public class OcrLabSkill implements AgentSkill {

    private final LlmClient llmClient;
    private final ResourceLoader resourceLoader;

    @Override
    public AgentCapability capability() {
        return AgentCapability.OCR_LAB;
    }

    @Override
    public boolean supports(AgentCapability routed, AgentChatCommand cmd) {
        if (routed == AgentCapability.OCR_LAB) {
            return true;
        }
        return StringUtils.hasText(cmd.imageBase64()) && matchesMessage(cmd.message());
    }

    @Override
    public AgentResponse execute(AgentSkillContext ctx) {
        AgentChatCommand cmd = ctx.command();
        if (!StringUtils.hasText(cmd.imageBase64())) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_LAB",
                    "请上传检验单图片后再识别。",
                    List.of(),
                    List.of(),
                    null);
        }
        String systemPrompt = loadSkillPrompt();
        String userPrompt = """
                请识别这张检验/化验单，输出严格 JSON（不要 markdown 代码块）：
                {
                  "specimenType": "标本类型",
                  "sampledAt": "采样时间 ISO-8601 或 null",
                  "reportedAt": "报告时间 ISO-8601 或 null",
                  "note": "备注",
                  "items": [
                    {
                      "itemName": "项目名",
                      "valueNum": 数值或null,
                      "valueText": "文本值",
                      "unit": "单位",
                      "refLow": 参考下限或null,
                      "refHigh": 参考上限或null,
                      "abnormalFlag": "H/L/N"
                    }
                  ],
                  "warnings": ["异常提示"]
                }
                """;
        LlmResponse llm = llmClient.chatWithImage(
                systemPrompt, userPrompt, cmd.imageBase64(), cmd.imageMimeType());
        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            return new AgentResponse(
                    ctx.sessionId(),
                    AgentCapability.OCR_LAB.name(),
                    "OCR_LAB",
                    "检验单识别失败，请检查图片清晰度或稍后重试。",
                    List.of(),
                    List.of(),
                    null);
        }
        JsonNode extracted = parseJson(llm.content());
        String path = "/workspace/patients/" + cmd.peopleId() + "/observations/labs";
        return new AgentResponse(
                ctx.sessionId(),
                AgentCapability.OCR_LAB.name(),
                "OCR_LAB",
                "检验单识别完成，请在检验页确认后保存。",
                List.of(AgentAction.navigate("前往检验录入", path)),
                List.of(),
                extracted);
    }

    public static boolean matchesMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String msg = message.toLowerCase(Locale.ROOT);
        return containsAny(msg, "检验", "化验", "检验单", "lab", "ocr");
    }

    private JsonNode parseJson(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                trimmed = trimmed.substring(start, end + 1);
            }
        }
        try {
            return JsonUtils.mapper().readTree(trimmed);
        } catch (Exception e) {
            return JsonUtils.emptyObject().put("rawText", content);
        }
    }

    private String loadSkillPrompt() {
        try {
            Resource res = resourceLoader.getResource("classpath:skills/ocr-lab/SKILL.md");
            if (res.exists()) {
                return StreamUtils.copyToString(res.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
            // fallback
        }
        return "你是医疗检验单 OCR 助手，仅输出 JSON，不做诊断。";
    }

    private static boolean containsAny(String msg, String... keywords) {
        for (String kw : keywords) {
            if (msg.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
