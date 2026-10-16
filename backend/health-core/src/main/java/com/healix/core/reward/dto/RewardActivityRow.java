package com.healix.core.reward.dto;

import java.time.LocalDate;
import lombok.Data;

/** 某患者某天的打卡动作数（方案打卡 + 服药 + 测量，仅本人录入；测量按次合并）。 */
@Data
public class RewardActivityRow {
    private String peopleId;
    private LocalDate day;
    private Integer actions;
}
