package com.healix.core.dict.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SysDict extends BaseEntity {
    private String dictType;
    private String parentCode;
    private String dictCode;
    private String dictCodeDesc;
    private String content;
    private Integer sortOrder;
    private String tenantId;
}
