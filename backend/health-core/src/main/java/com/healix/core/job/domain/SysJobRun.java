package com.healix.core.job.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 平台定时任务执行日志（每次调度/手动触发一条）。 */
@Getter
@Setter
public class SysJobRun extends BaseEntity {
    private String jobDefId;
    private String jobCode;
    private String triggerType;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String message;
    private String detailJson;
    private String instanceId;
    private String triggeredBy;
}
