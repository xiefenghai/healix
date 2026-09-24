package com.healix.core.opsstats.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class OpsStatsSummaryDto {
    private LocalDate from;
    private LocalDate to;
    private String careTeamId;
    private String staffId;
    private boolean canViewRanking;

    private long doneCount;
    private long doneWithDueCount;
    private long onTimeCount;
    private Double onTimeRate;
    private long doneNoDueCount;
    private long lateDoneCount;
    /** 此刻 OPEN。 */
    private long openCount;
    /** 此刻超期未结。 */
    private long overdueOpenCount;
    private long cancelledOrExpiredCount;

    private long followupDoneCount;
    private long reportPublishedCount;

    private List<OpsStatsSeriesPointDto> series = new ArrayList<>();
    private String footnote;
}
