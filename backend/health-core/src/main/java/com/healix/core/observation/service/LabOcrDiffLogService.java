package com.healix.core.observation.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.observation.domain.LabOcrDiffLog;
import com.healix.core.observation.mapper.LabOcrDiffLogMapper;
import com.healix.core.observation.service.LabReportService.OcrDiffItemPayload;
import com.healix.core.observation.service.LabReportService.OcrDiffLogPayload;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 落库 OCR 纠错 Diff，供后续「未修改率 / AI 准确率」统计。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabOcrDiffLogService {

    private final LabOcrDiffLogMapper labOcrDiffLogMapper;

    public void recordQuietly(
            String tenantId,
            String orgId,
            String peopleId,
            String reportId,
            String staffId,
            String imageKey,
            OcrDiffLogPayload payload) {
        if (payload == null || payload.items() == null || payload.items().isEmpty()) {
            return;
        }
        try {
            List<OcrDiffItemPayload> items = payload.items();
            int total = items.size();
            int changed = 0;
            for (OcrDiffItemPayload item : items) {
                if (item != null && item.changed()) {
                    changed++;
                }
            }
            LabOcrDiffLog row = new LabOcrDiffLog();
            row.setTenantId(tenantId);
            row.setOrgId(orgId);
            row.setPeopleId(peopleId);
            row.setReportId(reportId);
            row.setStaffId(staffId);
            row.setImageKey(StringUtils.hasText(payload.imageKey()) ? payload.imageKey() : imageKey);
            row.setTotalItems(total);
            row.setUnchangedItems(Math.max(0, total - changed));
            row.setChangedItems(changed);
            row.setDiffJson(JsonUtils.toJson(payload));
            EntityMeta.onCreate(row);
            labOcrDiffLogMapper.insert(row);
            log.info(
                    "ocr diff logged reportId={} total={} unchanged={} changed={} rate={}",
                    reportId,
                    total,
                    total - changed,
                    changed,
                    total == 0 ? 0 : (double) (total - changed) / total);
        } catch (Exception e) {
            // 埋点失败不影响主流程入库
            log.warn("ocr diff log failed reportId={}: {}", reportId, e.toString());
        }
    }
}
