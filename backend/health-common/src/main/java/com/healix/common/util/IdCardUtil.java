package com.healix.common.util;

import java.time.LocalDate;
import java.util.Locale;

/**
 * 中国大陆 18 位身份证：校验、解析生日/性别、脱敏。
 */
public final class IdCardUtil {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdCardUtil() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isValid(String idCardNo) {
        String id = normalize(idCardNo);
        if (id == null || id.length() != 18) {
            return false;
        }
        for (int i = 0; i < 17; i++) {
            if (!Character.isDigit(id.charAt(i))) {
                return false;
            }
        }
        char last = id.charAt(17);
        if (!(Character.isDigit(last) || last == 'X')) {
            return false;
        }
        try {
            parseBirthday(id);
        } catch (Exception e) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11] == last;
    }

    public static LocalDate parseBirthday(String idCardNo) {
        String id = normalize(idCardNo);
        requireNormalized(id);
        int year = Integer.parseInt(id.substring(6, 10));
        int month = Integer.parseInt(id.substring(10, 12));
        int day = Integer.parseInt(id.substring(12, 14));
        return LocalDate.of(year, month, day);
    }

    /** 奇数男、偶数女（第 17 位） */
    public static String parseGenderCode(String idCardNo) {
        String id = normalize(idCardNo);
        requireNormalized(id);
        int flag = id.charAt(16) - '0';
        return (flag % 2 == 1) ? "MALE" : "FEMALE";
    }

    public static String mask(String idCardNo) {
        String id = normalize(idCardNo);
        if (id == null || id.length() < 8) {
            return "****";
        }
        if (id.length() == 18) {
            return id.substring(0, 3) + "***********" + id.substring(14);
        }
        return id.substring(0, 2) + "****" + id.substring(id.length() - 2);
    }

    private static void requireNormalized(String id) {
        if (id == null || id.length() != 18) {
            throw new IllegalArgumentException("Invalid id card length");
        }
    }
}
