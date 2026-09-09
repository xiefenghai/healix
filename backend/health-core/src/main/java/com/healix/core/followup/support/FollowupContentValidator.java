package com.healix.core.followup.support;

import com.healix.common.exception.BusinessException;
import com.healix.core.followup.catalog.FollowupType;
import com.healix.core.vitals.enums.MetricTypeEnum;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

/** 随访表单字段校验：共用外壳 + 按 followupType 的 section。 */
public final class FollowupContentValidator {

    public static final Set<String> CONTACT_TARGETS = Set.of(
            "PATIENT", "PARENT", "SPOUSE", "CHILD", "FRIEND", "OTHER_RELATIVE");
    public static final Set<String> FOLLOWUP_METHODS = Set.of(
            "PHONE", "FACE_TO_FACE", "ONLINE", "WECHAT", "SMS", "CLINIC", "HOME");
    public static final Set<String> ONBOARDING_NEXT_ACTIONS = Set.of(
            "SUPPLEMENT_ARCHIVE", "CREATE_PLAN", "RETEST", "NONE");
    public static final Set<String> LIFESTYLE_LEVELS = Set.of("GOOD", "FAIR", "POOR");
    public static final Set<String> PLAN_SATISFACTIONS = Set.of("SATISFIED", "NEUTRAL", "UNSATISFIED");
    /** 方案执行随访：主要未完成原因 */
    public static final Set<String> PLAN_BLOCKERS = Set.of(
            "NO_TIME", "FORGOT", "TOO_HARD", "DISCOMFORT", "NO_MOTIVATION", "ENV_LIMIT", "OTHER");
    /** 用药随访：漏服频次 */
    public static final Set<String> MISSED_DOSE_FREQUENCIES = Set.of("NONE", "RARELY", "SOMETIMES", "OFTEN");
    /** 用药随访：漏服原因 */
    public static final Set<String> MISSED_DOSE_REASONS = Set.of(
            "FORGOT", "SIDE_EFFECT", "RAN_OUT", "COST", "FEEL_BETTER", "TOO_MANY", "OTHER");
    /** 指标/症状随访：处置结论 */
    public static final Set<String> SYMPTOM_DISPOSITIONS = Set.of(
            "OBSERVE", "RETEST", "VISIT_CLINIC", "URGENT_REFERRAL", "ADJUST_PLAN");
    public static final int TEXT_MAX = 400;

    private FollowupContentValidator() {}

