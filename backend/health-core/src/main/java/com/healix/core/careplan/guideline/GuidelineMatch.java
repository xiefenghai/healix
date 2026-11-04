package com.healix.core.careplan.guideline;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一次匹配结果。{@code enabled=false} 表示条目库未上线（无可用条目），所有依据校验跳过。
 *
 * @param sourceLabels 与 matched 同序的出处文案（《指南》版本 · 章节）
 */
public record GuidelineMatch(
        boolean enabled,
        String kbVersion,
        List<GuidelineRec> matched,
        List<String> sourceLabels,
        List<Excluded> excluded) {

    public record Excluded(GuidelineRec rec, String reason) {}

    public static final GuidelineMatch DISABLED = new GuidelineMatch(false, null, List.of(), List.of(), List.of());

    public Set<String> matchedCodes() {
        Set<String> out = new LinkedHashSet<>();
        matched.forEach(r -> out.add(r.code()));
        return out;
    }

    public Optional<Excluded> excluded(String code) {
        return excluded.stream().filter(e -> e.rec().code().equals(code)).findFirst();
    }

    public List<GuidelineRec> forPlanBlock(String planBlock) {
        return matched.stream().filter(r -> r.planBlock().equals(planBlock)).toList();
    }

    /**
     * 写入方案 context_snapshot：版本与 code 列表，便于追溯。
     *
     * <p>只存 code 不存原因文字：快照会被 {@code WaterIntakeRules} 全文扫描限水关键词。
     */
    public ObjectNode toSnapshot() {
        ObjectNode o = JsonUtils.emptyObject();
        o.put("kbVersion", kbVersion);
        ArrayNode m = o.putArray("matched");
        matched.forEach(r -> m.add(r.code()));
        ArrayNode ex = o.putArray("excluded");
        excluded.forEach(e -> ex.add(e.rec().code()));
        return o;
    }

    /** 喂给模型的可引用条目：只含 matched。 */
    public ArrayNode toPrompt() {
        ArrayNode arr = JsonUtils.mapper().createArrayNode();
        for (int i = 0; i < matched.size(); i++) {
            GuidelineRec r = matched.get(i);
            ObjectNode n = arr.addObject();
            n.put("code", r.code());
            n.put("kind", r.kind());
            n.put("block", r.planBlock());
            n.put("recommendation", r.staffText());
            if (r.patientHint() != null) {
                n.put("patientHint", r.patientHint());
            }
            if (r.quant() != null) {
                n.put("constraint", r.quant().describe());
            }
            n.put("source", sourceLabels.get(i));
        }
        return arr;
    }
}
