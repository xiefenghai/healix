package com.healix.core.mood.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MoodLog extends BaseEntity {
    private String tenantId;
    private String peopleId;
    private LocalDate bizDate;
    private Integer score;
    private String tagsJson;
    private String note;
    private String recordedByPeopleId;
    private String recordedByStaffId;
}
