package com.healix.core.water.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.careplan.domain.CarePlanTask;
import com.healix.core.careplan.dto.CarePlanTodayDto;
import com.healix.core.careplan.dto.CarePlanTodayTaskDto;
import com.healix.core.careplan.mapper.CarePlanTaskMapper;
import com.healix.core.careplan.service.CarePlanCheckinService;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.enums.MetricTypeEnum;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import com.healix.core.water.domain.WaterIntakeLog;
import com.healix.core.water.dto.WaterDayDto;
import com.healix.core.water.mapper.WaterIntakeLogMapper;
import com.healix.core.water.support.WaterIntakeRules;
import com.healix.core.water.support.WaterTaskTargetResolver;
import java.time.LocalDate;
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
public class WaterIntakeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String SOURCE_STAFF = "STAFF";
    private static final int STAFF_BACKFILL_DAYS = 7;

    private final WaterIntakeLogMapper logMapper;
    private final CarePlanTaskMapper taskMapper;
    private final CarePlanCheckinService checkinService;
    private final VitalRecordMapper vitalRecordMapper;
    private final PlatformTransactionManager transactionManager;

    public WaterDayDto day(String tenantId, String peopleId, LocalDate date) {
        LocalDate d = date != null ? date : LocalDate.now();
        CarePlanTask waterTask = findWaterTask(tenantId, peopleId);
        Integer target = waterTask != null ? WaterTaskTargetResolver.targetMlOf(waterTask) : null;
        if (target == null && waterTask != null) {
            target = suggestTarget(tenantId, peopleId);
        }
        int intake = nz(logMapper.sumByPeopleDate(tenantId, peopleId, d));
        WaterDayDto out = new WaterDayDto();
        out.setDate(d);
        out.setIntakeMl(intake);
        out.setTargetMl(target);
        out.setReached(target != null && intake >= target);
        out.setTaskPresent(waterTask != null);
        if (waterTask != null) {
            out.setTaskId(waterTask.getId());
            CarePlanTodayDto today = checkinService.getPatientToday(tenantId, peopleId, d);
            for (CarePlanTodayTaskDto t : today.getTasks()) {
                if (waterTask.getId().equals(t.getId())) {
                    out.setCheckinStatus(t.getCheckinStatus());
                    break;
                }
            }
        }
        List<WaterDayDto.Entry> entries = new ArrayList<>();
        for (WaterIntakeLog row : logMapper.listByPeopleDate(tenantId, peopleId, d)) {
            WaterDayDto.Entry e = new WaterDayDto.Entry();
            e.setId(row.getId());
            e.setAmountMl(row.getAmountMl() == null ? 0 : row.getAmountMl());
            e.setSource(row.getSource());
            e.setRecordedByStaff(StringUtils.hasText(row.getRecordedByStaffId()));
            java.time.LocalDateTime at =
                    row.getRecordedAt() != null ? row.getRecordedAt() : row.getGmtCreated();
            e.setCreatedAt(at == null ? null : at.format(TS));
            entries.add(e);
        }
        out.setEntries(entries);
        fillTrend(out, tenantId, peopleId, d, target);
        return out;
    }

    private void fillTrend(WaterDayDto out, String tenantId, String peopleId, LocalDate date, Integer target) {
        LocalDate from = date.minusDays(13);
        List<WaterIntakeLog> rows = logMapper.listByPeopleRange(tenantId, peopleId, from, date);
        Map<LocalDate, Integer> byDay = new LinkedHashMap<>();
        for (LocalDate cur = from; !cur.isAfter(date); cur = cur.plusDays(1)) {
            byDay.put(cur, 0);
        }
        for (WaterIntakeLog row : rows) {
            if (row.getBizDate() == null) {
                continue;
            }
            int ml = row.getAmountMl() == null ? 0 : row.getAmountMl();
            byDay.merge(row.getBizDate(), ml, Integer::sum);
        }
        int sum = 0;
        int loggedDays = 0;
        int reachedDays = 0;
        List<WaterDayDto.TrendPoint> trend = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> e : byDay.entrySet()) {
            WaterDayDto.TrendPoint p = new WaterDayDto.TrendPoint();
            p.setDate(e.getKey());
            p.setIntakeMl(e.getValue());
            p.setTargetMl(target);
            boolean reached = target != null && e.getValue() >= target;
            p.setReached(reached);
            trend.add(p);
            if (e.getValue() > 0) {
                sum += e.getValue();
                loggedDays++;
            }
            if (reached) {
                reachedDays++;
            }
        }
        out.setTrend(trend);
        out.setAvgIntakeMl14d(loggedDays == 0 ? null : sum / loggedDays);
        out.setReachedDays14d(reachedDays);
    }

    @Transactional
    public WaterDayDto add(
            String tenantId,
            String peopleId,
            LocalDate date,
            int amountMl,
            String source,
            java.time.LocalDateTime recordedAt) {
        return add(tenantId, peopleId, date, amountMl, source, recordedAt, null);
    }

    /** staffId 非空为 B 端代录：来源记 STAFF，患者端只读。 */
    @Transactional
    public WaterDayDto add(
            String tenantId,
            String peopleId,
            LocalDate date,
            int amountMl,
            String source,
            java.time.LocalDateTime recordedAt,
            String staffId) {
        if (amountMl < 50 || amountMl > 2000) {
            throw new BusinessException(400, "单次饮水量需在 50–2000 ml");
        }
        LocalDate d = date != null ? date : LocalDate.now();
        assertEditableDate(d, staffId);
        java.time.LocalDateTime at = resolveRecordedAt(d, recordedAt);
        WaterIntakeLog row = new WaterIntakeLog();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setBizDate(d);
        row.setAmountMl(amountMl);
        if (StringUtils.hasText(staffId)) {
            row.setSource(SOURCE_STAFF);
            row.setRecordedByStaffId(staffId);
        } else {
            row.setSource(StringUtils.hasText(source) ? source.trim() : "CUP");
            row.setRecordedByPeopleId(peopleId);
        }
        row.setRecordedAt(at);
        EntityMeta.onCreate(row);
        logMapper.insert(row);
        syncWaterCheckin(tenantId, peopleId, d);
        return day(tenantId, peopleId, d);
    }

    @Transactional
    public WaterDayDto update(
            String tenantId, String peopleId, String entryId, int amountMl, java.time.LocalDateTime recordedAt) {
        return update(tenantId, peopleId, entryId, amountMl, recordedAt, null);
    }

    @Transactional
    public WaterDayDto update(
            String tenantId,
            String peopleId,
            String entryId,
            int amountMl,
            java.time.LocalDateTime recordedAt,
            String staffId) {
        if (amountMl < 50 || amountMl > 2000) {
            throw new BusinessException(400, "单次饮水量需在 50–2000 ml");
        }
        WaterIntakeLog row = requireOwnedEntry(tenantId, peopleId, entryId);
        assertWritable(row, staffId);
        assertEditableDate(row.getBizDate(), staffId);
        java.time.LocalDateTime at =
                recordedAt != null
                        ? resolveRecordedAt(row.getBizDate(), recordedAt)
                        : (row.getRecordedAt() != null
                                ? row.getRecordedAt()
                                : resolveRecordedAt(row.getBizDate(), null));
        logMapper.updateEntry(row.getId(), amountMl, StringUtils.hasText(staffId) ? SOURCE_STAFF : "CUSTOM", at);
        syncWaterCheckin(tenantId, peopleId, row.getBizDate());
        return day(tenantId, peopleId, row.getBizDate());
    }

    private static java.time.LocalDateTime resolveRecordedAt(LocalDate bizDate, java.time.LocalDateTime recordedAt) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now().withSecond(0).withNano(0);
        if (recordedAt == null) {
            if (bizDate.equals(LocalDate.now())) {
                return now;
            }
            return bizDate.atTime(12, 0);
        }
        java.time.LocalDateTime at = recordedAt.withSecond(0).withNano(0);
        if (!at.toLocalDate().equals(bizDate)) {
            at = bizDate.atTime(at.toLocalTime());
        }
        if (bizDate.equals(LocalDate.now()) && at.isAfter(now)) {
            throw new BusinessException(400, "记录时间不能晚于现在");
        }
        return at;
    }

    @Transactional
    public WaterDayDto delete(String tenantId, String peopleId, String entryId) {
        return delete(tenantId, peopleId, entryId, null);
    }

    @Transactional
    public WaterDayDto delete(String tenantId, String peopleId, String entryId, String staffId) {
        WaterIntakeLog row = requireOwnedEntry(tenantId, peopleId, entryId);
        assertWritable(row, staffId);
        assertEditableDate(row.getBizDate(), staffId);
        LocalDate d = row.getBizDate();
        logMapper.softDelete(row.getId());
        syncWaterCheckin(tenantId, peopleId, d);
        return day(tenantId, peopleId, d);
    }

    private WaterIntakeLog requireOwnedEntry(String tenantId, String peopleId, String entryId) {
        if (!StringUtils.hasText(entryId)) {
            throw new BusinessException(400, "记录不存在");
        }
        WaterIntakeLog row = logMapper.findById(entryId.trim());
        if (row == null
                || !tenantId.equals(row.getTenantId())
                || !peopleId.equals(row.getPeopleId())) {
            throw new BusinessException(404, "记录不存在");
        }
        return row;
    }

    /** 患者只能改自己记的，健管师只能改代录的。 */
    private static void assertWritable(WaterIntakeLog row, String staffId) {
        boolean byStaff = StringUtils.hasText(row.getRecordedByStaffId());
        if (!StringUtils.hasText(staffId) && byStaff) {
            throw new BusinessException(400, "健管师代录的记录不可修改");
        }
        if (StringUtils.hasText(staffId) && !byStaff) {
            throw new BusinessException(400, "患者自行记录的数据不可修改");
        }
    }

    private static void assertEditableDate(LocalDate d, String staffId) {
        if (d == null) {
            throw new BusinessException(400, "日期无效");
        }
        if (d.isAfter(LocalDate.now())) {
            throw new BusinessException(400, "不能记录未来日期");
        }
        if (StringUtils.hasText(staffId)) {
            if (d.isBefore(LocalDate.now().minusDays(STAFF_BACKFILL_DAYS - 1L))) {
                throw new BusinessException(400, "仅支持代录最近 " + STAFF_BACKFILL_DAYS + " 天");
            }
            return;
        }
        if (d.isBefore(LocalDate.now().minusDays(1))) {
            throw new BusinessException(400, "只能修改今天或昨天");
        }
    }

    public int suggestTarget(String tenantId, String peopleId) {
        return WaterIntakeRules.suggestTargetMl(latestWeight(tenantId, peopleId));
    }

    public CarePlanTask findWaterTask(String tenantId, String peopleId) {
        List<CarePlanTask> tasks = taskMapper.listActiveByPeople(tenantId, peopleId);
        if (tasks == null) {
            return null;
        }
        for (CarePlanTask t : tasks) {
            if (t.getEnabled() != null && t.getEnabled() == 0) {
                continue;
            }
            if (WaterIntakeRules.looksLikeWaterTask(t.getCategory(), t.getTitle(), t.getTaskCode())) {
                return t;
            }
        }
        return null;
    }

    private void syncWaterCheckin(String tenantId, String peopleId, LocalDate date) {
        Runnable sync = () -> {
            try {
                requiresNew().executeWithoutResult(st -> {
                    CarePlanTask task = findWaterTask(tenantId, peopleId);
                    if (task == null) {
                        return;
                    }
                    Integer target = WaterTaskTargetResolver.targetMlOf(task);
                    if (target == null) {
                        target = suggestTarget(tenantId, peopleId);
                    }
                    int intake = nz(logMapper.sumByPeopleDate(tenantId, peopleId, date));
                    boolean done = intake >= target;
                    checkinService.syncSystemCheckin(
                            tenantId, peopleId, WaterIntakeRules.TASK_CODE, date, done, peopleId);
                    // 兼容旧 DIET 饮水任务编码非 WATER_LOG
                    if (!WaterIntakeRules.TASK_CODE.equals(task.getTaskCode())) {
                        checkinService.syncSystemCheckin(
                                tenantId, peopleId, task.getTaskCode(), date, done, peopleId);
                    }
                });
            } catch (RuntimeException e) {
                log.warn("sync WATER checkin failed people={} date={}: {}", peopleId, date, e.getMessage());
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

    private TransactionTemplate requiresNew() {
        TransactionTemplate t = new TransactionTemplate(transactionManager);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return t;
    }

    private Double latestWeight(String tenantId, String peopleId) {
        List<VitalRecord> rows =
                vitalRecordMapper.listByPeople(tenantId, peopleId, MetricTypeEnum.WEIGHT.name(), null, null, 1);
        if (rows == null || rows.isEmpty() || rows.get(0).getValue() == null) {
            return null;
        }
        return rows.get(0).getValue().doubleValue();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
