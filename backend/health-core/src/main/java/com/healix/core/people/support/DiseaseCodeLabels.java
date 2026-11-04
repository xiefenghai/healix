package com.healix.core.people.support;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * 病种档案 code → 中文展示名。字典存英文 code（如 diabetes），UI / Agent 对外用中文。
 */
public final class DiseaseCodeLabels {

    private DiseaseCodeLabels() {}

    /** 占位/无病选项，不应进入管理方案病种列表或模型上下文。 */
    public static boolean isMeaningful(String code) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        String key = code.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "NONE", "NULL", "N/A", "NA", "UNKNOWN", "无", "无病", "无疾病" -> false;
            default -> true;
        };
    }

    public static String label(String code) {
        if (!StringUtils.hasText(code)) {
            return "";
        }
        if (!isMeaningful(code)) {
            return "";
        }
        String key = code.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "diabetes", "type2_diabetes", "t2dm", "dm" -> "糖尿病";
            case "type1_diabetes", "t1dm" -> "1型糖尿病";
            case "hypertension", "htn" -> "高血压";
            case "obesity" -> "肥胖症";
            case "cad", "coronary_heart_disease" -> "冠心病";
            case "stroke", "cerebrovascular" -> "脑卒中";
            case "copd" -> "慢阻肺";
            case "ckd", "chronic_kidney_disease" -> "慢性肾病";
            case "hyperlipidemia" -> "高脂血症";
            case "gout" -> "痛风";
            case "osteoporosis" -> "骨质疏松";
            case "chronic_hepatitis", "hepatitis" -> "慢性肝炎";
            case "ascvd" -> "动脉粥样硬化性心血管疾病";
            default -> {
                // 已是中文则原样返回
                if (code.codePoints().anyMatch(Character::isIdeographic)) {
                    yield code.trim();
                }
                yield code.trim();
            }
        };
    }
}
