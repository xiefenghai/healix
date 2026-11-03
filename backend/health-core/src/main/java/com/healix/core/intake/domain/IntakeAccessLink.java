package com.healix.core.intake.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者首诊填写 Magic Link（已有 people，不建账号）。 */
@Getter
@Setter
public class IntakeAccessLink extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String token;
    /** OPEN / REVOKED / EXPIRED */
    private String status;
    private LocalDateTime expireAt;
    private Integer redeemCount;
    private LocalDateTime lastRedeemAt;
    private String createdByStaffId;
}
