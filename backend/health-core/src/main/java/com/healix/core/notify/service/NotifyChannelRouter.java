package com.healix.core.notify.service;

import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.enums.NotifyChannel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 事件 → 启用通道。未启用通道不返回（不插 Delivery）。
 * 站内 + 浏览器推送（无订阅时推送投递记为 SKIPPED）。
 */
@Component
public class NotifyChannelRouter {

    public List<NotifyChannel> resolve(NotifyEventType eventType, List<NotifyChannel> override) {
        if (override != null && !override.isEmpty()) {
            return List.copyOf(override);
        }
        List<NotifyChannel> channels = new ArrayList<>();
        switch (eventType) {
            case REPORT_PUBLISHED, CARE_PLAN_PUBLISHED, DAILY_HEALTH_TODO, STAFF_NUDGE, REWARD, STAFF_KUDOS, MEDICATION_REMIND,
                    ASSESSMENT_QUESTIONNAIRE -> {
                channels.add(NotifyChannel.IN_APP);
                channels.add(NotifyChannel.WEB_PUSH);
            }
            case ACTIVATION_OTP -> {
                // P1 SMS；本期不排任何通道
            }
        }
        return channels;
    }
}
