package com.healix.core.medication.dto;

import lombok.Getter;
import lombok.Setter;

/** 平台药品库条目（小库 MVP，来源 sys_dict drugCatalog）。 */
@Getter
@Setter
public class DrugCatalogItemDto {
    private String code;
    private String displayName;
    private String genericName;
    private String spec;
    private String dosageForm;
    private String category;
    private String defaultDoseUnit;
    private String defaultDoseAmount;
    private String defaultUsageMethod;
    private String defaultFrequency;
}
