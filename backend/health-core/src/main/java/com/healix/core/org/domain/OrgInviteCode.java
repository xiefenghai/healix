package com.healix.core.org.domain;

import com.healix.common.domain.BaseEntity;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 机构患者邀请码 */
@Getter
@Setter
public class OrgInviteCode extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 所属机构ID */
    private String orgId;
    /** 邀请码 */
    private String code;
    /** 是否启用：1启用 0停用 */
    private Integer enabled;
    /** 过期时间 */
    private LocalDateTime expireAt;
    /** 创建人（staff_profile.id） */
    private String createdByStaffId;
}
