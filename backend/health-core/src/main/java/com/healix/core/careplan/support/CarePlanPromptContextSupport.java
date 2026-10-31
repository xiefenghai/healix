package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.people.support.DiseaseCodeLabels;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 管理方案 LLM 上下文清洗：去掉占位病种、补生活方式中文、格式化检验/体征，并过滤明显异常指标。
 */
public final class CarePlanPromptContextSupport {

    /** 写入库允许更宽；喂给模型用成人常用区间，避免 40cm 腰围等脏数据带偏方案。 */
    private static final Map<String, double[]> PROMPT_METRIC_RANGE = Map.of(
            "BLOOD_GLUCOSE", new double[] {1, 35},
            "BLOOD_PRESSURE_SYS", new double[] {60, 260},
            "BLOOD_PRESSURE_DIA", new double[] {30, 160},
            "HEART_RATE", new double[] {30, 220},
            "WEIGHT", new double[] {30, 250},
            "HEIGHT", new double[] {100, 230},
            "WAIST", new double[] {50, 180},
            "TEMPERATURE", new double[] {34, 43},
            "SLEEP_HOURS", new double[] {0.5, 24},
            "STEPS", new double[] {1, 100000});

    private static final Map<String, String> METRIC_LABELS = Map.of(
            "BLOOD_GLUCOSE", "血糖",
            "BLOOD_PRESSURE_SYS", "收缩压",
            "BLOOD_PRESSURE_DIA", "舒张压",
            "HEART_RATE", "心率",
            "WEIGHT", "体重",
            "HEIGHT", "身高",
            "WAIST", "腰围",
            "TEMPERATURE", "体温",
            "SLEEP_HOURS", "睡眠时长",
            "STEPS", "步数");

    /** 字段名 → 枚举码中文（与 field-catalog optionSets 对齐的常用子集）。 */
    private static final Map<String, Map<String, String>> FIELD_ENUM_LABELS = buildFieldEnumLabels();

    private static final int MAX_TEXT_CHARS = 400;
    private static final int MAX_ARRAY_ITEMS = 24;

    private CarePlanPromptContextSupport() {}

    public static boolean isMeaningfulDiseaseCode(String code) {
        return DiseaseCodeLabels.isMeaningful(code);
    }

    private static final Map<String, String> PRIMARY_APPEAL_LABELS = parsePairs(
            "GLUCOSE=控糖（糖尿病）",
            "BP=控压（高血压）",
            "LIPID=控血脂",
            "FAT_LOSS=减脂减重",
            "BODY_SHAPE=塑形增肌",
            "METABOLIC=三高 / 代谢综合",
            "SLEEP=改善睡眠",
            "GUT=改善肠胃",
            "CONSULT=先了解看看",
            "OTHER=其他");

    private static final Map<String, String> CARE_TRACK_LABELS = parsePairs(
            "WEIGHT_LOSS=减重管理",
            "DIABETES=糖尿病管理",
            "HYPERTENSION=高血压管理",
            "NONE=暂不加入项目");

    private static final Map<String, String> GOAL_MODE_LABELS = parsePairs(
            "LOSS=减重",
            "BODY_COMPOSITION=改善体成分",
            "MAINTAIN=维持",
            "GLUCOSE_CONTROL=控糖",
            "BP_CONTROL=控压");

    private static final Map<String, String> GOAL_METRIC_LABELS = parsePairs(
            "WEIGHT=体重",
            "WAIST=腰围",
            "BODY_FAT=体脂率",
            "HBA1C=糖化血红蛋白",
            "FPG=空腹血糖",
            "BP_SYS=收缩压",
            "BP_DIA=舒张压");

