package com.healix.core.assessment.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

/**
 * 高血压评估：血压分级 / 易患人群 / 心脑血管病风险（主要危险因素计数）。
 *
 * <p>依据《中国高血压健康管理规范（2019）》。仅基于可采集数据；缺项不编造命中。
 */
public final class HypertensionRiskRules {

    private HypertensionRiskRules() {}

    public static final String GRADE_NORMAL = "NORMAL";
    public static final String GRADE_PRE = "PREHYPERTENSION";
    public static final String GRADE_1 = "GRADE_1";
    public static final String GRADE_2 = "GRADE_2";
    public static final String GRADE_3 = "GRADE_3";

    /** 血压水平分级：收缩压/舒张压取较高一级。 */
    public static String classifyBpGrade(BigDecimal sbp, BigDecimal dbp) {
        int s = gradeBySbp(sbp);
        int d = gradeByDbp(dbp);
        int g = Math.max(s, d);
        return switch (g) {
            case 3 -> GRADE_3;
            case 2 -> GRADE_2;
            case 1 -> GRADE_1;
            case 0 -> GRADE_PRE;
            default -> GRADE_NORMAL;
        };
    }

    public static boolean isIsolatedSystolic(BigDecimal sbp, BigDecimal dbp) {
        return sbp != null
                && dbp != null
                && sbp.compareTo(new BigDecimal("140")) >= 0
                && dbp.compareTo(new BigDecimal("90")) < 0;
    }

    public static String bpGradeLabel(String grade) {
        return switch (grade) {
            case GRADE_NORMAL -> "正常血压";
            case GRADE_PRE -> "高血压前期";
            case GRADE_1 -> "1级高血压";
            case GRADE_2 -> "2级高血压";
            case GRADE_3 -> "3级高血压";
            default -> grade;
        };
    }

    /** 易患人群：命中任一因素。 */
    public static Map<String, Object> evaluateSusceptible(AssessmentContext ctx, String bpGrade) {
        List<Map<String, Object>> hits = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();

        if (GRADE_PRE.equals(bpGrade)) {
            hits.add(hit(
                    "PREHYPERTENSION",
                    "高血压前期",
                    "SBP "
                            + plain(ctx.getSbp())
                            + " / DBP "
                            + plain(ctx.getDbp())
                            + " mmHg"));
        }

        Integer age = ctx.getAgeYears();
        if (age != null && age >= 45) {
            hits.add(hit("AGE_GE_45", "年龄≥45岁", age + " 岁"));
        }

        boolean central = isCentralObesity(ctx);
        boolean bmiGe24 = ctx.getBmi() != null && ctx.getBmi().compareTo(new BigDecimal("24")) >= 0;
        if (bmiGe24 || central) {
            StringBuilder detail = new StringBuilder();
            if (bmiGe24) {
                detail.append("BMI ").append(ctx.getBmi().toPlainString());
            }
            if (central) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("腰围 ").append(ctx.getWaistCm().toPlainString()).append(" cm");
            }
            hits.add(hit("BMI_OR_CENTRAL", "BMI≥24 或中心性肥胖", detail.toString()));
        }

        if (ctx.isFamilyHistoryCollected()) {
            if (Boolean.TRUE.equals(ctx.getFirstDegreeHypertensionFamilyHistory())) {
                hits.add(hit("FH_HTN", "高血压家族史", "一级亲属有高血压"));
            }
        } else {
            unchecked.add("高血压家族史（未采集）");
        }

        if (ctx.isDietCollected()) {
            if (isHighSaltDiet(ctx)) {
                hits.add(hit("HIGH_SALT", "高盐饮食", dietDetail(ctx)));
            }
        } else {
            unchecked.add("高盐饮食（饮食偏好未采集）");
        }

        if (ctx.isDrinkingCollected()) {
            if (isHeavyDrinking(ctx)) {
                hits.add(hit("HEAVY_DRINKING", "长期大量饮酒", drinkingDetail(ctx)));
            }
        } else {
            unchecked.add("长期大量饮酒（饮酒史未采集）");
        }

        if (ctx.isSmokingCollected()) {
            if (isCurrentSmoker(ctx.getSmokingStatus())) {
                hits.add(hit("SMOKING", "吸烟（含被动吸烟）", smokingLabel(ctx.getSmokingStatus())));
            }
        } else {
            unchecked.add("吸烟（未采集）");
        }

        if (ctx.isExerciseCollected()) {
            if ("NONE".equalsIgnoreCase(ctx.getExerciseFrequency())) {
                hits.add(hit("SEDENTARY", "缺乏体力活动", "运动频率：基本不运动"));
            }
        } else {
            unchecked.add("缺乏体力活动（运动频率未采集）");
        }

