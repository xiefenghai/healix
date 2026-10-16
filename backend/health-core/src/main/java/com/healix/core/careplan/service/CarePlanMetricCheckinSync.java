package com.healix.core.careplan.service;

import com.healix.core.careplan.support.MetricTaskRules;
import com.healix.core.vitals.domain.VitalRecord;
import com.healix.core.vitals.mapper.VitalRecordMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 血糖 / 血压录入、修改、删除后，同步对应监测任务的系统打卡。
 *
 * <p>在指标事务提交后执行：打卡同步失败不影响指标落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CarePlanMetricCheckinSync {

    private final VitalRecordMapper vitalRecordMapper;
    private final CarePlanCheckinService checkinService;

    public record MetricDay(String metricType, LocalDate day) {
        public static MetricDay of(VitalRecord row) {
            return new MetricDay(row.getMetricType(), row.getRecordedAt() == null ? null : row.getRecordedAt().toLocalDate());
        }
    }

    /** @param recordedByPeopleId 患者本人录入时传入（计入激励）；医护代录 / 随访写入传 null */
    public void onMetricsChanged(
            String tenantId, String peopleId, Collection<MetricDay> changed, String recordedByPeopleId) {
        Set<String> keys = new LinkedHashSet<>();
        for (MetricDay m : changed) {
            String ref = MetricTaskRules.metricRefOfMetricType(m.metricType());
            if (ref != null && m.day() != null) {
                keys.add(ref + "|" + m.day());
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        Runnable sync = () -> keys.forEach(k -> {
            String[] parts = k.split("\\|");
            syncOne(tenantId, peopleId, parts[0], LocalDate.parse(parts[1]), recordedByPeopleId);
        });
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

    private void syncOne(String tenantId, String peopleId, String metricRef, LocalDate day, String recordedByPeopleId) {
        try {
            boolean measured = MetricTaskRules.metricTypesOf(metricRef).stream()
                    .anyMatch(type -> !vitalRecordMapper
                            .listByRange(tenantId, peopleId, type, day.atStartOfDay(), day.atTime(LocalTime.MAX))
                            .isEmpty());
            checkinService.syncMetricCheckin(tenantId, peopleId, metricRef, day, measured, recordedByPeopleId);
        } catch (RuntimeException e) {
            log.warn("sync metric checkin failed people={} ref={} date={}: {}", peopleId, metricRef, day, e.getMessage());
        }
    }
}
