package com.healix.core.worktask.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.dict.domain.SysDict;
import com.healix.core.dict.mapper.SysDictMapper;
import com.healix.core.dict.service.DictService;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.worktask.catalog.MetricFamily;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 与前端 metric-ranges.ts 对齐的异常判定（后端 SSOT）。 */
@Component
@RequiredArgsConstructor
public class MetricAbnormalEvaluator {

    public record AbnormalHit(
            MetricFamily family,
            String sourceRecordId,
            LocalDateTime recordedAt,
            String summary,
            Map<String, Object> payload) {}

    public record FlagRange(Double refLow, Double refHigh) {}

    private final SysDictMapper sysDictMapper;

    public List<AbnormalHit> evaluate(List<VitalRecord> records, List<VitalRecord> heightHistory) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        Map<String, JsonNode> dict = loadMetricDict();
        List<VitalRecord> heights = heightHistory == null ? List.of() : heightHistory;
        List<AbnormalHit> hits = new ArrayList<>();
        hits.addAll(evaluateBp(records, dict));
        hits.addAll(evaluateGlucose(records, dict));
        hits.addAll(evaluateHr(records, dict));
        hits.addAll(evaluateBmi(records, heights, dict));
        return hits;
    }

    private List<AbnormalHit> evaluateBp(List<VitalRecord> records, Map<String, JsonNode> dict) {
        Map<String, List<VitalRecord>> groups = new LinkedHashMap<>();
        List<VitalRecord> unpaired = new ArrayList<>();
        for (VitalRecord row : records) {
            if (!isBp(row.getMetricType())) {
                continue;
            }
            if (StringUtils.hasText(row.getGroupId())) {
                groups.computeIfAbsent(row.getGroupId(), k -> new ArrayList<>()).add(row);
            } else {
                unpaired.add(row);
            }
        }
        List<AbnormalHit> hits = new ArrayList<>();
        for (Map.Entry<String, List<VitalRecord>> e : groups.entrySet()) {
            AbnormalHit hit = bpHit(e.getKey(), e.getValue(), dict);
            if (hit != null) {
                hits.add(hit);
            }
        }
        for (VitalRecord row : unpaired) {
            AbnormalHit hit = bpHit(row.getId(), List.of(row), dict);
            if (hit != null) {
                hits.add(hit);
            }
        }
        return hits;
    }

    private AbnormalHit bpHit(String sourceRecordId, List<VitalRecord> rows, Map<String, JsonNode> dict) {
        VitalRecord sysRow = firstOfType(rows, MetricTypeEnum.BLOOD_PRESSURE_SYS);
        VitalRecord diaRow = firstOfType(rows, MetricTypeEnum.BLOOD_PRESSURE_DIA);
        if (sysRow == null && diaRow == null) {
            return null;
        }
        Double sys = sysRow == null ? null : toDouble(sysRow.getValue());
        Double dia = diaRow == null ? null : toDouble(diaRow.getValue());
        FlagRange sysRange = rangeFromDict(dict.get(MetricTypeEnum.BLOOD_PRESSURE_SYS.name()), 90d, 139d);
        FlagRange diaRange = rangeFromDict(dict.get(MetricTypeEnum.BLOOD_PRESSURE_DIA.name()), 60d, 89d);
        String sysFlag = flag(sys, sysRange);
        String diaFlag = flag(dia, diaRange);
        if (!isAbnormal(sysFlag) && !isAbnormal(diaFlag)) {
            return null;
        }
        VitalRecord anchor = latest(rows);
        String bpContext = extra(anchor, "bpContext");
        if (!StringUtils.hasText(bpContext)) {
            bpContext = "HOME";
        }
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("sys", "N".equals(sysFlag) ? null : sysFlag);
        flags.put("dia", "N".equals(diaFlag) ? null : diaFlag);
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("sys", rangeText(sysRange));
        ref.put("dia", rangeText(diaRange));
        Map<String, Object> payload = basePayload(MetricFamily.BP, sourceRecordId, anchor);
        payload.put("sys", sys);
        payload.put("dia", dia);
        payload.put("unit", "mmHg");
        payload.put("bpContext", bpContext);
        payload.put("flags", flags);
        payload.put("ref", ref);
        String summary = "血压 " + formatNum(sys) + "/" + formatNum(dia) + " mmHg（" + bpContextLabel(bpContext) + "）"
                + flagHint(sysFlag, diaFlag);
        return new AbnormalHit(MetricFamily.BP, sourceRecordId, anchor.getRecordedAt(), summary, payload);
    }

    private List<AbnormalHit> evaluateGlucose(List<VitalRecord> records, Map<String, JsonNode> dict) {
        List<AbnormalHit> hits = new ArrayList<>();
        JsonNode meta = dict.get(MetricTypeEnum.BLOOD_GLUCOSE.name());
        for (VitalRecord row : records) {
            if (!MetricTypeEnum.BLOOD_GLUCOSE.matches(row.getMetricType())) {
                continue;
            }
            String meal = extra(row, "mealContext");
            if (!StringUtils.hasText(meal)) {
                meal = "FASTING";
            }
            FlagRange range = glucoseRange(meta, meal);
            String fl = flag(toDouble(row.getValue()), range);
            if (!isAbnormal(fl)) {
                continue;
            }
            Map<String, Object> payload = basePayload(MetricFamily.GLUCOSE, row.getId(), row);
            payload.put("value", toDouble(row.getValue()));
            payload.put("unit", StringUtils.hasText(row.getUnit()) ? row.getUnit() : "mmol/L");
            payload.put("mealContext", meal);
            payload.put("flags", Map.of("glucose", fl));
            payload.put("ref", Map.of("glucose", rangeText(range)));
            String summary = mealLabel(meal) + "血糖 " + formatNum(toDouble(row.getValue())) + " "
                    + payload.get("unit") + ("H".equals(fl) ? " 偏高" : " 偏低");
            hits.add(new AbnormalHit(MetricFamily.GLUCOSE, row.getId(), row.getRecordedAt(), summary, payload));
        }
        return hits;
    }

    private List<AbnormalHit> evaluateHr(List<VitalRecord> records, Map<String, JsonNode> dict) {
        List<AbnormalHit> hits = new ArrayList<>();
        FlagRange range = rangeFromDict(dict.get(MetricTypeEnum.HEART_RATE.name()), 60d, 100d);
        for (VitalRecord row : records) {
            if (!MetricTypeEnum.HEART_RATE.matches(row.getMetricType())) {
                continue;
            }
            String fl = flag(toDouble(row.getValue()), range);
            if (!isAbnormal(fl)) {
                continue;
            }
            Map<String, Object> payload = basePayload(MetricFamily.HR, row.getId(), row);
            payload.put("value", toDouble(row.getValue()));
            payload.put("unit", StringUtils.hasText(row.getUnit()) ? row.getUnit() : "bpm");
            payload.put("flags", Map.of("hr", fl));
            payload.put("ref", Map.of("hr", rangeText(range)));
            String summary = "心率 " + formatNum(toDouble(row.getValue())) + " bpm"
                    + ("H".equals(fl) ? " 偏高" : " 偏低");
            hits.add(new AbnormalHit(MetricFamily.HR, row.getId(), row.getRecordedAt(), summary, payload));
        }
        return hits;
    }

    private List<AbnormalHit> evaluateBmi(
            List<VitalRecord> records, List<VitalRecord> heights, Map<String, JsonNode> dict) {
        FlagRange range = rangeFromDict(dict.get("BMI"), 18.5d, 23.9d);
        List<AbnormalHit> hits = new ArrayList<>();
        for (VitalRecord weight : records) {
            if (!MetricTypeEnum.WEIGHT.matches(weight.getMetricType())) {
                continue;
            }
            VitalRecord height = nearestHeight(weight, records, heights);
            if (height == null || height.getValue() == null || weight.getValue() == null) {
                continue;
            }
            double hM = height.getValue().doubleValue() / 100d;
            if (hM <= 0) {
                continue;
            }
            double bmi = weight.getValue().doubleValue() / (hM * hM);
            BigDecimal bmiRounded = BigDecimal.valueOf(bmi).setScale(1, RoundingMode.HALF_UP);
            String fl = flag(bmiRounded.doubleValue(), range);
            if (!isAbnormal(fl)) {
                continue;
            }
            Map<String, Object> payload = basePayload(MetricFamily.BMI, weight.getId(), weight);
            payload.put("bmi", bmiRounded.doubleValue());
            payload.put("weight", toDouble(weight.getValue()));
            payload.put("height", toDouble(height.getValue()));
            payload.put("unit", "kg/m²");
            payload.put("flags", Map.of("bmi", fl));
            payload.put("ref", Map.of("bmi", rangeText(range)));
            String summary = "BMI " + bmiRounded + ("H".equals(fl) ? " 偏高" : " 偏低");
            hits.add(new AbnormalHit(MetricFamily.BMI, weight.getId(), weight.getRecordedAt(), summary, payload));
        }
        return hits;
    }

    private static VitalRecord nearestHeight(
            VitalRecord weight, List<VitalRecord> sameBatch, List<VitalRecord> history) {
        if (StringUtils.hasText(weight.getGroupId())) {
            for (VitalRecord row : sameBatch) {
                if (MetricTypeEnum.HEIGHT.matches(row.getMetricType())
                        && weight.getGroupId().equals(row.getGroupId())) {
                    return row;
                }
            }
        }
        LocalDateTime at = weight.getRecordedAt();
        return history.stream()
                .filter(h -> MetricTypeEnum.HEIGHT.matches(h.getMetricType()) && h.getValue() != null)
                .filter(h -> at == null || h.getRecordedAt() == null || !h.getRecordedAt().isAfter(at))
                .max(Comparator.comparing(VitalRecord::getRecordedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
    }

    private Map<String, JsonNode> loadMetricDict() {
        List<SysDict> rows = sysDictMapper.listByType(DictService.PLATFORM_TENANT, "metricType");
        Map<String, JsonNode> map = new HashMap<>();
        for (SysDict row : rows) {
            if (row.getDictCode() != null && StringUtils.hasText(row.getContent())) {
                map.put(row.getDictCode(), JsonUtils.readTree(row.getContent()));
            }
        }
        return map;
    }

    private static Map<String, Object> basePayload(MetricFamily family, String sourceRecordId, VitalRecord anchor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("family", family.name());
        payload.put("sourceRecordId", sourceRecordId);
        payload.put("recordedAt", anchor.getRecordedAt() == null ? null : anchor.getRecordedAt().toString());
        payload.put("suppressedCount", 0);
        payload.put("suppressedSourceRecordIds", List.of());
        return payload;
    }

    private static FlagRange rangeFromDict(JsonNode meta, Double fallbackLow, Double fallbackHigh) {
        Double low = fallbackLow;
        Double high = fallbackHigh;
        if (meta != null) {
            if (meta.hasNonNull("refLow")) {
                low = meta.get("refLow").asDouble();
            }
            if (meta.hasNonNull("refHigh")) {
                high = meta.get("refHigh").asDouble();
            }
        }
        return new FlagRange(low, high);
    }

    private static FlagRange glucoseRange(JsonNode meta, String meal) {
        JsonNode byMeal = meta == null ? null : meta.get("refByMeal");
        JsonNode slot = byMeal == null ? null : byMeal.get(meal);
        if (slot != null && slot.isObject()) {
            Double low = slot.hasNonNull("refLow") ? slot.get("refLow").asDouble() : null;
            Double high = slot.hasNonNull("refHigh") ? slot.get("refHigh").asDouble() : null;
            if (low != null || high != null) {
                return new FlagRange(low, high);
            }
        }
        return switch (meal) {
            case "POSTPRANDIAL" -> new FlagRange(null, 7.8d);
            case "RANDOM" -> new FlagRange(null, 11.1d);
            default -> new FlagRange(3.9d, 6.1d);
        };
    }

    private static String flag(Double value, FlagRange range) {
        if (value == null || range == null) {
            return null;
        }
        if (range.refLow() == null && range.refHigh() == null) {
            return null;
        }
        if (range.refLow() != null && value < range.refLow()) {
            return "L";
        }
        if (range.refHigh() != null && value > range.refHigh()) {
            return "H";
        }
        return "N";
    }

    private static boolean isAbnormal(String flag) {
        return "H".equals(flag) || "L".equals(flag);
    }

    private static boolean isBp(String type) {
        return MetricTypeEnum.BLOOD_PRESSURE_SYS.matches(type)
                || MetricTypeEnum.BLOOD_PRESSURE_DIA.matches(type);
    }

    private static VitalRecord firstOfType(List<VitalRecord> rows, MetricTypeEnum type) {
        for (VitalRecord row : rows) {
            if (type.matches(row.getMetricType())) {
                return row;
            }
        }
        return null;
    }

    private static VitalRecord latest(List<VitalRecord> rows) {
        return rows.stream()
                .max(Comparator.comparing(VitalRecord::getRecordedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(rows.get(0));
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private static String extra(VitalRecord row, String key) {
        if (row == null || !StringUtils.hasText(row.getExtraJson())) {
            return null;
        }
        JsonNode n = JsonUtils.readTree(row.getExtraJson()).get(key);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static String rangeText(FlagRange range) {
        if (range.refLow() != null && range.refHigh() != null) {
            return trimNum(range.refLow()) + "~" + trimNum(range.refHigh());
        }
        if (range.refLow() != null) {
            return "≥" + trimNum(range.refLow());
        }
        if (range.refHigh() != null) {
            return "≤" + trimNum(range.refHigh());
        }
        return "";
    }

    private static String formatNum(Double v) {
        return v == null ? "-" : trimNum(v);
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static String flagHint(String sysFlag, String diaFlag) {
        List<String> parts = new ArrayList<>();
        if ("H".equals(sysFlag)) {
            parts.add("收缩压偏高");
        } else if ("L".equals(sysFlag)) {
            parts.add("收缩压偏低");
        }
        if ("H".equals(diaFlag)) {
            parts.add("舒张压偏高");
        } else if ("L".equals(diaFlag)) {
            parts.add("舒张压偏低");
        }
        return parts.isEmpty() ? "" : " " + String.join("、", parts);
    }

    private static String bpContextLabel(String ctx) {
        if ("CLINIC".equals(ctx)) {
            return "诊室";
        }
        if ("HOME".equals(ctx)) {
            return "家庭";
        }
        return ctx;
    }

    private static String mealLabel(String meal) {
        return switch (meal) {
            case "POSTPRANDIAL" -> "餐后";
            case "RANDOM" -> "随机";
            default -> "空腹";
        };
    }

    /** 患者级异常单去重键：同一患者同时最多一张 OPEN METRIC_ALERT。 */
    public static String patientBizKey(String peopleId) {
        return peopleId;
    }

    /** @deprecated 旧版按测量条开单；保留解析历史 biz_key */
    public static String bizKey(String peopleId, MetricFamily family, String sourceRecordId) {
        return peopleId + ":" + family.name() + ":" + sourceRecordId;
    }

    public static String familyPrefix(String peopleId, MetricFamily family) {
        return peopleId + ":" + family.name() + ":";
    }

    public static String sourceIdFromBizKey(String bizKey) {
        if (!StringUtils.hasText(bizKey)) {
            return "";
        }
        int i = bizKey.lastIndexOf(':');
        return i < 0 ? bizKey : bizKey.substring(i + 1);
    }

    public static Map<String, Object> hitEntry(AbnormalHit hit) {
        Map<String, Object> entry = new LinkedHashMap<>(hit.payload());
        entry.put("summary", hit.summary());
        return entry;
    }

    public static Map<String, Object> aggregatePayload(List<Map<String, Object>> hits) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hits", hits);
        payload.put("hitCount", hits.size());
        return payload;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> extractHits(String payloadJson) {
        if (!StringUtils.hasText(payloadJson)) {
            return List.of();
        }
        JsonNode root = JsonUtils.readTree(payloadJson);
        JsonNode hitsNode = root.get("hits");
        if (hitsNode != null && hitsNode.isArray()) {
            List<Map<String, Object>> hits = new ArrayList<>();
            for (JsonNode n : hitsNode) {
                Map<String, Object> map = JsonUtils.fromJson(n.toString(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                if (map != null) {
                    hits.add(new LinkedHashMap<>(map));
                }
            }
            return hits;
        }
        // 兼容旧版单条 payload
        if (root.has("sourceRecordId") || root.has("family")) {
            Map<String, Object> map = JsonUtils.fromJson(payloadJson, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            if (map != null) {
                return List.of(new LinkedHashMap<>(map));
            }
        }
        return List.of();
    }

    public static Set<String> sourceIdsFromPayload(String payloadJson) {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> hit : extractHits(payloadJson)) {
            Object id = hit.get("sourceRecordId");
            if (id != null && StringUtils.hasText(String.valueOf(id))) {
                ids.add(String.valueOf(id));
            }
            Object suppressed = hit.get("suppressedSourceRecordIds");
            if (suppressed instanceof List<?> list) {
                for (Object s : list) {
                    if (s != null) {
                        ids.add(String.valueOf(s));
                    }
                }
            }
        }
        // 旧版顶层 suppressedSourceRecordIds
        if (StringUtils.hasText(payloadJson)) {
            JsonNode root = JsonUtils.readTree(payloadJson);
            JsonNode raw = root.get("suppressedSourceRecordIds");
            if (raw != null && raw.isArray()) {
                for (JsonNode n : raw) {
                    if (n != null && !n.isNull()) {
                        ids.add(n.asText());
                    }
                }
            }
            JsonNode top = root.get("sourceRecordId");
            if (top != null && !top.isNull() && StringUtils.hasText(top.asText())) {
                ids.add(top.asText());
            }
        }
        return ids;
    }

    public static String buildSummary(List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return "居家指标异常";
        }
        String first = String.valueOf(hits.get(hits.size() - 1).getOrDefault("summary", "指标异常"));
        if (hits.size() == 1) {
            return first;
        }
        return first + " 等共 " + hits.size() + " 项异常";
    }

    public static int hitCount(String payloadJson) {
        List<Map<String, Object>> hits = extractHits(payloadJson);
        if (!hits.isEmpty()) {
            return hits.size();
        }
        if (!StringUtils.hasText(payloadJson)) {
            return 0;
        }
        JsonNode n = JsonUtils.readTree(payloadJson).get("hitCount");
        return n == null || !n.isNumber() ? 0 : n.asInt(0);
    }

    /** @deprecated 使用 {@link #hitCount(String)} */
    public static int suppressedCount(String payloadJson) {
        int hits = hitCount(payloadJson);
        return Math.max(0, hits - 1);
    }
}
