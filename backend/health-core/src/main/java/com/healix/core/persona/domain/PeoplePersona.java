package com.healix.core.persona.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 患者案头用户画像（一人一租户一行）。 */
@Getter
@Setter
public class PeoplePersona extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private String dimensionsJson;
    private String narrativeJson;
    private String sourceFingerprint;
    private String narrativeFingerprint;
    /** READY / GENERATING / FAILED */
    private String status;
    private LocalDateTime generatedAt;
    private LocalDateTime dimensionsAt;
    private String editedByStaffId;
}
