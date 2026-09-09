package com.healix.core.job.catalog;

public record JobCatalogSpec(
        String jobCode, String displayName, String description, String defaultCron, String defaultParamsJson) {}
