package com.healix.core.ops.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** MyBatis 查询行，Service 层组装为 {@link StaffAccountListItem}。 */
@Data
public class StaffProfileListRow {

    private String staffId;
    private String accountId;
    private String username;
    private String displayName;
    private String mobile;
    private String title;
    private String status;
    private LocalDateTime createdAt;
}
