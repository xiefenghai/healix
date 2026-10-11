package com.healix.core.diet.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 餐次明细；营养值为保存时快照，食物库后续修订不影响历史。 */
@Getter
@Setter
public class DietMealItem extends BaseEntity {
    private String tenantId;
    private String mealId;
    /** NULL = 未命中食物库，营养为估算 */
    private String foodCode;
    private String foodName;
    private Double amount;
    private String unit;
    /** 可食部克数 */
    private Double grams;
    private String oilLevel;
    private Double energyKcal;
    private Double carbG;
    private Double proteinG;
    private Double fatG;
    private Double fiberG;
    private Double sodiumMg;
    private String precisionLevel;
    private Double confidence;
    private Integer gramsConfirmed;
    private Integer sortOrder;
}
