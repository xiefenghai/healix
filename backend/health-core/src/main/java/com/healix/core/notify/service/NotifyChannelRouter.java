package com.healix.core.notify.service;

import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.enums.NotifyChannel;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 事件 → 启用通道。未启用通道不返回（不插 Delivery）。
 * MVP 仅 IN_APP。
 */
@Component
public class NotifyChannelRouter {

    public List<NotifyChannel> resolve(NotifyEventType eventType, List<NotifyChannel> override) {
        if (override != null && !override.isEmpty()) {
            return List.copyOf(override);
        }
        List<NotifyChannel> channels = new ArrayList<>();
        // 刀1/刀2：仅站内
        switch (eventType) {
            case REPORT_PUBLISHED, CARE_PLAN_PUBLISHED, DAILY_HEALTH_TODO, STAFF_NUDGE ->
                channels.add(NotifyChannel.IN_APP);
            case ACTIVATION_OTP -> {
                // P1 SMS；本期不排任何通道
            }
        }
        return channels;
    }
}
