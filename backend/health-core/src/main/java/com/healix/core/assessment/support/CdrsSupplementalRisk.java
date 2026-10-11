package com.healix.core.assessment.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 糖尿病高危人群定义命中检测（《中国2型糖尿病防治指南（2020）》）。
 *
 * <p>与 CDRS 评分表为<strong>并列「或」关系</strong>：命中任一高危因素，或评分总分≥25，
 * 均标记为糖尿病高风险人群。本类只负责高危因素侧；不直接改写分数。
 *
 * <p>仅基于当前可采集数据；缺项不编造命中。
 */
public final class CdrsSupplementalRisk {

    private CdrsSupplementalRisk() {}

    public static Map<String, Object> evaluate(AssessmentContext ctx) {
        List<Map<String, Object>> hits = new ArrayList<>();
        List<String> unchecked = new ArrayList<>();

        Integer age = ctx.getAgeYears();
        if (age != null && age >= 40) {
            hits.add(hit("AGE_GE_40", "年龄≥40岁", age + " 岁"));
        }

        boolean central = isCentralObesity(ctx);
        boolean bmiGe24 = ctx.getBmi() != null && ctx.getBmi().compareTo(new BigDecimal("24")) >= 0;
        boolean bmiGe28 = ctx.getBmi() != null && ctx.getBmi().compareTo(new BigDecimal("28")) >= 0;
        if (bmiGe24 || central) {
            StringBuilder detail = new StringBuilder();
            if (bmiGe24) {
                detail
                        .append(bmiGe28 ? "肥胖" : "超重")
                        .append(" BMI ")
                        .append(ctx.getBmi().toPlainString())
                        .append(bmiGe28 ? "（≥28）" : "（≥24）");
            }
            if (central) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail
                        .append("腰围 ")
                        .append(ctx.getWaistCm().toPlainString())
                        .append(" cm（中心性肥胖）");
            }
            hits.add(hit(
                    "BMI_OR_CENTRAL_OBESITY",
                    "超重/肥胖和/或中心性肥胖",
                    detail.toString()));
        }

        if (ctx.isFamilyHistoryCollected()
                && Boolean.TRUE.equals(ctx.getFirstDegreeDiabetesFamilyHistory())) {
            hits.add(hit("FIRST_DEGREE_DM_FH", "一级亲属中有2型糖尿病家族史", "有"));
        }

        if (ctx.isHasHypertensionDiseaseArchive()) {
            hits.add(hit("HYPERTENSION", "高血压或正在接受降压治疗", "已建高血压病种档案"));
        } else {
            boolean sbpHit =
                    ctx.getSbp() != null && ctx.getSbp().compareTo(new BigDecimal("140")) >= 0;
            boolean dbpHit =
                    ctx.getDbp() != null && ctx.getDbp().compareTo(new BigDecimal("90")) >= 0;
            if (sbpHit || dbpHit) {
                StringBuilder detail = new StringBuilder();
                if (sbpHit) {
                    detail.append("收缩压 ").append(ctx.getSbp().toPlainString()).append(" mmHg");
                }
                if (dbpHit) {
                    if (detail.length() > 0) {
                        detail.append("；");
                    }
                    detail.append("舒张压 ").append(ctx.getDbp().toPlainString()).append(" mmHg");
                }
                hits.add(hit("HYPERTENSION", "高血压或正在接受降压治疗", detail.toString()));
            }
        }

        if (ctx.isExerciseCollected()) {
            if ("NONE".equalsIgnoreCase(ctx.getExerciseFrequency())) {
                hits.add(hit("LOW_PHYSICAL_ACTIVITY", "久坐生活方式或久坐少动", "运动频率：基本不运动"));
            }
        } else {
            unchecked.add("久坐少动（运动频率未采集）");
        }

        if (ctx.isPastHistoryCollected()) {
            if (Boolean.TRUE.equals(ctx.getPrediabetesHistory())) {
                hits.add(hit("PREDIABETES", "有糖尿病前期史（IGT/IFG）", "既往史提示"));
            }
        } else {
            unchecked.add("糖尿病前期史（既往史未采集）");
        }

        BigDecimal hdlMmol = toMmol(ctx.getHdlC(), ctx.getHdlUnit(), true);
        BigDecimal tgMmol = toMmol(ctx.getTg(), ctx.getTgUnit(), false);
        if (hdlMmol == null && tgMmol == null) {
            unchecked.add("血脂 HDL-C / TG（检验未采）");
        } else {
            boolean lipidHit = false;
            StringBuilder detail = new StringBuilder();
            // 指南：HDL-C ≤0.91 mmol/L 和/或 TG ≥2.22 mmol/L
            if (hdlMmol != null && hdlMmol.compareTo(new BigDecimal("0.91")) <= 0) {
                lipidHit = true;
                detail.append("HDL-C ").append(hdlMmol.toPlainString()).append(" mmol/L");
            }
            if (tgMmol != null && tgMmol.compareTo(new BigDecimal("2.22")) >= 0) {
                lipidHit = true;
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append("TG ").append(tgMmol.toPlainString()).append(" mmol/L");
            }
            if (lipidHit) {
                hits.add(hit(
                        "DYSLIPIDEMIA",
                        "血脂异常（HDL-C≤0.91 和/或 TG≥2.22 mmol/L）或正在调脂治疗",
                        detail.toString()));
            }
        }

        unchecked.add("妊娠期糖尿病史 / 一过性类固醇糖尿病史（档案未结构化）");
        unchecked.add("ASCVD / PCOS / 黑棘皮症等（档案未结构化）");
        unchecked.add("长期抗精神病药、抗抑郁药、他汀类用药（档案未结构化）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", "糖尿病高危人群定义（与 CDRS 评分表并列判定）");
        out.put("hits", hits);
        out.put("hitCount", hits.size());
        out.put("highRisk", !hits.isEmpty());
        out.put("unchecked", unchecked);
        out.put(
                "advice",
                hits.isEmpty()
                        ? "当前可判读项暂未命中高危因素；缺项见 unchecked，补齐后可再评估"
                        : "已命中 "
                                + hits.size()
                                + " 项高危因素，按指南应标记为糖尿病高风险人群（与评分≥25 为「或」关系；非诊断结论）");
        return out;
    }

    /** 是否命中任一可采集的高危因素。 */
    public static boolean isHighRisk(AssessmentContext ctx) {
        Object high = evaluate(ctx).get("highRisk");
        return Boolean.TRUE.equals(high);
    }

    static boolean isCentralObesity(AssessmentContext ctx) {
        if (ctx.getWaistCm() == null || ctx.getGender() == null) {
            return false;
        }
        boolean male = "MALE".equalsIgnoreCase(ctx.getGender());
        BigDecimal threshold = male ? new BigDecimal("90") : new BigDecimal("85");
        return ctx.getWaistCm().compareTo(threshold) >= 0;
    }

    /** HDL/TG：值很大时按 mg/dL 换算 mmol/L。 */
    public static BigDecimal toMmol(BigDecimal value, String unit, boolean hdl) {
        if (value == null) {
            return null;
        }
        String u = unit == null ? "" : unit.toLowerCase();
        boolean mgdl = u.contains("mg");
        if (!mgdl && value.compareTo(new BigDecimal("8")) > 0) {
            // 未标单位但数值像 mg/dL
            mgdl = true;
        }
        if (mgdl) {
            BigDecimal divisor = hdl ? new BigDecimal("38.67") : new BigDecimal("88.57");
            return value.divide(divisor, 2, RoundingMode.HALF_UP);
        }
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> hit(String code, String label, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("detail", detail);
        return m;
    }
}
