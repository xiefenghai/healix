package com.healix.core.archive.service;

import com.healix.core.field.FieldWriteSource;

public record ArchiveOperatorContext(
        String operatorType,
        String operatorId,
        String orgId,
        String sourceClientCode,
        String sourceBizCode) {

    public static ArchiveOperatorContext staff(String staffId, String orgId) {
        return staff(staffId, orgId, "ARCHIVE_PAGE");
    }

    public static ArchiveOperatorContext staff(String staffId, String orgId, String sourceBizCode) {
        return new ArchiveOperatorContext("STAFF", staffId, orgId, "B_WORKSPACE", sourceBizCode);
    }

    public static ArchiveOperatorContext people(String peopleId) {
        return people(peopleId, "C_LIFESTYLE");
    }

    public static ArchiveOperatorContext people(String peopleId, String sourceBizCode) {
        return new ArchiveOperatorContext("PEOPLE", peopleId, null, "C_APP", sourceBizCode);
    }

    public FieldWriteSource toFieldSource() {
        return FieldWriteSource.of(
                sourceBizCode, sourceClientCode, null, operatorType, operatorId, orgId);
    }

    public FieldWriteSource toFieldSource(String sourceRefId) {
        return FieldWriteSource.of(
                sourceBizCode, sourceClientCode, sourceRefId, operatorType, operatorId, orgId);
    }
}
