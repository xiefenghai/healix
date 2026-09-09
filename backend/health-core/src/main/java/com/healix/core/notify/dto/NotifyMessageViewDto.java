package com.healix.core.notify.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class NotifyMessageViewDto {
    private String id;
    private String peopleId;
    private String orgId;
    private String eventType;
    private String category;
    private String title;
    private String body;
    private String linkPath;
    private Object payload;
    private String priority;
    private LocalDateTime readAt;
    private boolean unread;
    private LocalDateTime gmtCreated;
}
