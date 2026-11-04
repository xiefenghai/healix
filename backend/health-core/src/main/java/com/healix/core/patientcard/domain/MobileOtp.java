package com.healix.core.patientcard.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MobileOtp extends BaseEntity {
    private String tenantId;
    private String accountId;
    private String mobile;
    private String codeHash;
    private LocalDateTime expireAt;
    private LocalDateTime consumedAt;
}
