package com.healix.core.exercise.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 运动主页「今日」：计划状态、今日运动任务及累计、今日记录。 */
@Getter
@Setter
public class ExerciseTodayDto {
    private LocalDate date;
    /** ACTIVE / EXPIRED / NONE */
    private String planState;
    private List<TaskItem> tasks;
    private List<String> contraindications;
    private List<String> precautions;
    private List<ExerciseSessionDto> sessions;

    @Getter
    @Setter
    public static class TaskItem {
        private String taskId;
        private String taskCode;
        private String title;
        private String typeCode;
        private String typeName;
        private String category;
        private int targetMin;
        private String intensity;
        /** 今日同大类累计分钟 */
        private int doneMin;
        /** DONE / SKIPPED / MISSED / null（未打卡） */
        private String checkinStatus;
    }
}
