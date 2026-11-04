package com.healix.core.assessment.service;

import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

/**
 * 评估输入变更标记：指标、检验、档案、用药、问卷写入后调用，由 ASSESSMENT_RECOMPUTE 任务异步重算。
 *
 * <p>在事务提交后写标记，保证标记时间晚于数据可见时间（重算任务按「标记时间 ≤ 开始时间」清标记）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssessmentDirtyMarker {

    private final AssessmentStoreMapper storeMapper;

    public void mark(String tenantId, String peopleId) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(peopleId)) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    write(tenantId, peopleId);
                }
            });
            return;
        }
        write(tenantId, peopleId);
    }

    private void write(String tenantId, String peopleId) {
        try {
            storeMapper.markDirty(tenantId, peopleId, LocalDateTime.now());
        } catch (RuntimeException e) {
            log.warn("assessment dirty mark failed people={}: {}", peopleId, e.getMessage());
        }
    }
}
