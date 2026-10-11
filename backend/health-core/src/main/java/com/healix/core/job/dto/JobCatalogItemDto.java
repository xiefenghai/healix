package com.healix.core.job.dto;

import lombok.Data;

/** 代码目录中的可注册任务类型。 */
@Data
public class JobCatalogItemDto {
    private String jobCode;
    private String displayName;
    private String description;
    private String defaultCron;
    private boolean added;
    private boolean handlerReady;
}
