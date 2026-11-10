package com.healix.core.workspace.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 删除健管组前的影响预览（防误触二次确认）。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CareTeamDeleteImpact {
    private String teamId;
    private String teamName;
    /** 组内患者数（不含员工成员） */
    private int patientCount;
    /** 这些患者名下未完成（OPEN）待办数 */
    private int openTaskCount;
}
