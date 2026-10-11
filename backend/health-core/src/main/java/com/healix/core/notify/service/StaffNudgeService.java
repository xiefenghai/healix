package com.healix.core.notify.service;

import com.healix.core.adherence.service.AdherenceQueryService;
import com.healix.core.adherence.service.AdherenceQueryService.DailyHealthTodoCandidate;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.notify.catalog.NotifyEventType;
import com.healix.core.notify.dto.NotifyPublishCommand;
import com.healix.core.notify.dto.NotifyPublishResult;
import com.healix.core.notify.enums.NotifyAudience;
import com.healix.core.notify.enums.NotifyDuplicatePolicy;
import com.healix.core.people.domain.PeopleProfile;
import com.healix.core.people.mapper.PeopleProfileMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * B 一键提醒患者：发 {@link NotifyEventType#STAFF_NUDGE} 站内信。
 *
 * <p>与日提醒 Job 口径一致（方案打卡 / 用药未完成），但由健管师主动触发；
 * 同一员工对同一患者同日只保留一条（upsert 保留已读）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffNudgeService {

    private final AdherenceQueryService adherenceQueryService;
    private final PeopleProfileMapper peopleProfileMapper;
    private final NotifyRecipientResolver recipientResolver;
    private final NotifyFacade notifyFacade;

    /**
     * @param sent 是否真的发出（false 见 reason）
     * @param reason NO_LINKED_ACCOUNT / NOTHING_PENDING / OK
     */
    public record NudgeResult(boolean sent, String reason, String message, int recipientCount, String linkPath) {}

    public NudgeResult nudgePatient(String tenantId, String orgId, String peopleId, String staffId, LocalDate day) {
        LocalDate today = day != null ? day : LocalDate.now(JobCronSupport.ZONE);

        int recipientCount = recipientResolver.distinctAccountIds(peopleId).size();
        if (recipientCount == 0) {
            return new NudgeResult(
                    false, "NO_LINKED_ACCOUNT", "该患者尚未绑定 C 端账号，无法发送站内提醒", 0, null);
        }

        String name = resolveName(peopleId);
        List<DailyHealthTodoCandidate> todos = adherenceQueryService.listDailyHealthTodos(
                tenantId, List.of(peopleId), name == null ? Map.of() : Map.of(peopleId, name), today);
        if (todos.isEmpty()) {
            return new NudgeResult(
                    false, "NOTHING_PENDING", "该患者当日方案与用药均无未完成项，无需提醒", recipientCount, null);
        }

        DailyHealthTodoCandidate todo = todos.get(0);
        String linkPath = resolveLinkPath(todo);
        String body = buildBody(name, todo);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("day", today.toString());
        payload.put("peopleId", peopleId);
        payload.put("staffId", staffId);
        payload.put("planIncompleteFlag", todo.planIncompleteFlag());
        payload.put("medIncompleteFlag", todo.medIncompleteFlag());

        try {
            NotifyPublishResult result = notifyFacade.publish(NotifyPublishCommand.builder()
                    .tenantId(tenantId)
                    .eventType(NotifyEventType.STAFF_NUDGE)
                    .dedupeKey(peopleId + ":" + (StringUtils.hasText(staffId) ? staffId : "STAFF") + ":" + today)
                    .audience(NotifyAudience.C_ACCOUNT)
                    .peopleId(peopleId)
                    .orgId(orgId)
                    .title("健管师提醒")
                    .body(body)
                    .linkPath(linkPath)
                    .payload(payload)
                    .onDuplicate(NotifyDuplicatePolicy.UPSERT_BODY_KEEP_READ)
                    .build());
            if (result.getMessageIds().isEmpty()) {
                return new NudgeResult(
                        false, "NO_LINKED_ACCOUNT", "该患者尚未绑定 C 端账号，无法发送站内提醒", 0, linkPath);
            }
            return new NudgeResult(
                    true, "OK", "已向患者发送站内提醒：" + body, result.getMessageIds().size(), linkPath);
        } catch (Exception ex) {
            log.warn("STAFF_NUDGE notify failed tenant={} people={}: {}", tenantId, peopleId, ex.getMessage());
            return new NudgeResult(false, "FAILED", "提醒发送失败，请稍后重试", recipientCount, linkPath);
        }
    }

    /** 方案未完成优先进方案页；仅用药未完成进用药页。 */
    private static String resolveLinkPath(DailyHealthTodoCandidate todo) {
        if (todo.planIncompleteFlag()) {
            return "/care-plan";
        }
        if (todo.medIncompleteFlag()) {
            return "/medications";
        }
        return "/home";
    }

    static String buildBody(String name, DailyHealthTodoCandidate todo) {
        String who = StringUtils.hasText(name) ? name : "您";
        StringBuilder sb = new StringBuilder();
        if (todo.planIncompleteFlag() && todo.planIncomplete() > 0) {
            sb.append("方案还有 ").append(todo.planIncomplete()).append(" 项待打卡");
        } else if (todo.planIncompleteFlag()) {
            sb.append("方案打卡待完成");
        }
        if (todo.medIncompleteFlag()) {
            if (sb.length() > 0) {
                sb.append("，");
            }
            int pending = Math.max(0, todo.medDueDoses() - todo.medTakenDoses());
            sb.append(pending > 0 ? "用药还有 " + pending + " 次未打卡" : "用药待打卡");
        }
        if (sb.length() == 0) {
            sb.append("今日健康待办待完成");
        }
        return "健管师提醒：" + who + "今日" + sb + "，请尽快完成";
    }

    private String resolveName(String peopleId) {
        PeopleProfile profile = peopleProfileMapper.findById(peopleId);
        return profile != null && StringUtils.hasText(profile.getDisplayName())
                ? profile.getDisplayName().trim()
                : null;
    }
}