    public static Map<String, Object> validateMetricReview(Map<String, Object> raw) {
        Map<String, Object> c = raw == null ? Map.of() : raw;
        String contactTarget = str(c.get("contactTarget"));
        String followupMethod = str(c.get("followupMethod"));
        String abnormalReason = str(c.get("abnormalReason"));
        String guidance = str(c.get("guidance"));
        requireContact(contactTarget, followupMethod);
        requireText(abnormalReason, "请填写异常原因", "异常原因");
        requireTextLen(guidance, "指导建议");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contactTarget", contactTarget);
        out.put("followupMethod", followupMethod);
        out.put("abnormalReason", abnormalReason);
        if (StringUtils.hasText(guidance)) {
            out.put("guidance", guidance);
        }
        Object hitsRaw = c.get("hits");
        if (hitsRaw instanceof Collection<?> col && !col.isEmpty()) {
            List<Map<String, Object>> hits = new ArrayList<>();
            for (Object item : col) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() != null) {
                            hit.put(String.valueOf(e.getKey()), e.getValue());
                        }
                    }
                    if (!hit.isEmpty()) {
                        hits.add(hit);
                    }
                }
            }
            if (!hits.isEmpty()) {
                out.put("hits", hits);
                out.put("hitCount", hits.size());
            }
        }
        return out;
    }

    public static Map<String, Object> validatePeriodic(Map<String, Object> raw) {
        Map<String, Object> c = raw == null ? Map.of() : raw;
        String followupTypeCode = str(c.get("followupType"));
        if (!FollowupType.CODES.contains(followupTypeCode)) {
            throw new BusinessException(400, "请选择随访类型");
        }
        FollowupType followupType = FollowupType.require(followupTypeCode);
        String contactTarget = str(c.get("contactTarget"));
        String followupMethod = str(c.get("followupMethod"));
        String guidance = str(c.get("guidance"));
        requireContact(contactTarget, followupMethod);
        requireTextLen(guidance, "指导建议");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("followupType", followupTypeCode);
        out.put("contactTarget", contactTarget);
        out.put("followupMethod", followupMethod);
        if (StringUtils.hasText(guidance)) {
            out.put("guidance", guidance);
        }
        Boolean suggest = bool(c.get("suggestPlanAdjust"));
        if (suggest != null) {
            out.put("suggestPlanAdjust", suggest);
        }

        Map<String, Object> sectionRaw = asMap(c.get("section"));
        Map<String, Object> section =
                switch (followupType) {
                    case ONBOARDING -> validateOnboardingSection(sectionRaw);
                    case ROUTINE -> validateRoutineSection(sectionRaw);
                    case PLAN_ADHERENCE -> validatePlanAdherenceSection(sectionRaw);
                    case MEDICATION -> validateMedicationSection(sectionRaw);
                    case SYMPTOM_METRIC -> validateSymptomMetricSection(sectionRaw);
                    case OTHER -> validateGenericSection(sectionRaw);
                };
        out.put("section", section);
        return out;
    }

    public static String requireFollowupType(Map<String, Object> raw) {
        String followupType = str(raw == null ? null : raw.get("followupType"));
        if (!FollowupType.CODES.contains(followupType)) {
            throw new BusinessException(400, "请选择随访类型");
        }
        return followupType;
    }

    /** 列表摘要：优先 section 关键字段。 */
    public static String buildSummary(Map<String, Object> normalized) {
        String typeCode = str(normalized.get("followupType"));
        Map<String, Object> section = asMap(normalized.get("section"));
        if ("ONBOARDING".equals(typeCode)) {
            String next = str(section.get("nextAction"));
            String head = switch (next) {
                case "SUPPLEMENT_ARCHIVE" -> "需补档案";
                case "CREATE_PLAN" -> "待制定方案";
                case "RETEST" -> "安排复测";
                case "NONE" -> "暂无后续动作";
                default -> "入组/首诊";
            };
            List<String> diseaseCodes = stringList(section.get("diseaseCodes"));
            String diseaseHint = diseaseCodes.isEmpty() ? "" : " · 病种" + diseaseCodes.size() + "项";
            return clip(head + " · 已写回档案" + diseaseHint, 200);
        }
        if ("ROUTINE".equals(typeCode)) {
            String sat = str(section.get("planSatisfaction"));
            String level = str(section.get("lifestyleLevel"));
            String satLabel = switch (sat) {
                case "SATISFIED" -> "方案满意";
                case "NEUTRAL" -> "方案一般";
                case "UNSATISFIED" -> "方案不满意";
                default -> "常规回访";
            };
            String levelLabel = switch (level) {
                case "GOOD" -> "习惯良好";
                case "FAIR" -> "习惯一般";
                case "POOR" -> "习惯较差";
                default -> "";
            };
            return clip(satLabel + (levelLabel.isEmpty() ? "" : " · " + levelLabel), 200);
        }
        if ("PLAN_ADHERENCE".equals(typeCode)) {
            Object rate = section.get("selfRatePct");
            String blocker = switch (str(section.get("mainBlocker"))) {
                case "NO_TIME" -> "没时间";
                case "FORGOT" -> "忘记";
                case "TOO_HARD" -> "难度大";
                case "DISCOMFORT" -> "身体不适";
                case "NO_MOTIVATION" -> "缺动力";
                case "ENV_LIMIT" -> "环境限制";
                case "OTHER" -> "其他原因";
                default -> "";
            };
            String head = rate == null ? "方案执行随访" : "自评执行 " + rate + "%";
            return clip(head + (blocker.isEmpty() ? "" : " · 主因" + blocker), 200);
        }
        if ("MEDICATION".equals(typeCode)) {
            String freq = switch (str(section.get("missedDoseFrequency"))) {
                case "NONE" -> "无漏服";
                case "RARELY" -> "偶有漏服";
                case "SOMETIMES" -> "时有漏服";
                case "OFTEN" -> "经常漏服";
                default -> "用药随访";
            };
            Boolean adverse = bool(section.get("hasAdverseReaction"));
            Boolean adjust = bool(section.get("needDoctorAdjust"));
            List<String> tail = new ArrayList<>();
            if (Boolean.TRUE.equals(adverse)) {
                tail.add("有不良反应");
            }
            if (Boolean.TRUE.equals(adjust)) {
                tail.add("需医生调药");
            }
            return clip(freq + (tail.isEmpty() ? "" : " · " + String.join("、", tail)), 200);
        }
        if ("SYMPTOM_METRIC".equals(typeCode)) {
            String disposition = switch (str(section.get("disposition"))) {
                case "OBSERVE" -> "继续观察";
                case "RETEST" -> "安排复测";
                case "VISIT_CLINIC" -> "建议就诊";
                case "URGENT_REFERRAL" -> "紧急转诊";
                case "ADJUST_PLAN" -> "调整方案";
                default -> "指标/症状随访";
            };
            List<String> symptoms = stringList(section.get("symptoms"));
            String head = symptoms.isEmpty() ? str(section.get("symptomNote")) : String.join("、", symptoms);
            return clip(StringUtils.hasText(head) ? head + " · " + disposition : disposition, 200);
        }
        return clip(str(section.get("content")), 200);
    }

    private static Map<String, Object> validateOnboardingSection(Map<String, Object> raw) {
        String nextAction = str(raw.get("nextAction"));
        if (!ONBOARDING_NEXT_ACTIONS.contains(nextAction)) {
            throw new BusinessException(400, "请选择下次动作");
        }
        Boolean archiveWritten = bool(raw.get("archiveWritten"));
        if (archiveWritten == null || !archiveWritten) {
            throw new BusinessException(400, "请先保存患者档案后再办结入组随访");
        }
        List<String> diseaseCodes = stringList(raw.get("diseaseCodes"));
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("nextAction", nextAction);
        section.put("archiveWritten", true);
        if (!diseaseCodes.isEmpty()) {
            section.put("diseaseCodes", diseaseCodes);
        }
        List<Map<String, Object>> baseline = validateBaselineMetrics(raw.get("baselineMetrics"));
        if (!baseline.isEmpty()) {
            section.put("baselineMetrics", baseline);
        }
        return section;
    }

    /**
     * 入组基线指标：{@code [{metricType, value, unit?}]}。
     * 办结时写入 vital_record，故此处只做类型与数值校验。
     */
    public static List<Map<String, Object>> validateBaselineMetrics(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof Collection<?> col) || col.isEmpty()) {
            return out;
        }
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (Object item : col) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Map<String, Object> entry = asMap(m);
            String metricType = str(entry.get("metricType")).toUpperCase();
            String valueRaw = str(entry.get("value"));
            if (!StringUtils.hasText(metricType) && !StringUtils.hasText(valueRaw)) {
                continue;
            }
            MetricTypeEnum metric;
            try {
                metric = MetricTypeEnum.valueOf(metricType);
            } catch (Exception e) {
                throw new BusinessException(400, "基线指标类型不合法: " + metricType);
            }
            if (!StringUtils.hasText(valueRaw)) {
                throw new BusinessException(400, "请填写基线指标数值");
            }
            BigDecimal value;
            try {
                value = new BigDecimal(valueRaw);
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "基线指标数值须为数字");
            }
            if (value.signum() <= 0) {
                throw new BusinessException(400, "基线指标数值须大于 0");
            }
            if (!seen.add(metric.name())) {
                throw new BusinessException(400, "基线指标重复: " + metric.name());
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("metricType", metric.name());
            normalized.put("value", value.stripTrailingZeros().toPlainString());
            String unit = str(entry.get("unit"));
            if (StringUtils.hasText(unit)) {
                normalized.put("unit", unit);
            }
            out.add(normalized);
        }
        return out;
    }

    private static Map<String, Object> validateRoutineSection(Map<String, Object> raw) {
        String adherenceNote = str(raw.get("adherenceNote"));
        requireText(adherenceNote, "请填写近期打卡概况", "近期打卡概况");
        String lifestyleLevel = str(raw.get("lifestyleLevel"));
        if (!LIFESTYLE_LEVELS.contains(lifestyleLevel)) {
            throw new BusinessException(400, "请选择生活习惯执行情况");
        }
        String lifestyleNote = str(raw.get("lifestyleNote"));
        requireTextLen(lifestyleNote, "生活习惯说明");
        String planSatisfaction = str(raw.get("planSatisfaction"));
        if (!PLAN_SATISFACTIONS.contains(planSatisfaction)) {
            throw new BusinessException(400, "请选择方案满意度");
        }
        String unsatisfiedReason = str(raw.get("unsatisfiedReason"));
        if ("UNSATISFIED".equals(planSatisfaction)) {
            requireText(unsatisfiedReason, "不满意时请填写原因", "不满意原因");
        } else {
            requireTextLen(unsatisfiedReason, "不满意原因");
        }
        String symptomNote = str(raw.get("symptomNote"));
        requireTextLen(symptomNote, "症状说明");
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("adherenceNote", adherenceNote);
        section.put("lifestyleLevel", lifestyleLevel);
        if (StringUtils.hasText(lifestyleNote)) {
            section.put("lifestyleNote", lifestyleNote);
        }
        section.put("planSatisfaction", planSatisfaction);
        if (StringUtils.hasText(unsatisfiedReason)) {
            section.put("unsatisfiedReason", unsatisfiedReason);
        }
        if (StringUtils.hasText(symptomNote)) {
            section.put("symptomNote", symptomNote);
        }
        return section;
    }

    /** 方案执行随访：自评执行率 + 未完成主因 + 拟调整项。 */
    private static Map<String, Object> validatePlanAdherenceSection(Map<String, Object> raw) {
        Integer selfRate = intInRange(raw.get("selfRatePct"), 0, 100, "自评执行率");
        if (selfRate == null) {
            throw new BusinessException(400, "请填写自评执行率（0-100）");
        }
        String blocker = str(raw.get("mainBlocker"));
        if (!PLAN_BLOCKERS.contains(blocker)) {
            throw new BusinessException(400, "请选择主要未完成原因");
        }
        String blockerNote = str(raw.get("blockerNote"));
        if ("OTHER".equals(blocker)) {
            requireText(blockerNote, "选择「其他」时请说明原因", "原因说明");
        } else {
            requireTextLen(blockerNote, "原因说明");
        }
        String planChange = str(raw.get("planChange"));
        requireText(planChange, "请填写拟调整项", "拟调整项");

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("selfRatePct", selfRate);
        section.put("mainBlocker", blocker);
        if (StringUtils.hasText(blockerNote)) {
            section.put("blockerNote", blockerNote);
        }
        section.put("planChange", planChange);
        return section;
    }

    /** 用药随访：漏服频次/原因 + 不良反应 + 是否需调药。 */
    private static Map<String, Object> validateMedicationSection(Map<String, Object> raw) {
        String freq = str(raw.get("missedDoseFrequency"));
        if (!MISSED_DOSE_FREQUENCIES.contains(freq)) {
            throw new BusinessException(400, "请选择漏服频次");
        }
        boolean hasMissed = !"NONE".equals(freq);
        String reason = str(raw.get("missedDoseReason"));
        if (hasMissed) {
            if (!MISSED_DOSE_REASONS.contains(reason)) {
                throw new BusinessException(400, "有漏服时请选择漏服原因");
            }
        } else if (StringUtils.hasText(reason) && !MISSED_DOSE_REASONS.contains(reason)) {
            throw new BusinessException(400, "漏服原因取值不合法");
        }
        Boolean hasAdverse = bool(raw.get("hasAdverseReaction"));
        if (hasAdverse == null) {
            throw new BusinessException(400, "请选择是否有不良反应");
        }
        String adverseNote = str(raw.get("adverseNote"));
        if (hasAdverse) {
            requireText(adverseNote, "有不良反应时请填写描述", "不良反应描述");
        } else {
            requireTextLen(adverseNote, "不良反应描述");
        }
        Boolean needAdjust = bool(raw.get("needDoctorAdjust"));

        Map<String, Object> section = new LinkedHashMap<>();
        section.put("missedDoseFrequency", freq);
        if (hasMissed || StringUtils.hasText(reason)) {
            section.put("missedDoseReason", reason);
        }
        section.put("hasAdverseReaction", hasAdverse);
        if (StringUtils.hasText(adverseNote)) {
            section.put("adverseNote", adverseNote);
        }
        section.put("needDoctorAdjust", needAdjust != null && needAdjust);
        return section;
    }

    /** 指标/症状随访：症状清单 + 复测值 + 处置结论。 */
    private static Map<String, Object> validateSymptomMetricSection(Map<String, Object> raw) {
        List<String> symptoms = stringList(raw.get("symptoms"));
        String symptomNote = str(raw.get("symptomNote"));
        if (symptoms.isEmpty() && !StringUtils.hasText(symptomNote)) {
            throw new BusinessException(400, "请填写症状清单或症状说明");
        }
        requireTextLen(symptomNote, "症状说明");
        String retestNote = str(raw.get("retestNote"));
        requireTextLen(retestNote, "复测值");
        String disposition = str(raw.get("disposition"));
        if (!SYMPTOM_DISPOSITIONS.contains(disposition)) {
            throw new BusinessException(400, "请选择处置结论");
        }

        Map<String, Object> section = new LinkedHashMap<>();
        if (!symptoms.isEmpty()) {
            section.put("symptoms", symptoms);
        }
        if (StringUtils.hasText(symptomNote)) {
            section.put("symptomNote", symptomNote);
        }
        if (StringUtils.hasText(retestNote)) {
            section.put("retestNote", retestNote);
        }
        section.put("disposition", disposition);
        return section;
    }

    private static Integer intInRange(Object v, int min, int max, String label) {
        if (v == null || !StringUtils.hasText(String.valueOf(v))) {
            return null;
        }
        int parsed;
        try {
            parsed = (int) Math.round(Double.parseDouble(String.valueOf(v).trim()));
        } catch (NumberFormatException e) {
            throw new BusinessException(400, label + "须为数字");
        }
        if (parsed < min || parsed > max) {
            throw new BusinessException(400, label + "须在 " + min + "-" + max + " 之间");
        }
        return parsed;
    }

    private static Map<String, Object> validateGenericSection(Map<String, Object> raw) {
        String content = str(raw.get("content"));
        requireText(content, "请填写随访内容", "随访内容");
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("content", content);
        return section;
    }

    private static void requireContact(String contactTarget, String followupMethod) {
        if (!CONTACT_TARGETS.contains(contactTarget)) {
            throw new BusinessException(400, "请选择沟通对象");
        }
        if (!FOLLOWUP_METHODS.contains(followupMethod)) {
            throw new BusinessException(400, "请选择随访方式");
        }
    }

    private static void requireText(String value, String emptyMsg, String label) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(400, emptyMsg);
        }
        requireTextLen(value, label);
    }

    private static void requireTextLen(String value, String label) {
        if (value != null && value.length() > TEXT_MAX) {
            throw new BusinessException(400, label + "不能超过" + TEXT_MAX + "字");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, val) -> out.put(String.valueOf(k), val));
            return out;
        }
        return Map.of();
    }

    private static List<String> stringList(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Collection<?> col) {
            for (Object o : col) {
                String s = str(o);
                if (StringUtils.hasText(s)) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String clip(String s, int max) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    public static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    public static Boolean bool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("false".equalsIgnoreCase(s)) {
                return false;
            }
        }
        return null;
    }
}
