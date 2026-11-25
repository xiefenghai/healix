package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import com.healix.core.careplan.support.CarePlanContextService.CarePlanContext;
import com.healix.core.careplan.support.CarePlanContextService.MetricBrief;
import com.healix.core.careplan.support.CarePlanDiseaseTags.Flags;
import com.healix.core.goal.support.HealthGoalRules;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按方案类型 + 首诊目标 + 最新体征 + 指南默认，解析阶段目标 phaseGoals。
 */
@Component
public class CarePlanPhaseGoalsResolver {

    private static final int DEFAULT_HORIZON_DAYS = 90;
    private static final double BMI_HEALTHY_MIN = 18.5;
    private static final double BMI_HEALTHY_MAX = 24.0;
    private static final int DEFAULT_BP_SYS_MAX = 130;
    private static final int DEFAULT_BP_DIA_MAX = 80;
    private static final double DEFAULT_FPG_MIN = 4.4;
    private static final double DEFAULT_FPG_MAX = 7.0;
    private static final double DEFAULT_HBA1C_MAX = 7.0;
    private static final double DEFAULT_HABIT_MIN = 80.0;

    public ObjectNode resolve(CarePlanTemplateKeyEnum templateKey, CarePlanContext ctx) {
        CarePlanTemplateKeyEnum key =
                (templateKey == null ? CarePlanTemplateKeyEnum.WELLNESS : templateKey).canonical();
        ObjectNode snap = ctx == null || ctx.snapshot() == null ? JsonUtils.emptyObject() : ctx.snapshot();
        JsonNode goals = snap.path("goals");
        Flags flags = CarePlanDiseaseTags.detect(ctx == null ? List.of() : ctx.diseaseCodes());
        Map<String, JsonNode> intakeByMetric = indexIntakeItems(goals);
        Double heightCm = metricDouble(ctx, "HEIGHT");
        Double weightKg = metricDouble(ctx, "WEIGHT");
        if (weightKg == null) {
            weightKg = intakeBaseline(intakeByMetric.get("WEIGHT"));
        }
        boolean lossBlocked = goals.path("lossBlocked").asBoolean(false);

        ObjectNode out = JsonUtils.emptyObject();
        ArrayNode items = out.putArray("items");
        String primary;

        switch (key) {
            case OBESITY -> {
                primary = CarePlanPhaseGoalsSupport.METRIC_WEIGHT;
                addWeightTarget(items, intakeByMetric, weightKg, heightCm, lossBlocked, true);
                putHorizonFromWeight(out, intakeByMetric.get("WEIGHT"));
            }
            case HYPERTENSION -> {
                primary = CarePlanPhaseGoalsSupport.METRIC_BP;
                addBp(items, intakeByMetric);
                putDefaultHorizon(out);
            }
            case DIABETES -> {
                boolean preferHba1c = intakeByMetric.containsKey("HBA1C")
                        || metricDouble(ctx, "HBA1C") != null;
                primary = preferHba1c
                        ? CarePlanPhaseGoalsSupport.METRIC_HBA1C
                        : CarePlanPhaseGoalsSupport.METRIC_FPG;
                addFpg(items, intakeByMetric);
                addHba1c(items, intakeByMetric);
                putDefaultHorizon(out);
            }
            case COMPREHENSIVE -> {
                primary = pickComprehensivePrimary(goals, flags, intakeByMetric, weightKg, heightCm, lossBlocked);
                addItemsForFlags(items, flags, intakeByMetric, weightKg, heightCm, lossBlocked, primary);
                if (CarePlanPhaseGoalsSupport.METRIC_WEIGHT.equals(primary)) {
                    putHorizonFromWeight(out, intakeByMetric.get("WEIGHT"));
                } else {
                    putDefaultHorizon(out);
                }
            }
            default -> {
                // WELLNESS
                if (!lossBlocked && weightKg != null && heightCm != null && heightCm > 0) {
                    primary = CarePlanPhaseGoalsSupport.METRIC_WEIGHT;
                    addWeightMaintainBand(items, weightKg, heightCm);
                } else {
                    primary = CarePlanPhaseGoalsSupport.METRIC_HABIT;
                    addHabit(items);
                }
                putDefaultHorizon(out);
            }
        }

        out.put("primaryMetric", primary);
        // 确保主项一定存在
        if (findItem(items, primary) == null) {
            if (CarePlanPhaseGoalsSupport.METRIC_WEIGHT.equals(primary)) {
                addWeightTarget(items, intakeByMetric, weightKg, heightCm, lossBlocked, false);
            } else if (CarePlanPhaseGoalsSupport.METRIC_BP.equals(primary)) {
                addBp(items, intakeByMetric);
            } else if (CarePlanPhaseGoalsSupport.METRIC_FPG.equals(primary)) {
                addFpg(items, intakeByMetric);
            } else if (CarePlanPhaseGoalsSupport.METRIC_HBA1C.equals(primary)) {
                addHba1c(items, intakeByMetric);
            } else {
                addHabit(items);
                out.put("primaryMetric", CarePlanPhaseGoalsSupport.METRIC_HABIT);
            }
        }
        return CarePlanPhaseGoalsSupport.normalize(out);
    }

