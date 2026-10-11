package com.healix.core.govern.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 租户配额用量。存量项（患者/员工）用 {@code periodKey = TOTAL}，
 * 按月项用 {@code periodKey = yyyyMM}，换月自然开新行，无需清理任务。
 *
 * <p>{@code limitValue} 为空表示沿用枚举里的默认上限；默认也为空则不限量。
 */
@Getter
@Setter
public class TenantQuotaUsage extends BaseEntity {
    private String tenantId;
    /** 见 {@link com.healix.core.govern.enums.QuotaKeyEnum} */
    private String quotaKey;
    private long usedValue;
    private Long limitValue;
    private String periodKey;
}
