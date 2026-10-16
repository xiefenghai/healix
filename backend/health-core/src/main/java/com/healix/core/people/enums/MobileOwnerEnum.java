package com.healix.core.people.enums;

import com.healix.common.exception.BusinessException;
import java.util.Locale;
import org.springframework.util.StringUtils;

/** 联系电话归属：老人用子女号码、夫妻共用号码时标明是谁的号。 */
public enum MobileOwnerEnum {
    SELF("本人"),
    SPOUSE("配偶"),
    CHILD("子女"),
    PARENT("父母"),
    RELATIVE("其他亲属"),
    OTHER("其他");

    private final String label;

    MobileOwnerEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 空值视为本人；非法值报 400。 */
    public static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return SELF.name();
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "号码归属不正确");
        }
    }
}
