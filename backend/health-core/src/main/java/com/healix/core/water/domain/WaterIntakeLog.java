package com.healix.core.water.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WaterIntakeLog extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate bizDate;
    private Integer amountMl;
    private String source;
    /** 用户可调整的饮水时刻（默认当前时间） */
    private LocalDateTime recordedAt;
    private String recordedByPeopleId;
    private String recordedByStaffId;
}
