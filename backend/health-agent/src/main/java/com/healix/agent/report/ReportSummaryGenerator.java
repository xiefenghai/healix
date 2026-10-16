package com.healix.agent.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.agent.llm.LlmClient;
import com.healix.core.compliance.ComplianceText;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
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
    /**
     * @param fromLlm true = LLM 生成；false = 模板降级
     */
    public record ReportSummaryDraft(
            String staffComment,
            String nextFocus,
            String quarterAdvice,
            Map<String, String> insights,
            List<Map<String, String>> highlights,
            boolean fromLlm,
            String note) {
        public ReportSummaryDraft(
                String staffComment, String nextFocus, String quarterAdvice, boolean fromLlm, String note) {
            this(staffComment, nextFocus, quarterAdvice, null, null, fromLlm, note);
        }
    }

    private static final List<String> INSIGHT_KEYS =
            List.of("plan", "med", "metrics", "diet", "exercise", "observations");
    private static final List<String> HIGHLIGHT_TYPES = List.of("GOOD", "WATCH", "FOCUS");

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
                    + "；未提及的部分尽量保持原样。highlights / insights 按修订后的口径一并输出。仍只输出完整 JSON。\n";
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
            String staffComment = ComplianceText.filter(text(parsed, "staffComment"));
            if (!StringUtils.hasText(staffComment)) {
                ReportSummaryDraft fb = fallback(content, quarter, "AI 未给出点评，已回退模板草稿");
                emitFallbackStream(sink, fb);
                return fb;
            }
            String nextFocus = ComplianceText.filter(text(parsed, "nextFocus"));
            String quarterAdvice = quarter ? ComplianceText.filter(text(parsed, "quarterAdvice")) : null;
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
            return new ReportSummaryDraft(
                    staffComment,
                    nextFocus,
                    quarterAdvice,
                    parseInsights(parsed.get("insights")),
                    parseHighlights(parsed.get("highlights")),
                    true,
                    null);
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

    private static Map<String, String> parseInsights(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : INSIGHT_KEYS) {
            String v = ComplianceText.filter(text(node, key));
            if (StringUtils.hasText(v)) {
                out.put(key, v);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<Map<String, String>> parseHighlights(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (JsonNode h : node) {
            String type = text(h, "type").toUpperCase();
            String t = ComplianceText.filter(text(h, "text"));
            if (HIGHLIGHT_TYPES.contains(type) && StringUtils.hasText(t)) {
                out.add(Map.of("type", type, "text", t));
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** 压缩 content_json：去掉逐日/逐点序列，只留统计量、上期对比与要点，控制 prompt 体积。 */
    private Map<String, Object> buildDigest(JsonNode content, String periodType) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("type", periodType);
        JsonNode periodNode = path(content, "period");
        period.put("start", text(periodNode, "start"));
        period.put("end", text(periodNode, "end"));
        period.put("dayCount", intVal(periodNode, "dayCount"));
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

        JsonNode overview = path(content, "overview");
        if (overview != null) {
            List<String> kpis = new ArrayList<>();
            for (JsonNode k : iter(path(overview, "kpis"))) {
                String delta = text(k, "deltaText");
                kpis.add(text(k, "label") + " " + text(k, "display") + text(k, "unit")
                        + (StringUtils.hasText(delta) ? "（" + delta + "）" : ""));
            }
            out.put("keyNumbers", kpis);
            List<Map<String, Object>> hl = new ArrayList<>();
            for (JsonNode h : iter(path(overview, "highlights"))) {
                hl.add(pick(h, "type", "text"));
            }
            out.put("ruleHighlights", hl);
        }

        JsonNode adherence = path(content, "adherence");
        Map<String, Object> adh = new LinkedHashMap<>();
        JsonNode planNode = path(adherence, "plan");
        Map<String, Object> plan = pick(planNode, "dueCount", "doneCount", "skippedCount", "rate", "longestStreak");
        plan.put("prev", pick(path(planNode, "prev"), "rate"));
        List<Map<String, Object>> weakTasks = new ArrayList<>();
        for (JsonNode t : iter(path(planNode, "byTask"))) {
            if (weakTasks.size() >= 3) {
                break;
            }
            Double r = doubleVal(t, "rate");
            if (r != null && r < 0.8) {
                weakTasks.add(pick(t, "title", "categoryLabel", "done", "due"));
            }
        }
        plan.put("weakTasks", weakTasks);
        adh.put("plan", plan);
        JsonNode medNode = path(adherence, "med");
        Map<String, Object> med = pick(medNode, "dueDayCount", "okDayCount", "rate");
        med.put("prev", pick(path(medNode, "prev"), "rate"));
        // 不给药名：避免寄语里出现具体药物
        List<Map<String, Object>> missed = new ArrayList<>();
        for (JsonNode m : iter(path(medNode, "missedBySlot"))) {
            missed.add(pick(m, "label", "count"));
        }
        med.put("missedBySlot", missed);
        adh.put("med", med);
        out.put("adherence", adh);

        List<Map<String, Object>> metrics = new ArrayList<>();
        for (JsonNode m : iter(path(content, "metrics"))) {
            if (metrics.size() >= MAX_METRIC_FAMILIES) {
                break;
            }
            Map<String, Object> item = pick(m, "label", "count", "abnormalCount", "inRangeRate", "ref");
            JsonNode stats = path(m, "stats");
            if (stats != null) {
                item.put("stats", stats);
            }
            JsonNode prev = path(m, "prev");
            if (prev != null) {
                item.put("prev", prev);
            }
            List<String> abnormal = new ArrayList<>();
            for (JsonNode a : iter(path(m, "abnormalItems"))) {
                abnormal.add(text(a, "text"));
            }
            if (!abnormal.isEmpty()) {
                item.put("abnormalItems", abnormal);
            }
            for (String key : new String[] {"bySlot", "byTime"}) {
                JsonNode n = path(m, key);
                if (n != null && n.isArray() && n.size() > 0) {
                    item.put(key, n);
                }
            }
            metrics.add(item);
        }
        out.put("metrics", metrics);

        JsonNode lifestyle = path(content, "lifestyle");
        if (lifestyle != null && lifestyle.isObject()) {
            Map<String, Object> ls = new LinkedHashMap<>();
            ls.put("dayCount", intVal(lifestyle, "dayCount"));
            JsonNode dietNode = path(lifestyle, "diet");
            Map<String, Object> diet = pick(
                    dietNode,
                    "recordDays", "completeDays", "breakfastDays", "targetKcal", "avgKcal", "onTargetDays",
                    "avgSodiumMg", "sodiumLimitMg", "highSodiumDays", "redMeals", "yellowMeals");
            JsonNode macro = path(dietNode, "macro");
            if (macro != null) {
                diet.put("macro", macro);
            }
            List<String> flags = new ArrayList<>();
            for (JsonNode f : iter(path(dietNode, "flagTop"))) {
                flags.add(text(f, "label") + " " + intVal(f, "count") + " 次");
            }
            diet.put("flagTop", flags);
            List<String> foods = new ArrayList<>();
            for (JsonNode f : iter(path(dietNode, "topFoods"))) {
                foods.add(text(f, "name") + "×" + intVal(f, "count"));
            }
            diet.put("topFoods", foods);
            diet.put("prev", pick(path(dietNode, "prev"), "recordDays", "avgKcal", "onTargetDays", "avgSodiumMg"));
            ls.put("diet", diet);
            JsonNode exNode = path(lifestyle, "exercise");
            Map<String, Object> ex = pick(
                    exNode,
                    "activeDays", "sessionCount", "totalMin", "activeMin", "avgWeeklyActiveMin",
                    "weeklyActiveMinTarget", "targetMetWeeks", "weekCount", "redCount");
            List<String> types = new ArrayList<>();
            for (JsonNode t : iter(path(exNode, "topTypes"))) {
                types.add(text(t, "name") + " " + intVal(t, "min") + " 分钟");
            }
            ex.put("topTypes", types);
            ex.put("prev", pick(path(exNode, "prev"), "activeDays", "avgWeeklyActiveMin"));
            ls.put("exercise", ex);
            out.put("lifestyle", ls);
        }

        List<String> followups = new ArrayList<>();
        for (JsonNode f : iter(path(content, "followups"))) {
            if (followups.size() >= MAX_FOLLOWUPS) {
                break;
            }
            String summary = text(f, "summary");
            followups.add(StringUtils.hasText(summary) ? summary : text(f, "title"));
        }
        out.put("followups", followups);

        JsonNode obs = path(content, "observations");
        if (obs != null && obs.isObject()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("labCount", arraySize(path(obs, "labs")));
            o.put("examCount", arraySize(path(obs, "exams")));
            o.put("items", observationTitles(obs));
            List<String> abnormal = new ArrayList<>();
            for (JsonNode a : iter(path(obs, "abnormalItems"))) {
                String prev = text(a, "prevValue");
                abnormal.add(text(a, "name") + " " + text(a, "value") + text(a, "unit")
                        + "（" + text(a, "flag") + "，参考 " + text(a, "ref") + "）"
                        + (StringUtils.hasText(prev) ? " 上次 " + prev : ""));
            }
            o.put("abnormalItems", abnormal);
            out.put("observations", o);
        }

        List<String> milestones = new ArrayList<>();
        for (JsonNode m : iter(path(content, "milestones"))) {
            milestones.add(text(m, "text"));
        }
        out.put("milestones", milestones);
        List<String> goals = new ArrayList<>();
        for (JsonNode g : iter(path(content, "goals"))) {
            goals.add(text(g, "label") + "：目标 " + text(g, "targetText") + "，实际 " + text(g, "actualText")
                    + (g.path("met").asBoolean(false) ? "（已达成）" : "（未达成）"));
        }
        out.put("goals", goals);
        return out;
    }

    private static Iterable<JsonNode> iter(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
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
                String note = StringUtils.hasText(text(n, "conclusion")) ? text(n, "conclusion") : text(n, "note");
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
            Resource res = new ClassPathResource("skills/report-summary/SKILL.md", getClass().getClassLoader());
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
