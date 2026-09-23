package com.healix.agent.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.agent.stream.AgentStreamEvent;
import com.healix.common.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * 管理报告寄语草稿：压 content_json 喂 LLM，产出面向患者的 staffComment / nextFocus / quarterAdvice。
 *
 * <p>只产出草稿，不落库、不发布；LLM 未开或异常时降级为第二人称分档模板。
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
        return generate(content, periodType, null, null);
    }

    /**
     * @param sink 非空时流式推送可读寄语/关注点
     * @param reviseInstruction 非空时在现有草稿基础上按意见修订
     */
    public ReportSummaryDraft generate(
            JsonNode content,
            String periodType,
            Consumer<AgentStreamEvent> sink,
            ReviseContext revise) {
        boolean quarter = "QUARTER".equals(periodType);
        if (!llmClient.isEnabled()) {
            ReportSummaryDraft fb = fallback(content, quarter, "AI 未启用，已按依从性档位生成模板草稿");
            emitFallbackStream(sink, fb);
            return fb;
        }
        String digest;
        try {
            digest = JsonUtils.toJson(buildDigest(content, periodType));
        } catch (Exception e) {
            log.warn("[ReportSummary] digest build failed: {}", e.getMessage());
            ReportSummaryDraft fb = fallback(content, quarter, "报告数据解析失败，已回退模板草稿");
            emitFallbackStream(sink, fb);
            return fb;
        }

        String system = loadSkillMarkdown();
        String userMessage = digest;
        String scene = "REPORT_SUMMARY";
        if (revise != null && StringUtils.hasText(revise.instruction())) {
            scene = "REPORT_SUMMARY_REVISE";
            system = system
                    + "\n\n## 修订模式（REVISE）\n"
                    + "在「当前点评草稿」基础上按「修订意见」调整 staffComment / nextFocus"
                    + (quarter ? " / quarterAdvice" : "")
                    + "；未提及的部分尽量保持原样。仍只输出完整 JSON。\n";
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reportDigest", JsonUtils.readTree(digest));
            Map<String, Object> current = new LinkedHashMap<>();
            current.put("staffComment", revise.staffComment());
            current.put("nextFocus", revise.nextFocus());
            current.put("quarterAdvice", revise.quarterAdvice());
            payload.put("currentDraft", current);
            payload.put("instruction", revise.instruction());
            userMessage = JsonUtils.toJson(payload);
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在按你的要求修订点评草稿…"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingStart("对照现有寄语落实修订意见…"));
        } else {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.progress("正在调用 AI 生成健管师寄语…"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingStart("梳理依从与指标，组织对患者的寄语…"));
        }

        long started = System.currentTimeMillis();
        AgentStreamEvent.safeEmit(sink, AgentStreamEvent.tool("DeepSeek", "running", "正在生成点评…"));
        ReportReadableStreamer readable = new ReportReadableStreamer(sink);
        LlmResponse llm = sink != null
                ? llmClient.streamChat(scene, system, userMessage, List.of(), readable::onToken)
                : llmClient.chat(scene, system, userMessage, List.of());
        long ms = System.currentTimeMillis() - started;

        if (!llm.fromLlm() || !StringUtils.hasText(llm.content())) {
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.tool("DeepSeek", "done", "未返回有效内容 · " + ms + "ms"));
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.thinkingDone("模型未返回有效内容", ms));
            ReportSummaryDraft fb = fallback(content, quarter, "AI 未返回内容，已回退模板草稿");
            emitFallbackStream(sink, fb);
            return fb;
        }
        try {
            JsonNode parsed = JsonUtils.readTree(stripCodeFence(llm.content()));
            if (parsed == null || !parsed.isObject()) {
                AgentStreamEvent.safeEmit(
                        sink, AgentStreamEvent.tool("DeepSeek", "done", "格式异常 · " + ms + "ms"));
                ReportSummaryDraft fb = fallback(content, quarter, "AI 返回格式异常，已回退模板草稿");
                emitFallbackStream(sink, fb);
                return fb;
            }
            String staffComment = text(parsed, "staffComment");
            if (!StringUtils.hasText(staffComment)) {
                ReportSummaryDraft fb = fallback(content, quarter, "AI 未给出点评，已回退模板草稿");
                emitFallbackStream(sink, fb);
                return fb;
            }
            String nextFocus = text(parsed, "nextFocus");
            String quarterAdvice = quarter ? text(parsed, "quarterAdvice") : null;
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.tool(
                            "DeepSeek",
                            "done",
                            (revise != null ? "修订完成 · " : "点评已生成 · ") + ms + "ms"));
            AgentStreamEvent.safeEmit(
                    sink,
                    AgentStreamEvent.thinkingDone(
                            revise != null ? "已按意见整理寄语" : "已整理寄语与下阶段关注", ms));
            log.info(
                    "[ReportSummary] llm ok scene={} periodType={} commentChars={} promptTokens={} completionTokens={}",
                    scene,
                    periodType,
                    staffComment.length(),
                    llm.promptTokens(),
                    llm.completionTokens());
            return new ReportSummaryDraft(staffComment, nextFocus, quarterAdvice, true, null);
        } catch (Exception e) {
            log.warn("[ReportSummary] parse failed: {}", e.getMessage());
            ReportSummaryDraft fb = fallback(content, quarter, "AI 返回解析失败，已回退模板草稿");
            emitFallbackStream(sink, fb);
            return fb;
        }
    }

    public record ReviseContext(
            String instruction, String staffComment, String nextFocus, String quarterAdvice) {}

    private static void emitFallbackStream(Consumer<AgentStreamEvent> sink, ReportSummaryDraft draft) {
        if (sink == null || draft == null) {
            return;
        }
        if (StringUtils.hasText(draft.staffComment())) {
            AgentStreamEvent.safeEmit(sink, AgentStreamEvent.token("健管师寄语\n" + draft.staffComment().trim()));
        }
        if (StringUtils.hasText(draft.nextFocus())) {
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.token("\n\n下阶段关注\n" + draft.nextFocus().trim()));
        }
        if (StringUtils.hasText(draft.quarterAdvice())) {
            AgentStreamEvent.safeEmit(
                    sink, AgentStreamEvent.token("\n\n阶段建议\n" + draft.quarterAdvice().trim()));
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
            case "GOOD" -> "您好，" + ratePart + abnormalPart
                    + "。整体执行不错，请您继续保持当前节奏，有不适或疑问随时联系我。";
            case "FAIR" -> "您好，" + ratePart + abnormalPart
                    + "。部分任务还有漏打，没关系，我们一起把薄弱环节补上；有困难请告诉我。";
            default -> "您好，" + ratePart + abnormalPart
                    + "。本周期节奏偏紧或任务偏多都有可能，我们一起找找原因、适当简化安排；有需要随时联系我。";
        };
        String focus = switch (tier) {
            case "GOOD" -> "请您保持现有打卡频率，并留意指标有无明显波动。";
            case "FAIR" -> "请您尽量补齐漏打任务，固定每日打卡时间；若有异常指标，建议安排复测。";
            default -> "请您先和我说说执行上的困难，必要时我们一起下调任务强度；异常指标建议复测或就诊。";
        };
        String advice = quarter
                ? "综合本季执行与指标情况，建议您与医生/健管师一起复核方案强度与任务数量，必要时调整方案并请医生复核用药。"
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
        return "为患者撰写将发布的健管师寄语 JSON（staffComment/nextFocus/quarterAdvice）。"
                + "必须用第二人称「您」对患者说话，禁止第三人称案头备注。只输出 JSON。";
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
