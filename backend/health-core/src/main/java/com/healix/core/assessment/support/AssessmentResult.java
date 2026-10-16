package com.healix.core.assessment.support;

import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import lombok.Builder;
import lombok.Getter;

/**
 * 引擎输出（结构化，前端通用渲染）。
 *
 * <p>{@code level} 为引擎原生等级，{@code tier} 为统一等级；{@code score} 只存真正的分数。
 */
@Getter
@Builder
public class AssessmentResult {
    private final AssessmentStatus status;
    private final String level;
    private final AssessmentTier tier;
    private final BigDecimal score;
    private final String advice;
    @Builder.Default
    private final List<Dimension> dimensions = new ArrayList<>();
    @Builder.Default
    private final List<Item> items = new ArrayList<>();
    @Builder.Default
    private final List<FactorGroup> factorGroups = new ArrayList<>();
    @Builder.Default
    private final List<Missing> missing = new ArrayList<>();
    @Builder.Default
    private final List<Comparison> comparisons = new ArrayList<>();
    /** 补充说明（如取值口径、暂定口径） */
    @Builder.Default
    private final List<String> notes = new ArrayList<>();
    /** 风险引擎疑似已患病 */
    private final Suspicion suspicion;

    public record Dimension(String code, String label, String value, String tier, String hint) {}

    public record Item(String code, String label, String value, Integer points, String rationale) {}

    public record Hit(String code, String label, String detail, String source) {}

    /** 未核实项：{@code field} 可在问卷补齐时给出。 */
    public record Unchecked(String field, String label, boolean questionnaire) {}

    public record FactorGroup(String code, String title, List<Hit> hits, List<Unchecked> unchecked) {
        public int hitCount() {
            return hits.size();
        }
    }

    /** 缺项：{@code questionnaire} 为 true 时可在问卷补齐，否则按 {@code fillHint} 去对应入口录入。 */
    public record Missing(String field, String label, String fillHint, boolean questionnaire) {}

    public record Comparison(String label, String value, String computed, boolean consistent, String hint) {}

    public record Suspicion(String diseaseCode, String diseaseLabel, String reason) {}

    public Map<String, Object> toResultJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("advice", advice);
        m.put("dimensions", dimensions);
        m.put("items", items);
        m.put("factorGroups", factorGroups);
        m.put("missing", missing);
        m.put("comparisons", comparisons);
        m.put("notes", notes);
        m.put("suspicion", suspicion);
        return m;
    }

    /** 结果指纹：等级、分数、命中项、缺项不变即视为同一结果。 */
    public String fingerprintSource() {
        TreeSet<String> hits = new TreeSet<>();
        for (FactorGroup g : factorGroups) {
            for (Hit h : g.hits()) {
                hits.add(g.code() + ":" + h.code());
            }
        }
        TreeSet<String> miss = new TreeSet<>();
        for (Missing x : missing) {
            miss.add(x.field());
        }
        return String.join(
                "|",
                String.valueOf(status),
                String.valueOf(level),
                tier == null ? "" : tier.name(),
                score == null ? "" : score.stripTrailingZeros().toPlainString(),
                String.join(",", hits),
                String.join(",", miss),
                suspicion == null ? "" : suspicion.diseaseCode());
    }
}
