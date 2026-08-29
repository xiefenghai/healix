package com.healix.core.workspace.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class OrgPatientListItem {
    private String peopleId;
    private String displayName;
    private String gender;
    private LocalDate birthday;
    private String identityMask;
    private String identityType;
    private String careTeamId;
    private String careTeamName;
    private LocalDateTime joinedAt;
}
