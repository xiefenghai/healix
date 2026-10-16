package com.healix.core.adherence.dto;

import java.time.LocalDate;
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
    private AdherenceLifestyleMetricsDto lifestyle;
    /** 当前方案版本执行期末日（含）；无已发布方案为 null */
    private LocalDate planEndDate;
}
