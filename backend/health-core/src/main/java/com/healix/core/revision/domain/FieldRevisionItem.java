package com.healix.core.revision.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FieldRevisionItem extends BaseEntity {
    private String batchId;
    private String fieldPath;
    private String oldValue;
    private String newValue;
    private String oldDisplay;
    private String newDisplay;
    /** 聊天整理确认时带上的原句，最多 80 字。 */
    private String quote;
}
