package com.healix.agent.card;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 管家回复下的确认卡。确认接口只认这张卡的 id。 */
@Getter
@Setter
public class AgentConfirmCard extends BaseEntity {
    private String tenantId;
    private String peopleId;
    /** VITAL */
    private String cardType;
    /** PENDING / CONFIRMED / CANCELLED / EXPIRED */
    private String status;
    private String payloadJson;
    private String summary;
    private LocalDateTime expiresAt;
}
