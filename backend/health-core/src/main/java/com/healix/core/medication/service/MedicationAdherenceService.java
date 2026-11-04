package com.healix.core.medication.service;

import com.healix.core.job.support.JobCronSupport;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.domain.PeopleMedicationIntake;
import com.healix.core.medication.dto.MedicationAdherenceDto;
import com.healix.core.medication.dto.MedicationAdherenceDto.Cell;
import com.healix.core.medication.dto.MedicationAdherenceDto.Day;
import com.healix.core.medication.dto.MedicationAdherenceDto.MedRow;
import com.healix.core.medication.dto.MedicationAdherenceDto.Stats;
import com.healix.core.medication.enums.MedicationIntakeStatusEnum;
import com.healix.core.medication.mapper.PeopleMedicationIntakeMapper;
import com.healix.core.medication.mapper.PeopleMedicationMapper;
import com.healix.core.medication.support.MedicationSlotRules;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 区间服药依从统计。
 *
 * <p>口径：应服 = 当天在用定时药 × 时段（今天尚未到点且未记录的时段不计）；依从率 = 已服 ÷ 应服；
 * 按时率 = 非补记且服药时刻距提醒时间 ≤ 60 分钟 ÷ 已服。
 */
@Service
@RequiredArgsConstructor
public class MedicationAdherenceService {

    public static final int MAX_DAYS = 90;
    private static final long ON_TIME_MINUTES = 60;

    public static final String UNRECORDED = "UNRECORDED";
    public static final String PENDING = "PENDING";
    public static final String NA = "NA";

    private final PeopleMedicationMapper medicationMapper;
    private final PeopleMedicationIntakeMapper intakeMapper;
    private final MedicationReminderService reminderService;

    public MedicationAdherenceDto range(String tenantId, String peopleId, int days) {
        int n = Math.max(1, Math.min(days <= 0 ? 14 : days, MAX_DAYS));
        LocalDate today = MedicationScheduleService.today();
        LocalDate from = today.minusDays(n - 1L);
        LocalTime nowTime = LocalDateTime.now(JobCronSupport.ZONE).toLocalTime();
        Map<String, String> times = MedicationReminderService.slotTimes(reminderService.get(tenantId, peopleId));

        List<PeopleMedication> meds = medicationMapper.listByPeople(tenantId, peopleId, null);
        Map<String, Map<LocalDate, List<PeopleMedicationIntake>>> intakes = new HashMap<>();
        for (PeopleMedicationIntake row : intakeMapper.listByPeopleRange(tenantId, peopleId, from, today)) {
            intakes.computeIfAbsent(row.getMedicationId(), k -> new HashMap<>())
                    .computeIfAbsent(row.getIntakeDate(), k -> new ArrayList<>())
                    .add(row);
        }

        Map<LocalDate, int[]> dayAgg = new HashMap<>();
        int onTime = 0;
        int prnCount = 0;
        List<MedRow> rows = new ArrayList<>();
        for (PeopleMedication med : meds) {
            boolean prn = MedicationSlotRules.isPrn(med);
            List<String> slots = MedicationSlotRules.slotsOf(med);
            List<Cell> cells = new ArrayList<>();
            boolean everActive = false;
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                boolean active = MedicationSlotRules.isDueOnDay(med, d, today);
                List<PeopleMedicationIntake> dayRows =
                        intakes.getOrDefault(med.getId(), Map.of()).getOrDefault(d, List.of());
                if (prn) {
                    int count = (int) dayRows.stream()
                            .filter(r -> MedicationIntakeStatusEnum.TAKEN.name().equals(r.getStatus()))
                            .count();
                    if (active || count > 0) {
                        everActive = true;
                    }
                    prnCount += count;
                    cells.add(new Cell(d, MedicationSlotRules.PRN, active ? null : NA, null, false, count));
                    continue;
                }
                Map<String, PeopleMedicationIntake> bySlot = new HashMap<>();
                for (PeopleMedicationIntake r : dayRows) {
                    bySlot.putIfAbsent(MedicationSlotRules.effectiveSlot(r.getTimeSlot(), slots), r);
                }
                int[] agg = dayAgg.computeIfAbsent(d, k -> new int[6]);
                for (String slot : slots) {
                    if (!active) {
                        cells.add(new Cell(d, slot, NA, null, false, 0));
                        continue;
                    }
                    everActive = true;
                    PeopleMedicationIntake r = bySlot.get(slot);
                    LocalTime slotTime = MedicationReminderService.parseTime(times.get(slot), LocalTime.NOON);
                    if (r == null) {
                        boolean future = d.equals(today) && nowTime.isBefore(slotTime);
                        if (!future) {
                            agg[0]++;
                            agg[4]++;
                        }
                        cells.add(new Cell(d, slot, future ? PENDING : UNRECORDED, null, false, 0));
                        continue;
                    }
                    boolean backfill = Integer.valueOf(1).equals(r.getBackfill());
                    agg[0]++;
                    if (backfill) {
                        agg[5]++;
                    }
                    if (MedicationIntakeStatusEnum.TAKEN.name().equals(r.getStatus())) {
                        agg[1]++;
                        if (!backfill && r.getTakenAt() != null) {
                            long diff = Math.abs(Duration.between(d.atTime(slotTime), r.getTakenAt()).toMinutes());
                            if (diff <= ON_TIME_MINUTES) {
                                onTime++;
                            }
                        }
                    } else if (MedicationIntakeStatusEnum.MISSED.name().equals(r.getStatus())) {
                        agg[2]++;
                    } else {
                        agg[3]++;
                    }
                    cells.add(new Cell(d, slot, r.getStatus(), r.getTakenAt(), backfill, 1));
                }
            }
            if (everActive) {
                rows.add(new MedRow(med.getId(), med.getDrugName(), prn, slots, cells));
            }
        }

        List<Day> dayList = new ArrayList<>();
        int due = 0;
        int taken = 0;
        int missed = 0;
        int skipped = 0;
        int unrecorded = 0;
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            int[] a = dayAgg.getOrDefault(d, new int[6]);
            dayList.add(new Day(d, a[0], a[1], a[2], a[3], a[4], a[5]));
            due += a[0];
            taken += a[1];
            missed += a[2];
            skipped += a[3];
            unrecorded += a[4];
        }
        Integer rate = due > 0 ? (int) Math.round(100.0 * taken / due) : null;
        Integer onTimeRate = taken > 0 ? (int) Math.round(100.0 * onTime / taken) : null;
        return new MedicationAdherenceDto(
                from,
                today,
                new Stats(due, taken, missed, skipped, unrecorded, rate, onTimeRate, prnCount),
                dayList,
                rows);
    }
}
