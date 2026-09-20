package com.healix.core.carechat.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CareChatThreadDto {
    private String id;
    private String tenantId;
    private String orgId;
    private String orgName;
    private String peopleId;
    private String peopleName;
    private LocalDateTime lastMessageAt;
    private String lastMessagePreview;
    private String lastSenderType;
    private int staffUnreadCount;
    private int patientUnreadCount;
    private boolean closed;
    private LocalDateTime gmtCreated;
}
