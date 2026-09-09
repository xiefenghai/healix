package com.healix.core.job.service;

import com.healix.common.context.RequestContext;
import com.healix.common.context.RequestContextHolder;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.result.PageResult;
import com.healix.core.identity.domain.OpsAccount;
import com.healix.core.identity.enums.OpsRoleEnum;
import com.healix.core.identity.mapper.OpsAccountMapper;
import com.healix.core.job.enums.JobTriggerType;
import com.healix.core.job.catalog.JobCatalog;
import com.healix.core.job.catalog.JobCatalogSpec;
import com.healix.core.job.domain.SysJobDef;
import com.healix.core.job.domain.SysJobRun;
import com.healix.core.job.dto.JobCatalogItemDto;
import com.healix.core.job.dto.JobDefViewDto;
import com.healix.core.job.dto.JobRunViewDto;
import com.healix.core.job.handler.JobHandlerRegistry;
import com.healix.core.job.mapper.SysJobDefMapper;
import com.healix.core.job.mapper.SysJobRunMapper;
import com.healix.core.job.support.JobCronSupport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class JobAdminService {

    private final JobCatalog jobCatalog;
    private final JobHandlerRegistry handlerRegistry;
    private final SysJobDefMapper jobDefMapper;
    private final SysJobRunMapper jobRunMapper;
    private final JobSchedulerService jobSchedulerService;
    private final OpsAccountMapper opsAccountMapper;

    public List<JobDefViewDto> list() {
        requireOpsReader();
        List<JobDefViewDto> out = new ArrayList<>();
        for (SysJobDef def : jobDefMapper.listAll()) {
            out.add(toView(def));
        }
        return out;
    }

    public List<JobCatalogItemDto> listCatalog() {
        requireOpsReader();
        List<JobCatalogItemDto> out = new ArrayList<>();
        for (JobCatalogSpec spec : jobCatalog.all()) {
            JobCatalogItemDto item = new JobCatalogItemDto();
            item.setJobCode(spec.jobCode());
            item.setDisplayName(spec.displayName());
            item.setDescription(spec.description());
            item.setDefaultCron(JobCronSupport.normalize(spec.defaultCron()));
            item.setAdded(jobDefMapper.findByJobCode(spec.jobCode()) != null);
            item.setHandlerReady(handlerRegistry.find(spec.jobCode()).isPresent());
            out.add(item);
        }
        return out;
    }

    @Transactional
    public JobDefViewDto create(String jobCode, Boolean enabled, String cronExpr) {
        requireSuperAdmin();
        JobCatalogSpec spec = jobCatalog
                .find(jobCode)
                .orElseThrow(() -> new BusinessException(404, "未知任务类型"));
        if (handlerRegistry.find(spec.jobCode()).isEmpty()) {
            throw new BusinessException("任务 Handler 未注册");
        }
        if (jobDefMapper.findByJobCode(spec.jobCode()) != null) {
            throw new BusinessException("任务已存在");
        }
        String cron = StringUtils.hasText(cronExpr) ? cronExpr : spec.defaultCron();
        String normalized = JobCronSupport.normalize(cron);
        JobCronSupport.assertMinInterval(normalized);

        SysJobDef row = new SysJobDef();
        row.setJobCode(spec.jobCode());
        row.setDisplayName(spec.displayName());
        row.setCronExpr(normalized);
        row.setTimezone(JobCronSupport.ZONE.getId());
        row.setEnabled(enabled == null || Boolean.TRUE.equals(enabled) ? 1 : 0);
        row.setParamsJson(spec.defaultParamsJson());
        row.setNextFireAt(JobCronSupport.nextFutureFire(normalized));
        EntityMeta.onCreate(row);
        jobDefMapper.insert(row);
        return toView(jobDefMapper.findByJobCode(spec.jobCode()));
    }

    @Transactional
    public JobDefViewDto update(String jobCode, Boolean enabled, String cronExpr) {
        requireSuperAdmin();
        if (!jobCatalog.contains(jobCode)) {
            throw new BusinessException(404, "未知任务");
        }
        SysJobDef def = requireDef(jobCode);
        if (StringUtils.hasText(cronExpr)) {
            String normalized = JobCronSupport.normalize(cronExpr);
            JobCronSupport.assertMinInterval(normalized);
            def.setCronExpr(normalized);
            def.setNextFireAt(JobCronSupport.nextFutureFire(normalized));
        }
        if (enabled != null) {
            def.setEnabled(Boolean.TRUE.equals(enabled) ? 1 : 0);
        }
        EntityMeta.onUpdate(def);
        jobDefMapper.updateConfig(def);
        return toView(jobDefMapper.findByJobCode(jobCode));
    }

    public JobRunViewDto runNow(String jobCode) {
        requireSuperAdmin();
        if (!jobCatalog.contains(jobCode)) {
            throw new BusinessException(404, "未知任务");
        }
        SysJobDef def = requireDef(jobCode);
        String accountId = RequestContextHolder.get() == null ? null : RequestContextHolder.get().getAccountId();
        SysJobRun run = jobSchedulerService.triggerManual(def, accountId);
        return toRunView(run, resolveOperatorNames(List.of(run)));
    }

    public PageResult<JobRunViewDto> listRuns(String jobCode, int page, int pageSize) {
        requireOpsReader();
        if (!jobCatalog.contains(jobCode)) {
            throw new BusinessException(404, "未知任务");
        }
        int p = Math.max(page, 1);
        int size = Math.min(Math.max(pageSize, 1), 100);
        int offset = (p - 1) * size;
        long total = jobRunMapper.countByJobCode(jobCode);
        List<SysJobRun> rows = jobRunMapper.listByJobCode(jobCode, offset, size);
        Map<String, String> names = resolveOperatorNames(rows);
        List<JobRunViewDto> items = rows.stream().map(row -> toRunView(row, names)).toList();
        return new PageResult<>(total, items);
    }

    private SysJobDef requireDef(String jobCode) {
        SysJobDef def = jobDefMapper.findByJobCode(jobCode);
        if (def == null) {
            throw new BusinessException(404, "任务不存在");
        }
        return def;
    }

    private JobDefViewDto toView(SysJobDef def) {
        JobDefViewDto dto = new JobDefViewDto();
        dto.setId(def.getId());
        dto.setJobCode(def.getJobCode());
        dto.setDisplayName(def.getDisplayName());
        dto.setCronExpr(def.getCronExpr());
        dto.setTimezone(def.getTimezone());
        dto.setEnabled(def.getEnabled() != null && def.getEnabled() == 1);
        dto.setNextFireAt(def.getNextFireAt());
        dto.setLastFireAt(def.getLastFireAt());
        dto.setLastStatus(def.getLastStatus());
        dto.setLastMessage(def.getLastMessage());
        jobCatalog.find(def.getJobCode()).ifPresent(spec -> {
            dto.setDescription(spec.description());
            if (!StringUtils.hasText(dto.getDisplayName())) {
                dto.setDisplayName(spec.displayName());
            }
        });
        return dto;
    }

    private JobRunViewDto toRunView(SysJobRun row, Map<String, String> operatorNames) {
        JobRunViewDto dto = new JobRunViewDto();
        dto.setId(row.getId());
        dto.setJobCode(row.getJobCode());
        dto.setTriggerType(row.getTriggerType());
        dto.setStatus(row.getStatus());
        dto.setStartedAt(row.getStartedAt());
        dto.setFinishedAt(row.getFinishedAt());
        dto.setMessage(row.getMessage());
        dto.setDetailJson(row.getDetailJson());
        dto.setInstanceId(row.getInstanceId());
        dto.setTriggeredBy(resolveTriggeredByLabel(row, operatorNames));
        return dto;
    }

    private Map<String, String> resolveOperatorNames(List<SysJobRun> rows) {
        List<String> ids = rows.stream()
                .map(SysJobRun::getTriggeredBy)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (OpsAccount account : opsAccountMapper.listByIds(ids)) {
            if (StringUtils.hasText(account.getDisplayName())) {
                names.put(account.getId(), account.getDisplayName());
            } else if (StringUtils.hasText(account.getUsername())) {
                names.put(account.getId(), account.getUsername());
            }
        }
        return names;
    }

    private static String resolveTriggeredByLabel(SysJobRun row, Map<String, String> operatorNames) {
        if (JobTriggerType.SCHEDULE.name().equals(row.getTriggerType()) || !StringUtils.hasText(row.getTriggeredBy())) {
            return "系统";
        }
        String name = operatorNames.get(row.getTriggeredBy());
        return StringUtils.hasText(name) ? name : row.getTriggeredBy();
    }

    private static void requireSuperAdmin() {
        if (!currentRoles().contains(OpsRoleEnum.SUPER_ADMIN.name())) {
            throw new BusinessException(403, "需要超级管理员权限");
        }
    }

    private static void requireOpsReader() {
        Set<String> roles = currentRoles();
        if (!roles.contains(OpsRoleEnum.SUPER_ADMIN.name()) && !roles.contains(OpsRoleEnum.OPERATOR.name())) {
            throw new BusinessException(403, "无权查看运营数据");
        }
    }

    private static Set<String> currentRoles() {
        RequestContext ctx = RequestContextHolder.get();
        if (ctx == null || ctx.getRoles() == null) {
            return Set.of();
        }
        return ctx.getRoles();
    }
}
