package com.healix.core.dict.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DictItemDto {
    private String dictCode;
    private String dictCodeDesc;
    private String content;
    private Integer sortOrder;
}
