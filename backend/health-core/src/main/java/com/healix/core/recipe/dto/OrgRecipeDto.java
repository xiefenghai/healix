package com.healix.core.recipe.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrgRecipeDto {
    private String id;
    private String orgId;
    private String name;
    private String description;
    private List<String> tags;
    private JsonNode meal;
    private JsonNode week;
    private Integer energyKcalHint;
    private List<String> allergenKeywords;
    private String status;
    private String createdByStaffId;
    private String updatedByStaffId;
    private String gmtModified;
}
