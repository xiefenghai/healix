package com.healix.core.notify.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NotifyMessage extends BaseEntity {
    private String tenantId;
    private String audience;
    private String recipientId;
    private String peopleId;
    private String orgId;
    private String eventType;
    private String category;
    private String dedupeKey;
    private String title;
    private String body;
    private String linkPath;
    private String payloadJson;
    private String priority;
    private LocalDateTime readAt;
}
