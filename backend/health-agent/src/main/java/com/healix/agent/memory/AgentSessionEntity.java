package com.healix.agent.memory;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AgentSessionEntity extends BaseEntity {
    private String agentType;
    private String tenantId;
    private String peopleId;
    private String staffId;
    private String orgId;
    private String title;
    private String status;
}
