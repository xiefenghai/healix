package com.healix.core.identity.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** B 端人员档案（归属租户） */
@Getter
@Setter
public class StaffProfile extends BaseEntity {
    /** 所属租户ID */
    private String tenantId;
    /** 关联 staff_account.id */
    private String accountId;
    /** 显示名称 */
    private String displayName;
    /** 手机号 */
    private String mobile;
    /** 职称/头衔 */
    private String title;
}
