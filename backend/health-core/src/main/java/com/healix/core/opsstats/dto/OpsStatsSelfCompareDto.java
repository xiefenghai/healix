package com.healix.core.opsstats.dto;

import lombok.Data;

/** 一线：本人 vs 健管组（或机构）均值。 */
@Data
public class OpsStatsSelfCompareDto {
    private OpsStatsStaffRowDto self;
    private OpsStatsStaffRowDto groupAvg;
    /** CARE_TEAM / ORG */
    private String avgScope;
    private String avgScopeLabel;
}