    private static final Map<String, String> INTAKE_FIELD_LABELS = parsePairs(
            "goal.careTracks=本次管理方向",
            "goal.primaryAppeal=核心诉求",
            "goal.mode=目标类型",
            "goal.targetWeightKg=目标体重",
            "goal.targetHba1c=糖化目标",
            "goal.targetFpg=空腹血糖目标",
            "goal.targetBpSys=收缩压目标",
            "goal.targetBpDia=舒张压目标",
            "disease.diabetes.type=糖尿病分型",
            "disease.diabetes.diagnosedYear=糖尿病患病年份",
            "disease.diabetes.hypoglycemia=低血糖情况",
            "disease.diabetes.monitorFreq=血糖监测频率",
            "disease.diabetes.latestHba1c=近期糖化",
            "disease.diabetes.latestFpg=近期空腹血糖",
            "disease.diabetes.latestPpg=近期餐后血糖",
            "disease.diabetes.medAdherence=降糖药规律",
            "disease.hypertension.diagnosedYear=高血压患病年份",
            "disease.hypertension.homeMonitor=家庭自测血压",
            "disease.hypertension.latestSystolic=近期收缩压",
            "disease.hypertension.latestDiastolic=近期舒张压",
            "disease.hypertension.morningPeak=晨峰血压",
            "disease.hypertension.medAdherence=降压药规律",
            "disease.hypertension.orthostatic=起立头晕");

    /**
     * 基础档案 → 方案 prompt 摘要：病种 + 饮食/运动/生活方式/睡眠/心理/妇幼/医疗限制/减重史等，并补中文标签。
     */
    public static ObjectNode summarizeBasicArchive(JsonNode content) {
        ObjectNode o = JsonUtils.emptyObject();
        if (content == null || content.isNull() || !content.isObject()) {
            return o;
        }
        ArrayNode illnessCodes = o.putArray("presentIllness");
        ArrayNode illnessLabels = o.putArray("presentIllnessLabels");
        JsonNode present = content.path("presentIllness");
        if (present.isArray()) {
            for (JsonNode n : present) {
                if (n == null || !n.isTextual()) {
                    continue;
                }
                String code = n.asText();
                if (!isMeaningfulDiseaseCode(code)) {
                    continue;
                }
                illnessCodes.add(code);
                String label = DiseaseCodeLabels.label(code);
                if (StringUtils.hasText(label)) {
                    illnessLabels.add(label);
                }
            }
        }

        copyEnrichedObject(o, content, "diet");
        copyEnrichedObject(o, content, "exercise");
        copyEnrichedObject(o, content, "lifestyle");
        copyEnrichedObject(o, content, "sleep");
        copyEnrichedObject(o, content, "psych");
        copyEnrichedObject(o, content, "women");
        copyEnrichedObject(o, content, "medical");
        copyEnrichedObject(o, content, "weightHistory");

        copyEnrichedArray(o, content, "medicationFlags");
        copyEnrichedArray(o, content, "supplements");

        return o;
    }

