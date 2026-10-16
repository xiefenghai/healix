package com.healix.core.followup.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 定期随访排期规则。org_id / disease_code 空串分别表示「全租户」「不限病种」。
 */
@Getter
@Setter
public class FollowupScheduleRule extends BaseEntity {
    private String tenantId;
    private String orgId;
    private String diseaseCode;
    /** 有执行中方案时的间隔（天） */
    private Integer intervalWithPlan;
    /** 无方案时的间隔（天） */
    private Integer intervalNoPlan;
    /** 0 = 命中该规则的患者不自动排期 */
    private Integer enabled;
    private String updatedByStaffId;
}
