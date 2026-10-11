package com.healix.core.carechat.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 沟通线程内一条气泡。 */
@Getter
@Setter
public class CareChatMessage extends BaseEntity {
    private String tenantId;
    private String threadId;
    private String senderType;
    private String senderStaffId;
    private String senderAccountId;
    private String contentType;
    private String content;
    private String clientMsgId;
    private LocalDateTime recalledAt;
}
