package com.healix.agent.followup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.skill.SkillMarkdown;
import com.healix.common.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * 随访 / 首诊案头总结：按 followup-summary Skill 调 LLM；失败或未启用时模板兜底。
 *
 * <p>只产出草稿，落库由调用方（问卷 answers / 随访 content）负责。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FollowupSummaryGenerator {

    private final LlmClient llmClient;

    public record FollowupSummaryDraft(
            String summary,
            List<String> keyPoints,
            List<String> risks,
            List<String> nextActions,
            boolean fromLlm,
            String note) {}

    /**
     * @param purpose {@code INTAKE} / {@code PERIODIC} 等
     * @param facts 已组装的事实 JSON（含 answers / highlights / patient）
     */
    public FollowupSummaryDraft generate(String purpose, JsonNode facts) {
        String p = StringUtils.hasText(purpose) ? purpose.trim().toUpperCase() : "INTAKE";
        if (!llmClient.isEnabled()) {
            return fallback(p, facts, "AI 未启用，已生成模板总结");
        }
        ObjectNode user = JsonUtils.emptyObject();
        user.put("purpose", p);
        if (facts != null && facts.isObject()) {
            facts.fields().forEachRemaining(e -> user.set(e.getKey(), e.getValue()));
        }
        String system = loadSkillMarkdown();
        String userMessage = JsonUtils.toJson(user);
        LlmResponse llm = llmClient.chat("FOLLOWUP_SUMMARY", system, userMessage, List.of());
        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            log.warn("[FollowupSummary] empty LLM response purpose={}", p);
            return fallback(p, facts, "AI 未返回有效内容，已回退模板总结");
        }
        try {
            JsonNode root = JsonUtils.readTree(extractJsonObject(llm.content()));
            String summary = text(root, "summary");
            if (!StringUtils.hasText(summary)) {
                return fallback(p, facts, "AI 总结为空，已回退模板");
            }
            return new FollowupSummaryDraft(
                    summary.trim(),
                    stringList(root.get("keyPoints")),
                    stringList(root.get("risks")),
                    stringList(root.get("nextActions")),
                    true,
                    null);
        } catch (Exception e) {
            log.warn("[FollowupSummary] parse failed: {}", e.getMessage());
            return fallback(p, facts, "AI 结果解析失败，已回退模板总结");
        }
    }

    private FollowupSummaryDraft fallback(String purpose, JsonNode facts, String note) {
        List<String> lines = new ArrayList<>();
        lines.add("INTAKE".equals(purpose) ? "【基本情况】" : "【随访概要】");
        lines.add("· 模板总结（AI 未就绪，待健管师润色）");
        ArrayNode answers = facts != null && facts.path("answers").isArray()
                ? (ArrayNode) facts.get("answers")
                : null;
        lines.add("【已采条目】");
        int n = 0;
        List<String> keyPoints = new ArrayList<>();
        if (answers != null) {
            for (JsonNode a : answers) {
                if (n >= 12) {
                    break;
                }
                String label = text(a, "label");
                String value = briefValue(a.get("value"));
                if (!StringUtils.hasText(label) || !StringUtils.hasText(value)) {
                    continue;
                }
                if (value.length() > 80) {
                    value = value.substring(0, 80) + "…";
                }
                lines.add("· " + label + "：" + value);
                if (keyPoints.size() < 5) {
                    keyPoints.add(label + "：" + value);
                }
                n++;
            }
        }
        if (n == 0) {
            lines.add("· 当前可用采集项较少，请核对后重新生成或手工补写");
        }
        List<String> risks = new ArrayList<>();
        if (facts != null && facts.path("highlights").isArray()) {
            for (JsonNode h : facts.get("highlights")) {
                String sev = text(h, "severity");
                String field = text(h, "field");
                if (StringUtils.hasText(sev)) {
                    risks.add((StringUtils.hasText(field) ? field + " · " : "") + sev);
                }
            }
        }
        if (!risks.isEmpty()) {
            lines.add("【风险与禁忌】");
            for (String r : risks) {
                lines.add("· " + r);
            }
        }
        List<String> next = new ArrayList<>();
        if ("INTAKE".equals(purpose)) {
            next.add("核对禁忌与过敏后制定管理方案");
            next.add("必要时补采缺失的首诊建议项");
        } else {
            next.add("按随访类型完成记录并设定下次动作");
        }
        lines.add("【建议下一步】");
        for (String a : next) {
            lines.add("· " + a);
        }
        return new FollowupSummaryDraft(String.join("\n", lines), keyPoints, risks, next, false, note);
    }

    private static String briefValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return "";
        }
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode x : v) {
                if (x.isValueNode()) {
                    parts.add(x.asText());
                } else if (x.isObject() && x.has("drugName")) {
                    parts.add(x.path("drugName").asText());
                } else if (x.isObject() && x.has("allergenName")) {
                    parts.add(x.path("allergenName").asText());
                }
            }
            return String.join("、", parts);
        }
        if (v.isObject()) {
            return JsonUtils.toJson(v);
        }
        return v.asText("");
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return "";
        }
        return n.get(field).asText("").trim();
    }

    private static List<String> stringList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n == null || !n.isArray()) {
            return out;
        }
        for (JsonNode x : n) {
            if (x != null && StringUtils.hasText(x.asText())) {
                out.add(x.asText().trim());
            }
        }
        return out;
    }

    private static String extractJsonObject(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            int lastFence = s.lastIndexOf("```");
            if (firstNl > 0 && lastFence > firstNl) {
                s = s.substring(firstNl + 1, lastFence).trim();
            }
        }
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    private String loadSkillMarkdown() {
        try {
            Resource res = new ClassPathResource("skills/followup-summary/SKILL.md", getClass().getClassLoader());
            String raw = StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            return SkillMarkdown.forLlm(raw);
        } catch (Exception e) {
            log.warn("followup-summary skill missing: {}", e.getMessage());
            return "你是健管师助手。根据用户 JSON 事实输出 summary/keyPoints/risks/nextActions 的 JSON。";
        }
    }
}
