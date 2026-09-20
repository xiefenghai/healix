package com.healix.core.carechat.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 机构视角：健管侧 ↔ 就诊人 一对一沟通线程。 */
@Getter
@Setter
public class CareChatThread extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private LocalDateTime lastMessageAt;
    private String lastMessagePreview;
    private String lastSenderType;
    private Integer staffUnreadCount;
    private Integer patientUnreadCount;
    private LocalDateTime closedAt;
}
