package com.healix.core.archive.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DiseaseArchiveViewDto {
    private String peopleId;
    private String diseaseCode;
    private Integer version;
    private String schemaVersion;
    private Object contentJson;
}
