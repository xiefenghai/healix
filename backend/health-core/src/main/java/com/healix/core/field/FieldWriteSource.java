package com.healix.core.field;

/**
 * 一次字段写入的来源：业务、端、单据、操作人。
 *
 * <p>{@code sourceBizCode}：{@code INTAKE_QUESTIONNAIRE} / {@code ASSESSMENT_QUESTIONNAIRE} /
 * {@code ARCHIVE_PAGE} / {@code C_LIFESTYLE} / {@code SYSTEM} / {@code LEGACY_ARCHIVE}
 */
public record FieldWriteSource(
        String sourceBizCode,
        String sourceClientCode,
        String sourceRefId,
        String updatedByType,
        String updatedById,
        String orgId) {

    public static FieldWriteSource of(
            String sourceBizCode,
            String sourceClientCode,
            String sourceRefId,
            String updatedByType,
            String updatedById,
            String orgId) {
        return new FieldWriteSource(
                sourceBizCode, sourceClientCode, sourceRefId, updatedByType, updatedById, orgId);
    }

    public static FieldWriteSource archivePage(String staffId, String orgId) {
        return of("ARCHIVE_PAGE", "B_WORKSPACE", null, "STAFF", staffId, orgId);
    }

    public static FieldWriteSource lifestyle(String peopleId) {
        return of("C_LIFESTYLE", "C_APP", null, "PEOPLE", peopleId, null);
    }

    public static FieldWriteSource legacyArchive() {
        return of("LEGACY_ARCHIVE", "SYSTEM", null, "SYSTEM", "SYSTEM", null);
    }
}