    private static void addItemsForFlags(
            ArrayNode items,
            Flags flags,
            Map<String, JsonNode> intake,
            Double weightKg,
            Double heightCm,
            boolean lossBlocked,
            String primary) {
        if (flags.obesity() || CarePlanPhaseGoalsSupport.METRIC_WEIGHT.equals(primary)) {
            boolean forceLoss = CarePlanPhaseGoalsSupport.METRIC_WEIGHT.equals(primary)
                    && !lossBlocked
                    && !"RANGE".equalsIgnoreCase(displayOf(intake.get("WEIGHT")));
            addWeightTarget(items, intake, weightKg, heightCm, lossBlocked, forceLoss);
        }
        if (flags.hypertension() || CarePlanPhaseGoalsSupport.METRIC_BP.equals(primary)) {
            addBp(items, intake);
        }
        if (flags.diabetes()
                || CarePlanPhaseGoalsSupport.METRIC_FPG.equals(primary)
                || CarePlanPhaseGoalsSupport.METRIC_HBA1C.equals(primary)) {
            addFpg(items, intake);
            addHba1c(items, intake);
        }
        if (items.isEmpty()) {
            addHabit(items);
        }
    }

    private static String pickComprehensivePrimary(
            JsonNode goals,
            Flags flags,
            Map<String, JsonNode> intake,
            Double weightKg,
            Double heightCm,
            boolean lossBlocked) {
        String mode = goals.path("mode").asText("");
        if (!StringUtils.hasText(mode)) {
            mode = goals.path("phase").asText("");
        }
        String m = mode.trim().toUpperCase(Locale.ROOT);
        if (HealthGoalRules.MODE_LOSS.equals(m) || "LOSE".equals(m) || "FAT_LOSS".equals(m)) {
            return CarePlanPhaseGoalsSupport.METRIC_WEIGHT;
        }
        if (HealthGoalRules.MODE_GLUCOSE_CONTROL.equals(m) || "GLUCOSE".equals(m)) {
            return intake.containsKey("HBA1C")
                    ? CarePlanPhaseGoalsSupport.METRIC_HBA1C
                    : CarePlanPhaseGoalsSupport.METRIC_FPG;
        }
        if (HealthGoalRules.MODE_BP_CONTROL.equals(m) || "BP".equals(m)) {
            return CarePlanPhaseGoalsSupport.METRIC_BP;
        }

        JsonNode tracks = goals.path("careTracks");
        if (!tracks.isArray()) {
            tracks = goals.path("preferences").path("careTracks");
        }
        if (tracks.isArray()) {
            for (JsonNode t : tracks) {
                String code = t == null ? "" : t.asText("").trim().toUpperCase(Locale.ROOT);
                if ("WEIGHT_LOSS".equals(code) || "OBESITY".equals(code)) {
                    return CarePlanPhaseGoalsSupport.METRIC_WEIGHT;
                }
                if ("DIABETES".equals(code) || "GLUCOSE".equals(code)) {
                    return CarePlanPhaseGoalsSupport.METRIC_FPG;
                }
                if ("HYPERTENSION".equals(code) || "BP".equals(code)) {
                    return CarePlanPhaseGoalsSupport.METRIC_BP;
                }
            }
        }

        if (flags.obesity()) {
            return CarePlanPhaseGoalsSupport.METRIC_WEIGHT;
        }
        if (flags.diabetes()) {
            return CarePlanPhaseGoalsSupport.METRIC_FPG;
        }
        if (flags.hypertension()) {
            return CarePlanPhaseGoalsSupport.METRIC_BP;
        }
        if (!lossBlocked && weightKg != null && heightCm != null) {
            return CarePlanPhaseGoalsSupport.METRIC_WEIGHT;
        }
        return CarePlanPhaseGoalsSupport.METRIC_HABIT;
    }

