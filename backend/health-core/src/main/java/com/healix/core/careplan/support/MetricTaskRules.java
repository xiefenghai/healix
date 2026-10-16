package com.healix.core.careplan.support;

import com.healix.core.vitals.enums.MetricTypeEnum;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 监测类执行任务（血糖 / 血压 / 体重）：录入对应指标即系统自动完成当日打卡。 */
public final class MetricTaskRules {

    public static final String REF_GLUCOSE = "BLOOD_GLUCOSE";
    public static final String REF_BP = "BLOOD_PRESSURE";
    public static final String REF_WEIGHT = "WEIGHT";

    private static final String CODE_GLUCOSE = "GLUCOSE_CHECK";
    private static final String CODE_BP = "BP_HOME";
    private static final String CODE_WEIGHT = "WEIGHT_LOG";

    private MetricTaskRules() {}

    /** 任务的监测指标；非监测类任务返回 null。Agent 生成的任务编码不固定，优先看 relatedRef。 */
    public static String metricRefOfTask(String relatedRef, String taskCode) {
        String ref = normalize(relatedRef);
        if (REF_GLUCOSE.equals(ref) || REF_BP.equals(ref) || REF_WEIGHT.equals(ref)) {
            return ref;
        }
        if (ref != null && ref.startsWith("BLOOD_PRESSURE")) {
            return REF_BP;
        }
        String code = normalize(taskCode);
        if (CODE_GLUCOSE.equals(code)) {
            return REF_GLUCOSE;
        }
        if (CODE_BP.equals(code)) {
            return REF_BP;
        }
        if (CODE_WEIGHT.equals(code)) {
            return REF_WEIGHT;
        }
        return null;
    }

    /** 指标类型对应的监测任务；不触发打卡的指标返回 null。 */
    public static String metricRefOfMetricType(String metricType) {
        String t = normalize(metricType);
        if (MetricTypeEnum.BLOOD_GLUCOSE.name().equals(t)) {
            return REF_GLUCOSE;
        }
        if (MetricTypeEnum.BLOOD_PRESSURE_SYS.name().equals(t) || MetricTypeEnum.BLOOD_PRESSURE_DIA.name().equals(t)) {
            return REF_BP;
        }
        if (MetricTypeEnum.WEIGHT.name().equals(t)) {
            return REF_WEIGHT;
        }
        return null;
    }

    public static List<String> metricTypesOf(String metricRef) {
        if (REF_GLUCOSE.equals(metricRef)) {
            return List.of(MetricTypeEnum.BLOOD_GLUCOSE.name());
        }
        if (REF_BP.equals(metricRef)) {
            return List.of(MetricTypeEnum.BLOOD_PRESSURE_SYS.name(), MetricTypeEnum.BLOOD_PRESSURE_DIA.name());
        }
        if (REF_WEIGHT.equals(metricRef)) {
            return List.of(MetricTypeEnum.WEIGHT.name());
        }
        return List.of();
    }

    private static String normalize(String s) {
        return StringUtils.hasText(s) ? s.trim().toUpperCase(Locale.ROOT) : null;
    }
}
