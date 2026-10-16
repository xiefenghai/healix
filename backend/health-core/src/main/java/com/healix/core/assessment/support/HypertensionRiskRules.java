package com.healix.core.assessment.support;

import static com.healix.core.assessment.support.FactorCollector.SRC_ARCHIVE;
import static com.healix.core.assessment.support.FactorCollector.SRC_LAB;
import static com.healix.core.assessment.support.FactorCollector.SRC_METRIC;
import static com.healix.core.assessment.support.FactorCollector.SRC_QUESTIONNAIRE;

import com.healix.core.assessment.catalog.InputField;
import java.math.BigDecimal;

/**
 * 高血压患病风险规则，《中国高血压健康管理规范（2019）》：血压分级 / 易患因素 / 心脑血管病主要危险因素。
 *
 * <p>仅基于可采集数据；未知项进「未核实」，不编造命中。
 */
public final class HypertensionRiskRules {

    private HypertensionRiskRules() {}

    public static final String GRADE_NORMAL = "NORMAL";
    public static final String GRADE_PRE = "PREHYPERTENSION";
    public static final String GRADE_1 = "GRADE_1";
    public static final String GRADE_2 = "GRADE_2";
    public static final String GRADE_3 = "GRADE_3";

    /** 收缩压 / 舒张压取较高一级。 */
    public static String classifyBpGrade(BigDecimal sbp, BigDecimal dbp) {
        int g = Math.max(gradeBySbp(sbp), gradeByDbp(dbp));
        return switch (g) {
            case 3 -> GRADE_3;
            case 2 -> GRADE_2;
            case 1 -> GRADE_1;
            case 0 -> GRADE_PRE;
            default -> GRADE_NORMAL;
        };
    }

    public static int gradeRank(String grade) {
        return switch (grade) {
            case GRADE_3 -> 3;
            case GRADE_2 -> 2;
            case GRADE_1 -> 1;
            case GRADE_PRE -> 0;
            default -> -1;
        };
    }

