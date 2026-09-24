package com.healix.core.opsstats.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class OpsStatsByStaffResponseDto {
    private boolean canViewRanking;
    private List<OpsStatsStaffRowDto> ranking = new ArrayList<>();
    private OpsStatsSelfCompareDto selfCompare;
}
