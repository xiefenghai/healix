package com.healix.core.sleep.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.dto.CarePlanTodayTaskDto;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.sleep.domain.SleepLog;
import com.healix.core.sleep.dto.SleepDayDto;
import com.healix.core.sleep.mapper.SleepLogMapper;
import com.healix.core.sleep.support.SleepLogRules;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class SleepLogService {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final SleepLogMapper sleepLogMapper;
    private final CarePlanTaskMapper taskMapper;
    private final CarePlanCheckinService checkinService;
    private final PlatformTransactionManager transactionManager;

    public SleepDayDto day(String tenantId, String peopleId, LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        List<SleepLog> rows = sleepLogMapper.listByPeopleDate(tenantId, peopleId, d);
        CarePlanTask task = findSleepTask(tenantId, peopleId);
        SleepDayDto out = toDay(d, rows, task);
        fillTrend(out, tenantId, peopleId, d);
        if (task != null) {
            CarePlanTodayDto today = checkinService.getPatientToday(tenantId, peopleId, d);
            for (CarePlanTodayTaskDto t : today.getTasks()) {
                if (task.getId().equals(t.getId())) {
                    out.setCheckinStatus(t.getCheckinStatus());
                    break;
                }
            }
        }
        return out;
    }

    @Transactional
    public SleepDayDto upsert(
            String tenantId,
            String peopleId,
            String entryId,
            LocalDate date,
            String segment,
            Integer durationMin,
            String quality,
            String bedtime,
            String wakeTime,
            String note) {
        return upsert(tenantId, peopleId, entryId, date, segment, durationMin, quality, bedtime, wakeTime, note, null);
    }

    /** staffId 非空为 B 端代录；患者与健管师互不覆盖对方的记录。 */
    @Transactional
    public SleepDayDto upsert(
            String tenantId,
            String peopleId,
            String entryId,
            LocalDate date,
            String segment,
            Integer durationMin,
            String quality,
            String bedtime,
            String wakeTime,
            String note,
            String staffId) {
        LocalDate d = date != null ? date : LocalDate.now();
        assertEditableDate(d);
        String seg = SleepLogRules.normalizeSegment(segment);
        SleepLogRules.assertDuration(seg, durationMin);
        String q = SleepLogRules.normalizeQuality(quality);

        SleepLog existing = null;
        if (StringUtils.hasText(entryId)) {
            existing = requireOwned(tenantId, peopleId, entryId.trim());
            assertWritable(existing, staffId);
            d = existing.getBizDate();
            assertEditableDate(d);
        } else if (SleepLogRules.SEGMENT_MAIN.equals(seg)) {
            existing = sleepLogMapper.findMainByPeopleDate(tenantId, peopleId, d);
            if (existing != null && StringUtils.hasText(existing.getRecordedByStaffId()) != StringUtils.hasText(staffId)) {
                throw new BusinessException(400, StringUtils.hasText(staffId)
                        ? "患者当天已记录主睡眠，不能代录覆盖"
                        : "当天主睡眠已由健管师代录，不可修改");
            }
        }

        if (existing == null) {
            SleepLog row = new SleepLog();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setBizDate(d);
            row.setSegment(seg);
            row.setDurationMin(durationMin);
            row.setQuality(q);
            row.setBedtime(parseTime(bedtime));
            row.setWakeTime(parseTime(wakeTime));
            row.setNote(trimNote(note));
            if (StringUtils.hasText(staffId)) {
                row.setRecordedByStaffId(staffId);
            } else {
                row.setRecordedByPeopleId(peopleId);
            }
            EntityMeta.onCreate(row);
            sleepLogMapper.insert(row);
        } else {
            existing.setSegment(seg);
            existing.setDurationMin(durationMin);
            existing.setQuality(q);
            existing.setBedtime(parseTime(bedtime));
            existing.setWakeTime(parseTime(wakeTime));
            existing.setNote(trimNote(note));
            EntityMeta.onUpdate(existing);
            sleepLogMapper.update(existing);
        }
        syncCheckin(tenantId, peopleId, d);
        return day(tenantId, peopleId, d);
    }

    @Transactional
    public SleepDayDto delete(String tenantId, String peopleId, String entryId) {
        return delete(tenantId, peopleId, entryId, null);
    }

    @Transactional
    public SleepDayDto delete(String tenantId, String peopleId, String entryId, String staffId) {
        SleepLog row = requireOwned(tenantId, peopleId, entryId);
        assertWritable(row, staffId);
        assertEditableDate(row.getBizDate());
        LocalDate d = row.getBizDate();
        sleepLogMapper.softDelete(row.getId());
        syncCheckin(tenantId, peopleId, d);
        return day(tenantId, peopleId, d);
    }

    public CarePlanTask findSleepTask(String tenantId, String peopleId) {
        List<CarePlanTask> tasks = taskMapper.listActiveByPeople(tenantId, peopleId);
        if (tasks == null) {
            return null;
        }
        for (CarePlanTask t : tasks) {
            if (t.getEnabled() != null && t.getEnabled() == 0) {
                continue;
            }
            if (SleepLogRules.looksLikeSleepTask(t.getCategory(), t.getTitle(), t.getTaskCode())) {
                return t;
            }
        }
        return null;
    }

    private SleepLog requireOwned(String tenantId, String peopleId, String entryId) {
        if (!StringUtils.hasText(entryId)) {
            throw new BusinessException(400, "记录不存在");
        }
        SleepLog row = sleepLogMapper.findById(entryId.trim());
        if (row == null
                || !tenantId.equals(row.getTenantId())
                || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(404, "记录不存在");
        }
        return row;
    }

    /** 患者只能改自己记的，健管师只能改代录的。 */
    private static void assertWritable(SleepLog row, String staffId) {
        boolean byStaff = StringUtils.hasText(row.getRecordedByStaffId());
        if (!StringUtils.hasText(staffId) && byStaff) {
            throw new BusinessException(400, "健管师代录的记录不可修改");
        }
        if (StringUtils.hasText(staffId) && !byStaff) {
            throw new BusinessException(400, "患者自行记录的数据不可修改");
        }
    }

    private static void assertEditableDate(LocalDate d) {
        if (d == null) {
            throw new BusinessException(400, "日期无效");
        }
        if (d.isAfter(LocalDate.now())) {
            throw new BusinessException(400, "不能记录未来日期");
        }
        if (d.isBefore(LocalDate.now().minusDays(7))) {
            throw new BusinessException(400, "只能补记近 7 天");
        }
    }

    private void syncCheckin(String tenantId, String peopleId, LocalDate date) {
        Runnable sync = () -> {
            try {
                requiresNew().executeWithoutResult(st -> {
                    CarePlanTask task = findSleepTask(tenantId, peopleId);
                    if (task == null) {
                        return;
                    }
                    List<SleepLog> rows = sleepLogMapper.listByPeopleDate(tenantId, peopleId, date);
                    boolean done = rows != null && !rows.isEmpty();
                    checkinService.syncSystemCheckin(
                            tenantId, peopleId, SleepLogRules.TASK_CODE, date, done, peopleId);
                    if (!SleepLogRules.TASK_CODE.equals(task.getTaskCode())) {
                        checkinService.syncSystemCheckin(
                                tenantId, peopleId, task.getTaskCode(), date, done, peopleId);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("sync SLEEP checkin failed people={} date={}: {}", peopleId, date, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sync.run();
                }
            });
        } else {
            sync.run();
        }
    }

    private void fillTrend(SleepDayDto out, String tenantId, String peopleId, LocalDate date) {
        LocalDate from = date.minusDays(13);
        List<SleepLog> rows = sleepLogMapper.listByPeopleRange(tenantId, peopleId, from, date);
        Map<LocalDate, Integer> byDay = new LinkedHashMap<>();
        Map<LocalDate, String> qualityByDay = new LinkedHashMap<>();
        for (LocalDate cur = from; !cur.isAfter(date); cur = cur.plusDays(1)) {
            byDay.put(cur, 0);
        }
        for (SleepLog r : rows) {
            if (r.getBizDate() == null) {
                continue;
            }
            int min = r.getDurationMin() == null ? 0 : r.getDurationMin();
            byDay.merge(r.getBizDate(), min, Integer::sum);
            String seg = SleepLogRules.normalizeSegment(r.getSegment());
            if (SleepLogRules.SEGMENT_MAIN.equals(seg) && !qualityByDay.containsKey(r.getBizDate())) {
                qualityByDay.put(r.getBizDate(), r.getQuality());
            } else {
                qualityByDay.putIfAbsent(r.getBizDate(), r.getQuality());
            }
        }
        int sum = 0;
        int n = 0;
        List<SleepDayDto.TrendPoint> trend = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> e : byDay.entrySet()) {
            SleepDayDto.TrendPoint p = new SleepDayDto.TrendPoint();
            p.setDate(e.getKey());
            p.setDurationMin(e.getValue() > 0 ? e.getValue() : null);
            p.setQuality(qualityByDay.get(e.getKey()));
            trend.add(p);
            if (e.getValue() > 0) {
                sum += e.getValue();
                n++;
            }
        }
        out.setTrend(trend);
        out.setAvgDurationMin14d(n == 0 ? null : sum / n);
    }

    private static SleepDayDto toDay(LocalDate d, List<SleepLog> rows, CarePlanTask task) {
        SleepDayDto out = new SleepDayDto();
        out.setDate(d);
        out.setTaskPresent(task != null);
        List<SleepLog> list = rows == null ? List.of() : rows;
        out.setLogged(!list.isEmpty());
        int total = 0;
        List<SleepDayDto.Segment> segments = new ArrayList<>();
        SleepLog primary = null;
        for (SleepLog row : list) {
            String seg = SleepLogRules.normalizeSegment(row.getSegment());
            SleepDayDto.Segment s = new SleepDayDto.Segment();
            s.setId(row.getId());
            s.setSegment(seg);
            s.setDurationMin(row.getDurationMin());
            s.setQuality(row.getQuality());
            s.setBedtime(row.getBedtime() == null ? null : row.getBedtime().format(TIME));
            s.setWakeTime(row.getWakeTime() == null ? null : row.getWakeTime().format(TIME));
            s.setNote(row.getNote());
            s.setRecordedByStaff(StringUtils.hasText(row.getRecordedByStaffId()));
            segments.add(s);
            if (row.getDurationMin() != null) {
                total += row.getDurationMin();
            }
            if (primary == null && SleepLogRules.SEGMENT_MAIN.equals(seg)) {
                primary = row;
            }
        }
        if (primary == null && !list.isEmpty()) {
            primary = list.get(0);
        }
        out.setSegments(segments);
        out.setDurationMin(list.isEmpty() ? null : total);
        if (primary != null) {
            out.setQuality(primary.getQuality());
            out.setBedtime(primary.getBedtime() == null ? null : primary.getBedtime().format(TIME));
            out.setWakeTime(primary.getWakeTime() == null ? null : primary.getWakeTime().format(TIME));
            out.setNote(primary.getNote());
        }
        return out;
    }

    private static LocalTime parseTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            String t = raw.trim();
            if (t.length() == 5) {
                return LocalTime.parse(t, TIME);
            }
            return LocalTime.parse(t);
        } catch (Exception e) {
            throw new BusinessException(400, "时间格式应为 HH:mm");
        }
    }

    private static String trimNote(String note) {
        if (!StringUtils.hasText(note)) {
            return null;
        }
        String t = note.trim();
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private TransactionTemplate requiresNew() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }
}
