package com.healix.core.revision.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RevisionItemViewDto {
    private String fieldPath;
    private String oldValue;
    private String newValue;
    private String oldDisplay;
    private String newDisplay;
}
