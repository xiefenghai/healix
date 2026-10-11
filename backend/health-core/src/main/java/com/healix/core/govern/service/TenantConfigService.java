package com.healix.core.govern.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.core.govern.domain.TenantConfig;
import com.healix.core.govern.enums.FeatureFlagKeyEnum;
import com.healix.core.govern.mapper.TenantConfigMapper;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 租户白标配置。读取时会看 {@link FeatureFlagKeyEnum#WHITE_LABEL} 开关：
 * 关掉白标的租户即使库里有配置也返回平台默认，避免退订后仍显示定制品牌。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantConfigService {

    private static final Pattern COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    private final TenantConfigMapper tenantConfigMapper;
    private final FeatureFlagService featureFlagService;

    /** 对外品牌视图；字段为空表示用前端默认值。 */
    public record BrandingView(
            boolean whiteLabel,
            String appName,
            String logoUrl,
            String primaryColor,
            String loginSlogan,
            String supportPhone) {

        public static BrandingView platformDefault() {
            return new BrandingView(false, null, null, null, null, null);
        }
    }

    public BrandingView branding(String tenantId) {
        if (!StringUtils.hasText(tenantId)
                || !featureFlagService.enabled(tenantId, FeatureFlagKeyEnum.WHITE_LABEL)) {
            return BrandingView.platformDefault();
        }
        TenantConfig row = tenantConfigMapper.findByTenant(tenantId);
        if (row == null) {
            return BrandingView.platformDefault();
        }
        return new BrandingView(
                true,
                row.getAppName(),
                row.getLogoUrl(),
                row.getPrimaryColor(),
                row.getLoginSlogan(),
                row.getSupportPhone());
    }

    /** Ops 配置页读原始值：不受开关影响，否则关掉开关后就没法预览/改配置了。 */
    public TenantConfig raw(String tenantId) {
        return tenantConfigMapper.findByTenant(tenantId);
    }

    @Transactional
    public void save(String tenantId, TenantConfig input) {
        if (!StringUtils.hasText(tenantId)) {
            throw new BusinessException("缺少租户");
        }
        String color = trimToNull(input.getPrimaryColor());
        if (color != null && !COLOR.matcher(color).matches()) {
            throw new BusinessException("主色需为 #RRGGBB 格式");
        }
        TenantConfig row = new TenantConfig();
        row.setTenantId(tenantId);
        row.setAppName(trimToNull(input.getAppName()));
        row.setLogoUrl(trimToNull(input.getLogoUrl()));
        row.setPrimaryColor(color);
        row.setLoginSlogan(trimToNull(input.getLoginSlogan()));
        row.setSupportPhone(trimToNull(input.getSupportPhone()));
        EntityMeta.onCreate(row);
        tenantConfigMapper.upsert(row);
    }

    private static String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }
}
