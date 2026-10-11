package com.healix.core.job.handler;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class JobHandlerRegistry {

    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> handlers) {
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(JobHandler::jobCode, Function.identity()));
    }

    public Optional<JobHandler> find(String jobCode) {
        return Optional.ofNullable(handlers.get(jobCode));
    }
}
