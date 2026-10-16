package com.healix.core.medication.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.medication.domain.PeopleMedicationReminder;
import com.healix.core.medication.dto.MedicationReminderDto;
import com.healix.core.medication.mapper.PeopleMedicationReminderMapper;
import com.healix.core.medication.support.MedicationSlotRules;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 服药提醒设置：无行即默认开启、默认时间。 */
@Service
@RequiredArgsConstructor
public class MedicationReminderService {

    public static final MedicationReminderDto DEFAULTS =
            new MedicationReminderDto(true, "08:00", "12:00", "18:00", "21:30", 30);

    private static final Set<Integer> FOLLOW_UP_OPTIONS = Set.of(0, 15, 30, 60);

    private final PeopleMedicationReminderMapper reminderMapper;
    private final ArchiveAccessService archiveAccessService;

    public MedicationReminderDto get(String tenantId, String peopleId) {
        return toDto(reminderMapper.findByPeople(tenantId, peopleId));
    }

    /** 批量读取：未设置的患者不在返回中（调用方按 {@link #DEFAULTS} 处理）。 */
    public Map<String, MedicationReminderDto> getAll(String tenantId, List<String> peopleIds) {
        Map<String, MedicationReminderDto> out = new HashMap<>();
        if (peopleIds == null || peopleIds.isEmpty()) {
            return out;
        }
        for (PeopleMedicationReminder row : reminderMapper.listByPeopleIds(tenantId, peopleIds)) {
            out.put(row.getPeopleId(), toDto(row));
        }
        return out;
    }

    @Transactional
    public MedicationReminderDto save(String tenantId, String peopleId, MedicationReminderDto cmd) {
        archiveAccessService.requirePeopleInTenant(tenantId, peopleId);
        if (cmd == null) {
            throw new BusinessException("提醒设置不能为空");
        }
        if (!FOLLOW_UP_OPTIONS.contains(cmd.followUpMin())) {
            throw new BusinessException("跟进提醒间隔仅支持 15 / 30 / 60 分钟或关闭");
        }
        PeopleMedicationReminder row = reminderMapper.findByPeople(tenantId, peopleId);
        boolean create = row == null;
        if (create) {
            row = new PeopleMedicationReminder();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
        }
        row.setEnabled(cmd.enabled() ? 1 : 0);
        row.setMorningAt(requireTime(cmd.morningAt(), "早上"));
        row.setNoonAt(requireTime(cmd.noonAt(), "中午"));
        row.setEveningAt(requireTime(cmd.eveningAt(), "晚上"));
        row.setBedtimeAt(requireTime(cmd.bedtimeAt(), "睡前"));
        row.setFollowUpMin(cmd.followUpMin());
        if (create) {
            EntityMeta.onCreate(row);
            reminderMapper.insert(row);
        } else {
            EntityMeta.onUpdate(row);
            reminderMapper.update(row);
        }
        return toDto(row);
    }

    /** 时段 → 提醒时间（HH:mm）。 */
    public static Map<String, String> slotTimes(MedicationReminderDto r) {
        MedicationReminderDto s = r == null ? DEFAULTS : r;
        Map<String, String> out = new LinkedHashMap<>();
        out.put(MedicationSlotRules.MORNING, s.morningAt());
        out.put(MedicationSlotRules.NOON, s.noonAt());
        out.put(MedicationSlotRules.EVENING, s.eveningAt());
        out.put(MedicationSlotRules.BEDTIME, s.bedtimeAt());
        return out;
    }

    public static LocalTime parseTime(String hhmm, LocalTime fallback) {
        try {
            return LocalTime.parse(hhmm);
        } catch (DateTimeParseException | NullPointerException e) {
            return fallback;
        }
    }

    private static String requireTime(String raw, String label) {
        if (raw == null || !raw.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw new BusinessException(label + "提醒时间格式应为 HH:mm");
        }
        return raw;
    }

    private static MedicationReminderDto toDto(PeopleMedicationReminder row) {
        if (row == null) {
            return DEFAULTS;
        }
        return new MedicationReminderDto(
                row.getEnabled() == null || row.getEnabled() == 1,
                row.getMorningAt(),
                row.getNoonAt(),
                row.getEveningAt(),
                row.getBedtimeAt(),
                row.getFollowUpMin() == null ? 30 : row.getFollowUpMin());
    }
}
