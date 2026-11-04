package com.healix.core.storage;

import com.healix.common.exception.BusinessException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 对象键：{@code lab/2026/10/32位hex.jpg}，不含本机路径。 */
public final class ObjectKeys {

    public static final String LAB = "lab";
    public static final String EXAM = "exam";
    public static final String DIET = "diet";
    public static final String MED = "med";

    private static final Set<String> CATEGORIES = Set.of(LAB, EXAM, DIET, MED);
    private static final Pattern KEY =
            Pattern.compile("^(lab|exam|diet|med)/[0-9]{4}/[0-9]{2}/[0-9a-f]{32}\\.(jpg|png|webp)$");

    private ObjectKeys() {}

    public static String newKey(String category, String extension) {
        if (!CATEGORIES.contains(category)) {
            throw new BusinessException("不支持的图片分类");
        }
        LocalDate today = LocalDate.now();
        return category
                + "/"
                + today.getYear()
                + "/"
                + String.format(Locale.ROOT, "%02d", today.getMonthValue())
                + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + "."
                + extension;
    }

    public static String require(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new BusinessException("图片不存在");
        }
        return key;
    }

    public static String categoryOf(String key) {
        String safe = require(key);
        return safe.substring(0, safe.indexOf('/'));
    }
}
