package com.healix.core.medication.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.healix.common.util.JsonUtils;
import com.healix.core.medication.domain.PeopleMedication;
import com.healix.core.medication.enums.MedicationStatusEnum;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * 服药时段规则：一种药每天在哪几个时段服用。
 *
 * <p>优先取 {@code slots_json}（医护或患者指定）；否则按频次推导次数，每日一次再按频次 / 服药时机推断落在哪个时段。
 * PRN 统一返回 {@link #PRN}，不计应服。
 */
public final class MedicationSlotRules {

    public static final String MORNING = "MORNING";
    public static final String NOON = "NOON";
    public static final String EVENING = "EVENING";
    public static final String BEDTIME = "BEDTIME";
    public static final String PRN = "PRN";
    public static final String OTHER = "OTHER";

    /** 定时时段（时间线顺序） */
    public static final List<String> TIMED_SLOTS = List.of(MORNING, NOON, EVENING, BEDTIME);

    private MedicationSlotRules() {}

    public static boolean isPrn(PeopleMedication med) {
        return med != null && MedicationFrequencySupport.isPrn(med.getFrequency());
    }

    /** 该药每日服药时段；PRN 返回 [PRN]。 */
    public static List<String> slotsOf(PeopleMedication med) {
        if (med == null) {
            return List.of();
        }
        return slotsOf(med.getFrequency(), med.getTimingNote(), med.getSlotsJson());
    }

    public static List<String> slotsOf(String frequency, String timingNote, String slotsJson) {
        if (MedicationFrequencySupport.isPrn(frequency)) {
            return List.of(PRN);
        }
        List<String> explicit = parseSlots(slotsJson);
        if (!explicit.isEmpty()) {
            return explicit;
        }
        return inferSlots(frequency, timingNote);
    }

    /** 按频次 + 服药时机推导（不看 slots_json）。 */
    public static List<String> inferSlots(String frequency, String timingNote) {
        if (MedicationFrequencySupport.isPrn(frequency)) {
            return List.of(PRN);
        }
        int doses = MedicationFrequencySupport.dosesPerDay(frequency);
        return switch (doses) {
            case 1 -> List.of(inferSingleSlot(frequency, timingNote));
            case 2 -> List.of(MORNING, EVENING);
            case 3 -> List.of(MORNING, NOON, EVENING);
            default -> TIMED_SLOTS;
        };
    }

    /** 每日一次落在哪个时段：睡前类频次/时机 → 睡前，晚餐 → 晚，午餐 → 午，其余 → 早。 */
    public static String inferSingleSlot(String frequency, String timingNote) {
        String f = StringUtils.hasText(frequency) ? frequency.trim().toUpperCase(Locale.ROOT).replace(" ", "") : "";
        if (f.contains("QN") || f.contains("HS") || f.contains("睡前") || f.contains("每晚")) {
            return BEDTIME;
        }
        String t = StringUtils.hasText(timingNote) ? timingNote.trim() : "";
        if (t.contains("睡前")) {
            return BEDTIME;
        }
        if (t.contains("晚")) {
            return EVENING;
        }
        if (t.contains("午")) {
            return NOON;
        }
        return MORNING;
    }

    /** 应服次数 = 定时时段数；PRN 为 0。 */
    public static int dueCount(PeopleMedication med) {
        if (isPrn(med)) {
            return 0;
        }
        return slotsOf(med).size();
    }

    /**
     * 该药在某日是否在用。开始日缺省取创建日；结束日取停药日 / 疗程末日。
     * 已停用的药结束日不晚于昨天：当天停药当天就不再算应服。
     */
    public static boolean isActiveOnDay(PeopleMedication med, LocalDate day, LocalDate today) {
        if (med == null || day == null) {
            return false;
        }
        LocalDate start = med.getStartDate();
        if (start == null && med.getGmtCreated() != null) {
            start = med.getGmtCreated().toLocalDate();
        }
        if (start != null && day.isBefore(start)) {
            return false;
        }
        LocalDate end = endDateOf(med);
        if (MedicationStatusEnum.STOPPED.name().equals(med.getStatus())) {
            if (end == null && med.getGmtModified() != null) {
                end = med.getGmtModified().toLocalDate();
            }
            if (end == null || !end.isBefore(today)) {
                end = today.minusDays(1);
            }
        }
        return end == null || !day.isAfter(end);
    }

    /** 当天是否应服：在用，且隔日 / 每周药从开始日起按间隔落在当天。 */
    public static boolean isDueOnDay(PeopleMedication med, LocalDate day, LocalDate today) {
        if (!isActiveOnDay(med, day, today)) {
            return false;
        }
        int interval = intervalDays(med.getFrequency());
        LocalDate start = med.getStartDate();
        if (interval <= 1 || start == null) {
            return true;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(start, day) % interval == 0;
    }

    /** 服药间隔天数：隔日 2、每周 7，其余 1。 */
    static int intervalDays(String frequency) {
        if (!StringUtils.hasText(frequency)) {
            return 1;
        }
        String f = frequency.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (f.contains("QOD") || f.contains("隔日") || f.contains("隔天")) {
            return 2;
        }
        if (f.equals("QW") || f.contains("每周")) {
            return 7;
        }
        return 1;
    }

    /** 结束日：优先停药日，否则开始日 + 疗程 - 1。 */
    public static LocalDate endDateOf(PeopleMedication med) {
        if (med.getStopDate() != null) {
            return med.getStopDate();
        }
        if (med.getCourseDays() != null && med.getCourseDays() > 0 && med.getStartDate() != null) {
            return med.getStartDate().plusDays(med.getCourseDays() - 1L);
        }
        return null;
    }

    /** 打卡槽归一：历史 OTHER 打卡落到单时段药的唯一时段上。 */
    public static String effectiveSlot(String intakeSlot, List<String> medSlots) {
        if (OTHER.equals(intakeSlot) && medSlots.size() == 1 && !PRN.equals(medSlots.get(0))) {
            return medSlots.get(0);
        }
        return intakeSlot;
    }

    public static List<String> parseSlots(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return normalize(JsonUtils.fromJson(json, new TypeReference<List<String>>() {}));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    /** 去重、过滤非法值并按时间线排序。 */
    public static List<String> normalize(Collection<String> slots) {
        if (slots == null || slots.isEmpty()) {
            return List.of();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String s : slots) {
            if (StringUtils.hasText(s)) {
                wanted.add(s.trim().toUpperCase(Locale.ROOT));
            }
        }
        List<String> out = new ArrayList<>();
        for (String s : TIMED_SLOTS) {
            if (wanted.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    public static String toJson(Collection<String> slots) {
        List<String> norm = normalize(slots);
        return norm.isEmpty() ? null : JsonUtils.toJson(norm);
    }

    public static String label(String slot) {
        if (slot == null) {
            return "其他";
        }
        return switch (slot) {
            case MORNING -> "早上";
            case NOON -> "中午";
            case EVENING -> "晚上";
            case BEDTIME -> "睡前";
            case PRN -> "需要时";
            default -> "其他";
        };
    }
}
