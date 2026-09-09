package com.healix.core.adherence.dto;

import java.time.LocalDate;
import lombok.Data;

/** 单日依从性汇总点（方案任务 + 用药）。 */
@Data
public class AdherenceDayPointDto {
    private LocalDate date;
    private int planDue;
    private int planDone;
    private int planSkipped;
    private int planIncomplete;
    private boolean planIncompleteFlag;
    /** 0~1；当日无应打任务时为 null */
    private Double planDoneRate;
    private int medActive;
    private int medTaken;
    /** 当日应服次数（按频次展开） */
    private int medDue;
    /** 当日已服次数 */
    private int medTakenDose;
    private boolean medIncomplete;
    /** 0~1；当日无在用药时为 null */
    private Double medDoneRate;
}
