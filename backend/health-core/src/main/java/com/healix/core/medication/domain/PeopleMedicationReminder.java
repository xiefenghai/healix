package com.healix.core.medication.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 服药提醒设置（每人一行；无行即默认开启 + 默认时间）。 */
@Getter
@Setter
public class PeopleMedicationReminder extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private Integer enabled;
    /** HH:mm */
    private String morningAt;
    private String noonAt;
    private String eveningAt;
    private String bedtimeAt;
    /** 未打卡跟进提醒间隔（分钟），0=不跟进 */
    private Integer followUpMin;
}
