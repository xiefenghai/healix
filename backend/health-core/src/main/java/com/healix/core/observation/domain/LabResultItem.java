package com.healix.core.observation.domain;

import com.healix.common.domain.BaseEntity;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LabResultItem extends BaseEntity {
    private String reportId;
    private String itemCode;
    private String itemName;
    private BigDecimal valueNum;
    private String valueText;
    private String unit;
    private BigDecimal refLow;
    private BigDecimal refHigh;
    private String abnormalFlag;
}
