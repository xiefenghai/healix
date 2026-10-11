package com.healix.core.careplan.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanVersion extends BaseEntity {
    private String planId;
    /** 同患者内发布序号（递增，仅作历史排序/唯一约束，非规则版本） */
    private Integer versionNo;
    /** 产品规则版本，见 {@link com.healix.core.careplan.support.CarePlanSchemaVersions} */
    private Integer schemaVersion;
    private String source;
    /** 发布时方案标题快照（历史列表互不影响） */
    private String title;
    private String exerciseJson;
    private String dietJson;
    private String executionJson;
    private String contextSnapshotJson;
    private String safetyFlagsJson;
    private LocalDateTime publishedAt;
    private String publishedByStaffId;
    private String signedByStaffId;
    private LocalDateTime signedAt;
    private String signStatus;
}
