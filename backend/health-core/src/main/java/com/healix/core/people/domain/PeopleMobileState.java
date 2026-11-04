package com.healix.core.people.domain;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 本人号之外的认人状态：无本人号标记、待核对字段。 */
@Getter
@Setter
public class PeopleMobileState {
    private String peopleId;
    private String tenantId;
    private Integer noSelfMobile;
    private String demoReviewJson;
    private LocalDateTime gmtModified;
}
