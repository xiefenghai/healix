package com.healix.core.patientcard.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者专属激活码（一码绑定 people + org） */
@Getter
@Setter
public class PeopleActivationInvite extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String code;
    private Integer enabled;
    private LocalDateTime expireAt;
    private LocalDateTime usedAt;
    private String usedByAccountId;
    private String createdByStaffId;
}