    /** 目标块补中文：诉求 / 模式 / careTracks / 指标目标名。 */
    public static ObjectNode enrichGoalsForPrompt(ObjectNode goals) {
        if (goals == null || goals.isNull()) {
            return null;
        }
        ObjectNode out = goals.deepCopy();
        JsonNode appealNode = out.get("primaryAppeal");
        ArrayNode appealLabels = primaryAppealLabels(appealNode);
        if (!appealLabels.isEmpty()) {
            out.set("primaryAppealLabels", appealLabels);
            // 兼容旧消费方：单码时仍写 primaryAppealLabel
            out.put("primaryAppealLabel", appealLabels.get(0).asText());
        }
        String mode = text(out, "mode");
        if (StringUtils.hasText(mode)) {
            out.put("modeLabel", GOAL_MODE_LABELS.getOrDefault(mode, mode));
        }
        JsonNode prefs = out.get("preferences");
        JsonNode tracks = prefs != null && prefs.isObject() ? prefs.get("careTracks") : null;
        if (tracks == null || tracks.isNull()) {
            tracks = out.get("careTracks");
        }
        if (tracks != null && !tracks.isNull()) {
            out.set("careTracks", tracks.deepCopy());
            ArrayNode labels = careTrackLabels(tracks);
            if (!labels.isEmpty()) {
                out.set("careTracksLabels", labels);
            }
            if (prefs != null && prefs.isObject()) {
                ObjectNode prefsCopy = ((ObjectNode) prefs).deepCopy();
                prefsCopy.set("careTracks", tracks.deepCopy());
                if (!labels.isEmpty()) {
                    prefsCopy.set("careTracksLabels", labels.deepCopy());
                }
                out.set("preferences", prefsCopy);
            }
        }
        JsonNode items = out.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode item : items) {
                if (item instanceof ObjectNode obj) {
                    String metric = text(obj, "metric");
                    if (StringUtils.hasText(metric)) {
                        obj.put("metricLabel", GOAL_METRIC_LABELS.getOrDefault(metric.toUpperCase(Locale.ROOT), metric));
                    }
                }
            }
        }
        return out;
    }

    /** 是否纳入减重管理方向（含诉求/模式推断）。 */
    public static boolean includesWeightLossTrack(JsonNode goals) {
        if (goals == null || goals.isNull()) {
            return false;
        }
        JsonNode tracks = goals.get("careTracks");
        if (tracks == null || tracks.isNull()) {
            JsonNode prefs = goals.get("preferences");
            if (prefs != null && prefs.isObject()) {
                tracks = prefs.get("careTracks");
            }
        }
        if (tracks != null) {
            if (tracks.isArray()) {
                for (JsonNode n : tracks) {
                    if (n != null && "WEIGHT_LOSS".equalsIgnoreCase(n.asText())) {
                        return true;
                    }
                }
            } else if ("WEIGHT_LOSS".equalsIgnoreCase(tracks.asText())) {
                return true;
            }
        }
        String mode = text(goals, "mode");
        return "LOSS".equalsIgnoreCase(mode) || "BODY_COMPOSITION".equalsIgnoreCase(mode);
    }

    /** disease.* 元数据 → 精简临床块（供糖/压方案引用）。 */
    public static ObjectNode summarizeDiseaseClinical(Map<String, JsonNode> values, String prefix) {
        ObjectNode out = JsonUtils.emptyObject();
        if (values == null || values.isEmpty() || !StringUtils.hasText(prefix)) {
            return out;
        }
        for (Map.Entry<String, JsonNode> e : values.entrySet()) {
            String code = e.getKey();
            if (code == null || !code.startsWith(prefix) || e.getValue() == null || e.getValue().isNull()) {
                continue;
            }
            String shortKey = code.substring(prefix.length());
            if (!StringUtils.hasText(shortKey)) {
                continue;
            }
            out.set(shortKey, e.getValue().deepCopy());
            String label = enumLabelForClinical(shortKey, e.getValue());
            if (StringUtils.hasText(label)) {
                out.put(shortKey + "Label", label);
            }
        }
        return out;
    }

    /** 从首诊 answers 抽出关键字段（带中文名），控制体量。 */
    public static ObjectNode extractIntakeKeyAnswers(JsonNode answers) {
        ObjectNode out = JsonUtils.emptyObject();
        if (answers == null || !answers.isObject()) {
            return out;
        }
        for (Map.Entry<String, String> e : INTAKE_FIELD_LABELS.entrySet()) {
            JsonNode v = answers.get(e.getKey());
            if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
                continue;
            }
            if (v.isArray() && v.isEmpty()) {
                continue;
            }
            ObjectNode cell = out.putObject(e.getKey());
            cell.put("label", e.getValue());
            cell.set("value", v.deepCopy());
            String pretty = prettyIntakeValue(e.getKey(), v);
            if (StringUtils.hasText(pretty)) {
                cell.put("display", pretty);
            }
        }
        return out;
    }

    private static ArrayNode careTrackLabels(JsonNode tracks) {
        ArrayNode labels = JsonUtils.mapper().createArrayNode();
        if (tracks == null || tracks.isNull()) {
            return labels;
        }
        if (tracks.isArray()) {
            for (JsonNode n : tracks) {
                if (n == null || !n.isTextual()) {
                    continue;
                }
                String code = n.asText();
                labels.add(CARE_TRACK_LABELS.getOrDefault(code, code));
            }
        } else if (tracks.isTextual()) {
            String code = tracks.asText();
            labels.add(CARE_TRACK_LABELS.getOrDefault(code, code));
        }
        return labels;
    }

    private static ArrayNode primaryAppealLabels(JsonNode appeal) {
        ArrayNode labels = JsonUtils.mapper().createArrayNode();
        if (appeal == null || appeal.isNull()) {
            return labels;
        }
        if (appeal.isArray()) {
            for (JsonNode n : appeal) {
                if (n == null || !n.isTextual() || n.asText().isBlank()) {
                    continue;
                }
                String code = n.asText().trim();
                labels.add(PRIMARY_APPEAL_LABELS.getOrDefault(code, code));
            }
            return labels;
        }
        if (appeal.isTextual()) {
            String raw = appeal.asText().trim();
            if (raw.startsWith("[")) {
                return primaryAppealLabels(JsonUtils.readTree(raw));
            }
            if (raw.contains(",")) {
                for (String p : raw.split(",")) {
                    if (!StringUtils.hasText(p)) {
                        continue;
                    }
                    String code = p.trim();
                    labels.add(PRIMARY_APPEAL_LABELS.getOrDefault(code, code));
                }
                return labels;
            }
            if (StringUtils.hasText(raw)) {
                labels.add(PRIMARY_APPEAL_LABELS.getOrDefault(raw, raw));
            }
        }
        return labels;
    }

    private static String enumLabelForClinical(String shortKey, JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        Map<String, String> map = switch (shortKey) {
            case "type" -> parsePairs("T2=2型糖尿病", "T1=1型糖尿病", "OTHER=其他/未分型", "UNKNOWN=不清楚");
            case "hypoglycemia" -> parsePairs(
                    "NONE=没有", "MILD=偶尔轻微", "SEVERE=有过较重或需他人帮助", "UNKNOWN=不清楚");
            case "monitorFreq" -> parsePairs(
                    "NONE=基本不测", "OCCASIONAL=偶尔", "WEEKLY=每周几次", "DAILY=几乎每天", "UNKNOWN=不清楚");
            case "medAdherence" -> parsePairs(
                    "ALWAYS=基本按时用药",
                    "MOSTLY=大多按时偶有漏服",
                    "IRREGULAR=经常漏服或自行加减",
                    "STOPPED=已自行停药",
                    "UNKNOWN=不清楚");
            case "homeMonitor" -> parsePairs("YES=有在家测", "NO=基本不测", "OCCASIONAL=偶尔测", "UNKNOWN=不清楚");
            case "morningPeak", "orthostatic" -> parsePairs("YES=有", "NO=没有", "UNSURE=不清楚", "UNKNOWN=不清楚");
            case "eyeCheck" -> parsePairs(
                    "WITHIN_1Y=近1年内查过", "1_TO_2Y=1–2年前", "OVER_2Y=超过2年/没查过", "UNKNOWN=不清楚");
            default -> null;
        };
        if (map == null || !value.isTextual()) {
            return null;
        }
        return map.get(value.asText());
    }

    private static String prettyIntakeValue(String field, JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if ("goal.careTracks".equals(field)) {
            ArrayNode labels = careTrackLabels(v);
            if (labels.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            labels.forEach(n -> {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(n.asText());
            });
            return sb.toString();
        }
        if ("goal.primaryAppeal".equals(field)) {
            ArrayNode labels = primaryAppealLabels(v);
            if (labels.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            labels.forEach(n -> {
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(n.asText());
            });
            return sb.toString();
        }
        if ("goal.mode".equals(field) && v.isTextual()) {
            return GOAL_MODE_LABELS.getOrDefault(v.asText(), v.asText());
        }
        if (v.isTextual()) {
            String shortKey = field.contains(".") ? field.substring(field.lastIndexOf('.') + 1) : field;
            String labeled = enumLabelForClinical(shortKey, v);
            return StringUtils.hasText(labeled) ? labeled : v.asText();
        }
        if (v.isNumber()) {
            return v.asText();
        }
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : v) {
                if (n == null || n.isNull()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("、");
                }
                sb.append(n.asText());
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.isObject()) {
            return null;
        }
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isTextual()) {
            return null;
        }
        String t = v.asText();
        return StringUtils.hasText(t) ? t.trim() : null;
    }

    private static void copyEnrichedObject(ObjectNode dest, JsonNode content, String key) {
        JsonNode raw = content.get(key);
        if (raw == null || raw.isNull() || !raw.isObject() || raw.isEmpty()) {
            return;
        }
        dest.set(key, enrichObject((ObjectNode) raw));
    }

    private static void copyEnrichedArray(ObjectNode dest, JsonNode content, String key) {
        JsonNode raw = content.get(key);
        if (raw == null || raw.isNull() || !raw.isArray() || raw.isEmpty()) {
            return;
        }
        ArrayNode arr = enrichArray(key, raw);
        if (arr.isEmpty()) {
            return;
        }
        dest.set(key, arr);
        ArrayNode labels = arrayLabels(key, raw);
        if (!labels.isEmpty()) {
            dest.set(key + "Labels", labels);
        }
    }

    /** 对象字段：递归补 label；文本截断。 */
    static ObjectNode enrichObject(ObjectNode raw) {
        ObjectNode out = JsonUtils.emptyObject();
        Iterator<Map.Entry<String, JsonNode>> it = raw.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = e.getKey();
            JsonNode v = e.getValue();
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isObject()) {
                ObjectNode nested = enrichObject((ObjectNode) v);
                if (!nested.isEmpty()) {
                    out.set(key, nested);
                }
                continue;
            }
            if (v.isArray()) {
                ArrayNode arr = enrichArray(key, v);
                if (!arr.isEmpty()) {
                    out.set(key, arr);
                    ArrayNode labels = arrayLabels(key, v);
                    if (!labels.isEmpty()) {
                        out.set(key + "Labels", labels);
                    }
                }
                continue;
            }
            if (v.isTextual()) {
                String t = truncate(v.asText());
                if (!StringUtils.hasText(t)) {
                    continue;
                }
                out.put(key, t);
                String label = enumLabel(key, t);
                if (StringUtils.hasText(label) && !label.equals(t)) {
                    out.put(key + "Label", label);
                }
                continue;
            }
            if (v.isNumber() || v.isBoolean()) {
                out.set(key, v);
            }
        }
        return out;
    }

    private static ArrayNode enrichArray(String field, JsonNode raw) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        int n = 0;
        for (JsonNode item : raw) {
            if (n >= MAX_ARRAY_ITEMS) {
                break;
            }
            if (item == null || item.isNull()) {
                continue;
            }
            if (item.isTextual()) {
                String t = item.asText();
                if (StringUtils.hasText(t) && !"NONE".equalsIgnoreCase(t.trim())) {
                    out.add(t.trim());
                    n++;
                }
            } else if (item.isNumber() || item.isBoolean()) {
                out.add(item);
                n++;
            }
        }
        return out;
    }

    private static ArrayNode arrayLabels(String field, JsonNode raw) {
        ArrayNode labels = JsonUtils.mapper().createArrayNode();
        for (JsonNode item : raw) {
            if (item == null || !item.isTextual()) {
                continue;
            }
            String code = item.asText();
            if (!StringUtils.hasText(code) || "NONE".equalsIgnoreCase(code.trim())) {
                continue;
            }
            String label = enumLabel(field, code.trim());
            if (StringUtils.hasText(label)) {
                labels.add(label);
            }
        }
        return labels;
    }

    private static String enumLabel(String field, String code) {
        if (!StringUtils.hasText(field) || !StringUtils.hasText(code)) {
            return null;
        }
        Map<String, String> map = FIELD_ENUM_LABELS.get(field);
        if (map == null) {
            return null;
        }
        String hit = map.get(code);
        if (hit != null) {
            return hit;
        }
        return map.get(code.trim().toUpperCase(Locale.ROOT));
    }

    private static String truncate(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String t = raw.trim();
        if (t.length() <= MAX_TEXT_CHARS) {
            return t;
        }
        return t.substring(0, MAX_TEXT_CHARS) + "…";
    }

    public static boolean isMetricUsefulForPrompt(String metricType, BigDecimal value) {
        if (value == null || !StringUtils.hasText(metricType)) {
            return false;
        }
        double[] range = PROMPT_METRIC_RANGE.get(metricType.trim());
        if (range == null) {
            return true;
        }
        double v = value.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return false;
        }
        return v >= range[0] && v <= range[1];
    }

    public static String metricTypeLabel(String metricType) {
        if (!StringUtils.hasText(metricType)) {
            return "";
        }
        return METRIC_LABELS.getOrDefault(metricType.trim(), metricType.trim());
    }

    public static String formatMetricValue(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    public static String formatLabItemSummary(String itemName, BigDecimal valueNum, String valueText, String unit) {
        String name = StringUtils.hasText(itemName) ? itemName.trim() : "检验项";
        String value;
        if (valueNum != null) {
            value = formatMetricValue(valueNum);
        } else if (StringUtils.hasText(valueText)) {
            value = valueText.trim();
        } else {
            value = "";
        }
        return name + "=" + joinValueAndUnit(value, unit);
    }

    static String joinValueAndUnit(String value, String unit) {
        if (!StringUtils.hasText(unit)) {
            return value == null ? "" : value;
        }
        String u = unit.trim();
        String v = value == null ? "" : value;
        if (looksLikeScientificUnit(u)) {
            if (u.startsWith("×") || u.startsWith("x") || u.startsWith("X")) {
                return v + u;
            }
            return v + "×" + u;
        }
        if (!StringUtils.hasText(v)) {
            return u;
        }
        return v + " " + u;
    }

    private static boolean looksLikeScientificUnit(String unit) {
        String u = unit.toLowerCase(Locale.ROOT);
        return u.startsWith("10^")
                || u.startsWith("10＾")
                || u.startsWith("×10")
                || u.startsWith("x10")
                || u.startsWith("*10");
    }

    public static String suspectMetricNote(String metricType, BigDecimal value, String unit) {
        String label = metricTypeLabel(metricType);
        String formatted = formatMetricValue(value);
        String u = StringUtils.hasText(unit) ? unit.trim() : "";
        return label + " " + formatted + u + " 超出成人常用范围，已忽略";
    }

    private static Map<String, Map<String, String>> buildFieldEnumLabels() {
        Map<String, Map<String, String>> m = new HashMap<>();
        m.put("type", parsePairs("NORMAL=普通饮食", "VEGETARIAN=素食", "LOW_SALT=低盐", "LOW_SUGAR=低糖",
                "LOW_SALT_SUGAR=低盐低糖", "OTHER=其他",
                "REFINED=以白米白面为主", "MIXED=粗细搭配", "LOW=主食吃得很少"));
        m.put("habit", parsePairs("GOOD=好", "FAIR=一般", "POOR=差"));
        m.put("frequency", parsePairs("NONE=基本不运动", "WEEKLY_3=每周≥3次", "WEEKLY_1=每周1-2次", "DAILY=每天",
                "RARELY=很少", "WEEKLY_1_3=每周1–3次", "MOST_DAYS=大多数天",
                "WEEKLY_4_7=每周4–7次", "DAILY_MULTI=每天不止一次"));
        m.put("intensity", parsePairs("LIGHT=轻度", "MODERATE=中度", "VIGOROUS=重度"));
        m.put("activityLevel", parsePairs(
                "SEDENTARY=久坐为主", "LIGHT=轻体力（站立走动较多）", "MODERATE=中等体力", "HEAVY=重体力"));
        m.put("status", parsePairs(
                "NEVER=从不", "FORMER=已戒", "CURRENT=现在吸", "OCCASIONAL=偶尔", "REGULAR=经常",
                "NONE=没有", "HAS=有", "NKA=没有已知过敏",
                "none=没有", "has=有"));
        m.put("passive", parsePairs("YES=是", "NO=否", "UNKNOWN=不清楚"));
        m.put("quality", parsePairs("GOOD=好", "FAIR=一般", "POOR=差"));
        m.put("disorder", parsePairs("NONE=没有", "INSOMNIA=失眠", "SNORING=打鼾", "OTHER=其他"));
        m.put("lateNight", parsePairs("RARELY=很少", "WEEKLY_1_3=每周1–3次", "MOST_DAYS=大多数天"));
        m.put("stressLevel", parsePairs("LOW=不大", "MEDIUM=有一些", "HIGH=长期压力很大"));
        m.put("stressEating", parsePairs("NO=没变化", "EAT_MORE=吃得更多", "EAT_LESS=吃得更少"));
        m.put("emotionalEating", parsePairs("NEVER=很少", "SOMETIMES=有时", "OFTEN=经常"));
        m.put("readiness", parsePairs(
                "NOT_READY=还没想好", "THINKING=想改但还没开始", "READY=准备好了", "ACTING=已经在做了"));
        m.put("familySupport", parsePairs("SUPPORTIVE=支持", "NEUTRAL=无所谓", "UNSUPPORTIVE=不太支持"));
        m.put("willingToRecord", parsePairs("YES=愿意", "MAYBE=可以试试", "NO=不太愿意"));
        m.put("eatingDisorderHistory", parsePairs("NO=没有", "YES=有过", "UNSURE=说不清", "DECLINE=不想回答"));
        m.put("soughtHelp", parsePairs("YES=找过", "NO=没有"));
        m.put("bodySatisfaction", parsePairs(
                "SATISFIED=满意", "NEUTRAL=一般", "DISSATISFIED=不太满意", "VERY_DISSATISFIED=很不满意"));
        m.put("bodyAnxiety", parsePairs("NONE=不会", "MILD=偶尔", "SEVERE=经常，比较严重"));
        m.put("reproductiveStatus", parsePairs(
                "NONE=都没有", "PREPARING=备孕中", "PREGNANT=怀孕中", "LACTATING=哺乳期", "UNSURE=不确定"));
        m.put("menstrualStatus", parsePairs(
                "REGULAR=规律", "IRREGULAR=不规律", "AMENORRHEA=停经（非绝经）",
                "PERIMENOPAUSE=围绝经期", "POSTMENOPAUSE=已绝经"));
        m.put("earlyMenopause", parsePairs("YES=是", "NO=否", "UNKNOWN=不清楚"));
        m.put("gdmHistory", parsePairs("YES=是", "NO=否", "UNKNOWN=不清楚"));
        m.put("exerciseRestriction", parsePairs(
                "NONE=没有", "DOCTOR_LIMIT=医生明确限制", "CARDIAC=运动时胸痛/心悸/晕厥",
                "BP_UNCONTROLLED=血压控制不好", "RECENT_SURGERY=近期手术未恢复", "OTHER=其他"));
        m.put("jointIssues", parsePairs(
                "NONE=没有", "KNEE=膝", "ANKLE=踝", "HIP=髋", "LOWER_BACK=腰", "SHOULDER=肩颈", "OTHER=其他"));
        m.put("steroidHyperglycemia", parsePairs("YES=是", "NO=否", "UNKNOWN=不清楚"));
        m.put("acanthosis", parsePairs("YES=是", "NO=否", "UNKNOWN=不清楚"));
        m.put("mealRegularity", parsePairs("REGULAR=基本规律", "SOMETIMES=偶尔不规律", "IRREGULAR=经常不规律"));
        m.put("breakfast", parsePairs("DAILY=每天吃", "SOMETIMES=有时吃", "RARELY=基本不吃"));
        m.put("takeoutFrequency", parsePairs(
                "RARELY=很少", "WEEKLY_1_3=每周1–3次", "WEEKLY_4_7=每周4–7次", "DAILY_MULTI=每天不止一次"));
        m.put("snackNight", parsePairs("RARELY=很少", "WEEKLY_1_3=每周1–3次", "MOST_DAYS=大多数天"));
        m.put("sugaryDrinks", parsePairs(
                "NONE=基本不喝", "WEEKLY_1_3=每周1–3杯", "DAILY_1=每天1杯", "DAILY_2PLUS=每天2杯以上"));
        m.put("staple", parsePairs("REFINED=以白米白面为主", "MIXED=粗细搭配", "LOW=主食吃得很少"));
        m.put("vegFruit", parsePairs("ENOUGH=每天都有不少", "SOME=有但不多", "RARE=很少吃"));
        m.put("cooking", parsePairs("STEAM_BOIL=蒸煮", "STIR_FRY=炒", "DEEP_FRY=油炸", "BRAISE=红烧/卤"));
        m.put("eatingSpeed", parsePairs("FAST=10分钟以内", "NORMAL=10–20分钟", "SLOW=20分钟以上"));
        m.put("taste", parsePairs(
                "LIGHT=清淡", "SALTY=偏咸/常吃咸菜腌制品", "SWEET=偏甜", "OILY=偏油", "SPICY=偏辣"));
        m.put("trackedBefore", parsePairs("NEVER=没有", "TRIED=试过没坚持", "ONGOING=一直在记"));
        m.put("cook", parsePairs("SELF=自己做", "FAMILY=家人做", "CANTEEN=单位食堂", "EAT_OUT=外卖/外食为主"));
        m.put("livingAlone", parsePairs("YES=是", "NO=否"));
        m.put("restriction", parsePairs(
                "NONE=没有", "VEGETARIAN=蛋奶素", "VEGAN=纯素", "HALAL=清真", "NO_SPICY=不吃辣",
                "NO_BEEF_LAMB=不吃牛羊肉", "NO_SEAFOOD=不吃海鲜", "OTHER=其他"));
        m.put("types", parsePairs(
                "WALK=快走", "RUN=跑步", "CYCLE=骑行", "SWIM=游泳", "STRENGTH=力量训练",
                "BALL=球类", "YOGA=瑜伽/普拉提", "DANCE=跳操/广场舞", "OTHER=其他"));
        m.put("strengthExperience", parsePairs("NONE=没练过", "BEGINNER=偶尔练", "REGULAR=规律练"));
        m.put("dailySteps", parsePairs(
                "LT3000=3000步以下", "S3000_6000=3000–6000步", "S6000_10000=6000–10000步",
                "GT10000=1万步以上", "UNKNOWN=不清楚"));
        m.put("availableSlots", parsePairs(
                "MORNING=早上", "NOON=中午", "EVENING=傍晚", "NIGHT=晚上", "WEEKEND=只有周末"));
        m.put("venue", parsePairs("HOME=家里", "OUTDOOR=户外", "GYM=健身房", "OFFICE=单位"));
        m.put("triedBefore", parsePairs("NEVER=没有", "ONCE=1次", "MULTIPLE=多次"));
        m.put("methods", parsePairs(
                "DIET_CONTROL=控制饮食", "LOW_CARB=低碳/生酮", "MEAL_REPLACEMENT=代餐",
                "FASTING=断食/轻断食", "EXERCISE=运动", "DRUG=药物", "SURGERY=减重手术", "OTHER=其他"));
        m.put("failReasons", parsePairs(
                "REBOUND=反弹了", "HUNGRY=太饿坚持不了", "NO_TIME=没时间", "NO_RESULT=没效果",
                "SOCIAL=应酬多", "INJURY=受伤", "OTHER=其他"));
        m.put("recentTrend", parsePairs(
                "STABLE=基本稳定", "UP=涨了", "DOWN_INTENDED=有意减下来了", "DOWN_UNINTENDED=没刻意减却瘦了"));
        m.put("expectedPace", parsePairs("STEADY=稳一点就好", "MODERATE=适中", "ASAP=越快越好"));
        m.put("medicationFlags", parsePairs(
                "NONE=都没有", "HYPOGLYCEMIC=降糖药或胰岛素", "ANTIHYPERTENSIVE=降压药", "STATIN=降脂药",
                "PSYCH=抗抑郁/抗精神病药", "STEROID=长期激素", "THYROID=甲状腺药",
                "WEIGHT_LOSS_DRUG=减重药", "OTHER=其他"));
        m.put("supplements", parsePairs(
                "NONE=都没有", "MEAL_REPLACEMENT=代餐", "PROTEIN=蛋白粉",
                "DIET_TEA=减肥茶/非处方减肥产品", "FAT_BURNER=燃脂类", "VITAMIN=维生素/矿物质", "OTHER=其他"));
        return Map.copyOf(m);
    }

    private static Map<String, String> parsePairs(String... pairs) {
        Map<String, String> out = new HashMap<>();
        for (String p : pairs) {
            int i = p.indexOf('=');
            if (i <= 0) {
                continue;
            }
            out.put(p.substring(0, i), p.substring(i + 1));
        }
        return Map.copyOf(out);
    }
}
