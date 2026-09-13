package com.healix.agent.skill;

import com.healix.agent.gateway.AgentCapability;
import com.healix.agent.gateway.AgentChatCommand;
import com.healix.agent.gateway.AgentResponse;

public interface AgentSkill {

    AgentCapability capability();

    boolean supports(AgentCapability routed, AgentChatCommand cmd);

    AgentResponse execute(AgentSkillContext ctx);

}
