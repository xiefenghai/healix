package com.healix.core.diet.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 食物库条目；营养素均为每 100g 可食部。 */
@Getter
@Setter
public class FoodItem extends BaseEntity {
    /** NULL = 平台级 */
    private String tenantId;
    private String code;
    private String name;
    private String aliasesJson;
    /** 见 {@link com.healix.core.diet.enums.FoodKindEnum} */
    private String kind;
    private String category;
    private String subCategory;
    private Double energyKcal;
    private Double proteinG;
    private Double fatG;
    private Double carbG;
    private Double fiberG;
    private Double sodiumMg;
    private Double ediblePct;
    private Integer gi;
    private String giLevel;
    private String unitsJson;
    private String tagsJson;
    /** 菜肴一份克数（按菜谱生料合计） */
    private Double servingGrams;
    private Integer isDefault;
    private String dataSource;
    private String precisionLevel;
    private Integer needsReview;
    private String seedVersion;
    private String status;
}