        unchecked.add("长期精神紧张（档案未结构化）");
        unchecked.add("被动吸烟（档案未结构化）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "高血压易患人群");
        out.put("hits", hits);
        out.put("hitCount", hits.size());
        out.put("susceptible", !hits.isEmpty());
        out.put("unchecked", unchecked);
        out.put(
                "advice",
                hits.isEmpty()
                        ? "当前可判读项暂未命中易患因素"
                        : "已命中 " + hits.size() + " 项易患因素，属高血压易患人群，建议重点预防");
        return out;
    }

    /** 心脑血管病主要危险因素计数（用于高危判定）。 */
    public static Map<String, Object> evaluateMajorRiskFactors(AssessmentContext ctx) {
        List<Map<String, Object>> hits = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();

        Integer age = ctx.getAgeYears();
        boolean male = "MALE".equalsIgnoreCase(ctx.getGender());
        if (age != null && StringUtils.hasText(ctx.getGender()) && !"UNKNOWN".equalsIgnoreCase(ctx.getGender())) {
            if (male && age > 55) {
                hits.add(hit("AGE_MALE_GT_55", "年龄（男>55岁）", age + " 岁"));
            } else if (!male && age > 65) {
                hits.add(hit("AGE_FEMALE_GT_65", "年龄（女>65岁）", age + " 岁"));
            }
        } else if (age == null || !StringUtils.hasText(ctx.getGender())) {
            unchecked.add("年龄/性别（用于男>55 / 女>65 计数）");
        }

        if (ctx.isSmokingCollected()) {
            if (isCurrentSmoker(ctx.getSmokingStatus())) {
                hits.add(hit("SMOKING", "吸烟", smokingLabel(ctx.getSmokingStatus())));
            }
        } else {
            unchecked.add("吸烟");
        }

        boolean glucoseChecked = false;
        if (ctx.getFastingGlucose() != null) {
            glucoseChecked = true;
            BigDecimal f = ctx.getFastingGlucose();
            if (f.compareTo(new BigDecimal("6.1")) >= 0 && f.compareTo(new BigDecimal("6.9")) <= 0) {
                hits.add(hit("IFG", "空腹血糖受损", f.toPlainString() + " mmol/L"));
            }
        }
        if (ctx.getPostprandialGlucose() != null) {
            glucoseChecked = true;
            BigDecimal p = ctx.getPostprandialGlucose();
            if (p.compareTo(new BigDecimal("7.8")) >= 0 && p.compareTo(new BigDecimal("11.0")) <= 0) {
                hits.add(hit("IGT", "糖耐量受损（餐后2h）", p.toPlainString() + " mmol/L"));
            }
        }
        if (ctx.isPastHistoryCollected() && Boolean.TRUE.equals(ctx.getPrediabetesHistory())) {
            glucoseChecked = true;
            if (hits.stream().noneMatch(h -> "IFG".equals(h.get("code")) || "IGT".equals(h.get("code")))) {
                hits.add(hit("PREDIABETES_HX", "糖耐量/空腹血糖受损史", "既往史提示"));
            }
        }
        if (!glucoseChecked) {
            unchecked.add("血糖异常（空腹/餐后血糖未采）");
        }

        BigDecimal tc = CdrsSupplementalRisk.toMmol(ctx.getTc(), ctx.getTcUnit(), false);
        BigDecimal ldl = CdrsSupplementalRisk.toMmol(ctx.getLdlC(), ctx.getLdlUnit(), false);
        BigDecimal hdl = CdrsSupplementalRisk.toMmol(ctx.getHdlC(), ctx.getHdlUnit(), true);
        if (tc == null && ldl == null && hdl == null) {
            unchecked.add("血脂（TC / LDL-C / HDL-C 未采）");
        } else {
            boolean lipidHit = false;
            StringBuilder detail = new StringBuilder();
            if (tc != null && tc.compareTo(new BigDecimal("5.7")) >= 0) {
                lipidHit = true;
                detail.append("TC ").append(tc.toPlainString());
            }
            if (ldl != null && ldl.compareTo(new BigDecimal("3.3")) > 0) {
                lipidHit = true;
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("LDL-C ").append(ldl.toPlainString());
            }
            if (hdl != null && hdl.compareTo(new BigDecimal("1.0")) < 0) {
                lipidHit = true;
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("HDL-C ").append(hdl.toPlainString());
            }
            if (lipidHit) {
                hits.add(hit("DYSLIPIDEMIA", "血脂异常", detail + " mmol/L"));
            }
        }

        if (ctx.isFamilyHistoryCollected()) {
            if (Boolean.TRUE.equals(ctx.getEarlyAscvdFamilyHistory())) {
                hits.add(hit("EARLY_ASCVD_FH", "早发心血管疾病家族史", "一级亲属早发心肌梗死"));
            } else if (Boolean.TRUE.equals(ctx.getFirstDegreeAscvdFamilyHistory())) {
                unchecked.add("早发心血管病家族史（有 ASCVD 家族史但未标注发病年龄）");
            }
        } else {
            unchecked.add("早发心血管疾病家族史");
        }

        boolean central = isCentralObesity(ctx);
        boolean bmiGe28 = ctx.getBmi() != null && ctx.getBmi().compareTo(new BigDecimal("28")) >= 0;
        if (central || bmiGe28) {
            StringBuilder detail = new StringBuilder();
            if (bmiGe28) {
                detail.append("BMI ").append(ctx.getBmi().toPlainString()).append("（≥28）");
            }
            if (central) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("腰围 ").append(ctx.getWaistCm().toPlainString()).append(" cm");
            }
            hits.add(hit("OBESITY", "中心性肥胖或 BMI≥28", detail.toString()));
        }

