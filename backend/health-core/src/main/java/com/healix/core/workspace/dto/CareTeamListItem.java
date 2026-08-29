package com.healix.core.workspace.dto;

import java.time.LocalDateTime;
import lombok.Data;

@Data
public class CareTeamListItem {
    private String id;
    private String teamCode;
    private String name;
    private String primaryCareManagerStaffId;
    private String primaryCareManagerName;
    private String primaryDoctorStaffId;
    private String primaryDoctorName;
    private int memberCount;
    private String status;
    private LocalDateTime createdAt;
}
