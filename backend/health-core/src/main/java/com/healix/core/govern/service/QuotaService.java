package com.healix.core.govern.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.govern.domain.TenantQuotaUsage;
import com.healix.core.govern.enums.QuotaKeyEnum;
import com.healix.core.govern.mapper.QuotaCountMapper;
import com.healix.core.govern.mapper.TenantQuotaUsageMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 租户配额。两类口径分开处理：
 *
 * <ul>
 *   <li>TOTAL（患者/员工数）：用量实时 COUNT，只在配额表存上限，不会漂移；
 *   <li>MONTH（AI/OCR 调用量）：用量按 {@code yyyyMM} 累加，换月自动开新行。
 * </ul>
 *
 * <p>上限取值优先级：配额表 limit_value → 枚举默认上限 → 不限量。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuotaService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String TOTAL_PERIOD = "TOTAL";

    private final TenantQuotaUsageMapper quotaMapper;
    private final QuotaCountMapper quotaCountMapper;

    /**
     * 配额视图，Ops 页面用。
     *
     * @param limitValue null 表示不限量
     */
    public record QuotaView(
            String key, String label, String period, String periodKey, long usedValue, Long limitValue) {

        public boolean exceeded() {
            return limitValue != null && usedValue > limitValue;
        }
    }

    /** 当前周期键：存量项恒为 TOTAL，按月项为 yyyyMM。 */
    public static String periodKey(QuotaKeyEnum key) {
        return key.period() == QuotaKeyEnum.Period.MONTH ? LocalDate.now().format(MONTH) : TOTAL_PERIOD;
    }

    public long used(String tenantId, QuotaKeyEnum key) {
        return switch (key) {
            case PATIENT_TOTAL -> quotaCountMapper.countPatients(tenantId);
            case STAFF_TOTAL -> quotaCountMapper.countStaff(tenantId);
            default -> {
                TenantQuotaUsage row = quotaMapper.find(tenantId, key.name(), periodKey(key));
                yield row == null ? 0L : row.getUsedValue();
            }
        };
    }

    /** null 表示不限量。 */
    public Long limit(String tenantId, QuotaKeyEnum key) {
        TenantQuotaUsage row = quotaMapper.find(tenantId, key.name(), periodKey(key));
        if (row != null && row.getLimitValue() != null) {
            return row.getLimitValue();
        }
        return key.defaultLimit();
    }

    /**
     * 配额不足时抛业务异常。只做校验不占用，供建档这类「先校验后走一长串写入」的场景。
     */
    public void assertAvailable(String tenantId, QuotaKeyEnum key, long delta) {
        if (!StringUtils.hasText(tenantId)) {
            return;
        }
        Long limit = limit(tenantId, key);
        if (limit == null) {
            return;
        }
        long used = used(tenantId, key);
        if (used + delta > limit) {
            throw new BusinessException(
                    "「" + key.label() + "」已达上限（" + used + "/" + limit + "），请联系平台调整配额");
        }
    }

    /**
     * 校验并占用按月配额。TOTAL 项不需要占用（用量实时 COUNT），传入会被忽略。
     *
     * <p>用独立事务提交：AI 调用往回滚的业务里嵌了也该计数，否则失败重试可以无限刷量。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void consume(String tenantId, QuotaKeyEnum key, long delta) {
        if (!StringUtils.hasText(tenantId) || key.period() != QuotaKeyEnum.Period.MONTH || delta == 0) {
            return;
        }
        assertAvailable(tenantId, key, delta);
        String period = periodKey(key);
        LocalDateTime now = LocalDateTime.now();
        if (quotaMapper.addUsed(tenantId, key.name(), period, delta, now) > 0) {
            return;
        }
        TenantQuotaUsage row = new TenantQuotaUsage();
        row.setTenantId(tenantId);
        row.setQuotaKey(key.name());
        row.setPeriodKey(period);
        row.setUsedValue(delta);
        row.setLimitValue(null);
        EntityMeta.onCreate(row);
        // 唯一键冲突时走 ON DUPLICATE 累加，并发首次写入不会丢量
        quotaMapper.insert(row);
    }

    /** 全量配额视图（含未落库的默认上限），Ops 配置页直接渲染。 */
    public List<QuotaView> list(String tenantId) {
        List<QuotaView> out = new ArrayList<>();
        for (QuotaKeyEnum key : QuotaKeyEnum.values()) {
            out.add(new QuotaView(
                    key.name(),
                    key.label(),
                    key.period().name(),
                    periodKey(key),
                    used(tenantId, key),
                    limit(tenantId, key)));
        }
        return out;
    }

    /** Ops 设置上限；limitValue 传 null 表示不限量。 */
    @Transactional
    public void setLimit(String tenantId, String quotaKey, Long limitValue) {
        QuotaKeyEnum key = QuotaKeyEnum.of(quotaKey);
        if (limitValue != null && limitValue < 0) {
            throw new BusinessException("配额上限不能为负数");
        }
        String period = periodKey(key);
        LocalDateTime now = LocalDateTime.now();
        if (quotaMapper.updateLimit(tenantId, key.name(), period, limitValue, now) > 0) {
            return;
        }
        TenantQuotaUsage row = new TenantQuotaUsage();
        row.setTenantId(tenantId);
        row.setQuotaKey(key.name());
        row.setPeriodKey(period);
        row.setUsedValue(0);
        row.setLimitValue(limitValue);
        EntityMeta.onCreate(row);
        quotaMapper.insert(row);
        // insert 走 ON DUPLICATE 只累加用量，上限需要再补一次写
        quotaMapper.updateLimit(tenantId, key.name(), period, limitValue, now);
    }
}
