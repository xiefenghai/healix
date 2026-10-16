package com.healix.core.assessment.service;

import com.healix.core.assessment.catalog.AssessmentTriggerSource;
import com.healix.core.assessment.domain.AssessmentQuestionnaireRequest;
import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.mapper.AssessmentStoreMapper.PeopleRef;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 评估后台任务：脏标记增量重算、每日全量、问卷请求提醒与过期。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentJobService {

    private static final long REMIND_AFTER_HOURS = 48;

    private final AssessmentStoreMapper storeMapper;
    private final AssessmentRecomputeService recomputeService;
    private final AssessmentQuestionnaireService questionnaireService;
    private final WorkspaceTaskGenerator taskGenerator;

    public record Counts(int processed, int failed) {}

    /** 处理有输入变更的患者；单人失败也清掉脏标记，避免反复卡在队首。 */
    public Counts recomputeDirty(int batchSize, BooleanSupplier timedOut) {
        int processed = 0;
        int failed = 0;
        List<PeopleRef> dirty = storeMapper.listDirty(Math.max(1, batchSize));
        for (PeopleRef ref : dirty) {
            if (timedOut.getAsBoolean()) {
                break;
            }
            try {
                recomputeService.recompute(ref.tenantId(), ref.peopleId(), AssessmentTriggerSource.AUTO, null, false);
                processed++;
            } catch (RuntimeException e) {
                failed++;
                log.warn("assessment recompute failed people={}", ref.peopleId(), e);
                storeMapper.markEvaluated(ref.tenantId(), ref.peopleId(), LocalDateTime.now());
            }
        }
        return new Counts(processed, failed);
    }

    /** 全量重算（按 people_id 分页）。quiet=true 时不触发闭环任务，用于上线首次回填。 */
    public Counts sweepAll(int batchSize, boolean quiet, BooleanSupplier timedOut) {
        int processed = 0;
        int failed = 0;
        String after = "";
        int size = Math.max(1, batchSize);
        while (!timedOut.getAsBoolean()) {
            List<PeopleRef> page = storeMapper.listSweepCandidates(after, size);
            if (page.isEmpty()) {
                break;
            }
            for (PeopleRef ref : page) {
                if (timedOut.getAsBoolean()) {
                    break;
                }
                try {
                    recomputeService.recompute(ref.tenantId(), ref.peopleId(), AssessmentTriggerSource.AUTO, null, quiet);
                    processed++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("assessment sweep failed people={}", ref.peopleId(), e);
                }
            }
            after = page.get(page.size() - 1).peopleId();
            if (page.size() < size) {
                break;
            }
        }
        return new Counts(processed, failed);
    }

    /** 48 小时未填提醒一次；过期置 EXPIRED 并给发起健管师开任务。 */
    public Map<String, Integer> handleRequests(LocalDateTime now) {
        int reminded = 0;
        int expired = 0;
        for (AssessmentQuestionnaireRequest req : storeMapper.listExpiredRequests(now)) {
            try {
                if (storeMapper.markRequestExpired(req.getId()) > 0) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("requestId", req.getId());
                    payload.put("staffId", req.getStaffId());
                    payload.put("sentAt", req.getGmtCreated() == null ? null : req.getGmtCreated().toString());
                    taskGenerator.onQuestionnaireExpired(
                            req.getTenantId(), req.getOrgId(), req.getPeopleId(), req.getId(),
                            "评估问卷发出 7 天未填写，建议电话随访补充", payload);
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("questionnaire expire failed request={}: {}", req.getId(), e.getMessage());
            }
        }
        for (AssessmentQuestionnaireRequest req : storeMapper.listRequestsToRemind(now.minusHours(REMIND_AFTER_HOURS))) {
            try {
                questionnaireService.publishRequest(req, true);
                storeMapper.markRequestReminded(req.getId(), now);
                reminded++;
            } catch (RuntimeException e) {
                log.warn("questionnaire remind failed request={}: {}", req.getId(), e.getMessage());
            }
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("reminded", reminded);
        out.put("expired", expired);
        return out;
    }

    public boolean needsBootstrap() {
        return storeMapper.countLatest() == 0;
    }

    public static BooleanSupplier deadline(Instant until) {
        return () -> until != null && Instant.now().isAfter(until);
    }
}
