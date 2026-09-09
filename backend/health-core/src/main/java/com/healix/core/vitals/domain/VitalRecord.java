package com.healix.core.vitals.domain;

import com.healix.common.domain.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 指标/体征记录（租户内共享） */
@Getter
@Setter
public class VitalRecord extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 患者ID */
    private String peopleId;
    /** 录入时工作机构 */
    private String orgId;
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
    /** 同一次测量分组ID */
    private String groupId;
    /** 备注 */
    private String note;
    /** B 端代录员工 */
    private String recordedByStaffId;
    /** 扩展信息 JSON（bpContext/mealContext 等） */
    private String extraJson;
}
