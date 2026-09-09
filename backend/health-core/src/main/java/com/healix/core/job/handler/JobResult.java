package com.healix.core.job.handler;

import java.util.Map;

public record JobResult(String message, Map<String, Object> detail) {

    public static JobResult of(String message, Map<String, Object> detail) {
        return new JobResult(message, detail == null ? Map.of() : detail);
    }

    public static JobResult message(String message) {
        return new JobResult(message, Map.of());
    }
}
