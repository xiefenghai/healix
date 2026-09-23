package com.healix.core.careplan.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CarePlanVersionDto {
    private String id;
    private String planId;
    private Integer versionNo;
    /** 产品规则版本 */
    private Integer schemaVersion;
    /** 展示用，如 V1 */
    private String versionLabel;
    /** 本版标题快照 */
    private String title;
    private String source;
    private JsonNode exercise;
    private JsonNode diet;
    private JsonNode execution;
    private JsonNode safetyFlags;
    private JsonNode contextSnapshot;
    private LocalDateTime publishedAt;
    private String publishedByStaffId;
    private String signStatus;
    private List<CarePlanTaskDto> tasks;
}
