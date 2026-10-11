package com.healix.core.careplan.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.healix.common.util.JsonUtils;
import com.healix.core.careplan.domain.CarePlanTask;
import java.time.LocalDate;
import org.springframework.util.StringUtils;

/** 管理方案任务「某日是否应打」判定，供今日待办与依从性看板共用。 */
public final class CarePlanDueSupport {

    public static final String DEFAULT_TIME_SLOT = "ALL";
    /** 与前端草稿/模板默认周期一致 */
    public static final int DEFAULT_HORIZON_DAYS = 14;

    private CarePlanDueSupport() {}

    /** 从 execution JSON 读取周期天数；缺省为 {@link #DEFAULT_HORIZON_DAYS}。 */
    public static int resolveHorizonDays(String executionJson) {
        if (!StringUtils.hasText(executionJson)) {
            return DEFAULT_HORIZON_DAYS;
        }
        JsonNode root = JsonUtils.readTree(executionJson);
        JsonNode n = root.get("horizonDays");
        if (n == null || n.isNull() || !n.isNumber()) {
            return DEFAULT_HORIZON_DAYS;
        }
        int days = n.asInt(DEFAULT_HORIZON_DAYS);
        return days > 0 ? days : DEFAULT_HORIZON_DAYS;
    }

    /** 执行周期末日（含）：published 日起共 horizonDays 天。 */
    public static LocalDate resolveHorizonEnd(LocalDate planStart, int horizonDays) {
        int days = horizonDays > 0 ? horizonDays : DEFAULT_HORIZON_DAYS;
        return planStart.plusDays(days - 1L);
    }

    /** 是否落在方案执行周期内（含起止日）。 */
    public static boolean isWithinHorizon(LocalDate day, LocalDate planStart, int horizonDays) {
        if (day == null || planStart == null) {
            return false;
        }
        if (day.isBefore(planStart)) {
            return false;
        }
        return !day.isAfter(resolveHorizonEnd(planStart, horizonDays));
    }

    /** MVP：按频次粗筛是否出现在某日待办；周期外一律不应打。 */
    public static boolean isDueOnDate(CarePlanTask task, LocalDate day, LocalDate planStart, int horizonDays) {
        if (!isWithinHorizon(day, planStart, horizonDays)) {
            return false;
        }
        return isDueByFrequency(task, day, planStart);
    }

    /** 无周期信息时按默认 14 天窗口判定。 */
    public static boolean isDueOnDate(CarePlanTask task, LocalDate day, LocalDate planStart) {
        return isDueOnDate(task, day, planStart, DEFAULT_HORIZON_DAYS);
    }

    private static boolean isDueByFrequency(CarePlanTask task, LocalDate day, LocalDate planStart) {
        String freq = task.getFrequency();
        if (!StringUtils.hasText(freq)) {
            return true;
        }
        return switch (freq.toUpperCase()) {
            case "QW" -> day.getDayOfWeek().getValue() == 1;
            case "TIW" -> {
                int dow = day.getDayOfWeek().getValue();
                yield dow == 1 || dow == 3 || dow == 5;
            }
            case "QOD" -> {
                long gap = Math.max(0, planStart.until(day).getDays());
                yield gap % 2 == 0;
            }
            case "PRN" -> true;
            default -> true;
        };
    }

    public static String resolveTimeSlot(String slot) {
        return StringUtils.hasText(slot) ? slot.trim().toUpperCase() : DEFAULT_TIME_SLOT;
    }
}