        if (ctx.isExerciseCollected()) {
            if ("NONE".equalsIgnoreCase(ctx.getExerciseFrequency())) {
                hits.add(hit("SEDENTARY", "静坐生活方式", "运动频率：基本不运动"));
            }
        } else {
            unchecked.add("静坐生活方式");
        }

        if (ctx.getHeartRate() != null) {
            if (ctx.getHeartRate().compareTo(new BigDecimal("80")) > 0) {
                hits.add(hit(
                        "RESTING_HR",
                        "静息心率>80次/min",
                        ctx.getHeartRate().toPlainString() + " 次/min"));
            }
        } else {
            unchecked.add("静息心率");
        }

        if (ctx.getUricAcid() != null && StringUtils.hasText(ctx.getGender())) {
            BigDecimal ua = toUmol(ctx.getUricAcid(), ctx.getUricAcidUnit());
            BigDecimal thr = male ? new BigDecimal("420") : new BigDecimal("360");
            if (ua != null && ua.compareTo(thr) > 0) {
                hits.add(hit(
                        "HYPERURICEMIA",
                        "高尿酸血症",
                        ua.toPlainString() + " μmol/L（阈值 " + thr.toPlainString() + "）"));
            }
        } else {
            unchecked.add("高尿酸血症（尿酸未采）");
        }

        unchecked.add("早发停经（<50岁，档案未结构化）");
        unchecked.add("24h 尿钠（未采）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "心脑血管病主要危险因素");
        out.put("hits", hits);
        out.put("hitCount", hits.size());
        out.put("unchecked", unchecked);
        return out;
    }

    /**
     * 心脑血管病风险高危：
     * <ul>
     *   <li>3级 → 高危
     *   <li>2级 + ≥1 因素 → 高危
     *   <li>前期或1级 + ≥3 因素 → 高危
     * </ul>
     */
    public static boolean isCvHighRisk(String bpGrade, int majorFactorCount) {
        if (GRADE_3.equals(bpGrade)) {
            return true;
        }
        if (GRADE_2.equals(bpGrade) && majorFactorCount >= 1) {
            return true;
        }
        if ((GRADE_1.equals(bpGrade) || GRADE_PRE.equals(bpGrade)) && majorFactorCount >= 3) {
            return true;
        }
        return false;
    }

    public static String adviceFor(
            String bpGrade, boolean susceptible, boolean cvHigh, int majorFactorCount) {
        if (GRADE_3.equals(bpGrade)) {
            return "3级高血压：心脑血管病风险高危（无论危险因素数量）。建议立即就医，启动药物治疗（非诊断结论，供健管参考）";
        }
        if (GRADE_2.equals(bpGrade) && cvHigh) {
            return "2级高血压且合并 "
                    + majorFactorCount
                    + " 个主要危险因素，心脑血管病风险高危。建议就医评估并启动药物治疗";
        }
        if (GRADE_1.equals(bpGrade) && cvHigh) {
            return "1级高血压且合并 ≥3 个主要危险因素，心脑血管病风险高危。建议就医评估，考虑药物治疗";
        }
        if (GRADE_PRE.equals(bpGrade) && cvHigh) {
            return "高血压前期且合并 ≥3 个主要危险因素，心脑血管病风险高危。建议强化生活方式干预，3–6 个月复查血压";
        }
        if (susceptible) {
            return "属高血压易患人群（心脑血管病风险暂非高危）。建议生活方式干预，每 3–6 个月测量血压";
        }
        if (GRADE_NORMAL.equals(bpGrade)) {
            return "血压正常且暂未命中易患/高危条件。建议每年测量血压 1–2 次";
        }
        return "血压分级为"
                + bpGradeLabel(bpGrade)
                + "，主要危险因素 "
                + majorFactorCount
                + " 个，心脑血管病风险暂非高危。建议生活方式干预并定期复测";
    }

