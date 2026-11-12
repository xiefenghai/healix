package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.people.support.DiseaseCodeLabels;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
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

    /**
     * 首诊核心诉求码（单码 / JSON 数组串 / 逗号串）→ 中文顿号拼接；空则 {@code null}。
     * 与首诊选项、喂模型标签同源。
     */
    public static String formatPrimaryAppealText(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String t = raw.trim();
        JsonNode node = t.startsWith("[") ? JsonUtils.readTree(t) : JsonUtils.mapper().getNodeFactory().textNode(t);
        ArrayNode labels = primaryAppealLabels(node);
        if (labels == null || labels.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode n : labels) {
            if (n == null || !n.isTextual() || n.asText().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(n.asText());
        }
        return sb.length() == 0 ? null : sb.toString();
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
            "goal.targetDate=希望多久达到体重目标(M1/M3/M6/M12/FLEXIBLE)",
            "goal.targetHba1c=糖化目标",
            "goal.targetFpg=空腹血糖目标",
            "goal.targetBpSys=收缩压目标",
            "goal.targetBpDia=舒张压目标",
            "basic.presentIllness=医生说过您有哪些疾病？",
            "basic.medicationFlags=目前在吃哪类药？",
            "basic.exerciseRestriction=有没有医生说过不能运动或要限制运动？",
            "basic.jointIssues=有没有关节问题或运动损伤？",
            "basic.eatingDisorderHistory=以前有没有出现过暴食、催吐，或者长时间刻意不吃饭的情况？",
            "basic.sugaryDrinks=奶茶、可乐、果汁这类含糖饮料喝多少？",
            "basic.taste=日常饮食特点",
            "basic.diet.taste=日常饮食更接近哪种",
            "basic.diet.dinnerTime=晚餐通常几点吃",
            "basic.schedule.offWork=平时大概几点下班",
            "basic.schedule.offWorkNote=倒班或夜班的上班时段",
            "basic.sleep.refreshed=早上起来觉得解乏吗",
            "medication.mealRelation=吃药和吃饭怎么配合",
            "goal.constraints=执行健康计划的最大阻力",
            "basic.triedBefore=以前减过重吗？",
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
        copyEnrichedObject(o, content, "schedule");
        copyEnrichedObject(o, content, "exercise");
        copyEnrichedObject(o, content, "lifestyle");
        copyEnrichedObject(o, content, "sleep");
        copyEnrichedObject(o, content, "psych");
        copyEnrichedObject(o, content, "women");
        copyEnrichedObject(o, content, "medical");
        copyEnrichedObject(o, content, "weightHistory");

        copyEnrichedArray(o, content, "medicationFlags");
        copyEnrichedArray(o, content, "supplements");
        copyFamilyHistory(o, content);

        return o;
    }

    /**
     * 家族史进方案 Prompt：状态 + 一级亲属早发心血管/糖尿病等摘要，避免只问不传。
     */
    static void copyFamilyHistory(ObjectNode dest, JsonNode content) {
        if (content == null || !content.isObject()) {
            return;
        }
        String status = text(content, "familyHistoryStatus");
        if (StringUtils.hasText(status)) {
            dest.put("familyHistoryStatus", status);
            String statusLabel =
                    switch (status.trim().toLowerCase(Locale.ROOT)) {
                        case "none" -> "无家族史";
                        case "has" -> "有家族史";
                        default -> status;
                    };
            dest.put("familyHistoryStatusLabel", statusLabel);
        }
        String freeText = text(content, "familyHistory");
        if (StringUtils.hasText(freeText)) {
            dest.put("familyHistory", truncate(freeText));
        }

        JsonNode items = content.get("familyHistoryItems");
        if (items == null || !items.isArray() || items.isEmpty()) {
            if ("none".equalsIgnoreCase(status)) {
                dest.put("familyHistorySummary", "已确认无一级亲属慢病家族史");
            }
            return;
        }

        ArrayNode outItems = dest.putArray("familyHistoryItems");
        List<String> summaryParts = new ArrayList<>();
        boolean earlyCv = false;
        boolean firstDegreeDiabetes = false;
        int n = 0;
        for (JsonNode item : items) {
            if (item == null || !item.isObject() || n >= MAX_ARRAY_ITEMS) {
                continue;
            }
            String kinship = item.path("kinshipLevel").asText("1").trim();
            String member = item.path("member").asText("").trim();
            JsonNode diseases = item.path("diseases");
            if (!diseases.isArray() || diseases.isEmpty()) {
                continue;
            }
            ObjectNode row = outItems.addObject();
            row.put("kinshipLevel", kinship);
            if (StringUtils.hasText(member)) {
                row.put("member", member);
                String memberLabel = FAMILY_MEMBER_LABELS.getOrDefault(member, member);
                row.put("memberLabel", memberLabel);
            }
            ArrayNode codes = row.putArray("diseases");
            ArrayNode labels = row.putArray("diseaseLabels");
            List<String> diseaseLabels = new ArrayList<>();
            for (JsonNode d : diseases) {
                if (d == null || !d.isTextual()) {
                    continue;
                }
                String code = d.asText().trim().toUpperCase(Locale.ROOT);
                if (!StringUtils.hasText(code) || "NONE".equals(code) || "UNKNOWN".equals(code)) {
                    continue;
                }
                codes.add(code);
                String label = FAMILY_DISEASE_LABELS.getOrDefault(code, code);
                labels.add(label);
                diseaseLabels.add(label);
                if ("1".equals(kinship)) {
                    if ("FH_EARLY_MI".equals(code) || "FH_CHD".equals(code) || "FH_STROKE".equals(code)) {
                        earlyCv = true;
                    }
                    if ("FH_DIABETES".equals(code)) {
                        firstDegreeDiabetes = true;
                    }
                }
            }
            if (codes.isEmpty()) {
                outItems.remove(outItems.size() - 1);
                continue;
            }
            String memberLabel = row.path("memberLabel").asText(
                    FAMILY_MEMBER_LABELS.getOrDefault(member, StringUtils.hasText(member) ? member : "亲属"));
            summaryParts.add(memberLabel + "·" + String.join("、", diseaseLabels));
            n++;
        }
        if (!summaryParts.isEmpty()) {
            dest.put("familyHistorySummary", String.join("；", summaryParts));
        } else if ("none".equalsIgnoreCase(status)) {
            dest.put("familyHistorySummary", "已确认无一级亲属慢病家族史");
        }
        if (earlyCv) {
            dest.put("earlyCvFamilyHistory", true);
        }
        if (firstDegreeDiabetes) {
            dest.put("firstDegreeDiabetesFamilyHistory", true);
        }
        JsonNode earlyFlag = content.get("earlyCvFamilyHistory");
        if (earlyFlag != null && earlyFlag.isBoolean() && earlyFlag.asBoolean()) {
            dest.put("earlyCvFamilyHistory", true);
        }
    }

    /**
     * 执行阻力 → 固定应对话术（写死策略，禁止模型空喊「要坚持」）。
     */
    public static ArrayNode barrierStrategies(JsonNode constraints) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        if (constraints == null || constraints.isNull()) {
            return out;
        }
        List<String> codes = new ArrayList<>();
        if (constraints.isArray()) {
            for (JsonNode n : constraints) {
                if (n != null && n.isTextual() && StringUtils.hasText(n.asText())) {
                    codes.add(n.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
        } else if (constraints.isTextual() && StringUtils.hasText(constraints.asText())) {
            codes.add(constraints.asText().trim().toUpperCase(Locale.ROOT));
        }
        for (String code : codes) {
            if ("NONE".equals(code)) {
                continue;
            }
            BarrierStrategy s = BARRIER_STRATEGIES.get(code);
            if (s == null) {
                continue;
            }
            ObjectNode row = out.addObject();
            row.put("code", code);
            row.put("label", s.label());
            row.put("dietNote", s.dietNote());
            row.put("exerciseNote", s.exerciseNote());
            row.put("executionHint", s.executionHint());
        }
        return out;
    }

    /**
     * 红线禁忌：进食障碍 / 严重关节问题 / 医嘱限制运动 → 方案运动禁忌最高优先级。
     */
    public static ArrayNode safetyRedlines(JsonNode content) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        if (content == null || !content.isObject()) {
            return out;
        }
        JsonNode psych = content.path("psych");
        String ed = psych.path("eatingDisorderHistory").asText("").trim().toUpperCase(Locale.ROOT);
        if ("YES".equals(ed) || "UNSURE".equals(ed)) {
            ObjectNode row = out.addObject();
            row.put("code", "EATING_DISORDER");
            row.put("priority", "HIGHEST");
            row.put("source", "basic.psych.eatingDisorderHistory");
            row.put("answer", ed);
            row.put(
                    "mustApplyExercise",
                    "禁止高强度运动处方；不设激进减重；称重改为每周；contraindications 须含「进食障碍风险：避免以体重为唯一目标的训练」");
            row.put(
                    "mustApplyDiet",
                    "不设热量缺口；避免极端节食话术；rationale 提示健管师考虑转介");
        }

        JsonNode medical = content.path("medical");
        JsonNode joints = medical.path("jointIssues");
        if (joints.isArray()) {
            List<String> jointCodes = new ArrayList<>();
            List<String> jointLabels = new ArrayList<>();
            for (JsonNode j : joints) {
                if (j == null || !j.isTextual()) {
                    continue;
                }
                String c = j.asText().trim().toUpperCase(Locale.ROOT);
                if (!StringUtils.hasText(c) || "NONE".equals(c)) {
                    continue;
                }
                jointCodes.add(c);
                String label = enumLabel("jointIssues", c);
                jointLabels.add(StringUtils.hasText(label) ? label : c);
            }
            if (!jointCodes.isEmpty()) {
                ObjectNode row = out.addObject();
                row.put("code", "JOINT_ISSUES");
                row.put("priority", "HIGHEST");
                row.put("source", "basic.medical.jointIssues");
                row.put("answer", String.join("、", jointLabels));
                row.put(
                        "mustApplyExercise",
                        "以低冲击为主（快走、骑车、游泳），避开受伤部位；contraindications 须含「"
                                + String.join("、", jointLabels)
                                + "疼痛/损伤时强行负重或跑跳」");
                row.put("mustApplyDiet", "");
            }
        }

        JsonNode restrictions = medical.path("exerciseRestriction");
        if (restrictions.isArray()) {
            List<String> codes = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (JsonNode r : restrictions) {
                if (r == null || !r.isTextual()) {
                    continue;
                }
                String c = r.asText().trim().toUpperCase(Locale.ROOT);
                if (!StringUtils.hasText(c) || "NONE".equals(c)) {
                    continue;
                }
                codes.add(c);
                String label = enumLabel("exerciseRestriction", c);
                labels.add(StringUtils.hasText(label) ? label : c);
            }
            if (!codes.isEmpty()) {
                ObjectNode row = out.addObject();
                row.put("code", "EXERCISE_RESTRICTION");
                row.put("priority", "HIGHEST");
                row.put("source", "basic.medical.exerciseRestriction");
                row.put("answer", String.join("、", labels));
                row.put(
                        "mustApplyExercise",
                        "存在运动医学限制（"
                                + String.join("、", labels)
                                + "）：强度上限 LIGHT；禁止跑步/负重/剧烈有氧；contraindications 必须写入上述限制原文意");
                row.put("mustApplyDiet", "");
            }
        }
        return out;
    }

    private record BarrierStrategy(String label, String dietNote, String exerciseNote, String executionHint) {}

    private static final Map<String, BarrierStrategy> BARRIER_STRATEGIES = Map.of(
            "SOCIAL",
            new BarrierStrategy(
                    "应酬多",
                    "应酬时优先点清蒸鱼、白灼虾，少喝浓汤，尽量用茶水代替酒水；能拒则拒第二轮酒",
                    "",
                    "执行与总结点出应酬场景的可执行点餐/少酒动作"),
            "TAKEOUT",
            new BarrierStrategy(
                    "经常外卖",
                    "点外卖时选清蒸/白灼/少油菜品，主食换成杂粮饭或全麦，酱料另上、少浇；不要按「自己蒸煮」写示例餐",
                    "",
                    "示例餐与 swapHint 偏外卖可执行替换"),
            "HUNGRY",
            new BarrierStrategy(
                    "总是觉得饿",
                    "正餐留足蛋白质和蔬菜，避免热量压得过低；两餐间可安排约100g无糖酸奶或一小把原味坚果",
                    "",
                    "禁止极端缺口；称重与饥饱反馈写进复评"),
            "FAMILY",
            new BarrierStrategy(
                    "家人不太支持",
                    "请家人少放油盐，主食换成杂粮；一起吃饭时先协商一盘清口菜",
                    "",
                    "notes 给一句家属能配合的做法"),
            "NO_TIME",
            new BarrierStrategy(
                    "没时间运动",
                    "",
                    "用碎片活动替代整段锻炼：通勤多走一站、饭后快走10–15分钟、久坐每小时起身2分钟",
                    "运动任务嵌进已有空档，勿默认每天整段1小时"));

    private static final Map<String, String> FAMILY_MEMBER_LABELS = parsePairs(
            "FATHER=父",
            "MOTHER=母",
            "SON=子",
            "DAUGHTER=女",
            "BROTHER=兄/弟",
            "SISTER=姐/妹",
            "UNSPECIFIED=一级亲属",
            "P_GRANDFATHER=祖父",
            "P_GRANDMOTHER=祖母",
            "M_GRANDFATHER=外祖父",
            "M_GRANDMOTHER=外祖母");

    private static final Map<String, String> FAMILY_DISEASE_LABELS = parsePairs(
            "FH_DIABETES=糖尿病",
            "FH_HYPERTENSION=高血压",
            "FH_CHD=冠心病",
            "FH_STROKE=脑卒中",
            "FH_EARLY_MI=早发心梗",
            "FH_HYPERLIPIDEMIA=高脂血症",
            "FH_THYROID=甲状腺疾病",
            "FH_GOUT=痛风",
            "FH_CKD=慢性肾病");

    /**
     * 首诊高亮 → 模型可读约束列表（方案影响 + 追问话术）。
     * 原始 {@code highlights} 仍保留供安全校验 / 指南匹配；生成时优先读本列表。
     */
    public static ArrayNode highlightGuidance(JsonNode highlights) {
        ArrayNode out = JsonUtils.mapper().createArrayNode();
        if (highlights == null || !highlights.isArray()) {
            return out;
        }
        for (JsonNode h : highlights) {
            if (h == null || h.isNull()) {
                continue;
            }
            String sev = h.path("severity").asText("").trim().toUpperCase(Locale.ROOT);
            String effect = h.path("planEffect").asText("").trim();
            String followUp = h.path("followUp").asText("").trim();
            if (!StringUtils.hasText(effect) && !StringUtils.hasText(followUp)) {
                continue;
            }
            ObjectNode row = out.addObject();
            if (StringUtils.hasText(sev)) {
                row.put("severity", sev);
                row.put(
                        "severityLabel",
                        switch (sev) {
                            case "BLOCK" -> "阻断";
                            case "REFER" -> "转介";
                            case "CAUTION" -> "谨慎";
                            default -> sev;
                        });
            }
            if (StringUtils.hasText(effect)) {
                row.put("mustApply", effect);
            }
            if (StringUtils.hasText(followUp)) {
                row.put("clinicianNote", followUp);
            }
            String label = h.path("label").asText("").trim();
            String field = h.path("field").asText("").trim();
            String answer = h.path("answerDisplay").asText("").trim();
            if (StringUtils.hasText(label)) {
                row.put("source", label);
            } else if (StringUtils.hasText(field)) {
                row.put("source", field);
            }
            if (StringUtils.hasText(answer)) {
                row.put("answer", answer);
            }
            if (StringUtils.hasText(field)) {
                row.put("field", field);
            }
        }
        return out;
    }

    /**
     * 问卷答案 → 给人看的中文摘要（高亮来源、首诊要点等复用）。
     * 枚举优先用字段 option 文案；病种码走 {@link DiseaseCodeLabels}。
     */
    public static String formatAnswerDisplay(String field, JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        String shortKey = shortFieldKey(field);
        if (v.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode n : v) {
                if (n == null || n.isNull()) {
                    continue;
                }
                String piece = formatSingleAnswerToken(shortKey, n);
                if (!StringUtils.hasText(piece)) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('、');
                }
                sb.append(piece);
            }
            return sb.length() == 0 ? null : truncate(sb.toString());
        }
        if (v.isObject()) {
            // 罕见结构：尽量不把整段 JSON 塞进提示
            return null;
        }
        String piece = formatSingleAnswerToken(shortKey, v);
        return StringUtils.hasText(piece) ? truncate(piece) : null;
    }

    /** 给已有高亮补 answerDisplay（历史答卷 highlightsJson 可能没有）。 */
    public static void enrichHighlightAnswers(JsonNode highlights, JsonNode answers) {
        if (highlights == null || !highlights.isArray() || answers == null || !answers.isObject()) {
            return;
        }
        for (JsonNode h : highlights) {
            if (!(h instanceof ObjectNode obj)) {
                continue;
            }
            if (StringUtils.hasText(obj.path("answerDisplay").asText(""))) {
                continue;
            }
            String field = obj.path("field").asText("");
            if (!StringUtils.hasText(field)) {
                continue;
            }
            String display = formatAnswerDisplay(field, answers.get(field));
            if (StringUtils.hasText(display)) {
                obj.put("answerDisplay", display);
            }
        }
    }

    private static String shortFieldKey(String field) {
        if (!StringUtils.hasText(field)) {
            return "";
        }
        int i = field.lastIndexOf('.');
        return i >= 0 ? field.substring(i + 1) : field;
    }

    private static String formatSingleAnswerToken(String shortKey, JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber() || n.isBoolean()) {
            return n.asText();
        }
        if (!n.isTextual()) {
            return null;
        }
        String code = n.asText().trim();
        if (!StringUtils.hasText(code)) {
            return null;
        }
        if ("presentIllness".equals(shortKey) || "presentIllnessOther".equals(shortKey)) {
            String disease = DiseaseCodeLabels.label(code);
            return StringUtils.hasText(disease) ? disease : code;
        }
        String labeled = enumLabel(shortKey, code);
        if (StringUtils.hasText(labeled)) {
            return labeled;
        }
        String clinical = enumLabelForClinical(shortKey, n);
        return StringUtils.hasText(clinical) ? clinical : code;
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
        if ("goal.mode".equals(field) && v != null && v.isTextual()) {
            return GOAL_MODE_LABELS.getOrDefault(v.asText(), v.asText());
        }
        return formatAnswerDisplay(field, v);
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
        return com.healix.core.vitals.support.MetricPlausibility.isUsefulForPrompt(metricType, value);
    }

    /** 带身高体重上下文；腰围与 BMI 不一致时不可喂给方案模型。 */
    public static boolean isMetricUsefulForPrompt(
            String metricType, BigDecimal value, BigDecimal heightCm, BigDecimal weightKg) {
        return com.healix.core.vitals.support.MetricPlausibility.isUsefulForPrompt(
                metricType, value, heightCm, weightKg);
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
                "SALTY=偏咸、口味重", "OILY=偏油腻，或爱吃油炸",
                "REFINED=主食主要是白米饭、白馒头、面条", "SWEET=常吃甜食或含糖饮料",
                "NONE=以上都不大符合",
                "LIGHT=清淡", "SPICY=偏辣"));
        m.put("dinnerTime", parsePairs(
                "BEFORE_18=下午6点前", "H18_19=下午6–7点", "H19_20=晚上7–8点",
                "H20_21=晚上8–9点", "AFTER_21=晚上9点以后", "IRREGULAR=不固定"));
        m.put("offWork", parsePairs(
                "BEFORE_18=下午6点前", "H18_19=下午6–7点", "H19_20=晚上7–8点",
                "H20_21=晚上8–9点", "AFTER_21=晚上9点以后", "IRREGULAR=不固定", "NOT_WORKING=不上班"));
        m.put("refreshed", parsePairs(
                "YES=完全解乏", "PARTIAL=有些疲惫", "NO=极度疲劳"));
        m.put("mealRelation", parsePairs(
                "BEFORE=饭前吃", "WITH_MEAL=跟第一口饭一起吃", "AFTER=饭后吃",
                "BEDTIME=睡前吃", "IRREGULAR=没固定，想到才吃"));
        m.put("constraints", parsePairs(
                "SOCIAL=应酬多，推不掉", "TAKEOUT=经常要点外卖", "HUNGRY=总是觉得饿",
                "FAMILY=家人不太支持", "NO_TIME=没时间运动", "NONE=暂时没什么特别的阻力"));
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
