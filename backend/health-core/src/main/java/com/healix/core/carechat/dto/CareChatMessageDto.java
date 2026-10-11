package com.healix.core.carechat.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CareChatMessageDto {
    private String id;
    private String threadId;
    private String senderType;
    private String senderStaffId;
    private String senderAccountId;
    private String senderName;
    private String contentType;
    private String content;
    private String clientMsgId;
    private LocalDateTime gmtCreated;
    private boolean recalled;
}
