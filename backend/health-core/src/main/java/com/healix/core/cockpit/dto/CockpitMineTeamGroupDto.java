package com.healix.core.cockpit.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 驾驶舱「我的患者」按主责健管组分组（含空组）。 */
@Data
public class CockpitMineTeamGroupDto {
    private String careTeamId;
    private String careTeamName;
    /** 组内患者卡；空组为 [] */
    private List<CockpitPriorityCardDto> patients = new ArrayList<>();
}
