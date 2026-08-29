package com.healix.core.archive.service;

public record ArchiveOperatorContext(
        String operatorType,
        String operatorId,
        String orgId,
        String sourceClientCode) {

    public static ArchiveOperatorContext staff(String staffId, String orgId) {
        return new ArchiveOperatorContext("STAFF", staffId, orgId, "B_WORKSPACE");
    }

    public static ArchiveOperatorContext people(String peopleId) {
        return new ArchiveOperatorContext("PEOPLE", peopleId, null, "C_APP");
    }
}
