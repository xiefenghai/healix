package com.healix.core.water.support;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 饮水目标与限水判定（管理方案 P2.1）。 */
public final class WaterIntakeRules {

    public static final String TASK_CODE = "WATER_LOG";
    public static final String CATEGORY = "WATER";
    public static final int DEFAULT_TARGET_ML = 1600;
    public static final int MIN_TARGET_ML = 1200;
    public static final int MAX_TARGET_ML = 2000;
    public static final int ML_PER_KG = 30;
    public static final int CUP_SMALL_ML = 200;
    public static final int CUP_LARGE_ML = 300;

    private static final String[] FLUID_RESTRICT_KEYWORDS = {
        "心衰",
        "心力衰竭",
        "慢性肾病",
        "CKD",
        "CHRONIC_KIDNEY",
        "KIDNEY_FAILURE",
        "HEART_FAILURE",
        "透析",
        "限水",
        "限制饮水",
        "肾功能不全",
        "肾衰竭"
    };

    private WaterIntakeRules() {}

    public static int suggestTargetMl(Double weightKg) {
        if (weightKg == null || weightKg < 30 || weightKg > 250) {
            return DEFAULT_TARGET_ML;
        }
        int raw = (int) Math.round(weightKg * ML_PER_KG);
        return Math.max(MIN_TARGET_ML, Math.min(MAX_TARGET_ML, raw));
    }

    public static boolean fluidRestricted(List<String> diseaseCodes, JsonNode contextSnapshot) {
        if (diseaseCodes != null) {
            for (String c : diseaseCodes) {
                if (containsRestrictKeyword(c)) {
                    return true;
                }
            }
        }
        if (contextSnapshot == null) {
            return false;
        }
        return containsRestrictKeyword(contextSnapshot.toString());
    }

    public static boolean looksLikeWaterTask(String category, String title, String taskCode) {
        if (TASK_CODE.equalsIgnoreCase(taskCode) || CATEGORY.equalsIgnoreCase(category)) {
            return true;
        }
        if (!StringUtils.hasText(title)) {
            return false;
        }
        String t = title.trim();
        return t.contains("饮水") || t.contains("喝水") || t.contains("足量饮水");
    }

    private static boolean containsRestrictKeyword(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String u = text.toUpperCase(Locale.ROOT);
        for (String kw : FLUID_RESTRICT_KEYWORDS) {
            if (u.contains(kw.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
