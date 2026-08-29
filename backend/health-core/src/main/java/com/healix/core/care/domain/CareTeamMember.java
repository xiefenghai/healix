package com.healix.core.care.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 健管组成员 */
@Getter
@Setter
public class CareTeamMember extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String teamId;
    private String memberType;
    private String staffId;
    private String peopleId;
    private LocalDateTime joinedAt;
}
