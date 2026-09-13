package com.healix.core.notify.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.notify.domain.NotifyDelivery;
import com.healix.core.notify.domain.NotifyMessage;
import com.healix.core.notify.dto.NotifyMessageViewDto;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.dto.NotifyPublishResult;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.enums.NotifyChannel;
import com.healix.core.notify.enums.NotifyDeliveryStatus;
import com.healix.core.notify.enums.NotifyDuplicatePolicy;
import com.healix.core.notify.mapper.NotifyDeliveryMapper;
import com.healix.core.notify.mapper.NotifyMessageMapper;
import com.healix.core.notify.service.NotifyRecipientResolver.Recipient;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 站内消息发布门面：落库消息 + 按受众解析收件人与投递。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyFacade {

    private final NotifyMessageMapper messageMapper;
    private final NotifyDeliveryMapper deliveryMapper;
    private final NotifyRecipientResolver recipientResolver;
    private final NotifyChannelRouter channelRouter;

    @Transactional
    public NotifyPublishResult publish(NotifyPublishCommand cmd) {
        if (cmd == null || !StringUtils.hasText(cmd.getTenantId())) {
            throw new BusinessException(400, "tenantId 必填");
        }
        if (cmd.getEventType() == null) {
            throw new BusinessException(400, "eventType 必填");
        }
        if (!StringUtils.hasText(cmd.getDedupeKey())) {
            throw new BusinessException(400, "dedupeKey 必填");
        }
        if (cmd.getAudience() == null) {
            throw new BusinessException(400, "audience 必填");
        }
        if (!StringUtils.hasText(cmd.getTitle())) {
            throw new BusinessException(400, "title 必填");
        }

        NotifyPublishResult result = new NotifyPublishResult();
        if (cmd.getAudience() != NotifyAudience.C_ACCOUNT) {
            log.warn("NotifyFacade: audience {} not implemented yet", cmd.getAudience());
            return result;
        }

        List<Recipient> recipients =
                recipientResolver.resolveCAccounts(cmd.getPeopleId(), cmd.getRecipientIds());
        if (recipients.isEmpty()) {
            return result;
        }

        List<NotifyChannel> channels = channelRouter.resolve(cmd.getEventType(), cmd.getChannelsOverride());
        String payloadJson =
                cmd.getPayload() == null || cmd.getPayload().isEmpty() ? null : JsonUtils.toJson(cmd.getPayload());

        for (Recipient recipient : recipients) {
            NotifyMessage existing = messageMapper.findByDedupe(
                    cmd.getTenantId(),
                    cmd.getAudience().name(),
                    recipient.accountId(),
                    cmd.getEventType().name(),
                    cmd.getDedupeKey());
            if (existing != null) {
                if (cmd.getOnDuplicate() == NotifyDuplicatePolicy.UPSERT_BODY_KEEP_READ) {
                    existing.setTitle(cmd.getTitle());
                    existing.setBody(blankToEmpty(cmd.getBody()));
                    existing.setLinkPath(cmd.getLinkPath());
                    existing.setPayloadJson(payloadJson);
                    existing.setPriority(StringUtils.hasText(cmd.getPriority()) ? cmd.getPriority() : "NORMAL");
                    EntityMeta.onUpdate(existing);
                    messageMapper.updateBodyKeepRead(existing);
                }
                ensureInAppDelivery(existing, channels);
                result.addExisting(existing.getId());
                continue;
            }

            NotifyMessage row = new NotifyMessage();
            EntityMeta.onCreate(row);
            row.setTenantId(cmd.getTenantId());
            row.setAudience(cmd.getAudience().name());
            row.setRecipientId(recipient.accountId());
            row.setPeopleId(
                    StringUtils.hasText(cmd.getPeopleId())
                            ? cmd.getPeopleId()
                            : recipient.peopleId());
            row.setOrgId(cmd.getOrgId());
            row.setEventType(cmd.getEventType().name());
            row.setCategory(cmd.getEventType().category().name());
            row.setDedupeKey(cmd.getDedupeKey());
            row.setTitle(cmd.getTitle());
            row.setBody(blankToEmpty(cmd.getBody()));
            row.setLinkPath(cmd.getLinkPath());
            row.setPayloadJson(payloadJson);
            row.setPriority(StringUtils.hasText(cmd.getPriority()) ? cmd.getPriority() : "NORMAL");
            messageMapper.insert(row);
            ensureInAppDelivery(row, channels);
            result.addCreated(row.getId());
        }
        return result;
    }

    public List<NotifyMessageViewDto> listForAccount(
            String tenantId,
            String accountId,
            String peopleId,
            boolean unreadOnly,
            int limit,
            int offset) {
        int lim = Math.min(Math.max(limit, 1), 100);
        int off = Math.max(offset, 0);
        List<NotifyMessageViewDto> out = new ArrayList<>();
        for (NotifyMessage row : messageMapper.listByRecipient(
                tenantId,
                NotifyAudience.C_ACCOUNT.name(),
                accountId,
                blankToNull(peopleId),
                unreadOnly,
                lim,
                off)) {
            out.add(toView(row));
        }
        return out;
    }

    public long unreadCount(String tenantId, String accountId) {
        return messageMapper.countByRecipient(
                tenantId, NotifyAudience.C_ACCOUNT.name(), accountId, null, true);
    }

    @Transactional
    public NotifyMessageViewDto markRead(String tenantId, String accountId, String messageId) {
        NotifyMessage row = messageMapper.findById(messageId);
        if (row == null
                || !tenantId.equals(row.getTenantId())
                || !accountId.equals(row.getRecipientId())
                || !NotifyAudience.C_ACCOUNT.matches(row.getAudience())) {
            throw new BusinessException(404, "消息不存在");
        }
        if (row.getReadAt() == null) {
            LocalDateTime now = LocalDateTime.now();
            messageMapper.markRead(messageId, accountId, now, now);
            row.setReadAt(now);
        }
        return toView(row);
    }

    @Transactional
    public int markAllRead(String tenantId, String accountId) {
        LocalDateTime now = LocalDateTime.now();
        return messageMapper.markAllRead(
                tenantId, NotifyAudience.C_ACCOUNT.name(), accountId, now, now);
    }

    private void ensureInAppDelivery(NotifyMessage message, List<NotifyChannel> channels) {
        if (channels == null || !channels.contains(NotifyChannel.IN_APP)) {
            return;
        }
        NotifyDelivery existing =
                deliveryMapper.findByMessageAndChannel(message.getId(), NotifyChannel.IN_APP.name());
        if (existing != null) {
            return;
        }
        NotifyDelivery delivery = new NotifyDelivery();
        EntityMeta.onCreate(delivery);
        delivery.setTenantId(message.getTenantId());
        delivery.setMessageId(message.getId());
        delivery.setChannel(NotifyChannel.IN_APP.name());
        delivery.setStatus(NotifyDeliveryStatus.SENT.name());
        delivery.setAttemptCount(1);
        delivery.setSentAt(LocalDateTime.now());
        deliveryMapper.insert(delivery);
    }

    private NotifyMessageViewDto toView(NotifyMessage row) {
        NotifyMessageViewDto dto = new NotifyMessageViewDto();
        dto.setId(row.getId());
        dto.setPeopleId(row.getPeopleId());
        dto.setOrgId(row.getOrgId());
        dto.setEventType(row.getEventType());
        dto.setCategory(row.getCategory());
        dto.setTitle(row.getTitle());
        dto.setBody(row.getBody());
        dto.setLinkPath(row.getLinkPath());
        dto.setPayload(parsePayload(row.getPayloadJson()));
        dto.setPriority(row.getPriority());
        dto.setReadAt(row.getReadAt());
        dto.setUnread(row.getReadAt() == null);
        dto.setGmtCreated(row.getGmtCreated());
        return dto;
    }

    private static Object parsePayload(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return JsonUtils.fromJson(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String blankToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
