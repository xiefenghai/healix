package com.healix.core.water.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WaterDayDto {
    private LocalDate date;
    private int intakeMl;
    private Integer targetMl;
    private boolean reached;
    private boolean taskPresent;
    private String taskId;
    private String checkinStatus;
    private List<Entry> entries;
    /** 近 14 日按日累计（含当日），无记录日可不出现或 intakeMl=0 */
    private Integer avgIntakeMl14d;
    private Integer reachedDays14d;
    private List<TrendPoint> trend = new ArrayList<>();

    @Getter
    @Setter
    public static class Entry {
        private String id;
        private int amountMl;
        private String source;
        private String createdAt;
        private boolean recordedByStaff;
    }

    @Getter
    @Setter
    public static class TrendPoint {
        private LocalDate date;
        private int intakeMl;
        private Integer targetMl;
        private boolean reached;
    }
}
