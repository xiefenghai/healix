package com.healix.core.careplan.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanDraftDto {
    private String id;
    private String planId;
    private Integer version;
    /** 产品规则版本 */
    private Integer schemaVersion;
    private String source;
    private JsonNode exercise;
    private JsonNode diet;
    private JsonNode execution;
    private JsonNode safetyFlags;
    private JsonNode contextSnapshot;
    private String baseVersionId;
}