    private static void addWeightTarget(
            ArrayNode items,
            Map<String, JsonNode> intake,
            Double weightKg,
            Double heightCm,
            boolean lossBlocked,
            boolean preferLoss) {
        if (findItem(items, CarePlanPhaseGoalsSupport.METRIC_WEIGHT) != null) {
            return;
        }
        JsonNode intakeWeight = intake.get("WEIGHT");
        Double target = intakeTarget(intakeWeight);
        Double baseline = weightKg != null ? weightKg : intakeBaseline(intakeWeight);
        String source = intakeWeight != null && target != null
                ? CarePlanPhaseGoalsSupport.SOURCE_INTAKE
                : CarePlanPhaseGoalsSupport.SOURCE_GUIDELINE;

        if (lossBlocked || !preferLoss) {
            addWeightMaintainBand(items, baseline, heightCm);
            return;
        }

        if (target == null && baseline != null && heightCm != null && heightCm > 0) {
            double mid = healthyMidKg(heightCm);
            if (baseline > mid + 0.5) {
                target = round1(mid);
            }
        }
        if (target == null || baseline == null) {
            addWeightMaintainBand(items, baseline, heightCm);
            return;
        }
        // 目标 BMI < 18.5 时降级为维持带
        if (heightCm != null && heightCm > 0) {
            double meters = heightCm / 100.0;
            double targetBmi = target / (meters * meters);
            if (targetBmi < BMI_HEALTHY_MIN) {
                addWeightMaintainBand(items, baseline, heightCm);
                return;
            }
        }

        ObjectNode item = items.addObject();
        item.put("metric", CarePlanPhaseGoalsSupport.METRIC_WEIGHT);
        item.put("display", CarePlanPhaseGoalsSupport.DISPLAY_TARGET);
        item.put("unit", "kg");
        item.put("baseline", round1(baseline));
        item.put("target", round1(target));
        item.put("source", source);
        copyHorizonFields(intakeWeight, item);
    }

    private static void addWeightMaintainBand(ArrayNode items, Double weightKg, Double heightCm) {
        if (findItem(items, CarePlanPhaseGoalsSupport.METRIC_WEIGHT) != null) {
            return;
        }
        ObjectNode item = items.addObject();
        item.put("metric", CarePlanPhaseGoalsSupport.METRIC_WEIGHT);
        item.put("display", CarePlanPhaseGoalsSupport.DISPLAY_RANGE);
        item.put("unit", "kg");
        item.put("source", CarePlanPhaseGoalsSupport.SOURCE_GUIDELINE);
        if (weightKg != null) {
            item.put("baseline", round1(weightKg));
        }
        if (heightCm != null && heightCm > 0) {
            double meters = heightCm / 100.0;
            item.put("min", round1(BMI_HEALTHY_MIN * meters * meters));
            item.put("max", round1(BMI_HEALTHY_MAX * meters * meters));
        } else if (weightKg != null) {
            item.put("min", round1(weightKg * 0.97));
            item.put("max", round1(weightKg * 1.03));
        }
    }

