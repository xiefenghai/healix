package com.healix.agent.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.common.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * 管理报告点评草稿生成：把 content_json 压成摘要喂给 LLM，产出 staffComment / nextFocus / quarterAdvice。
 *
 * <p>只产出草稿，不落库、不发布；LLM 未开或返回异常时降级为按依从性档位的模板文案。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportSummaryGenerator {

    private static final int MAX_METRIC_FAMILIES = 6;
    private static final int MAX_FOLLOWUPS = 8;
    private static final int MAX_OBSERVATIONS = 6;

    private final LlmClient llmClient;
    private final ResourceLoader resourceLoader;

    /**
     * @param fromLlm true = LLM 生成；false = 模板降级
     */
    public record ReportSummaryDraft(
            String staffComment, String nextFocus, String quarterAdvice, boolean fromLlm, String note) {}

    public ReportSummaryDraft generate(JsonNode content, String periodType) {
        boolean quarter = "QUARTER".equals(periodType);
        if (!llmClient.isEnabled()) {
            return fallback(content, quarter, "AI 未启用，已按依从性档位生成模板草稿");
        }
        String digest;
        try {
            digest = JsonUtils.toJson(buildDigest(content, periodType));
        } catch (Exception e) {
            log.warn("[ReportSummary] digest build failed: {}", e.getMessage());
            return fallback(content, quarter, "报告数据解析失败，已回退模板草稿");
        }

        LlmResponse llm = llmClient.chat("REPORT_SUMMARY", loadSkillMarkdown(), digest, List.of());
        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            return fallback(content, quarter, "AI 未返回内容，已回退模板草稿");
        }
        try {
            JsonNode parsed = JsonUtils.readTree(stripCodeFence(llm.content()));
            if (parsed == null || !parsed.isObject()) {
                return fallback(content, quarter, "AI 返回格式异常，已回退模板草稿");
            }
            String staffComment = text(parsed, "staffComment");
            if (!StringUtils.hasText(staffComment)) {
                return fallback(content, quarter, "AI 未给出点评，已回退模板草稿");
            }
            String nextFocus = text(parsed, "nextFocus");
            String quarterAdvice = quarter ? text(parsed, "quarterAdvice") : null;
            log.info(
                    "[ReportSummary] llm ok periodType={} commentChars={} promptTokens={} completionTokens={}",
                    periodType,
                    staffComment.length(),
                    llm.promptTokens(),
                    llm.completionTokens());
            return new ReportSummaryDraft(staffComment, nextFocus, quarterAdvice, true, null);
        } catch (Exception e) {
            log.warn("[ReportSummary] parse failed: {}", e.getMessage());
            return fallback(content, quarter, "AI 返回解析失败，已回退模板草稿");
        }
    }

    /** 压缩 content_json：去掉逐点序列，只留统计量与最近值，控制 prompt 体积。 */
    private Map<String, Object> buildDigest(JsonNode content, String periodType) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("type", periodType);
        JsonNode periodNode = path(content, "period");
        period.put("start", text(periodNode, "start"));
        period.put("end", text(periodNode, "end"));
        out.put("period", period);

        JsonNode profile = path(content, "profile");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("gender", text(profile, "gender"));
        p.put("birthday", text(profile, "birthday"));
        JsonNode tags = path(profile, "chronicTags");
        if (tags != null && !tags.isNull() && !tags.isMissingNode()) {
            p.put("chronicTags", tags);
        }
        out.put("profile", p);

        JsonNode adherence = path(content, "adherence");
        Map<String, Object> adh = new LinkedHashMap<>();
        adh.put("plan", pick(path(adherence, "plan"), "dueCount", "doneCount", "skippedCount", "rate"));
        adh.put("med", pick(path(adherence, "med"), "dueDayCount", "okDayCount", "rate"));
        out.put("adherence", adh);

        List<Map<String, Object>> metrics = new ArrayList<>();
        JsonNode metricsNode = path(content, "metrics");
        if (metricsNode != null && metricsNode.isArray()) {
            for (JsonNode m : metricsNode) {
                if (metrics.size() >= MAX_METRIC_FAMILIES) {
                    break;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("label", text(m, "label"));
                item.put("abnormalCount", intVal(m, "abnormalCount"));
                JsonNode series = path(m, "series");
                item.put("pointCount", series != null && series.isArray() ? series.size() : 0);
                JsonNode latest = path(m, "latest");
                if (latest != null && latest.isObject()) {
                    item.put("latest", latest);
                }
                metrics.add(item);
            }
        }
        out.put("metrics", metrics);

        List<String> followups = new ArrayList<>();
        JsonNode fuNode = path(content, "followups");
        if (fuNode != null && fuNode.isArray()) {
            for (JsonNode f : fuNode) {
                if (followups.size() >= MAX_FOLLOWUPS) {
                    break;
                }
                String summary = text(f, "summary");
                followups.add(StringUtils.hasText(summary) ? summary : text(f, "title"));
            }
        }
        out.put("followups", followups);

        JsonNode obs = path(content, "observations");
        if (obs != null && obs.isObject()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("labCount", arraySize(path(obs, "labs")));
            o.put("examCount", arraySize(path(obs, "exams")));
            o.put("items", observationTitles(obs));
            out.put("observations", o);
        }
        return out;
    }

    private List<String> observationTitles(JsonNode obs) {
        List<String> items = new ArrayList<>();
        for (String key : new String[] {"labs", "exams"}) {
            JsonNode arr = path(obs, key);
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (JsonNode n : arr) {
                if (items.size() >= MAX_OBSERVATIONS) {
                    return items;
                }
                String label = StringUtils.hasText(text(n, "specimenType"))
                        ? text(n, "specimenType")
                        : text(n, "examType");
                String note = text(n, "note");
                items.add(StringUtils.hasText(note) ? label + "：" + note : label);
            }
        }
        return items;
    }

    /** 依从性档位模板：与发布时 templateTier 口径一致（GOOD ≥0.8 / FAIR ≥0.5 / POOR）。 */
    private ReportSummaryDraft fallback(JsonNode content, boolean quarter, String note) {
        JsonNode adherence = path(content, "adherence");
        JsonNode plan = path(adherence, "plan");
        JsonNode med = path(adherence, "med");
        int planDue = intVal(plan, "dueCount");
        Double rate = planDue > 0 ? doubleVal(plan, "rate") : doubleVal(med, "rate");
        int abnormalTotal = 0;
        JsonNode metricsNode = path(content, "metrics");
        if (metricsNode != null && metricsNode.isArray()) {
            for (JsonNode m : metricsNode) {
                abnormalTotal += intVal(m, "abnormalCount");
            }
        }
        String tier = rate == null ? "POOR" : rate >= 0.8d ? "GOOD" : rate >= 0.5d ? "FAIR" : "POOR";
        String ratePart = rate == null
                ? "本周期无可统计的打卡记录"
                : "本周期依从性完成率约 " + Math.round(rate * 100) + "%";
        String abnormalPart = abnormalTotal > 0 ? "，指标异常 " + abnormalTotal + " 次" : "，指标未见明显异常";

        String comment = switch (tier) {
            case "GOOD" -> ratePart + abnormalPart + "，整体执行良好，请继续保持当前节奏。";
            case "FAIR" -> ratePart + abnormalPart + "，执行情况中等，部分任务存在漏打，建议聚焦薄弱环节。";
            default -> ratePart + abnormalPart + "，执行情况偏弱，建议尽快沟通阻碍原因并简化任务。";
        };
        String focus = switch (tier) {
            case "GOOD" -> "保持现有打卡频率，关注指标波动趋势。";
            case "FAIR" -> "补齐漏打任务，固定每日打卡时间；异常指标安排复测。";
            default -> "先与患者确认执行阻碍，必要时下调任务强度；异常指标建议复测或就诊。";
        };
        String advice = quarter
                ? "综合本季度执行与指标情况，建议复核现有方案强度与任务数量，必要时调整方案并请医生复核用药。"
                : null;
        return new ReportSummaryDraft(comment, focus, advice, false, note);
    }

    private String loadSkillMarkdown() {
        try {
            Resource res = resourceLoader.getResource("classpath:skills/report-summary/SKILL.md");
            if (res.exists()) {
                return StreamUtils.copyToString(res.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.debug("report-summary skill missing: {}", e.getMessage());
        }
        return "根据管理报告数据生成 JSON：staffComment、nextFocus、quarterAdvice。只输出 JSON。";
    }

    private static String stripCodeFence(String raw) {
        String s = raw.trim();
        if (!s.startsWith("```")) {
            return s;
        }
        int firstBreak = s.indexOf('\n');
        int lastFence = s.lastIndexOf("```");
        if (firstBreak < 0 || lastFence <= firstBreak) {
            return s;
        }
        return s.substring(firstBreak + 1, lastFence).trim();
    }

    private static Map<String, Object> pick(JsonNode node, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return out;
        }
        for (String key : keys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull()) {
                out.put(key, v.isNumber() ? v.numberValue() : v.asText());
            }
        }
        return out;
    }

    private static JsonNode path(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = path(node, field);
        return v == null ? "" : v.asText("").trim();
    }

    private static int intVal(JsonNode node, String field) {
        JsonNode v = path(node, field);
        return v == null || !v.isNumber() ? 0 : v.asInt();
    }

    private static Double doubleVal(JsonNode node, String field) {
        JsonNode v = path(node, field);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }

    private static int arraySize(JsonNode node) {
        return node != null && node.isArray() ? node.size() : 0;
    }
}
