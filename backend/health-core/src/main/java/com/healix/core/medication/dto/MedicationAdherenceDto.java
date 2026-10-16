package com.healix.core.medication.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 区间服药依从：每日汇总 + 药 × 日 × 时段网格。 */
public record MedicationAdherenceDto(
        LocalDate from, LocalDate to, Stats stats, List<Day> days, List<MedRow> meds) {

    public record Stats(
            int due,
            int taken,
            int missed,
            int skipped,
            int unrecorded,
            /** 已服 ÷ 应服；无应服为 null */
            Integer ratePercent,
            /** 按时（距提醒 ≤ 60 分钟且非补记）÷ 已服；无已服为 null */
            Integer onTimePercent,
            int prnCount) {}

    public record Day(
            LocalDate date, int due, int taken, int missed, int skipped, int unrecorded, int backfill) {}

    public record MedRow(
            String medicationId, String drugName, boolean prn, List<String> slots, List<Cell> cells) {}

    /**
     * @param status TAKEN / MISSED / SKIPPED / UNRECORDED；未在用为 NA；PRN 为当日次数格
     */
    public record Cell(
            LocalDate date, String slot, String status, LocalDateTime takenAt, boolean backfill, int count) {}
}
