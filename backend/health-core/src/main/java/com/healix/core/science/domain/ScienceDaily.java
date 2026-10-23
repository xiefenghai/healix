package com.healix.core.science.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 某人某一天的科普。一天一条。 */
@Getter
@Setter
public class ScienceDaily extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate bizDate;
    private String clipCode;
    /** 当天是否点过播放 */
    private Integer watched;
}
