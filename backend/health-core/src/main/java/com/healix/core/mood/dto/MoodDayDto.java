package com.healix.core.mood.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MoodDayDto {
    private LocalDate date;
    private Integer score;
    private String label;
    private List<String> tags = new ArrayList<>();
    private String note;
    private boolean logged;
    private boolean recordedByStaff;
    private boolean taskPresent;
    private String checkinStatus;
    private Double avgScore14d;
    private List<TrendPoint> trend = new ArrayList<>();

    @Getter
    @Setter
    public static class TrendPoint {
        private LocalDate date;
        private Integer score;
        private String label;
    }
}
