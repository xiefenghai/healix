package com.healix.core.reward.service;

import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.dto.NotifyPublishResult;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.enums.NotifyDuplicatePolicy;
import com.healix.core.notify.service.NotifyFacade;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** 激励推送：失败只记日志，不影响结算。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RewardNotifier {

    public static final String LINK_PATH = "/achievements";

    private final NotifyFacade notifyFacade;

    public boolean push(String tenantId, String peopleId, String dedupe, String title, String body) {
        return publish(NotifyEventType.REWARD, tenantId, null, peopleId, peopleId + ":" + dedupe, title, body, Map.of());
    }

    public boolean publish(
            NotifyEventType type,
            String tenantId,
            String orgId,
            String peopleId,
            String dedupeKey,
            String title,
            String body,
            Map<String, Object> payload) {
        try {
            NotifyPublishResult result = notifyFacade.publish(NotifyPublishCommand.builder()
                    .tenantId(tenantId)
                    .eventType(type)
                    .dedupeKey(dedupeKey)
                    .audience(NotifyAudience.C_ACCOUNT)
                    .peopleId(peopleId)
                    .orgId(orgId)
                    .title(title)
                    .body(body)
                    .linkPath(LINK_PATH)
                    .payload(payload)
                    .onDuplicate(NotifyDuplicatePolicy.UPSERT_BODY_KEEP_READ)
                    .build());
            return !result.getMessageIds().isEmpty();
        } catch (Exception ex) {
            log.warn("{} notify failed tenant={} people={}: {}", type, tenantId, peopleId, ex.getMessage());
            return false;
        }
    }
}
