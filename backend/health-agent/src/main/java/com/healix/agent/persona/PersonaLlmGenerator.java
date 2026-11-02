package com.healix.agent.persona;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.common.util.JsonUtils;
import com.healix.core.persona.support.PersonaTemplateSupport;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 用户画像 AI 叙事；失败回落模板（由调用方落库）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonaLlmGenerator {

    private static final String SYSTEM =
            """
            你是健管机构的案头助手，为健管师生成「患者用户画像」短叙事。
            只输出一个 JSON 对象，字段：
            - headline: 字符串，≤40 字，一句话人设
            - narrative: 字符串，120–220 字，B 端可用高危/分层等评估用语；禁止对患者口吻、禁止编造处方剂量、禁止无依据的确诊断言
            - watchPoints: 字符串数组，1–3 条，每条≤28 字，案头盯梢点
            事实规则：
            - 若 dimensions.goals.fromIntake 为 true，表示首诊问卷已提交：须按已采事实写画像，禁止写「尚未完成首诊」「入组当日尚未完成首诊」等未完成表述
            - 仅当 fromIntake 不为 true、且无可用首诊摘要时，才可写「尚未完成首诊 / 暂无评估」
            - intakeAiSummary 若与 fromIntake 矛盾，以 fromIntake=true 为准，忽略摘要里的「未完成首诊」措辞
            不要输出 Markdown 代码围栏或其它说明文字。
            """;

    private final LlmClient llmClient;

    public record PersonaNarrativeDraft(ObjectNode narrative, boolean fromLlm, String note) {}

    public PersonaNarrativeDraft generate(JsonNode dimensions, String intakeAiSummary) {
        ObjectNode facts = JsonUtils.emptyObject();
        if (dimensions != null) {
            facts.set("dimensions", dimensions);
        }
        if (StringUtils.hasText(intakeAiSummary)) {
            String s = intakeAiSummary.trim();
            facts.put("intakeAiSummary", s.length() > 800 ? s.substring(0, 800) : s);
        }
        if (!llmClient.isEnabled()) {
            return template(dimensions, "AI 未启用，已生成模板摘要");
        }
        LlmResponse llm = llmClient.chat("PERSONA_NARRATIVE", SYSTEM, JsonUtils.toJson(facts), List.of());
        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            return template(dimensions, "AI 未返回有效内容，已回退模板");
        }
        try {
            JsonNode root = JsonUtils.readTree(extractJsonObject(llm.content()));
            String headline = text(root, "headline");
            String narrative = text(root, "narrative");
            if (!StringUtils.hasText(headline) || !StringUtils.hasText(narrative)) {
                return template(dimensions, "AI 字段不完整，已回退模板");
            }
            ObjectNode out = JsonUtils.emptyObject();
            out.put("headline", truncate(headline, 40));
            out.put("narrative", truncate(narrative, 400));
            ArrayNode watch = out.putArray("watchPoints");
            JsonNode wp = root.get("watchPoints");
            int n = 0;
            if (wp != null && wp.isArray()) {
                for (JsonNode w : wp) {
                    if (n >= 3) {
                        break;
                    }
                    String s = w.asText(null);
                    if (StringUtils.hasText(s)) {
                        watch.add(truncate(s.trim(), 28));
                        n++;
                    }
                }
            }
            if (n == 0) {
                for (String p : PersonaTemplateSupport.watchPointsFromDimensions(dimensions)) {
                    if (n >= 3) {
                        break;
                    }
                    watch.add(p);
                    n++;
                }
            }
            out.put("model", "llm");
            out.put("staffLocked", false);
            return new PersonaNarrativeDraft(out, true, null);
        } catch (Exception e) {
            log.warn("[PersonaLlm] parse failed: {}", e.getMessage());
            return template(dimensions, "AI 结果解析失败，已回退模板");
        }
    }

    private PersonaNarrativeDraft template(JsonNode dimensions, String note) {
        return new PersonaNarrativeDraft(PersonaTemplateSupport.templateNarrative(dimensions), false, note);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText(null);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String extractJsonObject(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }
}
