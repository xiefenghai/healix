package com.healix.core.carechat.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.AuditDetails;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.audit.enums.AuditActionEnum;
import com.healix.core.audit.service.AuditService;
import com.healix.core.carechat.domain.CareChatMessage;
import com.healix.core.carechat.domain.CareChatThread;
import com.healix.core.carechat.dto.CareChatInboxDto;
import com.healix.core.carechat.dto.CareChatMessageDto;
import com.healix.core.carechat.dto.CareChatSessionDto;
import com.healix.core.carechat.dto.CareChatThreadDto;
import com.healix.core.carechat.enums.CareChatSenderType;
import com.healix.core.carechat.mapper.CareChatMessageMapper;
import com.healix.core.carechat.mapper.CareChatThreadMapper;
import com.healix.core.carechat.realtime.CareChatRealtimeEvent;
import com.healix.core.carechat.realtime.CareChatRealtimePublisher;
import com.healix.core.identity.domain.StaffProfile;
import com.healix.core.identity.mapper.StaffProfileMapper;
import com.healix.core.org.domain.Organization;
import com.healix.core.org.mapper.OrganizationMapper;
import com.healix.core.patient.domain.PatientCareAssignment;
import com.healix.core.patient.domain.PatientOrgMembership;
import com.healix.core.patient.mapper.PatientCareAssignmentMapper;
import com.healix.core.patient.mapper.PatientOrgMembershipMapper;
import com.healix.core.patientcard.domain.AccountPatient;
import com.healix.core.patientcard.mapper.AccountPatientMapper;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import com.healix.core.portal.enums.PortalEnum;
import com.healix.core.workspace.dto.OrgPatientListItem;
import com.healix.core.workspace.service.OrgWorkspaceService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 沟通域 Facade：建线程、发消息、历史、已读、未读。
 *
 * <p>与通知域解耦；V1 不接 WakeAdapter。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CareChatFacade {

    private static final int PREVIEW_LEN = 80;
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final String CONTENT_TEXT = "TEXT";

    private final CareChatThreadMapper threadMapper;
    private final CareChatMessageMapper messageMapper;
    private final ArchiveAccessService archiveAccessService;
    private final AccountPatientMapper accountPatientMapper;
    private final PeopleProfileMapper peopleProfileMapper;
    private final StaffProfileMapper staffProfileMapper;
    private final OrganizationMapper organizationMapper;
    private final PatientCareAssignmentMapper careAssignmentMapper;
    private final PatientOrgMembershipMapper membershipMapper;
    private final OrgWorkspaceService orgWorkspaceService;
    private final AuditService auditService;
    private final ObjectProvider<CareChatRealtimePublisher> realtimePublisher;

    @Transactional
    public CareChatSessionDto openForStaff(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CareChatThread thread = getOrCreateThread(tenantId, orgId, peopleId);
        boolean linked = accountPatientMapper.countByPeople(peopleId) > 0;
        CareChatSessionDto dto = new CareChatSessionDto();
        dto.setThread(toThreadDto(thread));
        dto.setMessages(loadRecentAsc(thread.getId(), DEFAULT_LIMIT));
        dto.setPatientLinked(linked);
        dto.setCanSend(linked && thread.getClosedAt() == null);
        if (!linked) {
            dto.setBlockReason("该患者尚未绑定 C 端账号，无法发起沟通（可引导激活）");
        } else if (thread.getClosedAt() != null) {
            dto.setBlockReason("会话已关闭，仅可查看历史");
        }
        return dto;
    }

    public List<CareChatMessageDto> listHistoryForStaff(
            String tenantId, String orgId, String peopleId, String beforeId, int limit) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CareChatThread thread = requireThread(tenantId, orgId, peopleId);
        return listHistory(thread.getId(), beforeId, limit);
    }

    public List<CareChatMessageDto> listAfterForStaff(
            String tenantId, String orgId, String peopleId, String afterId, int limit) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CareChatThread thread = requireThread(tenantId, orgId, peopleId);
        return listAfter(thread.getId(), afterId, limit);
    }

    @Transactional
    public CareChatMessageDto sendFromStaff(
            String tenantId,
            String orgId,
            String peopleId,
            String staffId,
            String accountId,
            String content,
            String clientMsgId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        if (accountPatientMapper.countByPeople(peopleId) <= 0) {
            throw new BusinessException(400, "该患者尚未绑定 C 端账号，无法发送沟通");
        }
        CareChatThread thread = getOrCreateThread(tenantId, orgId, peopleId);
        assertOpen(thread);
        String text = normalizeContent(content);
        if (StringUtils.hasText(clientMsgId)) {
            CareChatMessage existing = messageMapper.findByClientMsgId(thread.getId(), clientMsgId.trim());
            if (existing != null) {
                return toMessageDto(existing);
            }
        }
        CareChatMessage msg = newMessage(tenantId, thread.getId(), CareChatSenderType.STAFF, text, clientMsgId);
        msg.setSenderStaffId(staffId);
        EntityMeta.onCreate(msg);
        messageMapper.insert(msg);
        bumpThread(thread.getId(), msg, 0, 1);
        auditService.record(
                PortalEnum.B.code(),
                accountId,
                "STAFF",
                tenantId,
                AuditActionEnum.CARE_CHAT_SEND.name(),
                "care_chat_message",
                msg.getId(),
                peopleId,
                AuditDetails.of(
                        "threadId",
                        thread.getId(),
                        "orgId",
                        orgId,
                        "senderType",
                        CareChatSenderType.STAFF.name(),
                        "staffId",
                        staffId));
        CareChatMessageDto dto = toMessageDto(msg);
        publishMessageEvent(tenantId, orgId, peopleId, thread.getId(), dto);
        return dto;
    }

    @Transactional
    public void markStaffRead(String tenantId, String orgId, String peopleId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        CareChatThread thread = threadMapper.findByOrgPeople(tenantId, orgId, peopleId);
        if (thread == null) {
            return;
        }
        threadMapper.clearStaffUnread(thread.getId());
        publishReadEvent(tenantId, orgId, peopleId, thread.getId(), true);
    }

    public long staffUnreadTotal(String tenantId, String orgId) {
        return threadMapper.sumStaffUnreadByOrg(tenantId, orgId);
    }

    /** 机构沟通收件箱：患者按健管组分组，附带健管侧未读。 */
    public CareChatInboxDto inboxForStaff(String tenantId, String orgId) {
        List<OrgPatientListItem> patients = orgWorkspaceService.listOrgPatients(orgId, null, null, null);
        Map<String, CareChatThread> threadByPeople = new HashMap<>();
        for (CareChatThread t : threadMapper.listByOrg(tenantId, orgId)) {
            if (StringUtils.hasText(t.getPeopleId())) {
                threadByPeople.putIfAbsent(t.getPeopleId(), t);
            }
        }

        Map<String, CareChatInboxDto.Group> groups = new LinkedHashMap<>();
        List<CareChatInboxDto.PatientItem> allItems = new ArrayList<>();
        int totalUnread = 0;
        int patientWithUnread = 0;

        for (OrgPatientListItem p : patients) {
            CareChatThread thread = threadByPeople.get(p.getPeopleId());
            CareChatInboxDto.PatientItem item = new CareChatInboxDto.PatientItem();
            item.setPeopleId(p.getPeopleId());
            item.setDisplayName(p.getDisplayName());
            item.setCareTeamId(p.getCareTeamId());
            item.setCareTeamName(p.getCareTeamName());
            item.setPatientLinked(Boolean.TRUE.equals(p.getClientLinked()));
            item.setHasThread(thread != null);
            int unread = thread == null || thread.getStaffUnreadCount() == null ? 0 : thread.getStaffUnreadCount();
            item.setStaffUnreadCount(unread);
            if (thread != null) {
                item.setLastMessagePreview(thread.getLastMessagePreview());
                item.setLastSenderType(thread.getLastSenderType());
                item.setLastMessageAt(thread.getLastMessageAt());
            }
            totalUnread += unread;
            if (unread > 0) {
                patientWithUnread++;
            }
            allItems.add(item);

            String teamKey = StringUtils.hasText(p.getCareTeamId()) ? p.getCareTeamId() : "__UNASSIGNED__";
            CareChatInboxDto.Group group = groups.get(teamKey);
            if (group == null) {
                group = new CareChatInboxDto.Group();
                group.setCareTeamId(StringUtils.hasText(p.getCareTeamId()) ? p.getCareTeamId() : null);
                group.setCareTeamName(StringUtils.hasText(p.getCareTeamName()) ? p.getCareTeamName() : "未入组");
                groups.put(teamKey, group);
            }
            group.getPatients().add(item);
            group.setUnreadCount(group.getUnreadCount() + unread);
            group.setPatientCount(group.getPatientCount() + 1);
        }

        List<CareChatInboxDto.PatientItem> recent = allItems.stream()
                .filter(i -> i.getLastMessageAt() != null)
                .sorted(Comparator.comparing(
                                CareChatInboxDto.PatientItem::getLastMessageAt, Comparator.reverseOrder())
                        .thenComparing(
                                Comparator.comparingInt(CareChatInboxDto.PatientItem::getStaffUnreadCount)
                                        .reversed()))
                .limit(30)
                .collect(Collectors.toCollection(ArrayList::new));

        List<CareChatInboxDto.Group> groupList = new ArrayList<>(groups.values());
        for (CareChatInboxDto.Group g : groupList) {
            g.getPatients()
                    .sort(Comparator.comparingInt(CareChatInboxDto.PatientItem::getStaffUnreadCount)
                            .reversed()
                            .thenComparing(
                                    CareChatInboxDto.PatientItem::getLastMessageAt,
                                    Comparator.nullsLast(Comparator.reverseOrder()))
                            .thenComparing(
                                    p -> p.getDisplayName() == null ? "" : p.getDisplayName(),
                                    String.CASE_INSENSITIVE_ORDER));
        }
        groupList.sort(Comparator.comparingInt(CareChatInboxDto.Group::getUnreadCount)
                .reversed()
                .thenComparing(g -> g.getCareTeamId() == null ? 1 : 0)
                .thenComparing(
                        g -> g.getCareTeamName() == null ? "" : g.getCareTeamName(),
                        String.CASE_INSENSITIVE_ORDER));

        CareChatInboxDto dto = new CareChatInboxDto();
        dto.setTotalUnread(totalUnread);
        dto.setPatientWithUnread(patientWithUnread);
        dto.setRecent(recent);
        dto.setGroups(groupList);
        return dto;
    }

    public List<CareChatThreadDto> listThreadsForAccount(String tenantId, String accountId) {
        List<AccountPatient> cards = accountPatientMapper.listByAccount(accountId);
        if (cards.isEmpty()) {
            return List.of();
        }
        List<String> peopleIds = cards.stream()
                .map(AccountPatient::getPeopleId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (peopleIds.isEmpty()) {
            return List.of();
        }
        Map<String, String> nameByPeople = new HashMap<>();
        for (AccountPatient c : cards) {
            if (StringUtils.hasText(c.getPeopleId()) && StringUtils.hasText(c.getDisplayName())) {
                nameByPeople.putIfAbsent(c.getPeopleId(), c.getDisplayName());
            }
        }
        List<CareChatThread> threads = threadMapper.listByPeopleIds(tenantId, peopleIds);
        List<CareChatThreadDto> out = new ArrayList<>();
        for (CareChatThread t : threads) {
            CareChatThreadDto dto = toThreadDto(t);
            if (!StringUtils.hasText(dto.getPeopleName())) {
                dto.setPeopleName(nameByPeople.get(t.getPeopleId()));
            }
            out.add(dto);
        }
        return out;
    }

    public CareChatSessionDto openForPatient(String tenantId, String accountId, String threadId) {
        CareChatThread thread = requireOwnedThread(tenantId, accountId, threadId);
        return toPatientSession(thread);
    }

    /**
     * C 端「联系健管师团队」：按当前就诊人解析主管/入组机构，获取或创建会话。
     *
     * @param preferredOrgId 可选；多机构时指定目标机构
     */
    @Transactional
    public CareChatSessionDto contactCareTeam(
            String tenantId, String accountId, String peopleId, String preferredOrgId) {
        if (!StringUtils.hasText(peopleId)) {
            throw new BusinessException(400, "请先选择就诊人");
        }
        AccountPatient card = accountPatientMapper.findByAccountAndPeople(accountId, peopleId);
        if (card == null) {
            throw new BusinessException(403, "无权访问该就诊人");
        }
        String orgId = resolveOrgForContact(tenantId, peopleId.trim(), preferredOrgId);
        CareChatThread thread = getOrCreateThread(tenantId, orgId, peopleId.trim());
        return toPatientSession(thread);
    }

    public List<CareChatMessageDto> listHistoryForPatient(
            String tenantId, String accountId, String threadId, String beforeId, int limit) {
        CareChatThread thread = requireOwnedThread(tenantId, accountId, threadId);
        return listHistory(thread.getId(), beforeId, limit);
    }

    public List<CareChatMessageDto> listAfterForPatient(
            String tenantId, String accountId, String threadId, String afterId, int limit) {
        CareChatThread thread = requireOwnedThread(tenantId, accountId, threadId);
        return listAfter(thread.getId(), afterId, limit);
    }

    @Transactional
    public CareChatMessageDto sendFromPatient(
            String tenantId, String accountId, String threadId, String content, String clientMsgId) {
        CareChatThread thread = requireOwnedThread(tenantId, accountId, threadId);
        assertOpen(thread);
        String text = normalizeContent(content);
        if (StringUtils.hasText(clientMsgId)) {
            CareChatMessage existing = messageMapper.findByClientMsgId(thread.getId(), clientMsgId.trim());
            if (existing != null) {
                return toMessageDto(existing);
            }
        }
        CareChatMessage msg = newMessage(tenantId, thread.getId(), CareChatSenderType.PATIENT, text, clientMsgId);
        msg.setSenderAccountId(accountId);
        EntityMeta.onCreate(msg);
        messageMapper.insert(msg);
        bumpThread(thread.getId(), msg, 1, 0);
        auditService.record(
                PortalEnum.C.code(),
                accountId,
                "PATIENT",
                tenantId,
                AuditActionEnum.CARE_CHAT_SEND.name(),
                "care_chat_message",
                msg.getId(),
                thread.getPeopleId(),
                AuditDetails.of(
                        "threadId",
                        thread.getId(),
                        "orgId",
                        thread.getOrgId(),
                        "senderType",
                        CareChatSenderType.PATIENT.name()));
        CareChatMessageDto dto = toMessageDto(msg);
        publishMessageEvent(tenantId, thread.getOrgId(), thread.getPeopleId(), thread.getId(), dto);
        return dto;
    }

    @Transactional
    public void markPatientRead(String tenantId, String accountId, String threadId) {
        CareChatThread thread = requireOwnedThread(tenantId, accountId, threadId);
        threadMapper.clearPatientUnread(thread.getId());
        publishReadEvent(tenantId, thread.getOrgId(), thread.getPeopleId(), thread.getId(), false);
    }

    public int patientUnreadTotal(String tenantId, String accountId) {
        return listThreadsForAccount(tenantId, accountId).stream()
                .mapToInt(CareChatThreadDto::getPatientUnreadCount)
                .sum();
    }

    private CareChatThread getOrCreateThread(String tenantId, String orgId, String peopleId) {
        CareChatThread existing = threadMapper.findByOrgPeople(tenantId, orgId, peopleId);
        if (existing != null) {
            return existing;
        }
        CareChatThread row = new CareChatThread();
        row.setTenantId(tenantId);
        row.setOrgId(orgId);
        row.setPeopleId(peopleId);
        row.setStaffUnreadCount(0);
        row.setPatientUnreadCount(0);
        EntityMeta.onCreate(row);
        try {
            threadMapper.insert(row);
            return row;
        } catch (DuplicateKeyException ex) {
            CareChatThread again = threadMapper.findByOrgPeople(tenantId, orgId, peopleId);
            if (again != null) {
                return again;
            }
            throw ex;
        }
    }

    private CareChatThread requireThread(String tenantId, String orgId, String peopleId) {
        CareChatThread thread = threadMapper.findByOrgPeople(tenantId, orgId, peopleId);
        if (thread == null) {
            throw new BusinessException(404, "沟通会话不存在");
        }
        return thread;
    }

    private CareChatThread requireOwnedThread(String tenantId, String accountId, String threadId) {
        if (!StringUtils.hasText(threadId)) {
            throw new BusinessException(400, "缺少会话 ID");
        }
        CareChatThread thread = threadMapper.findById(threadId.trim());
        if (thread == null || !Objects.equals(tenantId, thread.getTenantId())) {
            throw new BusinessException(404, "沟通会话不存在");
        }
        AccountPatient card = accountPatientMapper.findByAccountAndPeople(accountId, thread.getPeopleId());
        if (card == null) {
            throw new BusinessException(403, "无权访问该沟通会话");
        }
        return thread;
    }

    private CareChatSessionDto toPatientSession(CareChatThread thread) {
        CareChatSessionDto dto = new CareChatSessionDto();
        dto.setThread(toThreadDto(thread));
        dto.setMessages(loadRecentAsc(thread.getId(), DEFAULT_LIMIT));
        dto.setPatientLinked(true);
        dto.setCanSend(thread.getClosedAt() == null);
        if (thread.getClosedAt() != null) {
            dto.setBlockReason("会话已关闭，仅可查看历史");
        }
        return dto;
    }

    /** 优先指定机构 → 主管机构 → 首个 ACTIVE 入组机构。 */
    private String resolveOrgForContact(String tenantId, String peopleId, String preferredOrgId) {
        List<PatientOrgMembership> memberships = membershipMapper.listActiveByPeople(peopleId);
        List<PatientOrgMembership> inTenant = memberships.stream()
                .filter(m -> Objects.equals(tenantId, m.getTenantId()))
                .toList();
        if (inTenant.isEmpty()) {
            throw new BusinessException(400, "尚未加入健管机构，请先使用邀请码入组");
        }
        if (StringUtils.hasText(preferredOrgId)) {
            String want = preferredOrgId.trim();
            boolean ok = inTenant.stream().anyMatch(m -> Objects.equals(want, m.getOrgId()));
            if (!ok) {
                throw new BusinessException(403, "未加入该机构，无法联系");
            }
            return want;
        }
        PatientCareAssignment care = careAssignmentMapper.find(tenantId, peopleId);
        if (care != null && StringUtils.hasText(care.getPrimaryOrgId())) {
            String primary = care.getPrimaryOrgId().trim();
            boolean ok = inTenant.stream().anyMatch(m -> Objects.equals(primary, m.getOrgId()));
            if (ok) {
                return primary;
            }
        }
        return inTenant.get(0).getOrgId();
    }

    private static void assertOpen(CareChatThread thread) {
        if (thread.getClosedAt() != null) {
            throw new BusinessException(400, "会话已关闭，无法发送");
        }
    }

    private static String normalizeContent(String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(400, "消息内容不能为空");
        }
        String text = content.trim();
        if (text.length() > 2000) {
            throw new BusinessException(400, "消息内容不能超过 2000 字");
        }
        return text;
    }

    private CareChatMessage newMessage(
            String tenantId, String threadId, CareChatSenderType senderType, String text, String clientMsgId) {
        CareChatMessage msg = new CareChatMessage();
        msg.setTenantId(tenantId);
        msg.setThreadId(threadId);
        msg.setSenderType(senderType.name());
        msg.setContentType(CONTENT_TEXT);
        msg.setContent(text);
        if (StringUtils.hasText(clientMsgId)) {
            msg.setClientMsgId(clientMsgId.trim());
        }
        return msg;
    }

    private void bumpThread(String threadId, CareChatMessage msg, int incStaff, int incPatient) {
        String preview = msg.getContent();
        if (preview.length() > PREVIEW_LEN) {
            preview = preview.substring(0, PREVIEW_LEN) + "…";
        }
        threadMapper.updateAfterSend(
                threadId, msg.getGmtCreated(), preview, msg.getSenderType(), incStaff, incPatient);
    }

    private void publishMessageEvent(
            String tenantId, String orgId, String peopleId, String threadId, CareChatMessageDto message) {
        CareChatThread fresh = threadMapper.findById(threadId);
        CareChatRealtimeEvent event = baseRealtimeEvent(tenantId, orgId, peopleId, threadId, fresh);
        event.setType(CareChatRealtimeEvent.TYPE_MESSAGE);
        event.setMessage(message);
        publishRealtime(event);
    }

    private void publishReadEvent(
            String tenantId, String orgId, String peopleId, String threadId, boolean staffSide) {
        CareChatThread fresh = threadMapper.findById(threadId);
        CareChatRealtimeEvent event = baseRealtimeEvent(tenantId, orgId, peopleId, threadId, fresh);
        event.setType(CareChatRealtimeEvent.TYPE_READ);
        if (staffSide && fresh != null) {
            event.setStaffUnreadCount(0);
        }
        if (!staffSide && fresh != null) {
            event.setPatientUnreadCount(0);
        }
        publishRealtime(event);
    }

    private CareChatRealtimeEvent baseRealtimeEvent(
            String tenantId, String orgId, String peopleId, String threadId, CareChatThread thread) {
        CareChatRealtimeEvent event = new CareChatRealtimeEvent();
        event.setTenantId(tenantId);
        event.setOrgId(orgId);
        event.setPeopleId(peopleId);
        event.setThreadId(threadId);
        event.setStaffUnreadTotal(threadMapper.sumStaffUnreadByOrg(tenantId, orgId));
        if (thread != null) {
            event.setStaffUnreadCount(thread.getStaffUnreadCount() == null ? 0 : thread.getStaffUnreadCount());
            event.setPatientUnreadCount(
                    thread.getPatientUnreadCount() == null ? 0 : thread.getPatientUnreadCount());
        }
        event.setPatientAccountIds(listAccountIdsByPeople(peopleId));
        return event;
    }

    private List<String> listAccountIdsByPeople(String peopleId) {
        if (!StringUtils.hasText(peopleId)) {
            return List.of();
        }
        return accountPatientMapper.listByPeople(peopleId).stream()
                .map(AccountPatient::getAccountId)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private void publishRealtime(CareChatRealtimeEvent event) {
        CareChatRealtimePublisher publisher = realtimePublisher.getIfAvailable();
        if (publisher == null) {
            return;
        }
        try {
            publisher.publish(event);
        } catch (Exception e) {
            log.warn("CareChat realtime publish failed type={}", event.getType(), e);
        }
    }

    private List<CareChatMessageDto> loadRecentAsc(String threadId, int limit) {
        List<CareChatMessageDto> desc = listHistory(threadId, null, limit);
        Collections.reverse(desc);
        return desc;
    }

    private List<CareChatMessageDto> listHistory(String threadId, String beforeId, int limit) {
        int lim = clampLimit(limit);
        LocalDateTime beforeAt = null;
        String bid = null;
        if (StringUtils.hasText(beforeId)) {
            CareChatMessage anchor = messageMapper.findById(beforeId.trim());
            if (anchor != null && Objects.equals(anchor.getThreadId(), threadId)) {
                beforeAt = anchor.getGmtCreated();
                bid = anchor.getId();
            }
        }
        return messageMapper.listHistory(threadId, beforeAt, bid, lim).stream()
                .map(this::toMessageDto)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private List<CareChatMessageDto> listAfter(String threadId, String afterId, int limit) {
        int lim = clampLimit(limit);
        if (!StringUtils.hasText(afterId)) {
            return loadRecentAsc(threadId, lim);
        }
        CareChatMessage anchor = messageMapper.findById(afterId.trim());
        if (anchor == null || !Objects.equals(anchor.getThreadId(), threadId)) {
            return List.of();
        }
        return messageMapper.listAfter(threadId, anchor.getGmtCreated(), anchor.getId(), lim).stream()
                .map(this::toMessageDto)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(MAX_LIMIT, limit);
    }

    private CareChatThreadDto toThreadDto(CareChatThread t) {
        CareChatThreadDto dto = new CareChatThreadDto();
        dto.setId(t.getId());
        dto.setTenantId(t.getTenantId());
        dto.setOrgId(t.getOrgId());
        dto.setPeopleId(t.getPeopleId());
        dto.setLastMessageAt(t.getLastMessageAt());
        dto.setLastMessagePreview(t.getLastMessagePreview());
        dto.setLastSenderType(t.getLastSenderType());
        dto.setStaffUnreadCount(t.getStaffUnreadCount() == null ? 0 : t.getStaffUnreadCount());
        dto.setPatientUnreadCount(t.getPatientUnreadCount() == null ? 0 : t.getPatientUnreadCount());
        dto.setClosed(t.getClosedAt() != null);
        dto.setGmtCreated(t.getGmtCreated());
        PeopleProfile people = peopleProfileMapper.findById(t.getPeopleId());
        if (people != null) {
            dto.setPeopleName(people.getDisplayName());
        }
        Organization org = organizationMapper.findById(t.getOrgId());
        if (org != null) {
            dto.setOrgName(org.getName());
        }
        return dto;
    }

    private CareChatMessageDto toMessageDto(CareChatMessage m) {
        CareChatMessageDto dto = new CareChatMessageDto();
        dto.setId(m.getId());
        dto.setThreadId(m.getThreadId());
        dto.setSenderType(m.getSenderType());
        dto.setSenderStaffId(m.getSenderStaffId());
        dto.setSenderAccountId(m.getSenderAccountId());
        dto.setContentType(m.getContentType());
        dto.setContent(m.getRecalledAt() != null ? "" : m.getContent());
        dto.setClientMsgId(m.getClientMsgId());
        dto.setGmtCreated(m.getGmtCreated());
        dto.setRecalled(m.getRecalledAt() != null);
        if (CareChatSenderType.STAFF.name().equals(m.getSenderType()) && StringUtils.hasText(m.getSenderStaffId())) {
            StaffProfile staff = staffProfileMapper.findById(m.getSenderStaffId());
            if (staff != null) {
                dto.setSenderName(staff.getDisplayName());
            }
        } else if (CareChatSenderType.PATIENT.name().equals(m.getSenderType())) {
            CareChatThread thread = threadMapper.findById(m.getThreadId());
            if (thread != null) {
                PeopleProfile people = peopleProfileMapper.findById(thread.getPeopleId());
                if (people != null) {
                    dto.setSenderName(people.getDisplayName());
                }
            }
            if (StringUtils.hasText(m.getSenderAccountId()) && thread != null) {
                AccountPatient card =
                        accountPatientMapper.findByAccountAndPeople(m.getSenderAccountId(), thread.getPeopleId());
                if (card != null
                        && StringUtils.hasText(card.getRelation())
                        && !"SELF".equalsIgnoreCase(card.getRelation())) {
                    String who = StringUtils.hasText(card.getDisplayName()) ? card.getDisplayName() : "家属";
                    dto.setSenderName(who + "（代发）");
                }
            }
        }
        return dto;
    }
}
