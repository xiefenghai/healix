package com.healix.core.persona.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

/** 规则灰字 headline / 失败模板叙事 / watchPoints 截取。 */
public final class PersonaTemplateSupport {

    private PersonaTemplateSupport() {}

    public static String displayHeadline(JsonNode dimensions) {
        String plan = planLabel(dimensions);
        String risk = highestRiskLabel(dimensions);
        String exec = executionLabel(dimensions);
        return plan + " · " + risk + " · " + exec;
    }

    public static ObjectNode templateNarrative(JsonNode dimensions) {
        ObjectNode n = JsonUtils.emptyObject();
        String headline = displayHeadline(dimensions);
        n.put("headline", truncate(headline, 40));
        n.put(
                "narrative",
                "【模板摘要】" + headline + "。数据来自规则聚合，AI 未就绪或调用失败；请健管师核对后可「生成叙事」重试或直接编辑。");
        ArrayNode watch = n.putArray("watchPoints");
        for (String p : watchPointsFromDimensions(dimensions)) {
            watch.add(truncate(p, 28));
        }
        n.put("model", "template");
        n.put("staffLocked", false);
        return n;
    }

    /**
     * 模板盯梢点：医疗风险优先，再补运营待办（合计最多 3 条）。
     * UI 侧会分别展示「医疗风险」与「待办」两栏；此处供叙事模板兜底。
     */
    public static List<String> watchPointsFromDimensions(JsonNode dimensions) {
        List<String> out = new ArrayList<>();
        for (String p : medicalWatchPoints(dimensions)) {
            if (out.size() >= 3) {
                break;
            }
            out.add(p);
        }
        for (String p : opsWatchPoints(dimensions)) {
            if (out.size() >= 3) {
                break;
            }
            out.add(p);
        }
        return out;
    }

    /** 医疗风险：高/中档评估 + 过敏 + 首诊方案影响备注。 */
    public static List<String> medicalWatchPoints(JsonNode dimensions) {
        List<String> out = new ArrayList<>();
        JsonNode tags = dimensions == null ? null : dimensions.path("risk").path("tags");
        if (tags != null && tags.isArray()) {
            for (JsonNode t : tags) {
                if (out.size() >= 3) {
                    break;
                }
                int rank = tierRank(text(t, "tier"), text(t, "level"));
                if (rank < 2) {
                    continue;
                }
                String disease = text(t, "diseaseCode");
                String label = text(t, "levelLabel");
                if (!StringUtils.hasText(label)) {
                    label = text(t, "level");
                }
                String line = StringUtils.hasText(disease) ? disease + "·" + label : label;
                if (StringUtils.hasText(line)) {
                    out.add(line.trim());
                }
            }
        }
        JsonNode allergens = dimensions == null ? null : dimensions.path("constraints").path("allergens");
        if (allergens != null && allergens.isArray()) {
            for (JsonNode a : allergens) {
                if (out.size() >= 3) {
                    break;
                }
                String name = a.asText(null);
                if (StringUtils.hasText(name)) {
                    out.add("过敏：" + name.trim());
                }
            }
        }
        JsonNode notes = dimensions == null ? null : dimensions.path("constraints").path("notes");
        if (notes != null && notes.isArray()) {
            for (JsonNode n : notes) {
                if (out.size() >= 3) {
                    break;
                }
                String s = n.asText(null);
                if (StringUtils.hasText(s)) {
                    out.add(truncate(s.trim(), 28));
                }
            }
        }
        return out;
    }

    /** 运营待办：工作台 OPEN 任务标题。 */
    public static List<String> opsWatchPoints(JsonNode dimensions) {
        List<String> out = new ArrayList<>();
        JsonNode tasks = dimensions == null ? null : dimensions.path("focus").path("openTasks");
        if (tasks != null && tasks.isArray()) {
            for (JsonNode t : tasks) {
                if (out.size() >= 3) {
                    break;
                }
                String title = text(t, "title");
                if (StringUtils.hasText(title)) {
                    out.add(title.trim());
                }
            }
        }
        return out;
    }

    private static String planLabel(JsonNode dimensions) {
        String key = text(dimensions == null ? null : dimensions.path("identity"), "activePlanTemplateKey");
        if (!StringUtils.hasText(key)) {
            return "未建方案";
        }
        return switch (key) {
            case "DIABETES" -> "糖尿病方案";
            case "HYPERTENSION" -> "高血压方案";
            case "OBESITY" -> "肥胖方案";
            case "COMPREHENSIVE" -> "综合方案";
            case "WELLNESS" -> "健康管理方案";
            default -> key;
        };
    }

    private static String highestRiskLabel(JsonNode dimensions) {
        JsonNode tags = dimensions == null ? null : dimensions.path("risk").path("tags");
        if (tags == null || !tags.isArray() || tags.isEmpty()) {
            return "暂无评估";
        }
        String best = null;
        int bestRank = -1;
        for (JsonNode t : tags) {
            int rank = tierRank(text(t, "tier"), text(t, "level"));
            if (rank > bestRank) {
                bestRank = rank;
                String label = text(t, "levelLabel");
                if (!StringUtils.hasText(label)) {
                    label = text(t, "level");
                }
                String disease = text(t, "diseaseCode");
                best = StringUtils.hasText(disease) ? disease + "·" + label : label;
            }
        }
        return StringUtils.hasText(best) ? best : "暂无评估";
    }

    private static int tierRank(String tier, String level) {
        String v = StringUtils.hasText(tier) ? tier : level;
        if (!StringUtils.hasText(v)) {
            return 0;
        }
        return switch (v.toUpperCase()) {
            case "VERY_HIGH", "很高危", "很高" -> 4;
            case "HIGH", "高危", "高" -> 3;
            case "MID", "MODERATE", "中危", "中" -> 2;
            case "LOW", "低危", "低" -> 1;
            default -> 0;
        };
    }

    private static String executionLabel(JsonNode dimensions) {
        JsonNode exec = dimensions == null ? null : dimensions.path("execution");
        if (exec == null || exec.isMissingNode()) {
            return "暂无打卡";
        }
        JsonNode rate = exec.get("planCompletion7d");
        if (rate == null || rate.isNull()) {
            return "暂无打卡";
        }
        int pct = (int) Math.round(rate.asDouble() * 100.0d);
        return "近7日方案" + pct + "%";
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return StringUtils.hasText(s) ? s : null;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
