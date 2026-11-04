package com.healix.core.medication.service;

import com.healix.core.job.support.JobCronSupport;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationIntake;
import com.healix.core.medication.dto.MedicationReminderDto;
import com.healix.core.medication.dto.MedicationScheduleDto;
import com.healix.core.medication.dto.MedicationScheduleDto.Item;
import com.healix.core.medication.dto.MedicationScheduleDto.PrnDose;
import com.healix.core.medication.dto.MedicationScheduleDto.PrnItem;
import com.healix.core.medication.dto.MedicationScheduleDto.SlotGroup;
import com.healix.core.medication.dto.MedicationScheduleDto.Summary;
import com.healix.core.medication.enums.MedicationIntakeStatusEnum;
import com.healix.core.medication.mapper.PeopleMedicationIntakeMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.support.MedicationSlotRules;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 服药时间线：首页卡片、今日服药、提醒扫描共用。 */
@Service
@RequiredArgsConstructor
public class MedicationScheduleService {

    /** PRN 单药每日最多记录次数 */
    public static final int PRN_DAILY_MAX = 6;

    private final PeopleMedicationMapper medicationMapper;
    private final PeopleMedicationIntakeMapper intakeMapper;
    private final MedicationReminderService reminderService;

    public static LocalDate today() {
        return LocalDate.now(JobCronSupport.ZONE);
    }

    /** 可打卡日期：今天与昨天。 */
    public static boolean isEditableDay(LocalDate day) {
        LocalDate today = today();
        return day != null && !day.isAfter(today) && !day.isBefore(today.minusDays(1));
    }

    public MedicationScheduleDto build(String tenantId, String peopleId, LocalDate date) {
        return build(tenantId, peopleId, date, reminderService.get(tenantId, peopleId));
    }

    public MedicationScheduleDto build(
            String tenantId, String peopleId, LocalDate date, MedicationReminderDto reminder) {
        LocalDate today = today();
        LocalDate day = date != null ? date : today;
        boolean isToday = day.equals(today);
        LocalTime nowTime = LocalDateTime.now(JobCronSupport.ZONE).toLocalTime();
        Map<String, String> times = MedicationReminderService.slotTimes(reminder);

        List<PeopleMedication> meds = new ArrayList<>();
        for (PeopleMedication med : medicationMapper.listByPeople(tenantId, peopleId, null)) {
            if (MedicationSlotRules.isDueOnDay(med, day, today)) {
                meds.add(med);
            }
        }
        Map<String, List<PeopleMedicationIntake>> intakesByMed = new HashMap<>();
        for (PeopleMedicationIntake row : intakeMapper.listByPeopleDate(tenantId, peopleId, day)) {
            intakesByMed.computeIfAbsent(row.getMedicationId(), k -> new ArrayList<>()).add(row);
        }

        Map<String, List<Item>> itemsBySlot = new HashMap<>();
        List<PrnItem> prn = new ArrayList<>();
        for (PeopleMedication med : meds) {
            List<String> slots = MedicationSlotRules.slotsOf(med);
            List<PeopleMedicationIntake> rows = intakesByMed.getOrDefault(med.getId(), List.of());
            if (MedicationSlotRules.isPrn(med)) {
                List<PrnDose> doses = new ArrayList<>();
                for (PeopleMedicationIntake row : rows) {
                    if (MedicationIntakeStatusEnum.TAKEN.name().equals(row.getStatus())) {
                        doses.add(new PrnDose(
                                row.getId(), row.getDoseSeq() == null ? 1 : row.getDoseSeq(), row.getTakenAt()));
                    }
                }
                prn.add(new PrnItem(
                        med.getId(),
                        med.getDrugName(),
                        med.getDoseAmount(),
                        med.getDoseUnit(),
                        med.getTimingNote(),
                        doses.size(),
                        PRN_DAILY_MAX,
                        doses));
                continue;
            }
            Map<String, PeopleMedicationIntake> bySlot = new HashMap<>();
            for (PeopleMedicationIntake row : rows) {
                bySlot.putIfAbsent(MedicationSlotRules.effectiveSlot(row.getTimeSlot(), slots), row);
            }
            for (String slot : slots) {
                PeopleMedicationIntake row = bySlot.get(slot);
                itemsBySlot.computeIfAbsent(slot, k -> new ArrayList<>()).add(new Item(
                        med.getId(),
                        med.getDrugName(),
                        med.getDoseAmount(),
                        med.getDoseUnit(),
                        med.getUsageMethod(),
                        med.getTimingNote(),
                        med.getSource(),
                        row == null ? null : row.getId(),
                        row == null ? null : row.getStatus(),
                        row == null ? null : row.getTakenAt(),
                        row != null && Integer.valueOf(1).equals(row.getBackfill())));
            }
        }

        List<SlotGroup> groups = new ArrayList<>();
        int due = 0;
        int taken = 0;
        int missed = 0;
        int skipped = 0;
        int unrecorded = 0;
        String nextSlot = null;
        for (String slot : MedicationSlotRules.TIMED_SLOTS) {
            List<Item> items = itemsBySlot.get(slot);
            if (items == null || items.isEmpty()) {
                continue;
            }
            String time = times.get(slot);
            boolean past = !isToday || !nowTime.isBefore(MedicationReminderService.parseTime(time, LocalTime.NOON));
            int pending = 0;
            for (Item item : items) {
                due++;
                if (item.status() == null) {
                    pending++;
                    unrecorded++;
                } else if (MedicationIntakeStatusEnum.TAKEN.name().equals(item.status())) {
                    taken++;
                } else if (MedicationIntakeStatusEnum.MISSED.name().equals(item.status())) {
                    missed++;
                } else {
                    skipped++;
                }
            }
            if (isToday && nextSlot == null && pending > 0) {
                nextSlot = slot;
            }
            groups.add(new SlotGroup(slot, MedicationSlotRules.label(slot), time, past, pending, items));
        }
        return new MedicationScheduleDto(
                day,
                isToday,
                isEditableDay(day),
                groups,
                prn,
                new Summary(due, taken, missed, skipped, unrecorded),
                nextSlot);
    }
}
