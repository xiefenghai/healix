package com.healix.core.careplan.support;

import com.healix.core.careplan.enums.CarePlanTemplateKeyEnum;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/** 从疾病档案 code/名称识别糖 / 压 / 肥（仅确诊标签，不含评估）。 */
public final class CarePlanDiseaseTags {

    public static final String TAG_DIABETES = "DIABETES";
    public static final String TAG_HYPERTENSION = "HYPERTENSION";
    public static final String TAG_OBESITY = "OBESITY";

    private CarePlanDiseaseTags() {}

    public record Flags(boolean diabetes, boolean hypertension, boolean obesity) {
        public int count() {
            return (diabetes ? 1 : 0) + (hypertension ? 1 : 0) + (obesity ? 1 : 0);
        }

        public List<String> tags() {
            List<String> out = new ArrayList<>(3);
            if (diabetes) {
                out.add(TAG_DIABETES);
            }
            if (hypertension) {
                out.add(TAG_HYPERTENSION);
            }
            if (obesity) {
                out.add(TAG_OBESITY);
            }
            return out;
        }
    }

    public static Flags detect(List<String> diseaseCodes) {
        boolean diabetes = false;
        boolean hypertension = false;
        boolean obesity = false;
        if (diseaseCodes != null) {
            for (String c : diseaseCodes) {
                if (!StringUtils.hasText(c)) {
                    continue;
                }
                String raw = c.trim();
                String u = raw.toUpperCase(Locale.ROOT);
                if (u.contains("DIABETES") || "糖尿病".equals(raw) || raw.contains("糖尿病")) {
                    diabetes = true;
                }
                if (u.contains("HYPERTENSION") || "高血压".equals(raw) || raw.contains("高血压")) {
                    hypertension = true;
                }
                if (u.contains("OBESITY") || u.contains("OBESE") || "肥胖".equals(raw) || raw.contains("肥胖")) {
                    obesity = true;
                }
            }
        }
        return new Flags(diabetes, hypertension, obesity);
    }

    public static CarePlanTemplateKeyEnum recommend(List<String> diseaseCodes) {
        Flags f = detect(diseaseCodes);
        if (f.count() >= 2) {
            return CarePlanTemplateKeyEnum.COMPREHENSIVE;
        }
        if (f.diabetes()) {
            return CarePlanTemplateKeyEnum.DIABETES;
        }
        if (f.hypertension()) {
            return CarePlanTemplateKeyEnum.HYPERTENSION;
        }
        if (f.obesity()) {
            return CarePlanTemplateKeyEnum.OBESITY;
        }
        return CarePlanTemplateKeyEnum.WELLNESS;
    }

    /** 专项模板与档案确诊是否不符 */
    public static boolean specialtyMismatch(CarePlanTemplateKeyEnum key, List<String> diseaseCodes) {
        if (key == null || !key.isSpecialty()) {
            return false;
        }
        Flags f = detect(diseaseCodes);
        return switch (key.canonical()) {
            case DIABETES -> !f.diabetes();
            case HYPERTENSION -> !f.hypertension();
            case OBESITY -> !f.obesity();
            default -> false;
        };
    }

    public static String specialtyMismatchMessage(CarePlanTemplateKeyEnum key) {
        if (key == null) {
            return "已选专项管理但档案未见对应确诊";
        }
        return "已选" + key.label() + "但档案未见对应确诊，请核对病种或改选方案类型";
    }

    /** 合并档案原始 code 与标准 tags，供 snapshot 展示 */
    public static List<String> mergeDisplayTags(List<String> diseaseCodes, Flags flags) {
        Set<String> out = new LinkedHashSet<>();
        if (diseaseCodes != null) {
            for (String c : diseaseCodes) {
                if (StringUtils.hasText(c)) {
                    out.add(c.trim());
                }
            }
        }
        out.addAll(flags.tags());
        return new ArrayList<>(out);
    }
}
