package com.healix.core.notify.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NotifyDelivery extends BaseEntity {
    private String tenantId;
    private String messageId;
    private String channel;
    private String status;
    private Integer attemptCount;
    private LocalDateTime nextRetryAt;
    private String provider;
    private String providerMsgId;
    private String endpointSnapshot;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime sentAt;
}
