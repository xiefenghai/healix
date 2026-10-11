package com.healix.core.govern.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.govern.domain.FeatureFlag;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.mapper.FeatureFlagMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 功能开关读写。三级取值：租户级记录 → 平台级记录 → 枚举默认值。
 *
 * <p>不做本地缓存：开关就是为了「立刻生效」，一次主键等值查询的代价远小于缓存不一致带来的困惑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeatureFlagService {

    /** 平台级记录的 tenant_id 占位 */
    public static final String PLATFORM_SCOPE = "";

    private final FeatureFlagMapper featureFlagMapper;

    /** 开关视图，Ops 页面用；{@code source} 说明当前值来自哪一级。 */
    public record FlagView(String key, String label, boolean enabled, String source, String configJson) {}

    public boolean enabled(String tenantId, FeatureFlagKeyEnum key) {
        if (StringUtils.hasText(tenantId)) {
            FeatureFlag own = featureFlagMapper.find(tenantId, key.name());
            if (own != null) {
                return own.isEnabled();
            }
        }
        FeatureFlag platform = featureFlagMapper.find(PLATFORM_SCOPE, key.name());
        if (platform != null) {
            return platform.isEnabled();
        }
        return key.defaultEnabled();
    }

    /** 关闭时抛业务异常，供接口入口一行拦截。 */
    public void assertEnabled(String tenantId, FeatureFlagKeyEnum key) {
        if (!enabled(tenantId, key)) {
            throw new BusinessException("当前套餐未开通「" + key.label() + "」，请联系管理员");
        }
    }

    /** 全量开关（含未落库的默认值），Ops 配置页直接渲染。 */
    public List<FlagView> list(String tenantId) {
        Map<String, FeatureFlag> own = index(StringUtils.hasText(tenantId) ? tenantId : null);
        Map<String, FeatureFlag> platform = index(PLATFORM_SCOPE);
        List<FlagView> out = new ArrayList<>();
        for (FeatureFlagKeyEnum key : FeatureFlagKeyEnum.values()) {
            FeatureFlag row = own.get(key.name());
            String source = "TENANT";
            if (row == null) {
                row = platform.get(key.name());
                source = "PLATFORM";
            }
            if (row == null) {
                out.add(new FlagView(key.name(), key.label(), key.defaultEnabled(), "DEFAULT", null));
            } else {
                out.add(new FlagView(key.name(), key.label(), row.isEnabled(), source, row.getConfigJson()));
            }
        }
        return out;
    }

    /** 写租户级开关；tenantId 传空串写平台级默认值。 */
    @Transactional
    public void set(String tenantId, String flagKey, boolean enabled, String configJson) {
        FeatureFlagKeyEnum key = FeatureFlagKeyEnum.of(flagKey);
        String scope = tenantId == null ? PLATFORM_SCOPE : tenantId;
        LocalDateTime now = LocalDateTime.now();
        if (featureFlagMapper.update(scope, key.name(), enabled, configJson, now) > 0) {
            return;
        }
        FeatureFlag row = new FeatureFlag();
        row.setTenantId(scope);
        row.setFlagKey(key.name());
        row.setEnabled(enabled);
        row.setConfigJson(configJson);
        EntityMeta.onCreate(row);
        featureFlagMapper.insert(row);
    }

    private Map<String, FeatureFlag> index(String scope) {
        if (scope == null) {
            return Map.of();
        }
        Map<String, FeatureFlag> map = new LinkedHashMap<>();
        for (FeatureFlag row : featureFlagMapper.listByTenant(scope)) {
            map.put(row.getFlagKey(), row);
        }
        return map;
    }
}
