package com.healix.core.adherence.dto;

import lombok.Data;

@Data
public class AdherencePatientItemDto {
    private String peopleId;
    private String displayName;
    private String careTeamId;
    private String careTeamName;
    private Boolean clientLinked;
    /** HIGH / MEDIUM / LOW */
    private String riskLevel;
    private AdherencePlanMetricsDto plan;
    private AdherenceMedMetricsDto med;
}
