package com.healix.core.assessment.support;

import java.math.BigDecimal;

/** 展示用数值格式化。 */
public final class Fmt {

    private Fmt() {}

    public static String num(BigDecimal v) {
        return v == null ? "—" : v.stripTrailingZeros().toPlainString();
    }

    /** 血压取值口径说明。 */
    public static String bpSource(AssessmentContext ctx) {
        if (ctx.getSbp() == null) {
            return "无血压记录";
        }
        return ctx.getBpReadings() >= 2 ? "近 4 周 " + ctx.getBpReadings() + " 次均值" : "最新一次（近 4 周不足 2 次）";
    }

    public static String bp(AssessmentContext ctx) {
        return num(ctx.getSbp()) + "/" + num(ctx.getDbp()) + " mmHg";
    }

    public static boolean ge(BigDecimal v, String threshold) {
        return v != null && v.compareTo(new BigDecimal(threshold)) >= 0;
    }

    public static boolean gt(BigDecimal v, String threshold) {
        return v != null && v.compareTo(new BigDecimal(threshold)) > 0;
    }

    public static boolean lt(BigDecimal v, String threshold) {
        return v != null && v.compareTo(new BigDecimal(threshold)) < 0;
    }

    public static boolean le(BigDecimal v, String threshold) {
        return v != null && v.compareTo(new BigDecimal(threshold)) <= 0;
    }

    public static boolean between(BigDecimal v, String lowInclusive, String highInclusive) {
        return ge(v, lowInclusive) && le(v, highInclusive);
    }
}
