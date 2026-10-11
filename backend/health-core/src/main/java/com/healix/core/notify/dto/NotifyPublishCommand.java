package com.healix.core.notify.dto;

import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.enums.NotifyChannel;
import com.healix.core.notify.enums.NotifyDuplicatePolicy;
import com.healix.core.notify.catalog.NotifyEventType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class NotifyPublishCommand {
    private final String tenantId;
    private final NotifyEventType eventType;
    private final String dedupeKey;
    private final NotifyAudience audience;
    /** 显式收件人；可与 peopleId 并用 */
    @Builder.Default
    private final List<String> recipientIds = new ArrayList<>();
    private final String peopleId;
    private final String orgId;
    private final String title;
    private final String body;
    private final String linkPath;
    @Builder.Default
    private final Map<String, Object> payload = new LinkedHashMap<>();
    @Builder.Default
    private final String priority = "NORMAL";
    private final List<NotifyChannel> channelsOverride;
    @Builder.Default
    private final NotifyDuplicatePolicy onDuplicate = NotifyDuplicatePolicy.IGNORE;
}
