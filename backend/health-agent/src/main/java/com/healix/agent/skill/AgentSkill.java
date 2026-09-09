package com.healix.agent.skill;

import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;
import java.util.List;

public interface AgentSkill {

    AgentCapability capability();

    boolean supports(AgentCapability routed, AgentChatCommand cmd);

    AgentResponse execute(AgentSkillContext ctx);

    default List<AgentCapability> quickCapabilities() {
        return List.of(capability());
    }
}
