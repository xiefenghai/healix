package com.healix.core.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.common.domain.EntityMeta;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.domain.PeopleDiseaseArchive;
import com.healix.core.archive.mapper.PeopleDiseaseArchiveMapper;
import com.healix.core.assessment.service.AssessmentDirtyMarker;
import com.healix.core.metadata.domain.PeopleMetadataInfo;
import com.healix.core.metadata.mapper.PeopleMetadataInfoMapper;
import com.healix.core.patient.enums.PatientArchiveSourceEnum;
import com.healix.core.people.domain.PeopleBasicArchive;
import com.healix.core.people.mapper.PeopleBasicArchiveMapper;
import com.healix.core.revision.enums.RevisionBizTypeEnum;
import com.healix.core.revision.service.FieldRevisionService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 字段统一读写：元数据为准，档案 {@code content_json} 由元数据重建。
 *
 * <p>档案页 / C 端整页提交走 {@link #replaceBasicArchive} / {@link #replaceDiseaseArchive}（legacy 作用域，
 * 不会清掉新字段）；问卷提交走 {@link #write}（只改传入的编码）。
 */
@Service
@RequiredArgsConstructor
public class PeopleFieldService {

    /** 已按元数据重建兼容视图的档案 schema。 */
    public static final String SCHEMA_V2 = "2.0";

    private final FieldCatalog catalog;
    private final PeopleMetadataInfoMapper metadataMapper;
    private final PeopleBasicArchiveMapper basicArchiveMapper;
    private final PeopleDiseaseArchiveMapper diseaseArchiveMapper;
    private final FieldRevisionService fieldRevisionService;
    private final AssessmentDirtyMarker assessmentDirtyMarker;

    /** 读取单个字段当前值（含来源）；虚拟字段即时推导。 */
    public Optional<FieldValue> read(String tenantId, String peopleId, String code) {
        ensureMigrated(tenantId, peopleId);
        if (FamilyHistoryFields.VIRTUAL.equals(code)) {
            Map<String, JsonNode> basic = loadValues(tenantId, peopleId, FieldStore.BASIC.prefix());
            JsonNode v = FamilyHistoryFields.read(basic);
            if (v == null) {
                return Optional.empty();
            }
            return Optional.of(new FieldValue(code, v, null, null, null, null, null, null));
        }
        PeopleMetadataInfo row = metadataMapper.findByPeopleAndCode(peopleId, code);
        if (row == null) {
            return Optional.empty();
        }
        return Optional.of(toFieldValue(row));
    }

    /** 按前缀批量读取（不含虚拟字段）。 */
    public Map<String, FieldValue> readPrefix(String tenantId, String peopleId, String prefix) {
        ensureMigrated(tenantId, peopleId);
        Map<String, FieldValue> out = new LinkedHashMap<>();
        for (PeopleMetadataInfo row : metadataMapper.listByPrefix(tenantId, peopleId, prefix)) {
            out.put(row.getMetadataCode(), toFieldValue(row));
        }
        return out;
    }

    /**
     * 按编码 diff 写入：{@code null}/空 = 清空；未出现的编码不动。
     *
     * <p>只处理元数据类前缀（{@code basic.*} / {@code disease.*}）；虚拟字段先展开。
     */
    @Transactional
    public void write(
            String tenantId, String peopleId, Map<String, JsonNode> values, FieldWriteSource source) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ensureMigrated(tenantId, peopleId);
        Map<String, JsonNode> expanded = expandVirtual(tenantId, peopleId, values);
        Map<String, Map<String, JsonNode>> byGroup = groupByArchive(expanded);
        boolean dirty = false;
        for (Map.Entry<String, Map<String, JsonNode>> g : byGroup.entrySet()) {
            if (applyGroupDiff(tenantId, peopleId, g.getKey(), g.getValue(), source, false)) {
                rebuildCompatView(tenantId, peopleId, g.getKey(), source);
                dirty = true;
            }
        }
        if (dirty) {
            assessmentDirtyMarker.mark(tenantId, peopleId);
        }
    }

    /**
     * 档案页 / C 端整页保存：提交 JSON 扁平化后，在 legacy（及未登记叶子）作用域内替换；新字段保留。
     */
    @Transactional
    public PeopleBasicArchive replaceBasicArchive(
            String tenantId,
            String peopleId,
            int expectedVersion,
            String contentJson,
            FieldWriteSource source,
            Map<String, String> quotesByPath) {
        ensureMigrated(tenantId, peopleId);
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        int versionBefore = archive == null || archive.getVersion() == null ? 0 : archive.getVersion();
        if (expectedVersion != versionBefore) {
            throw new BusinessException(409, "档案版本冲突，请刷新后重试");
        }
        String oldJson = archive == null || archive.getContentJson() == null ? "{}" : archive.getContentJson();
        JsonNode submittedRoot = JsonUtils.readTree(contentJson == null || contentJson.isBlank() ? "{}" : contentJson);
        Map<String, JsonNode> submitted =
                ArchiveFields.flatten(FieldStore.BASIC.prefix(), submittedRoot, catalog);
        applyGroupDiff(tenantId, peopleId, FieldStore.BASIC.prefix(), submitted, source, true);
        String newJson = JsonUtils.toJson(assembleBasic(tenantId, peopleId));
        archive = upsertBasicArchive(tenantId, peopleId, archive, newJson, versionBefore, source);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                source.updatedByType(),
                source.updatedById(),
                RevisionBizTypeEnum.BASIC_ARCHIVE.name(),
                null,
                versionBefore,
                archive.getVersion(),
                source.orgId(),
                oldJson,
                newJson,
                quotesByPath);
        assessmentDirtyMarker.mark(tenantId, peopleId);
        return archive;
    }

    @Transactional
    public PeopleDiseaseArchive replaceDiseaseArchive(
            String tenantId,
            String peopleId,
            String diseaseCode,
            int expectedVersion,
            String contentJson,
            FieldWriteSource source) {
        ensureMigrated(tenantId, peopleId);
        PeopleDiseaseArchive archive =
                diseaseArchiveMapper.findByTenantPeopleAndCode(tenantId, peopleId, diseaseCode);
        int versionBefore = archive == null || archive.getVersion() == null ? 0 : archive.getVersion();
        if (expectedVersion != versionBefore) {
            throw new BusinessException(409, "档案版本冲突，请刷新后重试");
        }
        String oldJson = archive == null || archive.getContentJson() == null ? "{}" : archive.getContentJson();
        String prefix = FieldStore.DISEASE.prefix() + diseaseCode + ".";
        JsonNode submittedRoot = JsonUtils.readTree(contentJson == null || contentJson.isBlank() ? "{}" : contentJson);
        Map<String, JsonNode> submitted = ArchiveFields.flatten(prefix, submittedRoot, catalog);
        applyGroupDiff(tenantId, peopleId, prefix, submitted, source, true);
        String newJson = JsonUtils.toJson(assembleDisease(tenantId, peopleId, diseaseCode));
        archive = upsertDiseaseArchive(tenantId, peopleId, diseaseCode, archive, newJson, versionBefore, source);
        fieldRevisionService.recordIfChanged(
                tenantId,
                peopleId,
                source.updatedByType(),
                source.updatedById(),
                RevisionBizTypeEnum.DISEASE_ARCHIVE.name(),
                diseaseCode,
                versionBefore,
                archive.getVersion(),
                source.orgId(),
                oldJson,
                newJson);
        assessmentDirtyMarker.mark(tenantId, peopleId);
        return archive;
    }

    /** 基础档案兼容视图（已迁移则直接读 content_json；否则先迁移）。 */
    public ObjectNode basicCompatView(String tenantId, String peopleId) {
        ensureMigrated(tenantId, peopleId);
        PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (archive == null || archive.getContentJson() == null) {
            return JsonUtils.emptyObject();
        }
        JsonNode n = JsonUtils.readTree(archive.getContentJson());
        return n instanceof ObjectNode o ? o : JsonUtils.emptyObject();
    }

    public ObjectNode diseaseCompatView(String tenantId, String peopleId, String diseaseCode) {
        ensureMigrated(tenantId, peopleId);
        PeopleDiseaseArchive archive =
                diseaseArchiveMapper.findByTenantPeopleAndCode(tenantId, peopleId, diseaseCode);
        if (archive == null || archive.getContentJson() == null) {
            return JsonUtils.emptyObject();
        }
        JsonNode n = JsonUtils.readTree(archive.getContentJson());
        return n instanceof ObjectNode o ? o : JsonUtils.emptyObject();
    }

    /**
     * 懒迁移：把未迁移档案的 {@code content_json} 投影到元数据（已有行不覆盖），再重建兼容视图并标
     * {@link #SCHEMA_V2}。
     */
    @Transactional
    public void ensureMigrated(String tenantId, String peopleId) {
        PeopleBasicArchive basic = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
        if (basic != null && !SCHEMA_V2.equals(basic.getSchemaVersion())) {
            migrateBasic(tenantId, peopleId, basic);
        }
        for (PeopleDiseaseArchive row : diseaseArchiveMapper.listByTenantAndPeople(tenantId, peopleId)) {
            if (!SCHEMA_V2.equals(row.getSchemaVersion())) {
                migrateDisease(tenantId, peopleId, row);
            }
        }
    }

    private void migrateBasic(String tenantId, String peopleId, PeopleBasicArchive archive) {
        JsonNode root = JsonUtils.readTree(archive.getContentJson() == null ? "{}" : archive.getContentJson());
        Map<String, JsonNode> flat = ArchiveFields.flatten(FieldStore.BASIC.prefix(), root, catalog);
        FieldWriteSource src = FieldWriteSource.legacyArchive();
        for (Map.Entry<String, JsonNode> e : flat.entrySet()) {
            if (metadataMapper.findByPeopleAndCode(peopleId, e.getKey()) == null) {
                upsertRow(tenantId, peopleId, e.getKey(), e.getValue(), src);
            }
        }
        String rebuilt = JsonUtils.toJson(assembleBasic(tenantId, peopleId));
        archive.setContentJson(rebuilt);
        archive.setSchemaVersion(SCHEMA_V2);
        EntityMeta.onUpdate(archive);
        basicArchiveMapper.updateContent(archive);
    }

    private void migrateDisease(String tenantId, String peopleId, PeopleDiseaseArchive archive) {
        String prefix = FieldStore.DISEASE.prefix() + archive.getDiseaseCode() + ".";
        JsonNode root = JsonUtils.readTree(archive.getContentJson() == null ? "{}" : archive.getContentJson());
        Map<String, JsonNode> flat = ArchiveFields.flatten(prefix, root, catalog);
        FieldWriteSource src = FieldWriteSource.legacyArchive();
        for (Map.Entry<String, JsonNode> e : flat.entrySet()) {
            if (metadataMapper.findByPeopleAndCode(peopleId, e.getKey()) == null) {
                upsertRow(tenantId, peopleId, e.getKey(), e.getValue(), src);
            }
        }
        String rebuilt = JsonUtils.toJson(assembleDisease(tenantId, peopleId, archive.getDiseaseCode()));
        archive.setContentJson(rebuilt);
        archive.setSchemaVersion(SCHEMA_V2);
        EntityMeta.onUpdate(archive);
        diseaseArchiveMapper.updateContent(archive);
    }

    /**
     * @param replaceScoped true = 作用域内缺席的编码清空（档案整页）；false = 只改传入项（问卷 diff）
     * @return 是否有变更
     */
    private boolean applyGroupDiff(
            String tenantId,
            String peopleId,
            String prefix,
            Map<String, JsonNode> submitted,
            FieldWriteSource source,
            boolean replaceScoped) {
        Map<String, JsonNode> current = loadValues(tenantId, peopleId, prefix);
        Map<String, JsonNode> desired = new LinkedHashMap<>(current);
        for (Map.Entry<String, JsonNode> e : submitted.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            if (isWritableMetadata(e.getKey())) {
                if (ArchiveFields.isEmpty(e.getValue())) {
                    desired.remove(e.getKey());
                } else {
                    desired.put(e.getKey(), e.getValue());
                }
            }
        }
        if (replaceScoped) {
            for (String code : List.copyOf(desired.keySet())) {
                if (!submitted.containsKey(code) && inClearScope(code)) {
                    desired.remove(code);
                }
            }
            // 提交里显式空 / 未出现且在作用域 → 上面已处理；提交含空值的 key 不会出现在 flatten 里
            for (String code : List.copyOf(current.keySet())) {
                if (!submitted.containsKey(code) && inClearScope(code)) {
                    desired.remove(code);
                }
            }
        }

        boolean changed = false;
        for (Map.Entry<String, JsonNode> e : desired.entrySet()) {
            JsonNode before = current.get(e.getKey());
            if (!jsonEquals(before, e.getValue())) {
                upsertRow(tenantId, peopleId, e.getKey(), e.getValue(), source);
                changed = true;
            }
        }
        for (String code : current.keySet()) {
            if (!desired.containsKey(code)) {
                PeopleMetadataInfo row = metadataMapper.findByPeopleAndCode(peopleId, code);
                if (row != null) {
                    metadataMapper.softDeleteById(row.getId());
                    changed = true;
                }
            }
        }
        return changed;
    }

    private void rebuildCompatView(String tenantId, String peopleId, String prefix, FieldWriteSource source) {
        if (FieldStore.BASIC.prefix().equals(prefix)) {
            PeopleBasicArchive archive = basicArchiveMapper.findByTenantAndPeople(tenantId, peopleId);
            String newJson = JsonUtils.toJson(assembleBasic(tenantId, peopleId));
            int versionBefore = archive == null || archive.getVersion() == null ? 0 : archive.getVersion();
            // write() 路径也升 version，保证读方缓存 / 乐观锁一致；修订由调用方（档案页）记，问卷另记
            upsertBasicArchive(tenantId, peopleId, archive, newJson, versionBefore, source);
            return;
        }
        if (prefix.startsWith(FieldStore.DISEASE.prefix())) {
            String rest = prefix.substring(FieldStore.DISEASE.prefix().length());
            String diseaseCode = rest.endsWith(".") ? rest.substring(0, rest.length() - 1) : rest;
            int dot = diseaseCode.indexOf('.');
            if (dot > 0) {
                diseaseCode = diseaseCode.substring(0, dot);
            }
            PeopleDiseaseArchive archive =
                    diseaseArchiveMapper.findByTenantPeopleAndCode(tenantId, peopleId, diseaseCode);
            String newJson = JsonUtils.toJson(assembleDisease(tenantId, peopleId, diseaseCode));
            int versionBefore = archive == null || archive.getVersion() == null ? 0 : archive.getVersion();
            upsertDiseaseArchive(tenantId, peopleId, diseaseCode, archive, newJson, versionBefore, source);
        }
    }

    private ObjectNode assembleBasic(String tenantId, String peopleId) {
        return ArchiveFields.assemble(FieldStore.BASIC.prefix(), loadValues(tenantId, peopleId, FieldStore.BASIC.prefix()));
    }

    private ObjectNode assembleDisease(String tenantId, String peopleId, String diseaseCode) {
        String prefix = FieldStore.DISEASE.prefix() + diseaseCode + ".";
        return ArchiveFields.assemble(prefix, loadValues(tenantId, peopleId, prefix));
    }

    private Map<String, JsonNode> loadValues(String tenantId, String peopleId, String prefix) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (PeopleMetadataInfo row : metadataMapper.listByPrefix(tenantId, peopleId, prefix)) {
            JsonNode v = unwrap(row.getMetadataValue());
            if (!ArchiveFields.isEmpty(v)) {
                out.put(row.getMetadataCode(), v);
            }
        }
        return out;
    }

    private Map<String, JsonNode> expandVirtual(
            String tenantId, String peopleId, Map<String, JsonNode> values) {
        if (!values.containsKey(FamilyHistoryFields.VIRTUAL)) {
            return values;
        }
        Map<String, JsonNode> out = new LinkedHashMap<>(values);
        JsonNode virtual = out.remove(FamilyHistoryFields.VIRTUAL);
        Map<String, JsonNode> current = loadValues(tenantId, peopleId, FieldStore.BASIC.prefix());
        out.putAll(FamilyHistoryFields.expandWrite(virtual, current));
        return out;
    }

    private Map<String, Map<String, JsonNode>> groupByArchive(Map<String, JsonNode> values) {
        Map<String, Map<String, JsonNode>> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : values.entrySet()) {
            FieldStore store = FieldStore.of(e.getKey());
            if (!store.metadata()) {
                continue;
            }
            String prefix =
                    store == FieldStore.BASIC
                            ? FieldStore.BASIC.prefix()
                            : FieldStore.DISEASE.prefix() + FieldStore.diseaseOf(e.getKey()) + ".";
            out.computeIfAbsent(prefix, k -> new LinkedHashMap<>()).put(e.getKey(), e.getValue());
        }
        return out;
    }

    private boolean isWritableMetadata(String code) {
        Optional<FieldDef> def = catalog.find(code);
        if (def.isEmpty()) {
            return true; // 未登记叶子：保留
        }
        FieldDef d = def.get();
        if (d.virtual() || d.readOnly() || d.external() || !d.store().metadata()) {
            return false;
        }
        return true;
    }

    /** 整页提交可清空的作用域：legacy 或未登记叶子。 */
    private boolean inClearScope(String code) {
        Optional<FieldDef> def = catalog.find(code);
        if (def.isEmpty()) {
            return true;
        }
        return def.get().legacy() && !def.get().virtual();
    }

    private void upsertRow(
            String tenantId, String peopleId, String code, JsonNode value, FieldWriteSource source) {
        String json = wrap(value);
        PeopleMetadataInfo existing = metadataMapper.findByPeopleAndCode(peopleId, code);
        if (existing == null) {
            PeopleMetadataInfo row = new PeopleMetadataInfo();
            row.setTenantId(tenantId);
            row.setPeopleId(peopleId);
            row.setMetadataCode(code);
            row.setMetadataValue(json);
            applySource(row, source);
            EntityMeta.onCreate(row);
            metadataMapper.insert(row);
        } else {
            existing.setMetadataValue(json);
            applySource(existing, source);
            EntityMeta.onUpdate(existing);
            metadataMapper.updateValue(existing);
        }
    }

    private static void applySource(PeopleMetadataInfo row, FieldWriteSource source) {
        row.setSourceBizCode(source.sourceBizCode());
        row.setSourceClientCode(source.sourceClientCode());
        row.setSourceRefId(source.sourceRefId());
        row.setUpdatedByType(source.updatedByType());
        row.setUpdatedById(source.updatedById());
    }

    private PeopleBasicArchive upsertBasicArchive(
            String tenantId,
            String peopleId,
            PeopleBasicArchive archive,
            String contentJson,
            int versionBefore,
            FieldWriteSource source) {
        if (archive == null) {
            archive = new PeopleBasicArchive();
            archive.setTenantId(tenantId);
            archive.setPeopleId(peopleId);
            archive.setSchemaVersion(SCHEMA_V2);
            archive.setContentJson(contentJson);
            archive.setVersion(1);
            archive.setSource(PatientArchiveSourceEnum.B_SIDE.name());
            if ("STAFF".equals(source.updatedByType())) {
                archive.setCreatedByStaffId(source.updatedById());
                archive.setUpdatedByStaffId(source.updatedById());
            }
            EntityMeta.onCreate(archive);
            basicArchiveMapper.insert(archive);
            return archive;
        }
        archive.setContentJson(contentJson);
        archive.setSchemaVersion(SCHEMA_V2);
        archive.setVersion(versionBefore + 1);
        if ("STAFF".equals(source.updatedByType())) {
            archive.setUpdatedByStaffId(source.updatedById());
        }
        EntityMeta.onUpdate(archive);
        basicArchiveMapper.updateContent(archive);
        return archive;
    }

    private PeopleDiseaseArchive upsertDiseaseArchive(
            String tenantId,
            String peopleId,
            String diseaseCode,
            PeopleDiseaseArchive archive,
            String contentJson,
            int versionBefore,
            FieldWriteSource source) {
        if (archive == null) {
            archive = new PeopleDiseaseArchive();
            archive.setTenantId(tenantId);
            archive.setPeopleId(peopleId);
            archive.setDiseaseCode(diseaseCode);
            archive.setSchemaVersion(SCHEMA_V2);
            archive.setContentJson(contentJson);
            archive.setVersion(1);
            archive.setSource(PatientArchiveSourceEnum.B_SIDE.name());
            if ("STAFF".equals(source.updatedByType())) {
                archive.setCreatedByStaffId(source.updatedById());
                archive.setUpdatedByStaffId(source.updatedById());
            }
            EntityMeta.onCreate(archive);
            diseaseArchiveMapper.insert(archive);
            return archive;
        }
        archive.setContentJson(contentJson);
        archive.setSchemaVersion(SCHEMA_V2);
        archive.setVersion(versionBefore + 1);
        if ("STAFF".equals(source.updatedByType())) {
            archive.setUpdatedByStaffId(source.updatedById());
        }
        EntityMeta.onUpdate(archive);
        diseaseArchiveMapper.updateContent(archive);
        return archive;
    }

    private static FieldValue toFieldValue(PeopleMetadataInfo row) {
        return new FieldValue(
                row.getMetadataCode(),
                unwrap(row.getMetadataValue()),
                row.getSourceBizCode(),
                row.getSourceClientCode(),
                row.getSourceRefId(),
                row.getUpdatedByType(),
                row.getUpdatedById(),
                row.getGmtModified());
    }

    static JsonNode unwrap(String metadataValue) {
        if (metadataValue == null || metadataValue.isBlank()) {
            return null;
        }
        JsonNode n = JsonUtils.readTree(metadataValue);
        if (n != null && n.isObject() && n.has("value")) {
            return n.get("value");
        }
        return n;
    }

    static String wrap(JsonNode value) {
        ObjectNode o = JsonUtils.emptyObject();
        o.set("value", value);
        return JsonUtils.toJson(o);
    }

    private static boolean jsonEquals(JsonNode a, JsonNode b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return Objects.equals(JsonUtils.toJson(a), JsonUtils.toJson(b));
    }
}
