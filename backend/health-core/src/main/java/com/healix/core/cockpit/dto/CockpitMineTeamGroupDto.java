package com.healix.core.cockpit.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 驾驶舱「我的患者」按主责健管组分组（含空组；含顶部「未分组患者」虚拟组）。 */
@Data
public class CockpitMineTeamGroupDto {
    /**
     * 健管组 id；未分组虚拟组为 {@code __UNASSIGNED__}。
     */
    private String careTeamId;
    private String careTeamName;
    /** 组内患者卡；空组为 [] */
    private List<CockpitPriorityCardDto> patients = new ArrayList<>();

    /** 左侧「未分组患者」虚拟组 id（非真实 care_team 行）。 */
    public static final String UNASSIGNED_GROUP_ID = "__UNASSIGNED__";
}
