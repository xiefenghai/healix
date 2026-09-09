package com.healix.core.observation.enums;

import org.springframework.util.StringUtils;

/**
 * 健康观测数据来源（指标 / 检验 / 检查共用语义）。
 *
 * <p>编码 = 录入主体 × 录入方式。展示文案 B/C 端统一。
 *
 * <p>兼容历史值：{@code SELF}→用户录入，{@code MANUAL}→医护代录，
 * 裸 {@code OCR} 按是否有员工 ID 归到 STAFF_OCR / PATIENT_OCR。
 */
public enum HealthDataSourceEnum {
    PATIENT("用户录入"),
    PATIENT_OCR("用户录入-OCR识别"),
    STAFF("医护代录"),
    STAFF_OCR("医护代录-OCR识别"),
    DEVICE("设备同步");

    private final String label;

    HealthDataSourceEnum(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 写入检验/检查：B 端手工。 */
    public static HealthDataSourceEnum staffManual() {
        return STAFF;
    }

    /** 写入检验：B 端 OCR 预填后保存。 */
    public static HealthDataSourceEnum staffOcr() {
        return STAFF_OCR;
    }

    /** 写入检验/检查：C 端手工。 */
    public static HealthDataSourceEnum patientManual() {
        return PATIENT;
    }

    /** 写入检验：C 端 OCR 预填后保存。 */
    public static HealthDataSourceEnum patientOcr() {
        return PATIENT_OCR;
    }

    /**
     * 规范化入库码（B 端检验）。
     *
     * <p>接受新码与历史 {@code MANUAL}/{@code OCR}/{@code PATIENT}。
     */
    public static String resolveStaffWrite(String raw) {
        HealthDataSourceEnum e = parse(raw);
        if (e == STAFF_OCR || e == PATIENT_OCR) {
            // B 端 OCR 一律记为医护代录-OCR
            return STAFF_OCR.name();
        }
        if (e == PATIENT) {
            return PATIENT.name();
        }
        return STAFF.name();
    }

    /** 规范化入库码（C 端检验）。仅允许患者侧。 */
    public static String resolvePatientWrite(String raw) {
        HealthDataSourceEnum e = parse(raw);
        if (e == PATIENT_OCR || e == STAFF_OCR) {
            return PATIENT_OCR.name();
        }
        return PATIENT.name();
    }

    /** 检查报告：B 手工 → STAFF；C 手工 → PATIENT。 */
    public static String resolveExamStaffWrite() {
        return STAFF.name();
    }

    public static String resolveExamPatientWrite() {
        return PATIENT.name();
    }

    /** 读出展示/API：归一历史码。 */
    public static HealthDataSourceEnum normalize(String raw, String createdByStaffId) {
        if (!StringUtils.hasText(raw)) {
            return StringUtils.hasText(createdByStaffId) ? STAFF : PATIENT;
        }
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "PATIENT", "SELF" -> PATIENT;
            case "PATIENT_OCR" -> PATIENT_OCR;
            case "STAFF", "MANUAL" -> STAFF;
            case "STAFF_OCR" -> STAFF_OCR;
            case "DEVICE" -> DEVICE;
            case "OCR" -> StringUtils.hasText(createdByStaffId) ? STAFF_OCR : PATIENT_OCR;
            case "LIS" -> STAFF; // 预留：机构检验系统代录，展示归医护代录
            default -> {
                try {
                    yield HealthDataSourceEnum.valueOf(s);
                } catch (IllegalArgumentException ex) {
                    yield StringUtils.hasText(createdByStaffId) ? STAFF : PATIENT;
                }
            }
        };
    }

    public static String labelOf(String raw, String createdByStaffId) {
        return normalize(raw, createdByStaffId).label();
    }

    /** 是否为用户侧上报（含历史 SELF / 用户 OCR）。 */
    public static boolean isPatientOwned(String raw) {
        if (!StringUtils.hasText(raw)) {
            return false;
        }
        String s = raw.trim().toUpperCase();
        return "PATIENT".equals(s)
                || "PATIENT_OCR".equals(s)
                || "SELF".equals(s);
    }

    private static HealthDataSourceEnum parse(String raw) {
        if (!StringUtils.hasText(raw)) {
            return STAFF;
        }
        String s = raw.trim().toUpperCase();
        return switch (s) {
            case "PATIENT", "SELF" -> PATIENT;
            case "PATIENT_OCR" -> PATIENT_OCR;
            case "STAFF", "MANUAL" -> STAFF;
            case "STAFF_OCR", "OCR" -> STAFF_OCR; // 写入解析时裸 OCR 默认按员工 OCR 处理，由 resolve* 再分流
            case "DEVICE" -> DEVICE;
            default -> {
                try {
                    yield HealthDataSourceEnum.valueOf(s);
                } catch (IllegalArgumentException ex) {
                    yield STAFF;
                }
            }
        };
    }
}
