package com.healix.agent.gateway;

import com.healix.agent.extract.IntakeExtractField;
import java.util.List;

public record AgentChatCommand(
        String tenantId,
        String orgId,
        String staffId,
        String peopleId,
        String sessionId,
        String message,
        String capabilityHint,
        String imageBase64,
        String imageMimeType,
        List<IntakeExtractField> intakeFields) {}
