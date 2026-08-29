package com.healix.core.workspace.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

@Data
public class OrgStaffItem {
    private String staffId;
    private String accountId;
    private String username;
    private String displayName;
    private String mobile;
    private String title;
    private String status;
    private List<String> roles;
    private LocalDateTime createdAt;
}
