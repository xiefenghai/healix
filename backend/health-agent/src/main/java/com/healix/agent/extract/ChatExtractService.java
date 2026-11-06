package com.healix.agent.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.healix.agent.llm.LlmClient;
import com.healix.agent.llm.LlmResponse;
import com.healix.common.exception.BusinessException;
import com.healix.common.util.JsonUtils;
import com.healix.core.archive.dto.ArchiveViewDto;
import com.healix.agent.memory.AgentConversationService;
import com.healix.core.archive.service.ArchiveAccessService;
import com.healix.core.archive.service.ArchiveOperatorContext;
import com.healix.core.archive.service.BasicArchiveService;
import com.healix.core.job.support.JobCronSupport;
import com.healix.core.vitals.service.VitalService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatExtractService {

    static final int MAX_CHARS = 4000;

    private static final String SYSTEM_PROMPT = """
            你是健康管理记录整理员。只输出一个 JSON 对象，不要散文，不要 markdown。
            JSON 只有 items 和 ignored。指标和档案都放在 items，不要再输出 profile。
            每条 item 要有 target：指标用 VITAL，档案用 ARCHIVE。
            从健管师粘贴的对话里抽出系统已有的指标和档案字段。对不上的句子放进 ignored。
            指标 code 只允许：BLOOD_PRESSURE、BLOOD_GLUCOSE、HEART_RATE、WEIGHT、WAIST。
            血压要同时给出 systolic 和 diastolic，不要对调。
            血糖 mealContext 只能是 FASTING、POSTPRANDIAL、RANDOM，原句没有说清就填 null。
            档案 code 使用不带 basic. 前缀的路径，例如 lifestyle.smoking.status。
            档案只在原句说的是平时习惯时 habit=true。某一晚、某一顿、某一天 habit=false，并放进 ignored，reason 用 NOT_HABIT。
            不要抽取身高、体温、步数、用药、某一餐克数、化验单项目、病种分级、家族史、既往史、现病史，也不要写任何 note 备注字段。
            化验项目或带参考范围的句子 reason 用 LAB_PANEL。
            选项编码：吸烟 NEVER/CURRENT/OCCASIONAL/FORMER；饮酒状态同样，已戒酒也用 FORMER。
            食欲 GOOD/NORMAL/FAIR/POOR。饮食规律 GOOD/FAIR/POOR。
            饮食类型 diet.type：NORMAL 普通饮食，VEGETARIAN 素食，LOW_SALT 低盐，LOW_SUGAR 低糖，LOW_SALT_SUGAR 低盐低糖。低盐、低糖、低盐低糖写入 diet.type，不要写进 diet.preference。
            食欲、饮食规律、运动频率、运动强度、睡眠质量、睡眠障碍、吸烟和饮酒状态都写入对应选项，不要塞进饮食偏好、运动方式或酒的种类。每周三次是运动频率，打鼾是睡眠障碍，不抽烟是吸烟状态，戒酒是饮酒状态。每次多少分钟写入 exercise.durationMin，睡几个小时写入 sleep.hours。
            运动频率 NONE/WEEKLY_1/WEEKLY_3/DAILY。运动强度 LIGHT/MODERATE/VIGOROUS。
            睡眠质量 GOOD/FAIR/POOR。睡眠障碍 NONE/INSOMNIA/SNORING。
            文字字段 diet.preference、exercise.type、lifestyle.drinking.type、lifestyle.drinking.amountPerDay 只写不超过 40 字的短值。
            quote 必须是粘贴文本里的原句，不要改写。
            只根据这次用户消息里的正文抽取。不要从其他对话、建议或历史记录里取数字。
            不要猜测测量时刻。不要把「早上」写成 7:30。
            """;

    private final LlmClient llmClient;
    private final BasicArchiveService basicArchiveService;
    private final VitalService vitalService;
    private final AgentConversationService conversationService;
    private final ArchiveAccessService archiveAccessService;

    public ChatExtractCard extract(String tenantId, String peopleId, String text) {
        String body = text == null ? "" : text.trim();
        if (body.length() > MAX_CHARS) {
            throw new BusinessException("请拆短后再贴");
        }
        // 只整理这一次贴进来的正文。会话里的方案建议、上一轮识别结果不能当数据来源。
        LlmResponse response = llmClient.chat("chat-extract", SYSTEM_PROMPT, body, List.of());
        if (response == null || !response.fromLlm() || !StringUtils.hasText(response.content())) {
            throw new BusinessException("这次没整理成功");
        }
        ChatExtractLlmPayload payload = parse(response.content());
        LocalDate today = LocalDate.now(JobCronSupport.ZONE);
        ChatExtractCard card = ChatExtractNormalizer.normalize(body, payload, today);
        card.setPeopleId(peopleId);
        fillCurrentArchive(tenantId, peopleId, card);
        markDuplicates(tenantId, peopleId, card);
        log.info(
                "chat extract peopleId={} chars={} rows={} ignored={}",
                peopleId,
                body.length(),
                card.getItems().size(),
                card.getIgnored().size());
        return card;
    }

    /** 问卷打开时：按题目清单让模型抽取，确认后由前端写入问卷草稿。 */
    public ChatExtractCard extractIntake(String peopleId, String text, List<IntakeExtractField> fields) {
        String body = text == null ? "" : text.trim();
        if (body.length() > MAX_CHARS) {
            throw new BusinessException("请拆短后再贴");
        }
        List<IntakeExtractField> schema = sanitizeIntakeFields(fields);
        if (schema.isEmpty()) {
            throw new BusinessException("问卷题目还没准备好");
        }
        LlmResponse response = llmClient.chat("chat-extract-intake", intakePrompt(schema), body, List.of());
        if (response == null || !response.fromLlm() || !StringUtils.hasText(response.content())) {
            throw new BusinessException("这次没整理成功");
        }
        ChatExtractCard card = ChatExtractIntakeNormalizer.normalize(body, readJson(response.content()), schema);
        card.setPeopleId(peopleId);
        card.setIntake(true);
        card.setBanner("这些内容将写入正在填写的问卷，请对照原句确认");
        card.setConfirmHint("确认后写入当前问卷草稿");
        log.info(
                "chat extract intake peopleId={} chars={} fields={} rows={}",
                peopleId,
                body.length(),
                schema.size(),
                card.getItems().size());
        return card;
    }

    @Transactional
    public String confirmSaved(
            String tenantId,
            String orgId,
            String staffId,
            String peopleId,
            String messageId,
            List<Map<String, Object>> submitted,
            List<String> discardIds) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        ChatExtractCard card = conversationService.requireOpenExtract(messageId, staffId);
        if (!peopleId.equals(card.getPeopleId())) {
            throw new BusinessException("核对卡与当前患者不一致");
        }
        List<Map<String, Object>> rows = submitted == null ? List.of() : submitted;
        List<String> drops = discardIds == null ? List.of() : discardIds;
        if (rows.isEmpty() && drops.isEmpty()) {
            throw new BusinessException("没有可写入的内容");
        }
        Map<String, ChatExtractRow> byId = new LinkedHashMap<>();
        for (ChatExtractRow item : card.getItems()) {
            byId.put(item.getId(), item);
        }
        Set<String> writeIds = new LinkedHashSet<>();
        for (Map<String, Object> raw : rows) {
            String id = str(raw.get("id"));
            ChatExtractRow item = byId.get(id);
            if (!writeIds.add(id) || item == null || StringUtils.hasText(item.getOutcome())) {
                throw new BusinessException("核对卡里没有这一行");
            }
        }
        Set<String> dropIds = new LinkedHashSet<>();
        for (String id : drops) {
            ChatExtractRow item = byId.get(id);
            if (!StringUtils.hasText(id) || !dropIds.add(id) || item == null || StringUtils.hasText(item.getOutcome())) {
                throw new BusinessException("核对卡里没有这一行");
            }
            if (writeIds.contains(id)) {
                throw new BusinessException("不能同时确认和丢弃");
            }
        }
        assertNoArchiveLeftBehind(card, writeIds, dropIds);
        if (!rows.isEmpty()) {
            confirm(tenantId, orgId, staffId, peopleId, card, rows);
            card.setWrittenCount(card.getWrittenCount() + rows.size());
            for (Map<String, Object> raw : rows) {
                rememberWritten(byId.get(str(raw.get("id"))), raw);
            }
        }
        for (String id : dropIds) {
            byId.get(id).setOutcome("DISCARDED");
        }
        boolean pending = card.getItems().stream().anyMatch(item -> !StringUtils.hasText(item.getOutcome()));
        if (!pending) {
            card.setStatus(card.getWrittenCount() > 0 ? "CONFIRMED" : "DISCARDED");
        }
        conversationService.saveChatExtract(messageId, card);
        return card.getStatus();
    }

    /** 整张卡都丢弃、且没有任何一行写入时，可以重新打开核对。 */
    @Transactional
    public String reopenDiscarded(String tenantId, String orgId, String staffId, String peopleId, String messageId) {
        archiveAccessService.assertStaffCanAccessPeople(tenantId, orgId, peopleId);
        ChatExtractCard card = conversationService.loadExtract(messageId, staffId);
        if (!peopleId.equals(card.getPeopleId())) {
            throw new BusinessException("核对卡与当前患者不一致");
        }
        if (!"DISCARDED".equals(card.getStatus())) {
            throw new BusinessException("这张核对卡还没丢弃");
        }
        List<ChatExtractRow> items = card.getItems() == null ? List.of() : card.getItems();
        boolean written = card.getWrittenCount() > 0
                || items.stream().anyMatch(item -> "WRITTEN".equals(item.getOutcome()));
        if (written) {
            throw new BusinessException("已经写入的内容不能撤销");
        }
        for (ChatExtractRow item : items) {
            if ("DISCARDED".equals(item.getOutcome())) {
                item.setOutcome(null);
            }
        }
        card.setStatus("OPEN");
        conversationService.saveChatExtract(messageId, card);
        return card.getStatus();
    }

    private static void assertNoArchiveLeftBehind(ChatExtractCard card, Set<String> writeIds, Set<String> dropIds) {
        Set<String> writing = new LinkedHashSet<>();
        Set<String> staying = new LinkedHashSet<>();
        for (ChatExtractRow item : card.getItems()) {
            if ((!"ARCHIVE".equals(item.getTarget()) && !"INTAKE".equals(item.getTarget()))
                    || StringUtils.hasText(item.getOutcome())) {
                continue;
            }
            if (writeIds.contains(item.getId())) {
                if (!writing.add(item.getCode())) {
                    throw new BusinessException("同一字段有多行，请只留一行");
                }
            } else if (!dropIds.contains(item.getId())) {
                staying.add(item.getCode());
            }
        }
        for (String code : writing) {
            if (staying.contains(code)) {
                throw new BusinessException("同一字段有多行，请只留一行");
            }
        }
    }

    @Transactional
    public void confirm(
            String tenantId,
            String orgId,
            String staffId,
            String peopleId,
            ChatExtractCard card,
            List<Map<String, Object>> submitted) {
        if (card == null || !"OPEN".equals(card.getStatus())) {
            throw new BusinessException("这张核对卡不能再确认");
        }
        if (submitted == null || submitted.isEmpty()) {
            throw new BusinessException("没有可写入的内容");
        }
        Map<String, ChatExtractRow> byId = new LinkedHashMap<>();
        for (ChatExtractRow row : card.getItems()) {
            byId.put(row.getId(), row);
        }
        List<ChatExtractRow> accepted = new ArrayList<>();
        Map<String, Integer> archiveCounts = new LinkedHashMap<>();
        for (Map<String, Object> raw : submitted) {
            String id = str(raw.get("id"));
            ChatExtractRow origin = byId.get(id);
            if (origin == null || StringUtils.hasText(origin.getOutcome())) {
                throw new BusinessException("核对卡里没有这一行");
            }
            if (!origin.getCode().equals(str(raw.get("code"))) || !origin.getTarget().equals(str(raw.get("target")))) {
                throw new BusinessException("不能改成另一种指标");
            }
            if (!origin.getQuote().equals(str(raw.get("quote")))) {
                throw new BusinessException("原句不能修改");
            }
            ChatExtractRow row = copyForConfirm(origin, raw);
            String problem = validateConfirmed(tenantId, peopleId, row);
            if (problem != null) {
                throw new BusinessException(problem);
            }
            if ("ARCHIVE".equals(row.getTarget()) || "INTAKE".equals(row.getTarget())) {
                archiveCounts.merge(row.getCode(), 1, Integer::sum);
            }
            accepted.add(row);
        }
        for (Integer count : archiveCounts.values()) {
            if (count > 1) {
                throw new BusinessException("同一字段有多行，请只留一行");
            }
        }
        writeVitals(tenantId, orgId, staffId, peopleId, accepted);
        writeArchive(tenantId, orgId, staffId, peopleId, accepted);
    }

    private void writeVitals(
            String tenantId, String orgId, String staffId, String peopleId, List<ChatExtractRow> rows) {
        List<VitalService.StaffExtractReading> readings = new ArrayList<>();
        for (ChatExtractRow row : rows) {
            if (!"VITAL".equals(row.getTarget())) {
                continue;
            }
            LocalDateTime at = LocalDateTime.parse(row.getMeasuredAt());
            String note = row.getQuote();
            if ("BLOOD_PRESSURE".equals(row.getCode())) {
                String groupId = com.healix.common.util.SnowflakeId.nextBizId();
                readings.add(new VitalService.StaffExtractReading(
                        "BLOOD_PRESSURE_SYS",
                        row.getSystolic(),
                        "mmHg",
                        at,
                        groupId,
                        note,
                        null,
                        row.getBpContext()));
                readings.add(new VitalService.StaffExtractReading(
                        "BLOOD_PRESSURE_DIA",
                        row.getDiastolic(),
                        "mmHg",
                        at,
                        groupId,
                        note,
                        null,
                        row.getBpContext()));
            } else {
                readings.add(new VitalService.StaffExtractReading(
                        row.getCode(),
                        row.getValue(),
                        row.getUnit(),
                        at,
                        com.healix.common.util.SnowflakeId.nextBizId(),
                        note,
                        row.getMealContext(),
                        null));
            }
        }
        vitalService.recordStaffExtract(tenantId, orgId, peopleId, staffId, readings);
    }

    private void writeArchive(
            String tenantId, String orgId, String staffId, String peopleId, List<ChatExtractRow> rows) {
        List<ChatExtractRow> archives = rows.stream().filter(row -> "ARCHIVE".equals(row.getTarget())).toList();
        if (archives.isEmpty()) {
            return;
        }
        ArchiveViewDto current = basicArchiveService.get(tenantId, peopleId);
        JsonNode content = JsonUtils.readTree(
                current.getContentJson() == null ? "{}" : JsonUtils.toJson(current.getContentJson()));
        ObjectNode root = content instanceof ObjectNode node ? node : JsonUtils.emptyObject();
        Map<String, String> quotes = new LinkedHashMap<>();
        for (ChatExtractRow row : archives) {
            putPath(root, row.getCode(), row.getArchiveValue());
            quotes.put(row.getCode(), row.getQuote());
        }
        basicArchiveService.save(
                tenantId,
                peopleId,
                current.getVersion() == null ? 0 : current.getVersion(),
                JsonUtils.toJson(root),
                ArchiveOperatorContext.staff(staffId, orgId),
                quotes);
    }

    private String validateConfirmed(String tenantId, String peopleId, ChatExtractRow row) {
        if (!StringUtils.hasText(row.getQuote()) || row.getQuote().length() > ChatExtractNormalizer.MAX_QUOTE) {
            return "原句不能为空";
        }
        if ("VITAL".equals(row.getTarget())) {
            if (!StringUtils.hasText(row.getMeasuredAt()) || !row.getMeasuredAt().contains("T")) {
                return "请补上日期和时刻";
            }
            LocalDateTime at;
            try {
                at = LocalDateTime.parse(row.getMeasuredAt());
            } catch (Exception ex) {
                return "请补上日期和时刻";
            }
            if ("BLOOD_PRESSURE".equals(row.getCode())) {
                if (row.getSystolic() == null || row.getDiastolic() == null) {
                    return "血压需要收缩压和舒张压";
                }
                if (row.getSystolic().compareTo(row.getDiastolic()) <= 0) {
                    return "收缩压不高于舒张压，请改数字";
                }
                if (!VitalValueRulesSafe.ok("BLOOD_PRESSURE_SYS", row.getSystolic())
                        || !VitalValueRulesSafe.ok("BLOOD_PRESSURE_DIA", row.getDiastolic())) {
                    return "血压超出合理范围";
                }
                if (!"HOME".equals(row.getBpContext()) && !"CLINIC".equals(row.getBpContext())) {
                    return "请选择血压场景";
                }
                if (vitalService.existsAtMinute(tenantId, peopleId, "BLOOD_PRESSURE_SYS", at)
                        || vitalService.existsAtMinute(tenantId, peopleId, "BLOOD_PRESSURE_DIA", at)) {
                    return "这个时刻已经有血压记录";
                }
                return null;
            }
            if (!ChatExtractCatalog.VITALS.containsKey(row.getCode())) {
                return "这个指标不能写入";
            }
            if ("BLOOD_GLUCOSE".equals(row.getCode())) {
                if (!"FASTING".equals(row.getMealContext())
                        && !"POSTPRANDIAL".equals(row.getMealContext())
                        && !"RANDOM".equals(row.getMealContext())) {
                    return "请选择空腹、餐后或随机";
                }
                row.setUnit("mmol/L");
            }
            if ("WEIGHT".equals(row.getCode())) {
                if (!"kg".equals(row.getUnit())) {
                    return "请选择体重单位";
                }
            }
            if (row.getValue() == null || !VitalValueRulesSafe.ok(row.getCode(), row.getValue())) {
                return row.getLabel() + "超出合理范围";
            }
            if (vitalService.existsAtMinute(tenantId, peopleId, row.getCode(), at)) {
                return "这个时刻已经有" + row.getLabel() + "记录";
            }
            return null;
        }
        if ("INTAKE".equals(row.getTarget())) {
            return validateIntake(row);
        }
        ChatExtractCatalog.ArchiveField field = ChatExtractCatalog.ARCHIVE.get(row.getCode());
        if (field == null) {
            return "这个档案字段不能写入";
        }
        if (!StringUtils.hasText(row.getArchiveValue())) {
            return "请填写" + row.getLabel();
        }
        if (field.text()) {
            if (row.getArchiveValue().length() > ChatExtractNormalizer.MAX_TEXT) {
                return row.getLabel() + "过长";
            }
            return null;
        }
        if (field.options() != null && !field.options().isEmpty()) {
            boolean ok = field.options().stream().anyMatch(option -> option.getValue().equals(row.getArchiveValue()));
            return ok ? null : "请选择已有选项";
        }
        try {
            double n = Double.parseDouble(row.getArchiveValue());
            if (field.min() != null && n < field.min()) {
                return row.getLabel() + "超出合理范围";
            }
            if (field.max() != null && n > field.max()) {
                return row.getLabel() + "超出合理范围";
            }
        } catch (NumberFormatException ex) {
            return "请填写" + row.getLabel();
        }
        return null;
    }

    private static ChatExtractRow copyForConfirm(ChatExtractRow origin, Map<String, Object> raw) {
        ChatExtractRow row = new ChatExtractRow();
        row.setId(origin.getId());
        row.setTarget(origin.getTarget());
        row.setCode(origin.getCode());
        row.setLabel(origin.getLabel());
        row.setQuote(origin.getQuote());
        row.setTextField(origin.isTextField());
        row.setDerived(Boolean.TRUE.equals(raw.get("derived")) || origin.isDerived());
        if ("VITAL".equals(origin.getTarget())) {
            row.setValue(decimal(raw.get("value")));
            row.setSystolic(decimal(raw.get("systolic")));
            row.setDiastolic(decimal(raw.get("diastolic")));
            row.setUnit(str(raw.get("unit")));
            row.setMeasuredAt(str(raw.get("measuredAt")));
            row.setMealContext(emptyToNull(str(raw.get("mealContext"))));
            row.setBpContext(emptyToNull(str(raw.get("bpContext"))));
            if ("WEIGHT".equals(row.getCode()) && "斤".equals(str(raw.get("sourceUnit"))) && row.getValue() != null) {
                row.setUnit("kg");
            }
        } else if ("INTAKE".equals(origin.getTarget())) {
            row.setArchiveValue(str(raw.get("archiveValue")));
            row.setIntakeType(origin.getIntakeType());
            row.setMulti(origin.isMulti());
            row.setIntakeMin(origin.getIntakeMin());
            row.setIntakeMax(origin.getIntakeMax());
            row.setUnit(origin.getUnit());
            row.setOptions(origin.getOptions() == null ? List.of() : new ArrayList<>(origin.getOptions()));
        } else {
            row.setArchiveValue(str(raw.get("archiveValue")));
        }
        return row;
    }

    /** 写入后的数字留在核对卡上，折叠后还能对上当时确认的内容。 */
    private static void rememberWritten(ChatExtractRow origin, Map<String, Object> raw) {
        if ("VITAL".equals(origin.getTarget())) {
            origin.setValue(decimal(raw.get("value")));
            origin.setSystolic(decimal(raw.get("systolic")));
            origin.setDiastolic(decimal(raw.get("diastolic")));
            origin.setUnit(emptyToNull(str(raw.get("unit"))));
            origin.setSourceUnit(emptyToNull(str(raw.get("sourceUnit"))));
            origin.setMeasuredAt(str(raw.get("measuredAt")));
            origin.setMealContext(emptyToNull(str(raw.get("mealContext"))));
            origin.setBpContext(emptyToNull(str(raw.get("bpContext"))));
        } else {
            origin.setArchiveValue(str(raw.get("archiveValue")));
        }
        origin.setOutcome("WRITTEN");
    }

    private void fillCurrentArchive(String tenantId, String peopleId, ChatExtractCard card) {
        ArchiveViewDto view = basicArchiveService.get(tenantId, peopleId);
        JsonNode root = JsonUtils.readTree(view.getContentJson() == null ? "{}" : JsonUtils.toJson(view.getContentJson()));
        for (ChatExtractRow row : card.getItems()) {
            if (!"ARCHIVE".equals(row.getTarget())) {
                continue;
            }
            String current = readPath(root, row.getCode());
            row.setCurrentDisplay(current == null || current.isBlank() ? "空" : ChatExtractCatalog.display(row.getCode(), current));
        }
    }

    private void markDuplicates(String tenantId, String peopleId, ChatExtractCard card) {
        for (ChatExtractRow row : card.getItems()) {
            if (!"VITAL".equals(row.getTarget()) || row.getMeasuredAt() == null || !row.getMeasuredAt().contains("T")) {
                continue;
            }
            LocalDateTime at = LocalDateTime.parse(row.getMeasuredAt());
            boolean exists = "BLOOD_PRESSURE".equals(row.getCode())
                    ? vitalService.existsAtMinute(tenantId, peopleId, "BLOOD_PRESSURE_SYS", at)
                            || vitalService.existsAtMinute(tenantId, peopleId, "BLOOD_PRESSURE_DIA", at)
                    : vitalService.existsAtMinute(tenantId, peopleId, row.getCode(), at);
            if (exists && ("READY".equals(row.getStatus()) || row.getStatus() == null)) {
                row.setStatus("DUPLICATE");
                row.setStatusText("这个时刻已经有记录");
            }
        }
    }

    private static ChatExtractLlmPayload parse(String content) {
        String json = content.trim();
        int fence = json.indexOf("```");
        if (fence >= 0) {
            int start = json.indexOf('\n', fence);
            int end = json.lastIndexOf("```");
            if (start >= 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }
        int left = json.indexOf('{');
        int right = json.lastIndexOf('}');
        if (left < 0 || right <= left) {
            throw new BusinessException("这次没整理成功");
        }
        try {
            return ChatExtractLlmPayload.read(json.substring(left, right + 1));
        } catch (RuntimeException ex) {
            throw new BusinessException("这次没整理成功");
        }
    }

    private static void putPath(ObjectNode root, String path, String value) {
        String[] parts = path.split("\\.");
        ObjectNode cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonNode child = cursor.get(parts[i]);
            if (child instanceof ObjectNode existing) {
                cursor = existing;
            } else {
                cursor = cursor.putObject(parts[i]);
            }
        }
        String leaf = parts[parts.length - 1];
        if (value != null && value.matches("-?\\d+(\\.\\d+)?")) {
            if (value.contains(".")) {
                cursor.put(leaf, new BigDecimal(value));
            } else if ("lifestyle.smoking.quitYear".equals(path)) {
                cursor.put(leaf, value);
            } else {
                cursor.put(leaf, Long.parseLong(value));
            }
            return;
        }
        cursor.put(leaf, value);
    }

    private static String readPath(JsonNode root, String path) {
        JsonNode cursor = root;
        for (String part : path.split("\\.")) {
            if (cursor == null) {
                return null;
            }
            cursor = cursor.get(part);
        }
        if (cursor == null || cursor.isNull()) {
            return null;
        }
        return cursor.isTextual() ? cursor.asText() : cursor.asText();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null || "".equals(value)) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String emptyToNull(String value) {
        return StringUtils.hasText(value) ? value : null;
    }

    private static String validateIntake(ChatExtractRow row) {
        if (!StringUtils.hasText(row.getArchiveValue())) {
            return "请填写" + row.getLabel();
        }
        String type = row.getIntakeType() == null ? "" : row.getIntakeType();
        return switch (type) {
            case "ENUM" -> optionOk(row, row.getArchiveValue()) ? null : "请选择已有选项";
            case "MULTI" -> multiOk(row) ? null : "请选择已有选项";
            case "NUMBER", "YEAR" -> numberOk(row) ? null : row.getLabel() + "超出合理范围";
            case "TEXT" -> row.getArchiveValue().length() > 200 ? row.getLabel() + "过长" : null;
            case "DATE" -> row.getArchiveValue().matches("\\d{4}-\\d{2}-\\d{2}") ? null : "请填写日期";
            case "TIME" -> row.getArchiveValue().matches("\\d{2}:\\d{2}") ? null : "请填写时间";
            default -> "这个题目不能写入";
        };
    }

    private static boolean optionOk(ChatExtractRow row, String value) {
        if (row.getOptions() == null) {
            return false;
        }
        return row.getOptions().stream().anyMatch(option -> option != null && value.equals(option.getValue()));
    }

    private static boolean multiOk(ChatExtractRow row) {
        JsonNode node;
        try {
            node = JsonUtils.readTree(row.getArchiveValue());
        } catch (RuntimeException ex) {
            return false;
        }
        if (node == null || !node.isArray() || node.isEmpty()) {
            return false;
        }
        for (JsonNode item : node) {
            if (!item.isTextual() || !optionOk(row, item.asText())) {
                return false;
            }
        }
        return true;
    }

    private static boolean numberOk(ChatExtractRow row) {
        double number;
        try {
            number = Double.parseDouble(row.getArchiveValue());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (row.getIntakeMin() != null && number < row.getIntakeMin()) {
            return false;
        }
        return row.getIntakeMax() == null || number <= row.getIntakeMax();
    }

    private static List<IntakeExtractField> sanitizeIntakeFields(List<IntakeExtractField> fields) {
        List<IntakeExtractField> out = new ArrayList<>();
        if (fields == null) {
            return out;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (IntakeExtractField field : fields) {
            if (out.size() >= 180 || field == null || !StringUtils.hasText(field.code())) {
                continue;
            }
            String code = field.code().trim();
            if (!code.matches("[A-Za-z0-9._]+") || !seen.add(code)) {
                continue;
            }
            String type = field.type() == null ? "" : field.type().trim().toUpperCase();
            if (!Set.of("ENUM", "MULTI", "NUMBER", "TEXT", "DATE", "TIME", "YEAR").contains(type)) {
                continue;
            }
            List<ChatExtractOption> options = new ArrayList<>();
            if (field.options() != null) {
                for (ChatExtractOption option : field.options()) {
                    if (options.size() >= 40 || option == null || !StringUtils.hasText(option.getValue())) {
                        continue;
                    }
                    String label = option.getLabel() == null ? option.getValue() : option.getLabel().trim();
                    options.add(new ChatExtractOption(option.getValue().trim(), clipLabel(label)));
                }
            }
            if (("ENUM".equals(type) || "MULTI".equals(type)) && options.isEmpty()) {
                continue;
            }
            out.add(new IntakeExtractField(
                    code,
                    clipLabel(field.label()),
                    type,
                    field.unit() == null ? null : clipLabel(field.unit()),
                    field.min(),
                    field.max(),
                    options));
        }
        return out;
    }

    private static String intakePrompt(List<IntakeExtractField> fields) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                你是健康问卷整理员。只输出一个 JSON 对象，不要散文，不要 markdown。
                JSON 形如 {"items":[{"field":"题目code","value":"选项code或数字或文字","quote":"原文片段"}],"ignored":[]}。
                field 必须是下面清单里的 code。原文没提到的题不要输出。不要推测，不要补默认值。
                ENUM 的 value 用选项 code。MULTI 的 value 用选项 code 数组。NUMBER 和 YEAR 用数字。
                DATE 用 YYYY-MM-DD，TIME 用 HH:mm。TEXT 只摘原文里的短句。
                quote 必须是用户原文中的连续片段，不要改写。一次最多 12 题。
                同一题如果原文里有两个不同的说法，items 只保留先出现的那一个，另一个放进 ignored，reason 写明另一个值。
                可填写的题目：
                """);
        for (IntakeExtractField field : fields) {
            prompt.append("- ")
                    .append(field.code())
                    .append(" | ")
                    .append(field.label() == null ? "" : field.label().replace('|', '／'))
                    .append(" | ")
                    .append(field.type());
            if (field.min() != null || field.max() != null) {
                prompt.append(" | ").append(field.min() == null ? "" : field.min()).append("-").append(field.max() == null ? "" : field.max());
            }
            if (StringUtils.hasText(field.unit())) {
                prompt.append(" | ").append(field.unit());
            }
            if (field.options() != null) {
                for (ChatExtractOption option : field.options()) {
                    prompt.append(" | ").append(option.getValue()).append("=").append(option.getLabel());
                }
            }
            prompt.append('\n');
        }
        return prompt.toString();
    }

    private static String clipLabel(String label) {
        if (!StringUtils.hasText(label)) {
            return "";
        }
        String text = label.trim().replace('\n', ' ');
        return text.length() > 80 ? text.substring(0, 80) : text;
    }

    private static JsonNode readJson(String content) {
        String json = content.trim();
        int fence = json.indexOf("```");
        if (fence >= 0) {
            int start = json.indexOf('\n', fence);
            int end = json.lastIndexOf("```");
            if (start >= 0 && end > start) {
                json = json.substring(start, end).trim();
            }
        }
        int left = json.indexOf('{');
        int right = json.lastIndexOf('}');
        if (left < 0 || right <= left) {
            throw new BusinessException("这次没整理成功");
        }
        try {
            return JsonUtils.readTree(json.substring(left, right + 1));
        } catch (RuntimeException ex) {
            throw new BusinessException("这次没整理成功");
        }
    }

    /** 避免确认路径直接抛出生硬的范围异常文案。 */
    private static final class VitalValueRulesSafe {
        private VitalValueRulesSafe() {}

        static boolean ok(String metric, BigDecimal value) {
            return com.healix.core.vitals.support.VitalValueRules.isPlausible(metric, value);
        }
    }
}
