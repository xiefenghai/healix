package com.healix.core.assessment.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.assessment.catalog.AssessmentCategory;
import com.healix.core.assessment.catalog.AssessmentStatus;
import com.healix.core.assessment.catalog.AssessmentTier;
import com.healix.core.assessment.catalog.AssessmentTriggerSource;
import com.healix.core.assessment.domain.PeopleAssessmentLatest;
import com.healix.core.assessment.domain.PeopleAssessmentSnapshot;
import com.healix.core.assessment.engine.AssessmentEngine;
import com.healix.core.assessment.engine.AssessmentEngine.LevelDef;
import com.healix.core.assessment.mapper.AssessmentStoreMapper;
import com.healix.core.assessment.mapper.PeopleAssessmentSnapshotMapper;
import com.healix.core.assessment.support.AssessmentContext;
import com.healix.core.assessment.support.AssessmentContextBuilder;
import com.healix.core.assessment.support.AssessmentResult;
import com.healix.core.worktask.service.WorkspaceTaskGenerator;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

/**
 * 评估重算：逐人跑全部适用引擎，维护最新结果表。
 *
 * <ul>
 *   <li>自动重算结果不变（指纹相同）只刷新「最近计算时间」，不新增快照
 *   <li>不再适用的引擎（如确诊后的风险引擎）在最新表置为失效
 *   <li>统一等级升到高 / 很高、风险引擎疑似已患病 → 工作台任务
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentRecomputeService {

    private final AssessmentEngineRegistry registry;
    private final AssessmentContextBuilder contextBuilder;
    private final PeopleAssessmentSnapshotMapper snapshotMapper;
    private final AssessmentStoreMapper storeMapper;
    private final WorkspaceTaskGenerator taskGenerator;

    public record Change(
            AssessmentEngine engine, PeopleAssessmentSnapshot snapshot, AssessmentResult result, AssessmentTier previousTier) {}

    public record Outcome(AssessmentContext context, List<Change> changes, List<PeopleAssessmentSnapshot> written) {}

    /**
     * @param staffId 手动 / B 端问卷时的操作人
     * @param quiet 为 true 时不触发闭环任务（上线首次全量回填）
     */
    @Transactional
    public Outcome recompute(
            String tenantId, String peopleId, AssessmentTriggerSource source, String staffId, boolean quiet) {
        LocalDateTime startedAt = LocalDateTime.now();
        AssessmentContext ctx = contextBuilder.build(tenantId, peopleId);
        Map<String, PeopleAssessmentLatest> previous = storeMapper.listLatest(tenantId, peopleId).stream()
                .collect(Collectors.toMap(PeopleAssessmentLatest::getEngineCode, Function.identity(), (a, b) -> a));

        List<Change> changes = new ArrayList<>();
        List<PeopleAssessmentSnapshot> written = new ArrayList<>();
        for (AssessmentEngine engine : registry.all()) {
            PeopleAssessmentLatest prev = previous.get(engine.code());
            if (!engine.applicable(ctx)) {
                if (prev != null && prev.effective()) {
                    storeMapper.deactivateLatest(peopleId, engine.code(), startedAt);
                }
                if (engine.category() == AssessmentCategory.RISK) {
                    closeSuspicionQuietly(tenantId, peopleId, engine.diseaseCode());
                }
                continue;
            }
            AssessmentResult result = engine.evaluate(ctx);
            String fingerprint = fingerprint(engine, result);
            boolean unchanged = prev != null && prev.effective() && fingerprint.equals(prev.getFingerprint());
            if (unchanged && source == AssessmentTriggerSource.AUTO) {
                storeMapper.touchLatest(peopleId, engine.code(), startedAt);
                continue;
            }
            PeopleAssessmentSnapshot row = toSnapshot(tenantId, peopleId, engine, result, ctx, fingerprint, source, staffId, startedAt);
            snapshotMapper.insert(row);
            storeMapper.upsertLatest(toLatest(engine, row, result, startedAt));
            written.add(row);
            AssessmentTier prevTier = prev != null && prev.effective() ? AssessmentTier.parse(prev.getTier()) : null;
            Change change = new Change(engine, row, result, prevTier);
            changes.add(change);
            if (!quiet && !unchanged) {
                closedLoop(tenantId, peopleId, change);
            }
        }
        storeMapper.markEvaluated(tenantId, peopleId, startedAt);
        return new Outcome(ctx, changes, written);
    }

    private void closedLoop(String tenantId, String peopleId, Change change) {
        AssessmentEngine engine = change.engine();
        AssessmentResult result = change.result();
        try {
            AssessmentTier tier = result.getTier();
            if (result.getStatus() == AssessmentStatus.COMPLETE && tier != null && tier.atLeastHigh()) {
                AssessmentTier prev = change.previousTier();
                if (prev == null || tier.rank() > prev.rank()) {
                    LevelDef def = engine.levelDef(result.getLevel());
                    String label = def == null ? result.getLevel() : def.label();
                    String summary = engine.label() + "：" + (prev == null ? "" : prev.label() + " → ") + label;
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("engineCode", engine.code());
                    payload.put("snapshotId", change.snapshot().getId());
                    payload.put("tier", tier.name());
                    payload.put("previousTier", prev == null ? null : prev.name());
                    payload.put("level", result.getLevel());
                    payload.put("advice", result.getAdvice());
                    taskGenerator.onAssessmentEscalation(tenantId, peopleId, engine.code(), tier.name(), summary, payload);
                }
            }
            if (engine.category() == AssessmentCategory.RISK) {
                if (result.getSuspicion() != null) {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("engineCode", engine.code());
                    payload.put("diseaseCode", result.getSuspicion().diseaseCode());
                    payload.put("snapshotId", change.snapshot().getId());
                    payload.put("reason", result.getSuspicion().reason());
                    taskGenerator.onAssessmentSuspected(
                            tenantId, peopleId, result.getSuspicion().diseaseCode(), result.getSuspicion().reason(), payload);
                } else {
                    taskGenerator.closeAssessmentSuspected(tenantId, peopleId, engine.diseaseCode());
                }
            }
        } catch (RuntimeException e) {
            log.warn("assessment closed loop failed people={} engine={}: {}", peopleId, engine.code(), e.getMessage());
        }
    }

    private void closeSuspicionQuietly(String tenantId, String peopleId, String diseaseCode) {
        try {
            taskGenerator.closeAssessmentSuspected(tenantId, peopleId, diseaseCode);
        } catch (RuntimeException e) {
            log.debug("close suspicion task failed people={}: {}", peopleId, e.getMessage());
        }
    }

    static String fingerprint(AssessmentEngine engine, AssessmentResult result) {
        String src = engine.rulePackVersion() + "|" + result.fingerprintSource();
        return DigestUtils.md5DigestAsHex(src.getBytes(StandardCharsets.UTF_8));
    }

    private static PeopleAssessmentSnapshot toSnapshot(
            String tenantId,
            String peopleId,
            AssessmentEngine engine,
            AssessmentResult result,
            AssessmentContext ctx,
            String fingerprint,
            AssessmentTriggerSource source,
            String staffId,
            LocalDateTime at) {
        PeopleAssessmentSnapshot row = new PeopleAssessmentSnapshot();
        row.setTenantId(tenantId);
        row.setPeopleId(peopleId);
        row.setKind(engine.category().name());
        row.setEngineCode(engine.code());
        row.setDiseaseCode(engine.diseaseCode());
        row.setRulePackVersion(engine.rulePackVersion());
        row.setStatus(result.getStatus().name());
        row.setLevel(result.getLevel());
        row.setTier(result.getTier() == null ? null : result.getTier().name());
        row.setScore(result.getScore());
        row.setResultJson(JsonUtils.toJson(result.toResultJson()));
        row.setInputSnapshotJson(JsonUtils.toJson(ctx.snapshotOf(engine.inputs())));
        row.setFingerprint(fingerprint);
        row.setTriggerSource(source.name());
        row.setAssessedAt(at);
        row.setAssessedByStaffId(staffId);
        EntityMeta.onCreate(row);
        return row;
    }

    private static PeopleAssessmentLatest toLatest(
            AssessmentEngine engine, PeopleAssessmentSnapshot row, AssessmentResult result, LocalDateTime at) {
        PeopleAssessmentLatest l = new PeopleAssessmentLatest();
        l.setTenantId(row.getTenantId());
        l.setPeopleId(row.getPeopleId());
        l.setEngineCode(engine.code());
        l.setCategory(engine.category().name());
        l.setDiseaseCode(engine.diseaseCode());
        l.setSnapshotId(row.getId());
        l.setStatus(row.getStatus());
        l.setLevel(row.getLevel());
        LevelDef def = engine.levelDef(result.getLevel());
        l.setLevelLabel(def == null ? null : def.label());
        l.setTier(row.getTier());
        l.setScore(row.getScore());
        l.setActive(1);
        l.setFingerprint(row.getFingerprint());
        l.setAssessedAt(at);
        l.setEvaluatedAt(at);
        return l;
    }
}