    private static void addBp(ArrayNode items, Map<String, JsonNode> intake) {
        if (findItem(items, CarePlanPhaseGoalsSupport.METRIC_BP) != null) {
            return;
        }
        JsonNode sys = intake.get("BP_SYS");
        JsonNode dia = intake.get("BP_DIA");
        Integer sysMax = asInt(sys == null ? null : sys.get("target"));
        Integer diaMax = asInt(dia == null ? null : dia.get("target"));
        String source = (sysMax != null || diaMax != null)
                ? CarePlanPhaseGoalsSupport.SOURCE_INTAKE
                : CarePlanPhaseGoalsSupport.SOURCE_GUIDELINE;
        if (sysMax == null) {
            sysMax = DEFAULT_BP_SYS_MAX;
        }
        if (diaMax == null) {
            diaMax = DEFAULT_BP_DIA_MAX;
        }
        ObjectNode item = items.addObject();
        item.put("metric", CarePlanPhaseGoalsSupport.METRIC_BP);
        item.put("display", CarePlanPhaseGoalsSupport.DISPLAY_RANGE);
        item.put("unit", "mmHg");
        item.put("sysMax", sysMax);
        item.put("diaMax", diaMax);
        item.put("source", source);
        Double sysBase = intakeBaseline(sys);
        Double diaBase = intakeBaseline(dia);
        if (sysBase != null) {
            item.put("baseline", sysBase);
        } else if (diaBase != null) {
            item.put("baseline", diaBase);
        }
    }

    private static void addFpg(ArrayNode items, Map<String, JsonNode> intake) {
        if (findItem(items, CarePlanPhaseGoalsSupport.METRIC_FPG) != null) {
            return;
        }
        JsonNode fpg = intake.get("FPG");
        Double target = intakeTarget(fpg);
        Double min = DEFAULT_FPG_MIN;
        Double max = target != null ? target : DEFAULT_FPG_MAX;
        if (target != null && target < min) {
            min = round1(Math.max(3.9, target - 0.5));
        }
        String source = fpg != null && (target != null || fpg.has("target"))
                ? CarePlanPhaseGoalsSupport.SOURCE_INTAKE
                : CarePlanPhaseGoalsSupport.SOURCE_GUIDELINE;
        ObjectNode item = items.addObject();
        item.put("metric", CarePlanPhaseGoalsSupport.METRIC_FPG);
        item.put("display", CarePlanPhaseGoalsSupport.DISPLAY_RANGE);
        item.put("unit", "mmol/L");
        item.put("min", min);
        item.put("max", max);
        item.put("source", source);
        Double baseline = intakeBaseline(fpg);
        if (baseline != null) {
            item.put("baseline", baseline);
        }
    }

    private static void addHba1c(ArrayNode items, Map<String, JsonNode> intake) {
        if (findItem(items, CarePlanPhaseGoalsSupport.METRIC_HBA1C) != null) {
            return;
        }
        JsonNode hba1c = intake.get("HBA1C");
        // 无首诊糖化目标且无档案值时，不强制塞默认项（避免干扰主项）
        Double target = intakeTarget(hba1c);
        if (hba1c == null && target == null) {
            return;
        }
        Double max = target != null ? target : DEFAULT_HBA1C_MAX;
        ObjectNode item = items.addObject();
        item.put("metric", CarePlanPhaseGoalsSupport.METRIC_HBA1C);
        item.put("display", CarePlanPhaseGoalsSupport.DISPLAY_RANGE);
        item.put("unit", "%");
        item.put("max", max);
        item.put("source", target != null
                ? CarePlanPhaseGoalsSupport.SOURCE_INTAKE
                : CarePlanPhaseGoalsSupport.SOURCE_GUIDELINE);
        Double baseline = intakeBaseline(hba1c);
        if (baseline != null) {
            item.put("baseline", baseline);
        }
    }

    private static void addHabit(ArrayNode items) {
        if (findItem(items, CarePlanPhaseGoalsSupport.METRIC_HABIT) != null) {
            return;
        }
        ObjectNode item = items.addObject();
        item.put("metric", CarePlanPhaseGoalsSupport.METRIC_HABIT);
        item.put("display", CarePlanPhaseGoalsSupport.DISPLAY_RANGE);
        item.put("unit", "%");
        item.put("min", DEFAULT_HABIT_MIN);
        item.put("source", CarePlanPhaseGoalsSupport.SOURCE_GUIDELINE);
    }

    private static void putDefaultHorizon(ObjectNode out) {
        out.put("horizonDays", DEFAULT_HORIZON_DAYS);
        out.put("horizonLabel", "约3个月");
    }

