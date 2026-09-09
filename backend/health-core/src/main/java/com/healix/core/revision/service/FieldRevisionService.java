package com.healix.core.revision.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.util.JsonUtils;
import com.healix.core.revision.domain.FieldRevisionBatch;
import com.healix.core.revision.domain.FieldRevisionItem;
import com.healix.core.revision.mapper.FieldRevisionBatchMapper;
import com.healix.core.revision.mapper.FieldRevisionItemMapper;
import com.healix.core.revision.support.FieldDiffUtils;
import com.healix.core.revision.support.FieldDiffUtils.FieldChange;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FieldRevisionService {

    /** 档案首次创建的修订标记字段（前端展示为「创建档案」）。 */
    public static final String CREATED_FIELD_PATH = "_created";

    /** 删除操作标记字段（前端展示为「删除…」）。 */
    public static final String DELETED_FIELD_PATH = "_deleted";

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
        List<FieldChange> changes = new ArrayList<>(FieldDiffUtils.diffJson(oldContentJson, newContentJson));
        boolean isCreate = versionBefore == 0;
        if (isCreate) {
            // 首次创建即使内容为空也要留下操作记录
            changes.add(0, new FieldChange(CREATED_FIELD_PATH, null, BooleanNode.TRUE));
        } else if (changes.isEmpty()) {
            return;
        }
        persistBatch(
                tenantId,
                targetPeopleId,
                operatorType,
                operatorId,
                bizType,
                bizKey,
                versionBefore,
                versionAfter,
                orgId,
                changes);
    }

    /**
     * 记录删除操作：写入 {@code _deleted} 标记，并保留快照中的关键字段（如药品名称）便于展示。
     */
    public void recordDeleted(
            String tenantId,
            String targetPeopleId,
            String operatorType,
            String operatorId,
            String bizType,
            String bizKey,
            String orgId,
            String snapshotJson) {
        List<FieldChange> changes = new ArrayList<>();
        changes.add(new FieldChange(DELETED_FIELD_PATH, null, BooleanNode.TRUE));
        JsonNode snap = JsonUtils.readTree(snapshotJson == null ? "{}" : snapshotJson);
        if (snap.has("drugName") && !snap.get("drugName").isNull()) {
            changes.add(new FieldChange("drugName", snap.get("drugName"), null));
        }
        if (snap.has("metricType") && !snap.get("metricType").isNull()) {
            changes.add(new FieldChange("metricType", snap.get("metricType"), null));
        }
        if (snap.has("examType") && !snap.get("examType").isNull()) {
            changes.add(new FieldChange("examType", snap.get("examType"), null));
        }
        if (snap.has("specimenType") && !snap.get("specimenType").isNull()) {
            changes.add(new FieldChange("specimenType", snap.get("specimenType"), null));
        }
        persistBatch(
                tenantId,
                targetPeopleId,
                operatorType,
                operatorId,
                bizType,
                bizKey,
                1,
                0,
                orgId,
                changes);
    }

    private void persistBatch(
            String tenantId,
            String targetPeopleId,
            String operatorType,
            String operatorId,
            String bizType,
            String bizKey,
            int versionBefore,
            int versionAfter,
            String orgId,
            List<FieldChange> changes) {
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

    private static String nodeToStoredJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return JsonUtils.toJson(node);
    }
}
