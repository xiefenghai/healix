package com.healix.core.careplan.dto;

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
