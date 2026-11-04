package com.healix.core.sleep.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SleepDayDto {
    private LocalDate date;
    /** 当日各段合计时长 */
    private Integer durationMin;
    /** 主睡眠质量（无主睡则取首段） */
    private String quality;
    private String bedtime;
    private String wakeTime;
    private String note;
    private boolean logged;
    private boolean taskPresent;
    private String checkinStatus;
    private Integer avgDurationMin14d;
    private List<Segment> segments = new ArrayList<>();
    private List<TrendPoint> trend = new ArrayList<>();

    @Getter
    @Setter
    public static class Segment {
        private String id;
        private String segment;
        private Integer durationMin;
        private String quality;
        private String bedtime;
        private String wakeTime;
        private String note;
        private boolean recordedByStaff;
    }

    @Getter
    @Setter
    public static class TrendPoint {
        private LocalDate date;
        private Integer durationMin;
        private String quality;
    }
}
