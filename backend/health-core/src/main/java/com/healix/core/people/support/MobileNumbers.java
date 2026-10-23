package com.healix.core.people.support;

import com.healix.common.exception.BusinessException;
import org.springframework.util.StringUtils;

/** 本人手机号收成 1 开头的 11 位；联系人电话可以是座机。 */
public final class MobileNumbers {

    private MobileNumbers() {}

    public static String trimName(String name) {
        return name == null ? "" : name.trim();
    }

    public static boolean sameName(String a, String b) {
        return trimName(a).equals(trimName(b));
    }

    /** 能当本人号则返回 11 位，否则 null。 */
    public static String trySelf(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.startsWith("86") && digits.length() == 13) {
            digits = digits.substring(2);
        }
        if (digits.matches("1\\d{10}")) {
            return digits;
        }
        return null;
    }

    public static String requireSelf(String raw) {
        String mobile = trySelf(raw);
        if (mobile == null) {
            throw new BusinessException(400, "请填写本人手机号");
        }
        return mobile;
    }

    /** 联系人电话：手机或座机，不参与认人。 */
    public static String requireContact(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BusinessException(400, "请填写联系人电话");
        }
        String m = raw.trim();
        if (m.length() > 32 || !m.matches("^[0-9+\\-\\s]{6,20}$")) {
            throw new BusinessException(400, "联系电话格式不正确");
        }
        return m;
    }

    public static String tail(String mobile) {
        if (!StringUtils.hasText(mobile) || mobile.length() < 4) {
            return "";
        }
        return mobile.substring(mobile.length() - 4);
    }
}
