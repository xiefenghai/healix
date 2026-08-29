package com.healix.core.vitals.domain;

import com.healix.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 体征记录（租户内共享） */
@Getter
@Setter
public class VitalRecord extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 患者ID */
    private String peopleId;
    /** 指标类型，见 {@link com.healix.core.vitals.enums.MetricTypeEnum} */
    private String metricType;
    /** 指标数值 */
    private BigDecimal value;
    /** 单位 */
    private String unit;
    /** 测量/记录时间 */
    private LocalDateTime recordedAt;
    /** 来源，见 {@link com.healix.core.vitals.enums.VitalSourceEnum} */
    private String source;
    /** 扩展信息 JSON */
    private String extraJson;
}