    static boolean isCentralObesity(AssessmentContext ctx) {
        if (ctx.getWaistCm() == null || !StringUtils.hasText(ctx.getGender())) {
            return false;
        }
        boolean male = "MALE".equalsIgnoreCase(ctx.getGender());
        BigDecimal thr = male ? new BigDecimal("90") : new BigDecimal("85");
        return ctx.getWaistCm().compareTo(thr) >= 0;
    }

    static boolean isCurrentSmoker(String status) {
        if (!StringUtils.hasText(status)) {
            return false;
        }
        String s = status.toUpperCase(Locale.ROOT);
        return "CURRENT".equals(s) || "OCCASIONAL".equals(s);
    }

    static boolean isHeavyDrinking(AssessmentContext ctx) {
        String status = ctx.getDrinkingStatus();
        if (!StringUtils.hasText(status)) {
            return false;
        }
        String s = status.toUpperCase(Locale.ROOT);
        if ("NEVER".equals(s) || "FORMER".equals(s)) {
            return false;
        }
        String freq = ctx.getDrinkingFrequency() == null ? "" : ctx.getDrinkingFrequency().toUpperCase(Locale.ROOT);
        if ("DAILY".equals(freq) || "WEEKLY_3".equals(freq)) {
            return true;
        }
        String amount = ctx.getDrinkingAmountPerDay() == null ? "" : ctx.getDrinkingAmountPerDay();
        return amount.contains("大量") || amount.contains("醉") || amount.matches("(?i).*\\b([5-9]|\\d{2,}).*");
    }

    static boolean isHighSaltDiet(AssessmentContext ctx) {
        String type = ctx.getDietType() == null ? "" : ctx.getDietType().toUpperCase(Locale.ROOT);
        if (type.contains("LOW_SALT")) {
            return false;
        }
        String pref = ctx.getDietPreference() == null ? "" : ctx.getDietPreference();
        return pref.contains("高盐")
                || pref.contains("偏咸")
                || pref.contains("口味重")
                || pref.contains("重口味")
                || pref.contains("咸食");
    }

    private static int gradeBySbp(BigDecimal sbp) {
        if (sbp == null) {
            return -1;
        }
        double v = sbp.doubleValue();
        if (v >= 180) {
            return 3;
        }
        if (v >= 160) {
            return 2;
        }
        if (v >= 140) {
            return 1;
        }
        if (v >= 120) {
            return 0;
        }
        return -1;
    }

    private static int gradeByDbp(BigDecimal dbp) {
        if (dbp == null) {
            return -1;
        }
        double v = dbp.doubleValue();
        if (v >= 110) {
            return 3;
        }
        if (v >= 100) {
            return 2;
        }
        if (v >= 90) {
            return 1;
        }
        if (v >= 80) {
            return 0;
        }
        return -1;
    }

    private static BigDecimal toUmol(BigDecimal value, String unit) {
        if (value == null) {
            return null;
        }
        String u = unit == null ? "" : unit.toLowerCase(Locale.ROOT);
        if (u.contains("mmol")) {
            return value.multiply(new BigDecimal("1000")).setScale(0, java.math.RoundingMode.HALF_UP);
        }
        return value;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    private static String dietDetail(AssessmentContext ctx) {
        if (StringUtils.hasText(ctx.getDietPreference())) {
            return ctx.getDietPreference();
        }
        return ctx.getDietType();
    }

    private static String drinkingDetail(AssessmentContext ctx) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(ctx.getDrinkingStatus())) {
            sb.append(ctx.getDrinkingStatus());
        }
        if (StringUtils.hasText(ctx.getDrinkingFrequency())) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(ctx.getDrinkingFrequency());
        }
        if (StringUtils.hasText(ctx.getDrinkingAmountPerDay())) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(ctx.getDrinkingAmountPerDay());
        }
        return sb.toString();
    }

    private static String smokingLabel(String status) {
        if (!StringUtils.hasText(status)) {
            return "";
        }
        return switch (status.toUpperCase(Locale.ROOT)) {
            case "CURRENT" -> "当前吸烟";
            case "OCCASIONAL" -> "偶尔吸烟";
            default -> status;
        };
    }

    private static Map<String, Object> hit(String code, String label, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("detail", detail);
        return m;
    }
}
