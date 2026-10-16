package com.healix.core.medication.service;

import com.healix.core.job.support.JobCronSupport;
import com.healix.core.medication.dto.MedicationReminderDto;
import com.healix.core.medication.dto.MedicationScheduleDto;
import com.healix.core.medication.dto.MedicationScheduleDto.Item;
import com.healix.core.medication.dto.MedicationScheduleDto.SlotGroup;
import com.healix.core.medication.mapper.PeopleMedicationReminderMapper;
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.service.NotifyFacade;
import com.healix.core.tenant.domain.Tenant;
import com.healix.core.tenant.mapper.TenantMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 服药到点提醒扫描：每个时段到点 1 次、未打卡再跟进 1 次；超过 2 小时不补发。
 * 发送前写 reminder_log 唯一键去重，扫描重叠或重启不会重复推送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MedicationReminderScanService {

    private static final int BATCH_SIZE = 200;
    private static final long LATE_WINDOW_MINUTES = 120;
    private static final int LOG_KEEP_DAYS = 7;

    private final TenantMapper tenantMapper;
    private final PeopleMedicationReminderMapper reminderMapper;
    private final MedicationReminderService reminderService;
    private final MedicationScheduleService scheduleService;
    private final NotifyFacade notifyFacade;

    public record ScanCounts(int candidates, int sent) {}

    public ScanCounts scanAll(LocalDateTime now) {
        LocalDateTime at = now != null ? now : LocalDateTime.now(JobCronSupport.ZONE);
        int candidates = 0;
        int sent = 0;
        for (Tenant tenant : tenantMapper.listByStatus("ACTIVE")) {
            List<String> peopleIds = reminderMapper.listReminderCandidates(tenant.getId());
            for (int i = 0; i < peopleIds.size(); i += BATCH_SIZE) {
                List<String> batch = peopleIds.subList(i, Math.min(i + BATCH_SIZE, peopleIds.size()));
                Map<String, MedicationReminderDto> settings = reminderService.getAll(tenant.getId(), batch);
                for (String peopleId : batch) {
                    MedicationReminderDto s = settings.getOrDefault(peopleId, MedicationReminderService.DEFAULTS);
                    if (!s.enabled()) {
                        continue;
                    }
                    candidates++;
                    try {
                        sent += scanPeople(tenant.getId(), peopleId, s, at);
                    } catch (Exception ex) {
                        log.warn("MEDICATION_REMIND failed tenant={} people={}: {}", tenant.getId(), peopleId, ex.getMessage());
                    }
                }
            }
        }
        reminderMapper.deleteLogsBefore(at.toLocalDate().minusDays(LOG_KEEP_DAYS));
        return new ScanCounts(candidates, sent);
    }

    private int scanPeople(String tenantId, String peopleId, MedicationReminderDto s, LocalDateTime now) {
        LocalDate day = now.toLocalDate();
        MedicationScheduleDto schedule = scheduleService.build(tenantId, peopleId, day, s);
        int sent = 0;
        for (SlotGroup group : schedule.slots()) {
            if (group.pendingCount() <= 0) {
                continue;
            }
            LocalTime slotTime = MedicationReminderService.parseTime(group.time(), null);
            if (slotTime == null) {
                continue;
            }
            int seq = dueSeq(day.atTime(slotTime), s.followUpMin(), now);
            if (seq == 0) {
                continue;
            }
            if (reminderMapper.insertLogIgnore(tenantId, peopleId, day, group.slot(), seq, now) <= 0) {
                continue;
            }
            publish(tenantId, peopleId, day, group, seq);
            sent++;
        }
        return sent;
    }

    /** 当前应发第几次提醒：0=不发，1=到点，2=跟进。 */
    static int dueSeq(LocalDateTime slotAt, int followUpMin, LocalDateTime now) {
        if (followUpMin > 0) {
            LocalDateTime followAt = slotAt.plusMinutes(followUpMin);
            if (!now.isBefore(followAt) && now.isBefore(followAt.plusMinutes(LATE_WINDOW_MINUTES))) {
                return 2;
            }
        }
        if (!now.isBefore(slotAt) && now.isBefore(slotAt.plusMinutes(LATE_WINDOW_MINUTES))) {
            return 1;
        }
        return 0;
    }

    private void publish(String tenantId, String peopleId, LocalDate day, SlotGroup group, int seq) {
        List<String> pendingNames = group.items().stream()
                .filter(i -> i.status() == null)
                .map(Item::drugName)
                .toList();
        String names = pendingNames.size() <= 2
                ? String.join("、", pendingNames)
                : String.join("、", pendingNames.subList(0, 2)) + " 等 " + pendingNames.size() + " 种";
        String title = seq == 1 ? group.time() + " 该吃药了" : group.label() + "的药还没记录";
        String body = seq == 1 ? group.label() + "要吃：" + names : names + "，吃了记得点一下";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("day", day.toString());
        payload.put("slot", group.slot());
        payload.put("seq", seq);
        payload.put("pending", pendingNames.size());
        notifyFacade.publish(NotifyPublishCommand.builder()
                .tenantId(tenantId)
                .eventType(NotifyEventType.MEDICATION_REMIND)
                .dedupeKey(peopleId + ":" + day + ":" + group.slot() + ":" + seq)
                .audience(NotifyAudience.C_ACCOUNT)
                .peopleId(peopleId)
                .title(title)
                .body(body)
                .linkPath("/medications")
                .payload(payload)
                .build());
    }
}
