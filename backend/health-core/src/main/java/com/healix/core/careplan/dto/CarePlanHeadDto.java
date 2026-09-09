package com.healix.core.careplan.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanHeadDto {
    private String id;
    private String peopleId;
    private String status;
    private String title;
    private String goalSummary;
    private JsonNode diseaseTags;
    private String currentVersionId;
    private Integer version;
}
