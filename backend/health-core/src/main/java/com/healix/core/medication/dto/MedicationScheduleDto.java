package com.healix.core.medication.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** 某日服药时间线：定时时段分组 + 需要时服用。 */
public record MedicationScheduleDto(
        LocalDate date,
        boolean today,
        boolean editable,
        List<SlotGroup> slots,
        List<PrnItem> prn,
        Summary summary,
        /** 首页卡片：第一个仍有未记录药的时段；全部完成为 null */
        String nextSlot) {

    public record SlotGroup(
            String slot, String label, String time, boolean past, int pendingCount, List<Item> items) {}

    public record Item(
            String medicationId,
            String drugName,
            String doseAmount,
            String doseUnit,
            String usageMethod,
            String timingNote,
            String source,
            String intakeId,
            /** TAKEN / MISSED / SKIPPED；未记录为 null */
            String status,
            LocalDateTime takenAt,
            boolean backfill) {}

    public record PrnItem(
            String medicationId,
            String drugName,
            String doseAmount,
            String doseUnit,
            String timingNote,
            int count,
            int max,
            List<PrnDose> doses) {}

    public record PrnDose(String intakeId, int seq, LocalDateTime takenAt) {}

    public record Summary(int due, int taken, int missed, int skipped, int unrecorded) {}
}
