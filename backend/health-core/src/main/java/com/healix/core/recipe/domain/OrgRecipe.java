package com.healix.core.recipe.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OrgRecipe extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String name;
    private String description;
    private String tagsJson;
    /** sampleDay 形 JSON */
    private String mealJson;
    /** sampleWeek 形 JSON，可空 */
    private String weekJson;
    private Integer energyKcalHint;
    private String allergenKeywordsJson;
    private String status;
    private String createdByStaffId;
    private String updatedByStaffId;
}
