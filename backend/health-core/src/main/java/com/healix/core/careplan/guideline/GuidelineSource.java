package com.healix.core.careplan.guideline;

/** 指南来源；升版时旧来源置 SUPERSEDED 并指向新来源。 */
public record GuidelineSource(
        String code, String name, String edition, String publisher, String status, String supersededBy) {

    public String label() {
        return "《" + name + "》" + (edition == null || edition.isBlank() ? "" : edition);
    }
}
