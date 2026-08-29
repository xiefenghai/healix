package com.healix.core.archive.dto;

import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArchiveViewDto {
    private String peopleId;
    private String tenantId;
    private Integer version;
    private String schemaVersion;
    private Object contentJson;
}
