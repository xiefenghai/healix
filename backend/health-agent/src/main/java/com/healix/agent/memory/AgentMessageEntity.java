package com.healix.agent.memory;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AgentMessageEntity extends BaseEntity {
    private String sessionId;
    private String role;
    private String content;
    private String toolName;
}
