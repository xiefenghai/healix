package com.healix.agent.gateway;

public record AgentChatCommand(
        String tenantId,
        String orgId,
        String staffId,
        String peopleId,
        String sessionId,
        String message,
        String capabilityHint,
        String imageBase64,
        String imageMimeType) {}
