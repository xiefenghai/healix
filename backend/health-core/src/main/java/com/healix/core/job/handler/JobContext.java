package com.healix.core.job.handler;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;

public record JobContext(
        String jobCode, String runId, String triggerType, JsonNode params, Clock clock, Instant deadline) {

    public boolean timedOut() {
        return clock.instant().isAfter(deadline);
    }
}
