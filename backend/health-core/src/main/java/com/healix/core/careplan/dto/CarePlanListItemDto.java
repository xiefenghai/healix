package com.healix.core.careplan.dto;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanListItemDto {
    /** 草稿 ID 或版本 ID */
    private String id;
    /** DRAFT / VERSION */
    private String recordType;
    /** 方案标题 */
    private String title;
    /** 方案摘要（目标一句话） */
    private String goalSummary;
    /** AI / MANUAL */
    private String sourceMode;
    private String source;
    /** 发布序号（同患者内递增） */
    private Integer versionNo;
    /** 产品规则版本 */
    private Integer schemaVersion;
    /** 展示用，如 V1 */
    private String versionLabel;
    private String staffId;
    private String staffName;
    private LocalDateTime createdAt;
    /** DRAFT / ACTIVE / ARCHIVED */
    private String status;
    private boolean deletable;
}
