package com.healix.core.careplan.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanDailyCheckinSummaryDto {
    private LocalDate date;
    private int totalTasks;
    private int doneTasks;
    private int skippedTasks;
    private int missedTasks;
    private int pendingTasks;
    private List<CarePlanTodayTaskDto> tasks;
    private List<CarePlanCheckinViewDto> checkins;
}
