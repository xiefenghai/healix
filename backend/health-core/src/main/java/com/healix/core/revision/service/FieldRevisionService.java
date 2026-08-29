package com.healix.core.revision.service;

import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.revision.domain.FieldRevisionBatch;
import com.healix.core.revision.domain.FieldRevisionItem;
import com.healix.core.revision.mapper.FieldRevisionBatchMapper;
import com.healix.core.revision.mapper.FieldRevisionItemMapper;
import com.healix.core.revision.support.FieldDiffUtils;
import com.healix.core.revision.support.FieldDiffUtils.FieldChange;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FieldRevisionService {

    private final FieldRevisionBatchMapper batchMapper;
    private final FieldRevisionItemMapper itemMapper;

    public void recordIfChanged(
            String tenantId,
            String targetPeopleId,
            String operatorType,
            String operatorId,
            String bizType,
            String bizKey,
            int versionBefore,
            int versionAfter,
            String orgId,
            String oldContentJson,
            String newContentJson) {
        List<FieldChange> changes = FieldDiffUtils.diffJson(oldContentJson, newContentJson);
        if (changes.isEmpty()) {
            return;
        }
        FieldRevisionBatch batch = new FieldRevisionBatch();
        batch.setTenantId(tenantId);
        batch.setTargetPeopleId(targetPeopleId);
        batch.setOperatorType(operatorType);
        batch.setOperatorId(operatorId);
        batch.setBizType(bizType);
        batch.setBizKey(bizKey);
        batch.setVersionBefore(versionBefore);
        batch.setVersionAfter(versionAfter);
        batch.setOrgId(orgId);
        EntityMeta.onCreate(batch);
        batchMapper.insert(batch);

        for (FieldChange change : changes) {
            FieldRevisionItem item = new FieldRevisionItem();
            item.setBatchId(batch.getId());
            item.setFieldPath(change.fieldPath());
            item.setOldValue(nodeToStoredJson(change.oldValue()));
            item.setNewValue(nodeToStoredJson(change.newValue()));
            EntityMeta.onCreate(item);
            itemMapper.insert(item);
        }
    }

    private static String nodeToStoredJson(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return JsonUtils.toJson(node);
    }
}