    private static void putHorizonFromWeight(ObjectNode out, JsonNode weightItem) {
        String horizon = weightItem == null ? null : weightItem.path("horizon").asText(null);
        if (!StringUtils.hasText(horizon)) {
            putDefaultHorizon(out);
            return;
        }
        String code = horizon.trim().toUpperCase(Locale.ROOT);
        Integer days = switch (code) {
            case "M1" -> 30;
            case "M3" -> 90;
            case "M6" -> 180;
            case "M12" -> 365;
            case "FLEXIBLE" -> DEFAULT_HORIZON_DAYS;
            default -> null;
        };
        String label = switch (code) {
            case "M1" -> "约1个月";
            case "M3" -> "约3个月";
            case "M6" -> "约6个月";
            case "M12" -> "约1年";
            case "FLEXIBLE" -> "先稳住节奏";
            default -> null;
        };
        if (days == null) {
            putDefaultHorizon(out);
            return;
        }
        out.put("horizonDays", days);
        out.put("horizonLabel", label);
        if (weightItem != null && weightItem.hasNonNull("deadline")) {
            // deadline 留给 item；此处不重复
        } else if (!"FLEXIBLE".equals(code)) {
            // no-op
        }
    }

    private static void copyHorizonFields(JsonNode intakeWeight, ObjectNode item) {
        if (intakeWeight == null) {
            return;
        }
        String horizon = intakeWeight.path("horizon").asText(null);
        if (StringUtils.hasText(horizon)) {
            item.put("horizon", horizon.trim());
        }
        String deadline = intakeWeight.path("deadline").asText(null);
        if (StringUtils.hasText(deadline)) {
            item.put("deadline", deadline.trim());
        } else if (StringUtils.hasText(horizon)) {
            Integer days = switch (horizon.trim().toUpperCase(Locale.ROOT)) {
                case "M1" -> 30;
                case "M3" -> 90;
                case "M6" -> 180;
                case "M12" -> 365;
                default -> null;
            };
            if (days != null) {
                item.put("deadline", LocalDate.now().plusDays(days).toString());
            }
        }
    }

    private static Map<String, JsonNode> indexIntakeItems(JsonNode goals) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        if (goals == null || goals.isNull()) {
            return map;
        }
        JsonNode items = goals.get("items");
        if (items != null && items.isArray()) {
            for (JsonNode n : items) {
                if (n == null || !n.isObject()) {
                    continue;
                }
                String metric = n.path("metric").asText(null);
                if (StringUtils.hasText(metric)) {
                    map.put(metric.trim().toUpperCase(Locale.ROOT), n);
                }
            }
            return map;
        }
        // 兼容 object 形态
        if (items != null && items.isObject()) {
            items.fields().forEachRemaining(e -> {
                if (e.getValue() != null && e.getValue().isObject()) {
                    map.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
                }
            });
        }
        return map;
    }

    private static Double metricDouble(CarePlanContext ctx, String type) {
        if (ctx == null || ctx.latestMetrics() == null) {
            return null;
        }
        for (MetricBrief m : ctx.latestMetrics()) {
            if (m != null && type.equalsIgnoreCase(m.metricType())) {
                try {
                    return Double.parseDouble(m.value());
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static Double intakeTarget(JsonNode item) {
        return item == null ? null : CarePlanPhaseGoalsSupport.asDouble(item.get("target"));
    }

    private static Double intakeBaseline(JsonNode item) {
        return item == null ? null : CarePlanPhaseGoalsSupport.asDouble(item.get("baseline"));
    }

    private static String displayOf(JsonNode item) {
        return item == null ? null : item.path("display").asText(null);
    }

    private static ObjectNode findItem(ArrayNode items, String metric) {
        if (items == null || !StringUtils.hasText(metric)) {
            return null;
        }
        for (JsonNode n : items) {
            if (n != null && n.isObject() && metric.equalsIgnoreCase(n.path("metric").asText(""))) {
                return (ObjectNode) n;
            }
        }
        return null;
    }

    private static double healthyMidKg(double heightCm) {
        double meters = heightCm / 100.0;
        double midBmi = (BMI_HEALTHY_MIN + BMI_HEALTHY_MAX) / 2.0;
        return midBmi * meters * meters;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static Integer asInt(JsonNode n) {
        return CarePlanPhaseGoalsSupport.asInt(n);
    }
}
