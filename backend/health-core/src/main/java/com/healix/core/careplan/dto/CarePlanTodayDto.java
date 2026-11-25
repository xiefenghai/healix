package com.healix.core.careplan.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanTodayDto {
    private LocalDate date;
    private String planId;
    private String planTitle;
    private String goalSummary;
    /** 阶段控制目标（结构化，供 C 端圆环） */
    private JsonNode phaseGoals;
    /** 首诊「这次最想解决什么问题」中文拼接；无诉求时为空 */
    private String primaryAppealText;
    private Integer versionNo;
    /** 产品规则版本 */
    private Integer schemaVersion;
    private String versionLabel;
    private int totalTasks;
    private int doneTasks;
    private int skippedTasks;
    private int pendingTasks;
    private List<CarePlanTodayTaskDto> tasks;
}
