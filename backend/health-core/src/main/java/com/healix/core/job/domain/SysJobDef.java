package com.healix.core.job.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 平台定时任务配置（按 job_code 一行）。 */
@Getter
@Setter
public class SysJobDef extends BaseEntity {
    private String jobCode;
    private String displayName;
    private String cronExpr;
    private String timezone;
    private Integer enabled;
    private String paramsJson;
    private LocalDateTime nextFireAt;
    private LocalDateTime lastFireAt;
    private String lastStatus;
    private String lastMessage;
}
