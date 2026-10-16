package com.healix.agent.careplan;

import com.healix.core.careplan.dto.CarePlanBundleDto;
import org.springframework.util.StringUtils;

/** 方案结束后的状态文案；结构化详情由前端卡片展示。 */
public final class CarePlanStreamSummary {

    private CarePlanStreamSummary() {}

    public static String buildReply(CarePlanBundleDto bundle) {
        return buildReply(bundle, false);
    }

    public static String buildReply(CarePlanBundleDto bundle, boolean revised) {
        String title = null;
        if (bundle != null && bundle.getPlan() != null) {
            title = bundle.getPlan().getTitle();
        }
        StringBuilder sb = new StringBuilder();
        if (revised) {
            sb.append("已按你的要求修订管理方案草稿");
        } else {
            sb.append("已生成管理方案草稿");
        }
        if (StringUtils.hasText(title)) {
            sb.append("「").append(title).append("」");
        }
        sb.append("，请核对后发布。");
        return sb.toString();
    }
}
