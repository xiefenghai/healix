package com.healix.core.adherence.dto;

import java.time.LocalDate;
import lombok.Data;

@Data
public class AdherenceOverviewDto {
    private LocalDate date;
    private String careTeamId;
    private int universeCount;
    private int followUpCount;
    private int planIncompleteCount;
    private int medIncompleteCount;
    private int streakGe3Count;
}
