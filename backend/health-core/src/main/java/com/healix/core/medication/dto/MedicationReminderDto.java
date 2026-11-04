package com.healix.core.medication.dto;

/** 服药提醒设置。 */
public record MedicationReminderDto(
        boolean enabled,
        String morningAt,
        String noonAt,
        String eveningAt,
        String bedtimeAt,
        int followUpMin) {}
