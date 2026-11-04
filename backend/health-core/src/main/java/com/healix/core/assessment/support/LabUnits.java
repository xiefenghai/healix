package com.healix.core.assessment.support;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/**
 * 检验值统一单位：血脂 / 血糖 mmol/L，尿酸 / 肌酐 μmol/L，UACR mg/g，HbA1c %。
 *
 * <p>未标单位时按数值量级推断（如 TC=200 显然是 mg/dL）。
 */
public final class LabUnits {

    private LabUnits() {}

    /** TC / LDL-C / HDL-C */
    public static BigDecimal cholesterol(BigDecimal v, String unit) {
        if (v == null) {
            return null;
        }
        boolean mg = unit(unit).contains("mg") || (!unit(unit).contains("mmol") && v.compareTo(BigDecimal.valueOf(20)) > 0);
        return mg ? v.divide(new BigDecimal("38.67"), 2, RoundingMode.HALF_UP) : scale2(v);
    }

    public static BigDecimal triglyceride(BigDecimal v, String unit) {
        if (v == null) {
            return null;
        }
        boolean mg = unit(unit).contains("mg") || (!unit(unit).contains("mmol") && v.compareTo(BigDecimal.valueOf(30)) > 0);
        return mg ? v.divide(new BigDecimal("88.57"), 2, RoundingMode.HALF_UP) : scale2(v);
    }

    public static BigDecimal glucose(BigDecimal v, String unit) {
        if (v == null) {
            return null;
        }
        boolean mg = unit(unit).contains("mg") || (!unit(unit).contains("mmol") && v.compareTo(BigDecimal.valueOf(40)) > 0);
        return mg ? v.divide(new BigDecimal("18"), 1, RoundingMode.HALF_UP) : v.setScale(1, RoundingMode.HALF_UP);
    }

    public static BigDecimal uricAcid(BigDecimal v, String unit) {
        if (v == null) {
            return null;
        }
        String u = unit(unit);
        if (u.contains("mmol") || (!u.contains("mol") && !u.contains("mg") && v.compareTo(BigDecimal.ONE) < 0)) {
            return v.multiply(BigDecimal.valueOf(1000)).setScale(0, RoundingMode.HALF_UP);
        }
        if (u.contains("mg") || (!u.contains("mol") && v.compareTo(BigDecimal.valueOf(30)) < 0)) {
            return v.multiply(new BigDecimal("59.48")).setScale(0, RoundingMode.HALF_UP);
        }
        return v.setScale(0, RoundingMode.HALF_UP);
    }

    public static BigDecimal creatinine(BigDecimal v, String unit) {
        if (v == null) {
            return null;
        }
        String u = unit(unit);
        if (u.contains("mg") || (!u.contains("mol") && v.compareTo(BigDecimal.valueOf(20)) < 0)) {
            return v.multiply(new BigDecimal("88.4")).setScale(0, RoundingMode.HALF_UP);
        }
        return v.setScale(0, RoundingMode.HALF_UP);
    }

    /** UACR → mg/g（mg/mmol × 8.84） */
    public static BigDecimal uacr(BigDecimal v, String unit) {
        if (v == null) {
            return null;
        }
        return unit(unit).contains("mmol") ? v.multiply(new BigDecimal("8.84")).setScale(1, RoundingMode.HALF_UP) : v;
    }

    /** HbA1c → %（IFCC mmol/mol 换算 NGSP） */
    public static BigDecimal hba1c(BigDecimal v, String unit) {
        if (v == null) {
            return null;
        }
        boolean ifcc = unit(unit).contains("mmol") || v.compareTo(BigDecimal.valueOf(25)) > 0;
        if (ifcc) {
            return v.divide(new BigDecimal("10.929"), 4, RoundingMode.HALF_UP)
                    .add(new BigDecimal("2.15"))
                    .setScale(1, RoundingMode.HALF_UP);
        }
        return v;
    }

    private static BigDecimal scale2(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static String unit(String u) {
        return u == null ? "" : u.toLowerCase(Locale.ROOT);
    }
}
