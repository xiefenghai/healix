package com.healix.core.opsstats.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 运营统计下钻：与汇总口径一致的单条任务。 */
@Data
public class OpsStatsTaskItemDto {
    private String id;
    private String peopleId;
    private String peopleName;
    private String taskType;
    private String taskTypeLabel;
    private String title;
    private String summary;
    private String status;
    private String assigneeStaffId;
    private String assigneeName;
    private String doneByStaffId;
    private String doneByName;
    private LocalDateTime dueAt;
    private LocalDateTime doneAt;
    /** 有截止且已办结时：是否在截止前关单。无截止或未办结为空。 */
    private Boolean onTime;
}
