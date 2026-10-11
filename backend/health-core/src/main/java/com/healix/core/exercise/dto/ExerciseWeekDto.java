package com.healix.core.exercise.dto;

import java.time.LocalDate;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 自然周（周一至周日）运动汇总。 */
@Getter
@Setter
public class ExerciseWeekDto {
    private LocalDate weekStart;
    private LocalDate weekEnd;
    /** ACTIVE / EXPIRED / NONE（按今天判定） */
    private String planState;
    /** 截至今天应打的运动任务次数 */
    private int dueCount;
    /** 其中已完成（含手动打卡） */
    private int doneCount;
    private int activeMin;
    private int weeklyActiveMinTarget;
    private int totalMin;
    private int sessionCount;
    /** 本周是否有步数数据；无则前端不展示步数 */
    private boolean hasSteps;
    private List<Day> days;

    @Getter
    @Setter
    public static class Day {
        private LocalDate date;
        private int dueCount;
        private int doneCount;
        private int activeMin;
        private int totalMin;
        private Integer steps;
        private String level;
    }
}
