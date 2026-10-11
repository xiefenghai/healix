package com.healix.core.allergy.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者过敏史采集状态；「无已知过敏」必须显式记录，不能用条目为空推断。 */
@Getter
@Setter
public class PeopleAllergyProfile extends BaseEntity {
    private String tenantId;
    private String peopleId;
    /** 见 {@link com.healix.core.allergy.enums.AllergyStatusEnum} */
    private String status;
    private LocalDateTime reviewedAt;
    private String reviewedByStaffId;
    private String updatedByType;
    private String updatedById;
}
