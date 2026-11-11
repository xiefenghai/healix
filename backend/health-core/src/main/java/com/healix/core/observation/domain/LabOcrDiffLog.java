package com.healix.core.observation.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** OCR 纠错 Diff 埋点：AI 初值 vs 医生终值，供准确率统计。 */
@Getter
@Setter
public class LabOcrDiffLog extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String peopleId;
    private String reportId;
    private String staffId;
    private String imageKey;
    private Integer totalItems;
    private Integer unchangedItems;
    private Integer changedItems;
    /** 完整 Diff JSON */
    private String diffJson;
}
