package com.healix.core.govern.domain;

import com.healix.common.domain.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 功能开关。{@code tenantId} 为空串表示平台级默认值。 */
@Getter
@Setter
public class FeatureFlag extends BaseEntity {
    /** 租户业务ID；空串 = 平台级 */
    private String tenantId;
    /** 见 {@link com.healix.core.govern.enums.FeatureFlagKeyEnum} */
    private String flagKey;
    private boolean enabled;
    private String configJson;
}