    public static boolean isIsolatedSystolic(BigDecimal sbp, BigDecimal dbp) {
        return Fmt.ge(sbp, "140") && Fmt.lt(dbp, "90");
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

    /** 易患人群因素（命中任一即易患）。 */
    public static FactorCollector susceptible(AssessmentContext ctx, String bpGrade) {
        FactorCollector c = new FactorCollector("HTN_SUSCEPTIBLE", "高血压易患因素");
        if (GRADE_PRE.equals(bpGrade)) {
            c.hit("PREHYPERTENSION", "高血压前期", Fmt.bp(ctx), SRC_METRIC);
        }
        Integer age = ctx.getAgeYears();
        if (age == null) {
            c.unchecked(InputField.BIRTHDAY, "年龄≥45岁");
        } else if (age >= 45) {
            c.hit("AGE_GE_45", "年龄≥45岁", age + " 岁", SRC_ARCHIVE);
        }
        boolean bmi24 = Fmt.ge(ctx.getBmi(), "24");
        Boolean central = ctx.centralObesity();
        if (bmi24 || Boolean.TRUE.equals(central)) {
            c.hit("OVERWEIGHT_OR_CENTRAL", "超重/肥胖或中心性肥胖",
                    (bmi24 ? "BMI " + Fmt.num(ctx.getBmi()) : "")
                            + (Boolean.TRUE.equals(central) ? (bmi24 ? "；" : "") + "腰围 " + Fmt.num(ctx.getWaistCm()) + " cm" : ""),
                    SRC_METRIC);
        } else if (ctx.getBmi() == null && central == null) {
            c.unchecked(InputField.WEIGHT, "超重/肥胖或中心性肥胖");
        }
        c.check(ctx.isFamilyHistoryCollected() ? ctx.getFhHypertension() : null,
                InputField.FAMILY_HISTORY, "FH_HYPERTENSION", "高血压家族史", null, SRC_ARCHIVE);
        c.check(ctx.highSalt(), InputField.HIGH_SALT, "HIGH_SALT", "高盐饮食", null,
                ctx.answered(InputField.HIGH_SALT) ? SRC_QUESTIONNAIRE : SRC_ARCHIVE);
        c.check(ctx.heavyDrinking(), InputField.DRINKING, "HEAVY_DRINKING", "长期大量饮酒", null, SRC_ARCHIVE);
        Boolean smoke = smokingIncludingPassive(ctx);
        c.check(smoke, InputField.SMOKING, "SMOKING", "吸烟（含被动吸烟）", smokingDetail(ctx), SRC_ARCHIVE);
        c.check(ctx.sedentary(), InputField.EXERCISE, "SEDENTARY", "缺乏体力活动", "基本不运动", SRC_ARCHIVE);
        c.fact(ctx, InputField.CHRONIC_STRESS, "CHRONIC_STRESS", "长期精神紧张");
        return c;
    }

    /** 心脑血管病主要危险因素（健康管理规范口径）。 */
    public static FactorCollector majorRiskFactors(AssessmentContext ctx) {
        FactorCollector c = new FactorCollector("CV_MAJOR_RF", "心脑血管病主要危险因素");
        Integer age = ctx.getAgeYears();
        if (age == null || !ctx.hasGender()) {
            c.unchecked(age == null ? InputField.BIRTHDAY : InputField.GENDER, "年龄（男>55 / 女>65）");
        } else if ((ctx.isMale() && age > 55) || (ctx.isFemale() && age > 65)) {
            c.hit("AGE", "年龄（男>55 / 女>65）", age + " 岁", SRC_ARCHIVE);
        }
        c.check(ctx.currentSmoker(), InputField.SMOKING, "SMOKING", "吸烟", smokingDetail(ctx), SRC_ARCHIVE);
        c.check(impairedGlucose(ctx), InputField.GLUCOSE, "IMPAIRED_GLUCOSE", "糖耐量受损或空腹血糖异常",
                glucoseDetail(ctx), SRC_METRIC);
        Boolean lipid = null;
        if (Fmt.ge(ctx.getTc(), "5.7") || Fmt.gt(ctx.getLdlC(), "3.3") || Fmt.lt(ctx.getHdlC(), "1.0")) {
            lipid = true;
        } else if (ctx.getTc() != null || ctx.getLdlC() != null || ctx.getHdlC() != null) {
            lipid = false;
        }
        c.check(lipid, InputField.LIPIDS, "DYSLIPIDEMIA", "血脂异常（TC≥5.7 / LDL-C>3.3 / HDL-C<1.0）", lipidDetail(ctx), SRC_LAB);
        c.check(ctx.isFamilyHistoryCollected() ? ctx.getFhEarlyAscvd() : null,
                InputField.FAMILY_HISTORY, "EARLY_CVD_FH", "早发心血管病家族史", null, SRC_ARCHIVE);
        boolean bmi28 = Fmt.ge(ctx.getBmi(), "28");
        Boolean central = ctx.centralObesity();
        if (bmi28 || Boolean.TRUE.equals(central)) {
            c.hit("OBESITY", "腹型肥胖或 BMI≥28",
                    bmi28 ? "BMI " + Fmt.num(ctx.getBmi()) : "腰围 " + Fmt.num(ctx.getWaistCm()) + " cm", SRC_METRIC);
        } else if (ctx.getBmi() == null && central == null) {
            c.unchecked(InputField.WEIGHT, "腹型肥胖或 BMI≥28");
        }
        c.check(ctx.sedentary(), InputField.EXERCISE, "SEDENTARY", "静坐生活方式", "基本不运动", SRC_ARCHIVE);
        Boolean hr = ctx.getHeartRate() == null ? null : Fmt.gt(ctx.getHeartRate(), "80");
        c.check(hr, InputField.HEART_RATE, "RESTING_HR", "静息心率>80次/分", Fmt.num(ctx.getHeartRate()) + " 次/分", SRC_METRIC);
        Boolean ua = null;
        if (ctx.getUricAcid() != null && ctx.hasGender()) {
            ua = Fmt.gt(ctx.getUricAcid(), ctx.isMale() ? "420" : "360");
        }
        c.check(ua, InputField.URIC_ACID, "HYPERURICEMIA", "高尿酸血症", Fmt.num(ctx.getUricAcid()) + " μmol/L", SRC_LAB);
        if (!ctx.isMale()) {
            c.fact(ctx, InputField.EARLY_MENOPAUSE, "EARLY_MENOPAUSE", "早发绝经（<50岁）");
        }
        return c;
    }

    /** 3级；2级 + ≥1 因素；前期或1级 + ≥3 因素 → 心脑血管病风险高危。 */
    public static boolean isCvHighRisk(String bpGrade, int majorFactorCount) {
        return GRADE_3.equals(bpGrade)
                || (GRADE_2.equals(bpGrade) && majorFactorCount >= 1)
                || ((GRADE_1.equals(bpGrade) || GRADE_PRE.equals(bpGrade)) && majorFactorCount >= 3);
    }

    public static Boolean smokingIncludingPassive(AssessmentContext ctx) {
        Boolean active = ctx.currentSmoker();
        Boolean passive = ctx.fact(InputField.PASSIVE_SMOKING);
        if (Boolean.TRUE.equals(active) || Boolean.TRUE.equals(passive)) {
            return true;
        }
        return active == null ? null : false;
    }

    /** 空腹 6.1–6.9 / 餐后 7.8–11.0 / 档案或问卷糖尿病前期。 */
    public static Boolean impairedGlucose(AssessmentContext ctx) {
        if (Fmt.between(ctx.getFastingGlucose(), "6.1", "6.99")
                || Fmt.between(ctx.getPostprandialGlucose(), "7.8", "11.09")
                || Boolean.TRUE.equals(ctx.prediabetes())) {
            return true;
        }
        if (ctx.getFastingGlucose() != null || ctx.getPostprandialGlucose() != null || ctx.prediabetes() != null) {
            return false;
        }
        return null;
    }

    static String glucoseDetail(AssessmentContext ctx) {
        if (ctx.getFastingGlucose() != null) {
            return "空腹 " + Fmt.num(ctx.getFastingGlucose()) + " mmol/L";
        }
        if (ctx.getPostprandialGlucose() != null) {
            return "餐后 " + Fmt.num(ctx.getPostprandialGlucose()) + " mmol/L";
        }
        return "糖尿病前期史";
    }

    static String lipidDetail(AssessmentContext ctx) {
        return "TC " + Fmt.num(ctx.getTc()) + " / LDL-C " + Fmt.num(ctx.getLdlC()) + " / HDL-C " + Fmt.num(ctx.getHdlC()) + " mmol/L";
    }

    static String smokingDetail(AssessmentContext ctx) {
        if (Boolean.TRUE.equals(ctx.currentSmoker())) {
            return "OCCASIONAL".equalsIgnoreCase(ctx.getSmokingStatus()) ? "偶尔吸烟" : "当前吸烟";
        }
        if (Boolean.TRUE.equals(ctx.fact(InputField.PASSIVE_SMOKING))) {
            return "被动吸烟";
        }
        return null;
    }

    private static int gradeBySbp(BigDecimal sbp) {
        if (sbp == null) {
            return -1;
        }
        double v = sbp.doubleValue();
        return v >= 180 ? 3 : v >= 160 ? 2 : v >= 140 ? 1 : v >= 120 ? 0 : -1;
    }

    private static int gradeByDbp(BigDecimal dbp) {
        if (dbp == null) {
            return -1;
        }
        double v = dbp.doubleValue();
        return v >= 110 ? 3 : v >= 100 ? 2 : v >= 90 ? 1 : v >= 80 ? 0 : -1;
    }
}
